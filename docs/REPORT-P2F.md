# REPORT-P2F — spawn 热修（cwd 修复 + linker64 降级 + 全量诊断上屏 + INTERNET）

基线：`1f77127`（P2D 报告）→ P2E 收尾 `373c92c` → 本轮四个提交：
`fac7e54`(docs RECON) → `261cb06`(core/engine) → `635f120`(feature/chat) → `fca8964`(app)。

## 1. 结论摘要

- 交付物 §4.1/§4.2/§4.3/§4.4/§4.5 **全部落地**，无降级项：`§4.4` 里那句
  「若剪贴板断言不便实现可降级为按钮存在」没有用到——Robolectric 的 ShadowClipboardManager
  接住了 `LocalClipboardManager.setText`，剪贴板内容按**逐字相等**断言（`InstallGateFailedTest` 用例 02）。
- §1 成功判据②（若仍失败则全文可见可复制）：本机全绿，具备交付条件。
  判据①（真机引擎 Ready）必须在真机重装后观测，**本机与编译机都没有连设备**，见 §8 第 6 条「未执行」。

## 2. 根因与修复对照

| # | 根因（RECON-P2F §2/§3） | 修复落点 | 可观测证据 |
|---|---|---|---|
| 1 | `filesDir/projects/default` 全仓无人创建；ProcessBuilder 子进程 **chdir 先于 execve**，两种失败共用同一条 errno 管道 → 报 `error=2` 时消息里的路径是 **exe**，说谎 | `core/engine/.../SessionSupervisor.kt:223` `workingDir?.let { w -> runCatching { java.io.File(w).mkdirs() } }`（try 块内、`starter.start(...)` 之前） | `SessionSupervisorTest` 用例 12/13/14；**副作用实证**：编译机（root）跑完 engine 测试后 `/data/user/0/works.earendil.pihost/files/projects/default` 真实出现（`ls -ld` 实录见 §7.1），本机没跑该用例故无此目录；详见 §7.1 |
| 2 | SELinux `apk_data_file` 对 `untrusted_app` 的 execve 拒绝（概率低但未排除） | 新类 `core/engine/.../ExecFallbackStarter.kt`（115 行）：errno==13 → `listOf("/system/bin/linker64") + command` 重试一次；非 13 / 无 errno → 原样重抛 | `ExecFallbackStarterTest` 用例 01–09 |
| 3 | spawn 失败过程零落盘，事后无从判断哪一次尝试用了什么 argv | 新类 `SpawnDiagnosticsSink.kt`（62 行）写 `File(PathLayout.homeDir(ctx), "spawn-diagnostics.log")`；`EngineModule.kt:78-88` 接线 `ExecFallbackStarter(JvmProcessFactory, sink)` | `SpawnDiagnosticsSinkTest` 6 条（01 首写建父目录+头部 banner+行 / 02 后续只追加 / 03 超字节上限整文件重写 / 04 IO 失败吞掉不上抛 / 05 banner 自己抛也包住 / 06 文件名与 256KB 阈值对齐规格书） |
| 4 | 显示层三处截断：顶栏 `take(40)`、Failed 屏 `maxLines=6 + Ellipsis`、VM 里 `CAUSE_MAX_LEN=240` 的 `.truncate()`（**§3.1 没列这一条**） | `InstallGate.kt:68-105`（滚动容器，撤 maxLines/Ellipsis）；`ChatViewModel.kt:191`/`:225`/`:242` 三处（其中 191、242 撤 `.truncate()`；规格书 §4.2(c) 写的 189/220/235 是改动前行号）；`ErrnoHint.kt`（errno 人话） | `InstallGateFailedTest` 5 条 + `ChatViewModelTest` 22/23；**变异检查**见 §4 |
| 5 | Manifest 无 `INTERNET` | `app/src/main/AndroidManifest.xml:12` | `ManifestPermissionTest` 3 条 + §7.2 新 APK `aapt2 dump badging` 实录 |
| 6 | versionCode 不升则覆盖不掉带 bug 的旧包 | `app/build.gradle.kts` `versionCode = 2` / `versionName = "0.1.1"` | `ManifestPermissionTest` 用例 03 + badging 实录 |

顶栏 `ChatScreen.kt:163` 的 `take(40)` 按 §4.2(d) **不动**，`MainActivityTest:455-457` 保持绿（§7 表 `:app` 行）。

## 3. 改动清单（P2F 独占，`git diff --name-status fac7e54^..HEAD` 共 **19 个路径**：9 新增 / 10 修改）

**core/engine（§4.1）**
- `core/engine/src/main/kotlin/works/earendil/pihost/core/engine/SessionSupervisor.kt` M — spawn 前 mkdirs（:223）
- `core/engine/src/main/kotlin/works/earendil/pihost/core/engine/ExecFallbackStarter.kt` A — 115 行，纯 JVM
- `core/engine/src/main/kotlin/works/earendil/pihost/core/engine/SpawnDiagnosticsSink.kt` A — 62 行
- `core/engine/src/main/kotlin/works/earendil/pihost/core/engine/di/EngineModule.kt` M — starter 提供者签名加 `@ApplicationContext`，装 sink
- 测试：`ExecFallbackStarterTest.kt` A(9) / `SpawnDiagnosticsSinkTest.kt` A(6) / `SessionSupervisorTest.kt` M(+3) / `FakePiProcess.kt` M（记录 `workingDirExistedAtStart`）

**feature/chat（§4.2）**
- `ErrnoHint.kt` A — 37 行纯函数（errno 2/13 追加人话，其余直通）
- `ChatViewModel.kt` M — 三处 Failed 过 `humanize`，两处撤 `.truncate()`
- `ui/InstallGate.kt` M — Failed 分支：`heightIn(max=280.dp)` + `verticalScroll` 容器、去 maxLines/Ellipsis、加「复制详情」`OutlinedButton`
- `ui/ChatTags.kt` M — `INSTALL_COPY`
- 测试：`ui/InstallGateFailedTest.kt` A(5) / `ChatViewModelTest.kt` M(+2)

**app（§4.3）**
- `app/src/main/AndroidManifest.xml` M — INTERNET
- `app/build.gradle.kts` M — versionCode 2 / versionName 0.1.1
- `app/src/test/kotlin/works/earendil/pihost/ManifestPermissionTest.kt` A(3)

**docs**：`docs/RECON-P2F.md` A（155 行）+ 本报告 `docs/REPORT-P2F.md` A。
红线复核见 §5。

## 4. 变异检查（证明护栏不是假阳）

`assertTextEquals` 这类**文本**断言证伪不了视觉截断：Compose 的 `SemanticsProperties.Text`
本来就存全文，`maxLines + Ellipsis` 只裁画布不裁语义。因此护栏换成节点高度
（`InstallGateFailedTest.kt:93`，`assertHeightIsAtLeast(180.dp)`）。两个阈值都是**实测值**，不是估算：

| 实现 | `INSTALL_CAUSE` 节点高度（编译机实测） | `assertHeightIsAtLeast(180.dp)` |
|---|---|---|
| 把 `maxLines=6 + TextOverflow.Ellipsis` 加回 `InstallGate.kt:83` | `Actual height is 136.0.dp` | **红**（`java.lang.AssertionError at InstallGateFailedTest.kt:90`） |
| 当前实现（不截断） | `Actual height is 196.0.dp`（临时把阈值改 9999.dp 探出来的） | 绿 |

- 那一次变异跑的栈里 `assertTextEquals(longCause)`（`:83`）**没红、红在下一句高度断言** —— 直接印证
  「文本断言抓不住截断」这条判断。
- 栈里的 `:90` 与现在的 `:93` 是同一句：变异跑之后才把护栏的理由注释补长了 3 行，行号随之漂移（按内容定位）。
- 阈值取 180 而不是贴着 150：离截断档 44dp（连 `maxLines=7/8` 这种「少截一点」的回退也抓得住），离实测 196 留 16dp。
- 变异跑完已回滚：`git diff` 里 `InstallGate.kt` 只剩 `maxLines = 2`（Installing 档，非本轮改动）。

## 5. 红线复核（§2）

- **冻结区零改动**：
  `git diff --stat 1f77127..HEAD -- core/rpc core/process/src/main core/environment docs/protocol.md tools/capture tools/runtime-packaging app/src/main/jniLibs`
  → **无输出**（空）。同一集合再按 P2F 独占范围 `fac7e54^..HEAD` 复核一次，同样空；
  再加 `gradle/libs.versions.toml`（白名单）一起测，也是空。
- **P2F 全部改动只有 19 个路径**（`git diff --name-status fac7e54^..HEAD`，逐条列在 §3），
  全部落在 `core/engine`、`feature/chat`、`app`、`docs` 四个允许区。
- **公共 API 不变**：`git diff fac7e54^..HEAD -- core/engine/src/main` 里被删的声明行只有
  一条 `- fun processStarter(): ProcessStarter`，对应新的一条
  `+ fun processStarter(@ApplicationContext ctx: Context)` —— §4.1(c) 明确允许（DI 提供者签名，模块内部）。
  `SessionSupervisor` / `ChatEngine` / `RuntimeInstall` 三个类没有任何签名删除；
  `ChatEngine`、`RuntimeInstall`、`ProcessStarter` 连文件名都没出现在改动清单里（`grep` 实测无命中）。
- **`ProcessStarter` 只实现不修改**：`ExecFallbackStarter` 是 `core/engine` 里的新类
  （`class ExecFallbackStarter(...) : ProcessStarter`，只出现 `override fun start`）。
- **不新增第三方依赖**：`gradle/libs.versions.toml` 未改（上面已证），本轮没加任何 `implementation` 行。
- **版本必须升**：`versionCode 1→2`、`versionName 0.1.0→0.1.1`，见 §7.2 的 `aapt2 dump badging` 实录。
- **提交纪律**：4 次提交全部显式路径 `git add`（无 `git add -A`/`.`），一块一提交，顺序同规格书 §6。

## 6. 本机（dshbox）冻结模块基线

命令：`JAVA_HOME=/root/pi/.tools/jdk-21.0.12.1+1 /root/pi/.tools/gradle-8.13/bin/gradle --no-daemon
:core:process:cleanTest :core:process:test :core:rpc:cleanTest :core:rpc:test --no-build-cache`
→ `BUILD SUCCESSFUL in 21s`（11 actionable tasks: 4 executed, 7 up-to-date）。

| 模块 | 测试类 | tests | fail | err |
|---|---|---|---|---|
| `:core:process:test` | JvmProcessTest | 3 | 0 | 0 |
| `:core:rpc:test` | GoldenReplayTest | 9 | 0 | 0 |
| | LineFramerTest | 9 | 0 | 0 |
| | PiSessionTest | 8 | 0 | 0 |
| | RpcModelTest | 7 | 0 | 0 |
| | PiDesktopE2eTest | 1 | 0 | 0 |
| **合计** | | **37** | **0** | **0** |

**37 基线不回归**（规格书 §7 要求的数）成立，逐类相加 = 3+9+9+8+7+1 = 37。

- e2e 这条要说清：规格书 §7 写的是「保持 env-gated skip」，门在
  `core/rpc/src/test/.../PiDesktopE2eTest.kt:46` 的 `assumeTrue(environmentReady())`（cli/home/8787 mock/node 任一缺失即 skip）。
  本机这套抓包环境**齐**，所以这一条这轮**没有 skip，而是真跑了 4.52s 并绿**
  （XML：`<testcase name="real pi rpc roundtrip ..."/>`，`time="4.52"`，无 `<skipped/>`）。
  即门没坏、也没被绕过——只是本机命中了「环境就绪」那一侧。

## 7. 编译机矩阵（规格书 §7 全量）+ assembleDebug + APK 双端落盘

矩阵脚本 `/root/matrix.sh`（P2E 那份原样复用：逐模块一次 gradle 调用、`--no-build-cache`、
每模块先 `cleanTestDebugUnitTest`、模块间 `pkill KotlinCompileDaemon`、打包前 `rm` 掉旧 APK 逼 `packageDebug` 真跑）。
本轮 7 步全部 `RC=0`，`MATRIX-DONE`，逐段 `BUILD SUCCESSFUL`：37s / 1m3s / 52s / 54s / 1m / 59s / 44s。

| 模块 | 测试类（tests/fail/err） | 合计 | 门槛 | 判定 |
|---|---|---|---|---|
| `:core:environment` | EnvFactoryTest 2 / PathLayoutTest 3 | **5** / 0 / 0 | 5 | ✓ |
| `:core:engine` | AssetInstallerTest 5 / ChatEngineAdapterTest 6 / DataStoreProbeTest 4 / **ExecFallbackStarterTest 9** / PiEngineServiceTest 7 / PiSpawnTest 4 / RealRuntimeAssetsInstallTest 2 / RuntimeInstallTest 5 / **SessionSupervisorTest 14** / **SpawnDiagnosticsSinkTest 6** / SettingsRepositoryTest 6 | **68** / 0 / 0 | ≥57 | ✓（50 基线 + 新增 18） |
| `:feature:chat` | **ChatViewModelTest 23** / ChatScreenUiTest 6 / ComposeProbeTest 3 / HiltComposeProbeTest 1 / HiltProbeTest 1 / **InstallGateFailedTest 5** / MarkwonRenderTest 4 / MarkwonSmokeTest 4 | **47** / 0 / 0 | ≥43 | ✓（40 基线 + 新增 7） |
| `:feature:sessions` | SessionScannerTest 10 / SessionsViewModelTest 12 / SessionsScreenTest 3 | **25** / 0 / 0 | 25 | ✓ |
| `:feature:settings` | SettingsViewModelTest 16 / SettingsScreenTest 6 | **22** / 0 / 0 | 22 | ✓ |
| `:app` | **MainActivityTest 11**（含 `:457` 顶栏断言 `assertTextEquals("失败：spawn 失败：ENOENT")`，对应 `ChatScreen.kt:163` 的 `take(40)` 按 §4.2(d) 未动 → 仍绿）/ **ManifestPermissionTest 3** / PiEngineServiceWiringTest 3 | **17** / 0 / 0 | ≥16 | ✓（14 基线 + 新增 3） |
| **编译机合计** | | **184**，fail 0 / err 0 / skip 0 | | 全绿零红 |

跑完后编译机资源实录（规格书 §8 要求磁盘 ≥4G）：`/dev/vda1 49G 44G 4.5G 91%`，`Mem available 3441M`。

### 7.1 测试副作用（如实记录，已清理）

`SessionSupervisorTest` 的 `WORKING_DIR` 用的是设备路径常量，而 mkdirs 修复让它**真的去建目录**；
编译机是 root，所以 `/data/user/0/works.earendil.pihost/files/projects/default` 在跑完后确实存在：

```
drwxr-xr-x 2 root root 4096 Oct  6 23:15 /data/user/0/works.earendil.pihost/files/projects/default
```

本机这一轮只跑 `:core:process` / `:core:rpc`（没跑 engine），同路径自然不存在；
本机 `/data/user/0` 的属主就是跑构建的 `dshbox`(uid 10335)，所以「本机没有」
**不能**当反证，只说明本机没跑过这条用例。真正的手是编译机（root）——
也正因为它是 root，这条修复才会写出界外目录，故下面顺手清理。
已按 `rm -rf /data/user/0/works.earendil.pihost` 清理（`find` 先列出四个路径，全部是本测试造的）。

### 7.2 新 APK 体检

`aapt2 dump badging`（编译机 build-tools 36.0.0）实测：

```
package: name='works.earendil.pihost' versionCode='2' versionName='0.1.1' compileSdkVersion='36'
minSdkVersion:'26'  targetSdkVersion:'35'  native-code: 'arm64-v8a'
uses-permission: FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE / POST_NOTIFICATIONS / **INTERNET**
uses-permission: works.earendil.pihost.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION（aapt 自动生成，非本轮引入）
```

旧包基线（同一命令、替换前实测）：`versionCode='1' versionName='0.1.0'`，权限只有前三条 + 那条自动生成 —— **INTERNET 是本轮新增的第 4 条**，版本也从 1/0.1.0 变成 2/0.1.1。

### 7.3 双端落盘（§4.5）

| 落点 | 尺寸 | sha256 |
|---|---|---|
| 编译机 `/root/apk-serve/2dce9c4ca97c/pi-host-full-debug.apk` | 63,761,093 B | `ca754f966b7b162c027a1296c310fe95b92b4fe88cd7462b8639eed07f60affd` |
| 本机 `/root/pi/dist/pi-host-full-debug.apk` | 63,761,093 B | `ca754f966b7b162c027a1296c310fe95b92b4fe88cd7462b8639eed07f60affd` |

两处 sha256 **逐字相等**，URL 不变（同名覆盖）。对比旧版：63,752,089 B → 63,761,093 B，
**+9,004 B**（≈ 一条 uses-permission + 三个新类 + 文案常量，与「应与旧版近似」吻合）。
sha256 分段复核过（`len=64`，22/22/20 三段拼接），不是抄来的单点回显。

## 8. 偏差、遗留与未执行

1. **诊断落点从 runtimeDir 改到 homeDir**（规格书 §9 已定案，这里补一条实测理由）：安装器原子切换会清 runtimeDir。
2. **§3.1 漏了第三个截断点**：`ChatViewModel` 的 `.truncate()`（`CAUSE_MAX_LEN=240`）。
   只撤 §3.1 列的两处（顶栏 + maxLines）拿到的仍是**被 240 字符砍过的**消息，
   「全文 + 复制」会是假的，所以本轮一并撤掉。`truncate` 本身没删——`:626` 的 `_retryNote` 还在用。
3. **PI_OFFLINE 保留**（§3.6/§4.1(d) 判明）：`grep -rn PI_OFFLINE /root/pi/.tools/pi-1.0.4/packages --include='*.ts'`
   12 处命中逐处定性见 `docs/RECON-P2F.md` §4；`EnvFactory.kt:24` 与 `EnvFactoryTest.kt:39` 都没改。
   遗留风险：`modelNetworkEnabled=false` 会关掉**远端模型目录刷新**（`model-runtime.ts:239/243/850`），
   真机上若发现「模型列表不全」，改法是一行删掉 `EnvFactory.kt:24` 并同步 `EnvFactoryTest` 期望值。
4. **护栏从「文本断言」换成「高度断言」**（§4 的变异检查）：这不是加戏，是因为
   `SemanticsProperties.Text` 存全文，旧实现也能过文本断言。`assertHeightIsAtLeast(180.dp)`
   的两个阈值数（136.0 / 196.0）都是实测。
5. **`ExecFallbackStarterTest` 用 TemporaryFolder 派生的缺失目录，不用设备路径常量**：
   与 §7.1 那个副作用同源——若两个测试类共用 `/data/user/0/...`，`(不在)` 断言会随执行顺序翻脸。
6. **未执行（必须真机才能做的两步）**：
   - §1 成功判据①「真机重装后引擎进入 Ready」：**未执行——两侧都没有设备**。
     本机 `adb: command not found`（根本没装 platform-tools）；
     编译机 `/root/android-sdk/platform-tools/adb devices` 实测 `List of devices attached` 下为空。
     有设备的机器上执行：`adb install -r pi-host-full-debug.apk`
     （或直接在真机浏览器装 `/root/apk-serve/2dce9c4ca97c/` 那个不变 URL；versionCode 2 > 1，允许覆盖安装）。
   - 装完看诊断文件（路径按 `PathLayout.kt:23/34` 推出来的：`homeDir = File(filesDir, "home")`）：
     `adb shell run-as works.earendil.pihost cat files/home/spawn-diagnostics.log`
     （sink 只在 `runCatching` 里写，且 `SpawnDiagnosticsSink.kt:37` 会 `parentFile?.mkdirs()`，
     所以「runtime 还没装、第一次 spawn 就失败」这种情况也能落盘；`run-as` 可读应用私有目录）。
     若仍 Failed，Failed 屏「复制详情」→ 直接贴回报告（剪贴板内容与 `state.cause` 逐字相等，见 §4）。
   - 降级链在真机上是否被触发，只能由这一轮的 argv 记录判定：日志里出现
     `降级重试 | 改用 /system/bin/linker64 装载` 即 errno 13 命中；只有 `失败 | errno=2` 则是 exe 真缺失。

## 9. 复现命令（逐条可直接跑）

```bash
# —— 编译机（64.90.11.50，密钥 pihost-builder）——
bash /root/pi/.tools/bbuild.sh sync                       # 本机 → /root/android-host（含 .git，排掉 .gradle/.kotlin）
bash /root/pi/.tools/bbuild.sh gradle ':core:engine:cleanTestDebugUnitTest :core:engine:testDebugUnitTest \
  --no-build-cache -Dorg.gradle.jvmargs=-Xmx1100m -Dkotlin.daemon.jvmargs=-Xmx700m'   # 换模块名即可逐模块跑
bash /root/pi/.tools/bbuild.sh raw 'bash /root/matrix.sh > /tmp/matrix.log 2>&1; cat /tmp/matrix.sum'  # 全量矩阵+打包
bash /root/pi/.tools/bbuild.sh raw 'python3 /root/tally.py /root/android-host/core/engine/build/test-results/testDebugUnitTest'
bash /root/pi/.tools/bbuild.sh raw 'aapt2=/root/android-sdk/build-tools/36.0.0/aapt2; $aapt2 dump badging /root/apk-serve/2dce9c4ca97c/pi-host-full-debug.apk | grep -i "^package:\|^uses-permission"'
bash /root/pi/.tools/bssh.sh get /root/apk-serve/2dce9c4ca97c/pi-host-full-debug.apk /root/pi/dist/pi-host-full-debug.apk

# —— 本机（dshbox，只跑冻结 JVM 模块）——
cd /root/pi/android-host && JAVA_HOME=/root/pi/.tools/jdk-21.0.12.1+1 /root/pi/.tools/gradle-8.13/bin/gradle \
  --no-daemon :core:process:cleanTest :core:process:test :core:rpc:cleanTest :core:rpc:test --no-build-cache

# —— 红线复核 ——
git diff --stat 1f77127..HEAD -- core/rpc core/process/src/main core/environment docs/protocol.md \
  tools/capture tools/runtime-packaging app/src/main/jniLibs gradle/libs.versions.toml   # 期望：无输出

# —— 变异检查（§4）：把 maxLines=6 + overflow=Ellipsis 临时加回 InstallGate.kt 的 cause Text ——
bash /root/pi/.tools/bbuild.sh gradle ':feature:chat:testDebugUnitTest \
  --tests "works.earendil.pihost.feature.chat.ui.InstallGateFailedTest" --no-build-cache \
  -Dorg.gradle.jvmargs=-Xmx1100m -Dkotlin.daemon.jvmargs=-Xmx700m'   # 期望：用例 01 红在高度断言（136.0 < 180）
```

> 注：`-Dorg.gradle.jvmargs=-Xmx1100m -Dkotlin.daemon.jvmargs=-Xmx700m` 是 P2E 实证过的限额串
> （`-Dkotlin.daemon.jvm.options=` 那条会让 gradle 打 usage 帮助，别用）；
> `bbuild.sh gradle` 内部已带 `cd $REPO`，任务串**只填 gradle 参数**，别把 shell 命令塞进去（会被拼到 gradle 后面）。

## 10. 计数汇总（改动清单 + 测试计数）

- 改动：19 个路径（新增 9 / 修改 10；`git diff --shortstat fac7e54..99980f1` = 1490 insertions(+) / 20 deletions(-)，
  取报告初版那个 commit 为基准，免得把本报告的自我增长算进代码量），
  全部在 `core/engine`、`feature/chat`、`app`、`docs` 四个允许区；
  新增主源码 214 行（`ExecFallbackStarter.kt` 115 + `SpawnDiagnosticsSink.kt` 62 + `ErrnoHint.kt` 37，含 KDoc；`wc -l` 实测），另有 `InstallGate.kt`/`ChatViewModel.kt`/`SessionSupervisor.kt`/`EngineModule.kt` 四处定点改动，未删除任何既有公共函数。
- 测试：编译机 **184** 全绿（environment 5 / engine 68 / chat 47 / sessions 25 / settings 22 / app 17），
  本机 **37** 全绿（process 3 + rpc 34，含真跑的 e2e 1）→ **合计 221 用例，0 红 0 跳过**；
  本轮新增 **28** 条（engine 18：9+6+3；chat 7：5+2；app 3）——与 P2E 的 193（156+37）对得上：
  156 + 28 = 184 编译机，加本机 37 = 221。
- 提交：`fac7e54` → `261cb06` → `635f120` → `fca8964` → 本报告（第 5 步「矩阵 + 双端落盘」本身不产生源码改动，
  其产物就是本报告的 §7 数字与 sha256，故与第 6 步合并成这一个 commit）。
- 一处如实纠偏：提交 `fca8964` 标题写的「16 用例绿」是照规格书 §7 门槛（`app ≥16`）写的，
  实测 `:app` 是 **17**（MainActivityTest 11 + ManifestPermissionTest 3 + PiEngineServiceWiringTest 3）。
  门槛仍满足，数字以本报告 §7 为准。
