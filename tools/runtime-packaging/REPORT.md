# P2B 第 0 步侦察报告 — Termux arm64 运行时 + pi bundle 打包

编译机：`root@64.90.11.50`（Ubuntu，arm64 交叉产物在 x86_64 主机上制作）
索引抓取时间：2026-10-06，源 `https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages`
所有数字都是本机 `readelf` / `file` / `ls -l` 实测输出，非估算。

复现入口（本文档每个结论都能被这三条命令重放）：

```bash
bash tools/runtime-packaging/recon-probe.sh        # 只读探测，输出 /root/runtime-pkg/probe.txt
python3 tools/runtime-packaging/elf-closure.py      # readelf 驱动的依赖闭包 → out 之外的 closure.json
bash tools/runtime-packaging/build-jniLibs.sh       # job A
bash tools/runtime-packaging/build-pi-runtime.sh    # job B
```

---

## 1. 包版本钉死表（23 个包）

`tools/runtime-packaging/termux-pins.tsv` 为准（`pkg \t version \t repo 相对路径 \t sha256`），
版本由 `pkg-index.py` 从真实 Packages 索引解析生成，不是手抄。下载后强制校验 sha256，
不匹配即终止。

| 包 | 版本 | 进包原因 |
|---|---|---|
| nodejs-lts | 24.18.0-1 | spawn 主进程（`libpi_node.so`）|
| bash | 5.3.20 | 工具执行 shell（`libpi_bash.so`）|
| git | 2.56.0 | `libpi_git.so` + `libpi_git_remote_http.so` + libexec 脚本 |
| coreutils | 9.11-1 | multicall（`libpi_coreutils.so` + 101 个 applet symlink）|
| ca-certificates | 1:2026.09.25 | `ca-bundle.crt`（SSL_CERT_FILE）|
| openssl | 1:3.6.5 | `libpi_crypto.so` / `libpi_ssl.so` |
| libc++ | 30 | node NEEDED `libc++_shared.so` |
| c-ares | 1.34.8 | node NEEDED `libcares.so`（DNS）|
| libicu | 78.3 | node NEEDED `libicui18n/uc/data.so.78` |
| libsqlite | 3.53.4 | node NEEDED `libsqlite3.so` |
| zlib | 1.3.2 | node + git NEEDED `libz.so.1` |
| libandroid-support | 29-1 | bash/coreutils NEEDED |
| libiconv | 1.19 | bash/git NEEDED |
| readline | 8.3.6 | bash NEEDED |
| pcre2 | 10.49 | git NEEDED `libpcre2-8.so` |
| libcurl | 8.22.0 | `git-remote-http` NEEDED |
| libandroid-selinux | 14.0.0.11-1 | coreutils NEEDED |
| libgmp | 6.3.0-2 | coreutils NEEDED |
| ncurses | 6.6.20260307+really6.5.20250830 | readline NEEDED `libncursesw.so.6` |
| libnghttp2 | 1.70.0 | libcurl NEEDED |
| libnghttp3 | 1.18.0 | libcurl NEEDED |
| libngtcp2 | 1.25.0 | libcurl NEEDED（+ `libngtcp2_crypto_ossl.so`）|
| libssh2 | 1.11.1-2 | libcurl NEEDED（scp/sftp url）|

依赖闭包算法（`recon-termux.py`）从 6 个种子包沿 `Depends:` 展开，共解析出 **65 个包**；
其中只有下面第 3 节 readelf 命中的 22 个库真正进入 jniLibs，其余（krb5/ldns/libedit/gawk/grep/
findutils/diffutils/tar/dash/less/curl/openssh/…）在 ELF 层不被必需链引用，不打包。

## 2. RUNPATH 实测：规格书的假设不成立

规格书 §1 提示「Termux 的 bin 是动态链接的，RUNPATH 若引用 `$ORIGIN/../lib`」。实测：

```
$ readelf -d <闭包内 235 个 ELF> | grep RUNPATH
178   /data/data/com.termux/files/usr/lib
 53   /data/data/com.termux/files/usr/lib:/data/data/com.termux/files/usr/lib
  4   /data/data/com.termux/files/usr/lib:...:...
```

**235 个 ELF 全部是绝对 RUNPATH，零个 `$ORIGIN`。** 也就是说无论选方案 i 还是 ii，
都必须重写 RUNPATH；「保留原样 + LD_LIBRARY_PATH」不是可选项，因为 DT_RUNPATH 的语义是
**在 `LD_LIBRARY_PATH` 之后**参与搜索（DT_RPATH 才是之前），而 Termux 的 RUNPATH 指向一个
本 app 里不存在、且 Android 上不允许加载的目录（`/data/data/com.termux/...` 属于别人，
我们的进程既读不到也加载不了，SELinux `app_data_file` 隔离）。
→ 必须 patchelf，且必须让 jniLibs 目录自洽。

架构/类型抽查（`probe.txt` 第 6 节）：`readelf -h` 对 `bin/node` 报
`Class: ELF64 / Machine: AArch64 / Type: DYN (Shared object file)`，即 PIE 可执行文件，
带 `libc.so`/`libm.so`/`libdl.so` 三个 bionic NEEDED（Termux 用 Android NDK 的 bionic 链接，
不是 glibc）——这正是 execve 能走 `nativeLibraryDir` 的前提。

## 3. 依赖闭包（readelf 实测，`elf-closure.py`）

种子：`bin/node`、`bin/bash`、`bin/git`、`bin/coreutils`、`libexec/git-core/git-remote-http`。
每个 ELF 的 DT_NEEDED 逐名在解包池里找实体文件，命中的库再入队做传递闭包；
系统库白名单 `libc.so libm.so libdl.so liblog.so libandroid.so`（bionic 提供，不打包）。

直接依赖（真实 `readelf -d` 输出，只列非系统项）：

| 种子 | DT_NEEDED（非系统）|
|---|---|
| node | `libz.so.1 libcares.so libsqlite3.so libcrypto.so.3 libssl.so.3 libicui18n.so.78 libicuuc.so.78 libc++_shared.so` |
| bash | `libandroid-support.so libreadline.so.8 libiconv.so` |
| git | `libpcre2-8.so libz.so.1 libiconv.so libcrypto.so.3` |
| coreutils | `libandroid-support.so libandroid-selinux.so libgmp.so libcrypto.so.3 libiconv.so` |
| git-remote-http | `libcurl.so libz.so.1 libiconv.so …`（libcurl 再拉 nghttp2/nghttp3/ngtcp2/ssh2/cares）|

闭包结果：**22 个共享库 + 5 个可执行，0 个未解析**。
22 个库 → `libpi_*.so` 的映射见 `elf-name-map.tsv`（左列 DT_NEEDED 原始请求名、
右列 jniLibs 文件名），patchelf 同时改写 NEEDED 与 SONAME，保证名字闭环。

改名（而不是保留 `libz.so.1.3.2` 这种带版本尾巴的名字）有两个硬理由：
1. AGP 的 jniLibs 只把文件名形如 `lib*.so` 的文件当 native lib，`libcrypto.so.3` 会被忽略；
2. `libz.so` / `libcrypto.so` / `libc++_shared.so` 这些名字在 Android 系统命名空间里
   **本身就存在**，bionic linker 命中同名已加载库就复用系统那份，跨版本 ABI 不受控。
   `libpi_` 前缀同时解决这两件事。

### 3.1 几个关键判定的证据

* **git 的 https**：`bin/git` 自己没有 `libcurl` 的 DT_NEEDED，https 走
  `libexec/git-core/git-remote-http` 这个独立 ELF（2109120 B）。故 job A 把它提升为
  `libpi_git_remote_http.so`；`git-remote-https/ftp/ftps` 三个 symlink 在 manifest 里
  指到它。不这样，pi 的 git 工具在 https 仓库上会直接失败。
* **coreutils 是 multicall**：`ls -l` 证据（`probe.txt` 第 2 节）显示
  `bin/[`、`bin/cat`、`bin/ls` … 全是 **9 字节 symlink → `coreutils`**，`bin` 下真实 ELF 只有 1 个。
  因此 jniLibs 只放 1 个 `libpi_coreutils.so`，manifest.symlinks 生成 101 个 applet 名
  （argv[0] 分派）。
* **openssl 没有可执行文件**：Termux `openssl` 包里只有 `libcrypto.so.3` / `libssl.so.3`，
  没有 `bin/openssl`（`bin/openssl` 在独立的 `openssl-utils` 包，spawn 链不需要）。
  规格书里设想的 `libpi_openssl.so` 落地为 **`libpi_crypto.so` + `libpi_ssl.so`** 两个库，
  名字里的 "openssl" 语义由它们承担。
* **git-core 目录构成**（`probe.txt` 第 1 节）：7 个 ELF / 152 个 symlink / 24 个脚本。
  7 个 ELF 里只 `git-remote-http` 进 jniLibs（其余 6 个：`git-daemon`、`git-http-backend`、
  `git-http-fetch`、`git-http-push`、`git-imap-send`、`git-sh-i18n--envsubst`、`git-subtree`…，
  只有 `git-sh-i18n--envsubst` 会被 `git-sh-setup` 间接调用，属可裁剪项，见第 7 节遗留问题）。
  24 个脚本（`git-submodule`、`git-filter-branch`、`git-mergetool`…）以数据形式进
  `runtime/libexec/`，符号链接与 ELF 条目跳过（符号链接由 manifest 声明，ELF 由 jniLibs 提供）。
* **ca 证书**：`ca-certificates` 包内 `etc/tls/cert.pem`，实测 188900 B，直接作为
  `runtime/ca-bundle.crt` 落进 zip（与 EnvFactory 的 `SSL_CERT_FILE` 键名对齐）。

## 4. 方案 i / ii 的判定：**采用方案 i**（全部 ELF 进 jniLibs）

规格书给了两个候选：

* i — 所有 arm64 ELF（node/bash/git + 全部依赖 `lib*.so`）进 jniLibs，`RUNPATH=$ORIGIN`，
  `runtime.zip` 只带 pi bundle + git libexec + ca-bundle + 纯数据；
* ii — 依赖库放 `runtime/lib`，spawn 前由 EnvFactory 注入 `LD_LIBRARY_PATH`。

**证据链（三条，任意一条单独即足以否决 ii）：**

1. **SELinux 执行域**：app `targetSdk = 35`（Android 14+ 行为），`filesDir`（`/data/data/<pkg>/files`）
   下所有文件标签为 `app_data_file`。Android 10（API 29）起，untrusted_app 域对 `app_data_file`
   **禁止 `execve` 与 `mmap(PROT_EXEC)`**。这不只影响可执行文件，也影响共享库——动态链接器
   加载 `runtime/lib/libc++_shared.so` 同样要 `mmap PROT_EXEC`，会被 `avc: denied { execute }` 直接拒。
   只有 `nativeLibraryDir`（`/data/app/~~<hash>/lib/arm64`，标签 `app_lib_file`，
   且需 `extractNativeLibs=true` 让安装器把 so 落成真文件）同时允许读+执行+映射执行。
2. **DT_RUNPATH 的搜索顺序**：即使 ii 能加载，`LD_LIBRARY_PATH` 在 RUNPATH 之后；
   Termux 原 RUNPATH 是不存在的绝对路径（第 2 节实测），ii 方案仍然要 patchelf。
   既然两个方案都要改写 RUNPATH，ii 并没有省下任何东西，只是把 ELF 放进了一个不可执行的目录。
3. **EnvFactory 现状不需要动**：`PATH=<runtime>/bin:<libDir>:/system/bin` 已经把 `libDir`
   放进搜索路径；`runtime/bin/*` 与 `runtime/libexec/*` 由 AssetInstaller 的 symlink 农场
   指向 `nativeLibraryDir/libpi_*.so`，`execvp("bash")` 经 symlink 落到 `app_lib_file`，
   执行域正确。方案 ii 反而要求改 EnvFactory（新增 `LD_LIBRARY_PATH`）并补测试——红线之外的多余改动。

结论：**27 个 ELF 全进 `jniLibs/arm64-v8a/`（5 个 exec + 22 个 lib），`RUNPATH=$ORIGIN`，
`SONAME` 与 `NEEDED` 一起改成 `libpi_*`；`runtime.zip` 里没有任何 ELF。**
`core/environment` 未做任何修改（方案 ii 未被采用）。

## 5. job A 产物（`out/jniLibs/arm64-v8a/`，27 个文件 / 106.5 MB）

完整清单（`out/jniLibs-inventory.tsv`，含 sha256）：

| 文件 | kind | 来源包 | 版本 | 字节 |
|---|---|---|---|---|
| libpi_node.so | exec | nodejs-lts | 24.18.0-1 | 47,257,744 |
| libpi_icudata.so | lib | libicu | 78.3 | 33,162,264 |
| libpi_icui18n.so | lib | libicu | 78.3 | 5,063,280 |
| libpi_icuuc.so | lib | libicu | 78.3 | 2,521,928 |
| libpi_crypto.so | lib | openssl | 1:3.6.5 | 5,371,512 |
| libpi_git.so | exec | git | 2.56.0 | 3,675,400 |
| libpi_git_remote_http.so | exec | git | 2.56.0 | 2,168,336 |
| libpi_cxx_shared.so | lib | libc++ | 30 | 1,760,048 |
| libpi_coreutils.so | exec | coreutils | 9.11-1 | 1,317,656 |
| libpi_sqlite3.so | lib | libsqlite | 3.53.4 | 1,333,072 |
| libpi_iconv.so | lib | libiconv | 1.19 | 1,116,600 |
| libpi_curl.so | lib | libcurl | 8.22.0 | 1,079,896 |
| libpi_ssl.so | lib | openssl | 1:3.6.5 | 1,038,720 |
| libpi_bash.so | exec | bash | 5.3.20 | 953,176 |
| libpi_pcre2_8.so | lib | pcre2 | 10.49 | 529,232 |
| libpi_gmp.so | lib | libgmp | 6.3.0-2 | 471,576 |
| libpi_readline.so | lib | readline | 8.3.6 | 432,560 |
| libpi_ncursesw.so | lib | ncurses | 6.6.20260307+really6.5.20250830 | 406,104 |
| libpi_ngtcp2.so | lib | libngtcp2 | 1.25.0 | 400,968 |
| libpi_android_selinux.so | lib | libandroid-selinux | 14.0.0.11-1 | 279,448 |
| libpi_cares.so | lib | c-ares | 1.34.8 | 276,784 |
| libpi_ssh2.so | lib | libssh2 | 1.11.1-2 | 342,616 |
| libpi_nghttp2.so | lib | libnghttp2 | 1.70.0 | 205,488 |
| libpi_nghttp3.so | lib | libnghttp3 | 1.18.0 | 201,928 |
| libpi_z.so | lib | zlib | 1.3.2 | 134,864 |
| libpi_ngtcp2_crypto_ossl.so | lib | libngtcp2 | 1.25.0 | 140,528 |
| libpi_android_support.so | lib | libandroid-support | 29-1 | 68,288 |

体积说明：strip 后 107 MB 里 **ICU 占 40.7 MB**（`libicudata.so.78.3` 是 node 的硬 DT_NEEDED，
Termux 打的是 full-data ICU，没有 small-icu 变体可换）。node 本体 47.3 MB，
其中约 1.9 MB 是 patchelf 重写字符串表带来的段对齐增长（对照实验：单次合并调用
`patchelf --set-rpath '$ORIGIN' --replace-needed …` 得到 47,255,416 B；每个属性单独调用
10 次会膨胀到 61.5 MB——所以脚本改成"NEEDED+RUNPATH 合一趟、SONAME 单独一趟"两趟式）。

patchelf 的坑（实测，写进了脚本注释）：`--set-soname` 与 `--replace-needed` **合在同一次调用**
会写坏 SONAME（readline 上观测到被写成 `libpi_ncursesw.so`）。故 SONAME 必须单独一次调用，
并由自检断言 `SONAME == 文件名` 把住。

版本戳：`out/jniLibs-version.txt` = `termux-140b597a9bd5`
（= 参与包 `pkg=version` 排序列表的 sha256 前 12 位），job B 的 `runtimeVersion` 直接引用它。

自检（`build-jniLibs.sh` 末尾 + `smoke-elf.sh` 独立复跑，27 个文件全 PASS）：
`file` 报 ELF 64-bit ARM / `readelf -h` e_machine=AArch64、e_type=DYN / `RUNPATH=='$ORIGIN'` /
`SONAME==文件名`（库）/ 每个非系统 `NEEDED` 在本目录都能找到实体 / 单文件 ≤ 100 MB。

幂等：连跑两次，27 个文件的 sha256 列表逐字节一致。

## 6. job B 产物（`out/assets/runtime/`）

```
manifest.json    177,042 B
pi-runtime.zip  8,215,598 B   ← 22.4 MB 源（pi 包 + git libexec 脚本 + cert.pem）压缩后
runtimeVersion  = pi-1.0.4-termux-140b597a9bd5
files           = 1294 条（每条 path + sha256）
symlinks        = 259 条
```

zip 内部布局（**不含任何 ELF，也不含任何 zip symlink 条目**）：

```
pi/dist/bundle/**            # @earendil-works/pi-coding-agent@1.0.4 完整 bundle（cli.js/cli-runtime.js/rpc-entry.js/chunks/…）
libexec/<git 脚本+数据>       # Termux git libexec/git-core 里的 24 个脚本，拍平到 libexec/ 供 GIT_EXEC_PATH
ca-bundle.crt                 # 188,900 B，来自 ca-certificates 1:2026.09.25 的 etc/tls/cert.pem
```

manifest.symlinks 的 259 条按 AssetInstaller 语义生成
（`path` = `filesDir/runtime/<相对路径>`，`target` = `nativeLibraryDir/<文件名>`），实测分布：

| 组 | 条数 | target |
|---|---|---|
| `bin/{bash,sh}` | 2 | `libpi_bash.so`（EnvFactory 的 `SHELL` 也指这里）|
| `bin/{node,nodejs}` | 2 | `libpi_node.so` |
| `bin/{git,git-upload-pack,git-receive-pack,git-upload-archive}` | 4 | `libpi_git.so` |
| `bin/<coreutils applet>` | 101 | `libpi_coreutils.so`（multicall，argv[0] 分派）|
| `libexec/git-*`（helper 名，覆盖 ELF 与脚本两种实体）| 146 | `libpi_git.so` |
| `libexec/git-remote-{http,https,ftp,ftps}` | 4 | `libpi_git_remote_http.so` |
| 合计 | **259** | `bin/` 109 + `libexec/` 150 |

版本校验：脚本先读 `pi-src/package.json` 断言 `version == 1.0.4`，不匹配即终止（防止
无声漂版本）；zip 用确定性打包（条目按名排序、`date_time=(1980,1,1,0,0,0)`、`external_attr=0`、
DEFLATE），两次构建的 zip sha256 一致；写完后重新逐条目解出并复算 sha256 与 manifest 对照
（先验飞行），保证 manifest 与 zip 不可能脱钩。

## 7. 第 3 步产物：带真实资产的 APK

`tools/runtime-packaging/assemble-apk.sh` 一次跑完「铺产物 → `:app:assembleDebug` → 体检」。

```
APK: /root/pi/dist/pi-host-full-debug.apk
     53,270,000 字节（50.8 MB）
     sha256 24f45b6fd4a3f6945642159cb09a4dd37cbe097440349e8151079ccfd8a146f9
     （对照：无资产的 pi-host-debug.apk 1,626,471 字节）
```

体检结果（全部 PASS）：

| 判据 | 实测 |
|---|---|
| `lib/arm64-v8a/*.so` 条目数 | 27 == out/jniLibs 27 |
| `assets/runtime/manifest.json` | 在（177,042 B，sha256 `de05ce57…` 与 job B 产物逐字节一致）|
| `assets/runtime/pi-runtime.zip` | 在（8,215,598 B，sha256 `abe3366c…` 与 job B 产物逐字节一致）|
| `android:extractNativeLibs` | `aapt2 dump xmltree` 报 `extractNativeLibs(0x010104ea)=true`（`useLegacyPackaging` 落地证据）|
| so 存储方式 | 27 个全部 DEFLATE 压缩 —— 这是 `extractNativeLibs=true` 的合法形态：APK 只负责传输，安装期由 PackageManager 解压落盘成真文件，正是我们要的（"必须未压缩"只适用于 `extractNativeLibs=false` 的直接 mmap 模式）|
| 从 APK 解出的 lib 目录再过 `smoke-elf.sh` | PASS（27 个 / 107M，AArch64 + DYN + `RUNPATH=$ORIGIN` + SONAME==文件名 + NEEDED 全本地命中）|
| zip 内容一致性（本机复算）| 1294 条目 == manifest 1294 条；随机抽样 5 条 sha256 复算全对；zip 内 `.so` 条目 0、symlink 条目 0 |

AGP 的 `stripDebugDebugSymbols` 报 "Unable to strip the following libraries, packaging them
as they are"（27 个全列）——因为 job A 已经用 NDK `llvm-strip` 剥过，AGP 的二次 strip 空手而归，
按原样打包，符合预期，不是错误。

## 8. 验证矩阵（全绿）

| 位置 | 任务 | 结果 |
|---|---|---|
| 本机 JVM | `:core:rpc:test` | 34 tests / 0 skipped / 0 failures（强制 `cleanTest` + `--no-build-cache` 真跑）|
| 本机 JVM | `:core:process:test` | 3 tests / 0 failures —— 37 用例零回归 |
| 编译机 | `:core:engine:testDebugUnitTest` | `AssetInstallerTest` 5/0/0（红线：原 5 用例语义未破坏）+ `RealRuntimeAssetsInstallTest` 2/0/0 |
| 编译机 | `:core:environment:testDebugUnitTest` | `EnvFactoryTest` 2/0/0 + `PathLayoutTest` 3/0/0 —— 12 用例零回归 |
| 编译机 | `:app:assembleDebug` | BUILD SUCCESSFUL |
| 编译机 | `smoke-elf.sh`（out/jniLibs 与 APK 解出目录各一遍）| PASS |
| 编译机 | job A 幂等（连跑两次 sha256 列表比对）| 逐字节一致 |
| 编译机 | job B 确定性（连跑两次 zip sha256 比对）| 一致 |

`RealRuntimeAssetsInstallTest` 的两个用例是规格书第 3 步要求的"沙盒安装演练"：
1. `real bundle installs, verifies every sha256 and links the whole ELF farm` —— 拿**真实
   8.2 MB zip + 真实 manifest.json**跑完 AssetInstaller 的 tmp→verify→switch→symlink 全流程，
   断言 `InstallResult.Installed`、`pi/dist/bundle/cli.js` 以 `#!/usr/bin/env node` 开头、
   `rpc-entry.js`/`ca-bundle.crt` 就位、**259 个 symlink 全部是真实符号链接且指向
   `nativeLibraryDir/libpi_*.so` 并可穿透读取**、`runtime.old`/`runtime.tmp` 无残留、
   marker 版本 == `pi-1.0.4-termux-140b597a9bd5`、第二次调用返回 `UpToDate` 且
   `runtimeOpens` 计数 == 1（不重复安装）；
2. `real archive carries only regular files and matches the manifest exactly` —— zip 条目集
   合与 `manifest.files` 完全相等、无越界路径（绝对/`..`/目录条目）、单一 mtime（确定性证明）、
   所有 symlink target 都匹配 `libpi_*.so`。

fixture 门控：产物不入 git，测试用 `org.junit.Assume` 在找不到
`tools/runtime-packaging/out/assets/runtime/` 时跳过（可用 `PI_RUNTIME_ASSETS` 显式指定目录）；
本次在编译机上产物存在，**实际执行**（`skipped="0"`），耗时 1.4 s + 0.17 s。

## 9. 已知遗留问题（都在"能跑通 spawn 链"之外，如实列出）

1. `runtime/libexec/` 里 24 个 git shell/perl/ruby 脚本**不能直接 exec**（`app_data_file`
   SELinux 限制，同第 4 节第 1 条）。它们是 `git submodule` / `git filter-branch` /
   `git mergetool` 一类低频子命令的 helper；常用路径（add/commit/diff/log/status/clone/push）
   全在 `libpi_git.so` + `libpi_git_remote_http.so` 里，不碰这些脚本。彻底解法是把这些也
   挪进 jniLibs 或改用 sh-in-process 解释，属后续里程碑。
2. **openssh 未打包**（`ssh://` / `git@…:` 协议不可用）。EnvFactory 的 PATH 尾接
   `/system/bin`，Android 系统里也没有 ssh 客户端；如需要，追加 `libpi_ssh.so` +
   `libpi_libssh*.so`（openssh 10.5p1 已在闭包清单里，成本约 1 MB）。
3. **gawk / grep / findutils / diffutils / tar / sed 未打包**，走 `/system/bin` 的
   **toybox** 兜底（POSIX 子集，GNU 扩展参数如 `grep -P`、`sed -i` 行为差异需注意）。
   pi bundle 的 shell 工具若用到 GNU 特性，会在运行期暴露。
4. `git-daemon` / `git-http-backend` / `git-imap-send` / `git-http-fetch` / `git-http-push` /
   `scalar` / `git-shell` 主动裁剪（服务端或交互场景，spawn 链不需要）。
5. node 的 `libpi_node.so` 体积里含约 **1.9 MB** patchelf 字符串表增长；如在意可用
   `--no-sort` / 预置 `.dynstr` padding 的方案再压，未做。
6. `pi-tui` 附带一个 **glibc** 的 `linux-arm64` `.node` 预编译插件（在 pi bundle 内，
   原样进 zip）。bionic 下能否 dlopen 尚未在真机验证——这是运行时风险，不在本里程碑判定范围。
7. 真机验证缺口：Robolectric 只能验安装器逻辑与 symlink 农场（已做），
   `execve(nativeLibraryDir/libpi_node.so)` + node 起 RPC 的端到端只能上 arm64 真机，
   留到下一里程碑（P2C）。

## 10. 脚本清单与职责（都在 git 里，产物不在）

| 文件 | 阶段 | 作用 |
|---|---|---|
| `pkg-index.py` | 侦察 | 解析 Termux `Packages` 索引（含多行续行），出 `pkg/version/filename/sha256` TSV |
| `recon-termux.py` | 侦察 | 沿 `Depends:` 求传递闭包（65 包），下载+校验+解包，打印每个 NEEDED 的归属包 |
| `elf-closure.py` | 侦察 | **权威**闭包器：`readelf -d` 逐 DT_NEEDED 解析实体文件，22 库 / 5 种子 / 0 未解析 |
| `recon-probe.sh` | 侦察 | 只读证据转储（git-core 分类、coreutils multicall、cert.pem、RUNPATH 普查、`readelf -h`）|
| `termux-pins.tsv` | 输入 | 23 个包的版本 + repo 路径 + sha256 钉死表 |
| `exec-map.tsv` | 输入 | 哪 5 个包内 ELF 提升为 `libpi_*.so` 可执行 |
| `elf-name-map.tsv` | 输入 | 22 个库的 `原始 NEEDED 名 → 包 → 实体文件 → libpi_*.so` 映射 |
| `build-jniLibs.sh` | job A | 下载→校验→解包→改名→patchelf（两趟）→llvm-strip→自检→清单/版本戳 |
| `build-pi-runtime.sh` | job B | 拼 stage（pi bundle + git libexec 脚本 + ca-bundle）→确定性 zip→manifest.json→复算校验 |
| `smoke-elf.sh` | 体检 | 6 项 ELF 断言，可对 out/jniLibs 或 APK 解出目录跑 |
| `assemble-apk.sh` | 第 3 步 | 铺产物→`assembleDebug`→APK 体检（so 数量 / 资产 / extractNativeLibs / 复跑 smoke-elf）；支持 `SKIP_BUILD` / `PREPARE_ONLY` |

产物目录 `out/`、铺进 app 的 `app/src/main/{jniLibs,assets}/` 均已 `.gitignore`
（107 MB so + 8 MB zip 不入 git；manifest 的 sha256 全部可从脚本 + pins 表重建）。

## 11. 编译机磁盘治理

规格书红线要求汇报 `du -sh`：

```
$ df -h /            # 首次 assembleDebug 后 2.6G；复跑测试+构建后 2.2G（见下）
/dev/vda1  49G   47G  2.2G  96% /

$ du -sh /root/runtime-pkg     # 中间产物（.deb 缓存 + 解包树 + 探测输出）
319M
  52M  dl/      （65 个 .deb，可删，重跑会自动重下）
 241M  arm64/   （解包树，侦察证据来源，保留）
  25M  pi-src/  （pi bundle 源）

$ du -sh /root/android-host/tools/runtime-packaging/out
141M   （jniLibs 107M + assets 8M + stage 25M）
```

磁盘余量从任务开始的 3.4 G 降到 2.2 G：一次 `:app:assembleDebug` 的 intermediates
（`mergeDebugNativeLibs` 会把 107 MB 原样再抄一遍）+ Robolectric 跑真实 8.2 MB zip 的
临时沙盒是主要消耗，且 `app/build` 不会被自动回收。
`dl/` 的 52 M `.deb` 按红线"多余的 .deb 缓存可清"列为可回收项，本次**未删除**——
保留它是为了让 `build-jniLibs.sh` 的幂等复跑不必重新联网下载；下一个里程碑如果磁盘
吃紧，回收顺序：`app/build/intermediates` → `runtime-pkg/dl`（52 M）→ `out/stage`（25 M）。
