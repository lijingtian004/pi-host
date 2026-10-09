# REPORT-P3C — 新建项目 + git 可用 + 顶栏分支（versionCode 8 / 0.1.7）

> 日期：2026-10-09 ｜ 基线：`80f5e13`（P3B-FIX1 收尾）｜ 交付：`dist/pi-host-full-debug-0.1.7-p3c.apk`
> 任务来源：用户指令「添加 git，允许在应用私有目录新建文件夹」，经 P3C 计划批准（9 个小节 + 红线重划）。
> **本期起点有一次目标变更**：终点从「消费者上架」改为「这台 rooted 自有设备自用闭环」，
> 因此 P3 原定的「资产 OTA + 验签」「国产 ROM onboarding」「Play specialUse/dataSafety」
> **移出路线图**（见 §8.6，这是目标变更，不是未完成）。

---

## 1. 为什么是这三件事（实测事实，不是推测）

| # | 事实 | 取证命令 | 结论 |
|---|---|---|---|
| 1 | `projects/` 只有 `default` 一个目录，且**完全空、无 `.git`** | 机上 `ls -la files/projects/default` → `total 0`；`ls -d .../default/.git` → `No such file` | 「新建文件夹」的入口确实不存在 |
| 2 | 会话工作目录写死单项目 | `EngineModule.kt:141` `const val DEFAULT_PROJECT="default"`、`:216` 直接取它；注释自陈「P3 才有项目切换」 | 本期就是那条注释指的那一期 |
| 3 | git **已随运行时发货且真机可执行** | 机上直接 exec `lib/arm64/libpi_git.so --version` → **`git version 2.56.0`**；`runtime/bin` 有 `git/git-receive-pack/git-upload-pack`，`libexec` 180 个子命令 | 不缺二进制，缺的是「能用」 |
| 4 | **git 提交身份零配置** | `grep -rniE "user\.name\|user\.email\|GIT_AUTHOR\|GIT_COMMITTER\|gitconfig\|safe\.directory" --include=*.kt --include=*.kts .` → **0 命中** | pi 一跑 `git commit` 必 `fatal: unable to auto-detect email address` |
| 5 | 设备上没有任何系统级 gitconfig | `ls /etc/gitconfig` / `ls /system/etc/gitconfig` → 均 `No such file or directory`（`/etc` 本身存在） | 第 4 条没有兜底 |
| 6 | 宿主 UI 完全没有 git 功能 | `grep -rniE '\bgit\b\|branch' --include=*.kt core feature app`（排除 `/test/`）→ 只命中注释里的「分支」二字 | 方案A §12 的 P1 判据「git 分支名显示」从未落地 |
| 7 | **工具通路从没在真机触发过** | 机上唯一会话 `files/home/.pi/agent/sessions/--…-default--/2026-10-09T11-38-06…jsonl`：`type` 全集只有 `session/model_change/thinking_level_change/message`，含 `toolCall` 的行数 = **0**；`files/audit/` 不存在 | 见 §8.1，这条决定了本期验收的重心 |

**本轮同时纠正了两条此前写进报告的错判**（证据链在上面）：

- **对话链路其实早就在真机上通了**。P3B §11 与 P3B-FIX1 §8.3 都写「真机验证未执行」，
  但那条 session 文件里有三轮真实往返（`你好`/`接受`/`你好`），两条 assistant 回复带 thinking 块，
  且 `model_change`、11 次 `thinking_level_change` 都落进了会话——说明 RPC 桥、流式、中转出网、
  思考档、运行时切模型**全部实机有效**。那半笔「真机欠账」是虚账。
- **`switch_session` 不需要重生子进程**。`rpc-mode.ts:603` 只传 `sessionPath`，
  `agent-session-runtime.ts:210-215` 会用目标会话记录的 cwd 重建服务（`:211 assertSessionCwdExists` 兜底），
  pi 的工具拿的是显式 cwd（`tools/bash.ts:86`），只有 OS 的 `process.cwd()` 不动。
  所以「跨项目打开会话」是白送的，本期**不拦**（§3.7）。

---

## 2. 修复面（26 改 + 6 新增，`git diff --shortstat 80f5e13` = **+1468 / −49**）

### 2.1 新增（core/engine 两个新包，全部纯 JVM 可测）

```
core/engine/.../engine/projects/ProjectNames.kt        校验 + 反查（纯函数，无 Android API）
core/engine/.../engine/projects/ProjectStore.kt        目录账本（list / pathOf / create / exists）
core/engine/.../engine/git/GitSpawn.kt                 宿主 permitted 的两条 argv（红线 12 的落点）
core/engine/.../engine/git/ShortProcessRunner.kt       短命令执行底座（deadline / 字节上限 / stderr 泵）
core/engine/.../engine/git/ProcessGitInitializer.kt    写面：git init
core/engine/.../engine/git/ProcessGitProbe.kt          读面：git status --porcelain=v1 --branch
core/engine/.../engine/git/GitIdentity.kt              $HOME/.gitconfig 播种（仅当不存在）
feature/sessions/.../ProjectsSource.kt                 UI 侧接缝（接口 + FileProjectsSource）
```

### 2.2 关键签名变化（改前后）

```kotlin
// SessionSupervisor：工作目录从「装配时固定」改成「出生时采样」
-    private val workingDir: String?,
+    private val workingDirFor: () -> String?,          // 只在 start() 调一次
-    private class Entry(val sessionId: String)
+    private class Entry(val sessionId: String, val workingDir: String?)   // val，无 @Volatile

// SettingsRepository：第四键
+    val KEY_ACTIVE_PROJECT: Preferences.Key<String> = stringPreferencesKey("active_project")
+    data class AppSettings(..., val activeProject: String = ProjectNames.DEFAULT_NAME)
+    val activeProject: String get() = settings.value.activeProject        // 同步，给 spawn lambda
+    suspend fun setActiveProject(name: String?)

// AgentConfigFiles：原子写的实现从 JSON 专用变成文本通用，JSON 变成薄封装
+    fun writeTextAtomic(dir: File, target: File, content: String) { … }    // tmp+rename+finally 清残骸
-    fun writeAtomic(dir, target, content: JsonObject) { …原文… }
+    fun writeAtomic(dir, target, content: JsonObject) = writeTextAtomic(dir, target, content.toString())

// GitSpawn：红线 12 的两条命令，收在一个类里
+    fun initCommand(dir: String)   = [libpi_git.so, -C, dir, init]
+    fun statusCommand(dir: String) = [libpi_git.so, -C, dir, status, --porcelain=v1, --branch]

// ChatViewModel：尾默认值，既有构造点零改动（照 P3A 的 auditSink 那招）
+    private val gitProbe: GitProbe = NoopGitProbe,
+    val git: StateFlow<GitSnapshot?>
+    fun refreshGit()
```

---

## 3. 三个设计决定（每个都有反例支撑，不是风格偏好）

### 3.1 `workingDir` 必须出生钉死，**不能是活 lambda**

`--session-id` 的查找是按 cwd 分域的（`main.ts:435-448`）：未命中时 pi **只打一句 stderr 警告**
就 `SessionManager.create(cwd, …, {id})` 新建一个同 id 的空会话。而 `SessionSupervisor`
的退避重启会对**同一个 UUID** 最多 spawn 4 次（`:293-303`）。
如果 spawn 时重取项目，「用户切了项目 → 旧会话崩溃重启」就会把项目 A 的 UUID 拿到项目 B 目录下
找不到 → 当场造一个空会话：**历史从 UI 消失，且没有任何错误可看**。

`case15` 钉的就是这条：supplier 第一次返回 A、之后每次返回 B，断言四次 spawn 的 cwd 全是 A
**且 supplier 调用次数恰好为 1**。`case17` 更狠——切项目发生在「崩溃」与「重启」之间时，
既不许 spawn 到新目录，也不许把新目录**建出来**（`assertFalse(dirB.exists())`）。

副作用写进 UI 文案：**切活动项目不打断在跑的会话**，只对下一次 `engine.start()` 生效。

### 3.2 身份落 `$HOME/.gitconfig`，不落环境变量

最直觉的做法是往 spawn env 塞 `GIT_AUTHOR_NAME`，但 `EnvFactory` 在冻结区
（`core/environment` 零 diff，红线 2）一字不能动。`$HOME` 已经是 `filesDir/home`
（`EnvFactory.kt:20`），git 的全局配置路径正是 `$HOME/.gitconfig`——同一条通道、不碰冻结面、
`git config --global` 的读口天然一致。

**幂等性按「存在与否」判，不按「内容对不对」判**：手工写过或 pi 写过的永不覆盖，
包括**故意留空**的文件（`GitIdentityTest` 第 09 条专门钉这条）。
两个调用点：`ProjectStore.create`（新建项目）与 `RuntimeInstallImpl.ensureInstalled`
的**两条成功支**（含 `UpToDate`）——后者是为本机这种情况准备的：这台设备的运行时早就装好了，
升级后走的是 `UpToDate`，只挂 `Installed` 分支的话 `.gitconfig` 永远不会出现。

不写 `safe.directory`（仓库属主 == 进程 uid `u0_a401`，dubious-ownership 不该触发；真机若报警算环境异常，见 §8.2），
不写 `credential.helper`（红线 11/13）。

### 3.3 顶栏分支只能宿主 shell-out，且**不许问 pi**

三条路都堵：① `get_state` 的载荷里**没有 `cwd`**（`rpc-mode.ts:448-464`，只有 `sessionFile`）；
② 反解 `--…--` slug 要动 `core/rpc` + `docs/protocol.md`，两个都在冻结区；
③ 让 pi 用 bash 跑 git，等于把「顶栏该显示什么」交给一次模型往返——花钱、等流式、且模型可以拒绝。

注入的是 `ProcessStarter`（`core/process/ProcessHandle.kt:45-47`）而不是 `ProcessBuilder`：
`ExecFallbackStarter` 只在 **errno 13 EACCES** 时降级 `/system/bin/linker64`（`:58`），
那正是在设备上能跑通 git 的同一条通路；裸 `ProcessBuilder` 会在 pi 的 bash 工具能成功的场合先失败。
代价是冻结接口没有定时 `waitFor`、不合并流，所以 **deadline、字节上限、stderr 独立线程泵**
三件事都得自己写（`ShortProcessRunner` 类头逐条记了为什么），`ProcessGitProbeTest` 第 12 条
「stderr 灌 8KB 不死锁」就是这套编排的对拍。

刷新时机**只有三个**（Ready / `AgentSettled` / 用户点 chip），**永不轮询**——
`case76` 用 `advanceTimeBy(10 分钟)` 断言探针次数不变。
`onSessionRolled` 故意不挂：本期探针目录 = 活动项目目录，跨项目续用时重读会给出
「你的活动项目在 main」这种**答非所问的真话**，不如不读（见 §8.3）。

---

## 4. 测试矩阵（编译机 `ser843493624838`，gradle 8.13 + JDK21，一条命令一个模块）

| 模块 | 基线 | P3C | 增 | 新文件 / 新代 |
|---|---|---|---|---|
| `core/process` | 3 | 3 | — | 冻结区 ✓ |
| `core/rpc` | 34 (skip 1) | 34 (skip 1) | — | 冻结区 ✓ |
| `core/environment` | 5 | 5 | — | 冻结区 ✓ |
| `core/engine` | 147 (skip 2) | **240** (skip 2) | **+93** | ProjectNames 23、ProjectStore 17、**git 三面 46**（ShortProcessRunner 14 + ProcessGitProbe 22 + GitIdentity 10）、Supervisor 3、SettingsRepo 4 |
| `feature/chat` | 147 | **167** | **+20** | `case73`–`case86`（VM 14）+ `ChatScreenUiTest` 顶栏 6 条 |
| `feature/sessions` | 25 | **38** | **+13** | `v13`–`v20`（8）+ `u04`–`u08`（5） |
| `feature/settings` | 77 | 77 | — | **主源码零 diff** ✓（测试假体见 §8.4） |
| `app` | 24 | **28** | **+4** | chip 缺席冒烟 1、`ProjectStoreWiringTest` 3 |
| **合计** | **462** | **592** | **+130** | `failures=0 errors=0 skip=3`（skip 三条与基线同） |

计数用 `.tools/p3a-count-tests.sh`（只读 `testDebugUnitTest`，纯 JVM 模块读 `test`）。
**口径教训**：本轮第一版计数脚本把 `testReleaseUnitTest` 的 XML 一起加了，Android 模块整整翻倍
（`core/engine` 报 294 而非 147）——与 REPORT-P3B §8.12「矩阵表少算一模块」同一类口径坑，
已改回复用他们的脚本，不再自建第二套计数。

---

## 5. APK 指纹

| 项 | 值 |
|---|---|
| 产物（编译机） | `/root/android-host/app/build/outputs/apk/debug/app-debug.apk` |
| 本机 | `dist/pi-host-full-debug-0.1.7-p3c.apk` |
| 大小 | **63,976,877 B**（0.1.6 为 63,928,829 B，**+48,048 B**） |
| sha256 | `86cd5e1799cea4cd3646940e8f58a04df22ffc0ba4951fcd2b0638bb59c4a7ac`（**双端逐字节一致**） |
| 清单 | `package=works.earendil.pihost` `versionCode=8` `versionName=0.1.7` `minSdk=26` `targetSdk=35`（`.tools/p3b-axml.py` 解析二进制 AXML） |

装机后 `dist/pi-host-full-debug.apk`（0.1.6，与机上 `base.apk` 同 sha256 `7e5de17a…`）**保留不动**，
它是「P3B-FIX1 已装机」的证据。

---

## 6. 红线复核（本期重划后的 §2，逐条实测）

| # | 红线 | 实测命令 | 结果 |
|---|---|---|---|
| 1 | pi 源码零写入 | `find ../.tools/pi-1.0.4 -type f -newermt '2026-10-09 00:00' \| wc -l`（本机，**不是** P3B 那条 `-newermt '1 day ago'`：跨零点跑会漏算，这里直接钉今日零点） | **0** |
| 2 | 冻结区零 diff | `git diff --stat 80f5e13 -- core/rpc/src/main core/process/src/main core/environment docs/protocol.md tools/capture tools/runtime-packaging` | **空** ✓ |
| 2b | `ChatEngine` / `RuntimeInstall` / `AssetInstaller` 公共 API 零改动 | `git diff 80f5e13 -- …/ChatEngine.kt …/RuntimeInstall.kt` | `ChatEngine` 未动；`RuntimeInstall` 只多一条 **private** `seedGitIdentity()` ✓ |
| 3 | `feature/settings` **主源码**一字不动 | `git diff --stat 80f5e13 -- feature/settings/src/main` | **空** ✓（测试假体见 §8.4） |
| 5 | 不新增权限；`app/build.gradle.kts` 只改版本 | `git diff 80f5e13 -- app/src/main/AndroidManifest.xml`（空）；`git diff --numstat -- app/build.gradle.kts` = **2 2** | ✓ 只有 versionCode/versionName 两行 |
| 7（改写） | 私有目录只许两类新落点：`projects/<name>/`（含 `.git/`）与 `home/.gitconfig`（**仅当不存在**） | 全仓 `grep -n "File(\|writeText\|appendText"` 于本期新增主源码 | 落点仅这两类；设置仍只有 `pihost.preferences_pb`（加键不算新文件）；审计仍走 P3A `AuditSink` |
| 9 | 不猜 pi 行为：没写进 `protocol.md` 的一律先实测 | §1 的 7 条事实全部带命令与回显；`main.ts:435-448`、`rpc-mode.ts:603`/`:448-464`、`agent-session-runtime.ts:210-215`、`tools/write.ts:36,78`、`tools/edit.ts:174-182` 全部逐行读过 | ✓ 并据此**推翻**了 §1 末尾那两条旧结论 |
| 10 | 每步都要有测试 | §4 矩阵 +130 条，全部随本节代码同批交付 | ✓ |
| 11 | 凭据永不离开本机 | `grep -rn "sk-" --include=*.kt */src/main` = 0 | ✓ |
| **12（新）** | 宿主只许两条 git argv；`add/commit/push/checkout/reset/gc/clean` 全仓不得出现 | `grep -rnoE '"(init\|status\|add\|…)"' core/engine/…/git/` → 只有 `"init"` `"status"`；全域 `grep -rnE '"(add\|commit\|push\|…)"'` 主源码 → **空** | ✓ |
| **13（新）** | `.gitconfig` 不含任何 token/key | `GitIdentityTest` 第 03 条逐串断言 + `grep -rn "sk-\|token\|password" …/git/` → 只命中 KDoc 里那句「不含凭据」的说明 | ✓ |

---

## 6b. commit 序列（与计划的偏差：**按模块切，没做到一节一 commit**）

```
8cfd85f feat(p3c): §3.1–§3.5 项目名规则、第四键、ProjectStore 与 git 三面（core/engine）
aa363a4 feat(p3c): §3.7 项目条、新建项目卡与跨项目告知（feature/sessions）
c761867 feat(p3c): §3.6 顶栏分支 chip（三个读时机，永不轮询）+ §3.2 两份假体补 override
e0c4b71 feat(p3c): §3.8/§3.9 app 注入边与版本 8 / 0.1.7
（本文件）   docs(p3c): REPORT-P3C
```

计划写的是「一节一 commit、测试同 commit」，实交四条代码 commit。两个原因，都记在这里而不是含糊过去：

1. **`core/engine` 的五个小节编译上互相依赖**：`EngineModule` 既要改 `workingDir` 的形状（§3.3），
   又要新增四条 git binding（§3.5），而 `ProjectStore`（§3.3）的构造参数就是 §3.5 的
   `GitInitializer`。再拆就会出现「某个 commit 里 Hilt 图建不起来」的历史——那是假的整洁。
2. **`SettingsRepository` 加一个抽象成员，就牵连三个模块的假体**。
   `:feature:chat` 与 `:feature:settings` 的 override 物理上无法与 §3.2 同 commit
   （它们所在文件同时还带着 §3.6 的改动），只能跟着各自模块走，并在 message 里写明「本属 §3.2」。

因此**每个 commit 单独 checkout 不保证可编译**；可编译、可跑的保证点在 `e0c4b71` 这一次
完整状态上，也就是 §4 矩阵那次数码（8 模块一条命令、`failures=0 errors=0`）。
这条偏差比「一节一 commit」的信号弱，是本期在交付纪律上实际付出去的代价。

提交后复验：`git status --short | wc -l` = **0**（工作树干净），
`git log origin/master..HEAD | wc -l` = **5**（本地领先，**未 push**——推送需要用户点头，
且 `origin` 的 URL 里现在带着明文 token，见 §8.8 第 5 条）。

## 7. 复现命令

```bash
# 第 0 步（本轮新工具，替代已失效的 bbuild.sh，见 §8.5）：
bash /data/data/com.termux/files/home/pi/pi/.tools/p3c-build.sh test :core:engine

# 全模块（一条一命令，红即停）
for m in :core:process :core:rpc :core:environment :core:engine \
         :feature:chat :feature:sessions :feature:settings :app; do
  bash .tools/p3c-build.sh test $m || break
done

# 计数（复用他们的口径，不自建第二套）
bash .tools/p3c-build.sh count

# APK
bash .tools/p3c-build.sh build :app:assembleDebug
# 拉回（双端 sha256 必须一致）
rsync -az -e "ssh -F none -o UserKnownHostsFile=$PWD/kh_buildsrv" \
  root@64.90.11.50:/root/android-host/app/build/outputs/apk/debug/app-debug.apk \
  ../dist/pi-host-full-debug-0.1.7-p3c.apk
sha256sum ../dist/pi-host-full-debug-0.1.7-p3c.apk   # 86cd5e17…c4a7ac

# 清单核对（二进制 AXML，不经 aapt）
python3 .tools/p3b-axml.py ../dist/pi-host-full-debug-0.1.7-p3c.apk | grep -i version

# 真机冒烟（只读那步不需要装机）
bash .tools/p3c-device-smoke.sh files
```

---

## 8. 偏差、遗留与未执行

### 8.1 真机工具通路仍未触发（本期**最大**的未闭环项）

装机前的磁盘事实（`bash .tools/p3c-device-smoke.sh files` 的真实回显）：

```
files/projects/            → 只有 default（19:37），无一个子目录新增
files/home/.gitconfig      → No such file or directory
files/projects/default/.git→ GIT无
files/audit/               → No such file or directory
```

即：0.1.6 上 `.gitconfig` 从未写过（§3.2 那条 `UpToDate` 分支正是为它补的路径），
`projects/<新项目>` 也从没建过。**592 条单测全绿不等于设备上是好的**——
本期新代码里 `git init`、`.gitconfig` 被 git 认领、项目目录在设备上可建这三件事
一条都没有真机证据，见 §8.2。

装机 + 建项目 + 让 pi 真跑一次 `git commit` 的冒烟脚本已就位
（`.tools/p3c-device-smoke.sh`，`am`/`pm`/`input`/`uiautomator`/`logcat` 全部实测可用，
只需 `PATH=/system/bin`——不这么做 `uiautomator` 会报 `app_process: inaccessible or not found`），
但**装机与 force-stop 会打断正在用的会话，未擅自执行**，待用户点头。

### 8.2 只有真机可验（五条，不许拿单测冒充）

1. **`git init` 在 SELinux 下能否成功**——Robolectric 跑不了打包的 git，而 `p3b-matrix`
   在编译机上以 **root** 运行，正是「编译机能跑 git」冒充「设备上 app 的 uid 能跑 git」的假绿形态
   （`ProjectStoreWiringTest` 第 2 条专门把这条堵在测试图上：绑的是 `NoopGitInitializer`）。
2. **git 2.56 是否真认我们写的 `$HOME/.gitconfig`**——要 pi 真发一次 `git commit`。
3. **`safe.directory` 会不会对 `filesDir` 报警**——§3.2 决定不写它，前提是「属主同 uid」，未在真机证过。
4. **空仓库 / 分离 HEAD 下 `status --porcelain=v1 --branch` 首行的实际措辞**——
   `ProcessGitProbeTest` 用的是按 man page 写的形状，不是从这台设备抓的。
5. **`File.canonicalFile` 在 `filesDir` vs `/data/user/0` vs `/data/data` 的符号链接包含性**
   （`RECON-P2E §5` 第 5 条同一条坑）——`pathOf` 的 canonical 断言在真机的挂载形态上未证。

### 8.3 顶栏 chip 与审计 cwd 本期取「活动项目」，不取「会话自己的 cwd」

计划里写的是后者，实现做了前者，原因是一处依赖没接上：
`ScannedSession.cwd` 在 `:feature:sessions` 里，而导航到 chat 的路由只带 `sessionId`
（`AppNavHost.kt:52-81` 的 `ROUTE_CHAT_SESSION`）。把 cwd 带过去要动 `:app` 的路由表，
本期红线把 `:app` 限定在「注入边 + 版本」。

因此：**跨项目打开会话时，chip 显示的是活动项目的分支**，可能与该会话真实工作目录不符。
已做的缓解：`v19` 那条告知（"这条会话属于项目 X，当前活动项目是 Y"）会在切换时出现，
用户看得见两个名字。P3D 的正解是路由带 `projectOf(cwd)` 并把 `auditCwd()` 改成同源。

### 8.4 红线 3「`:feature:settings` 一字不动」**没能守住**，按计划批准时的写法是错的

`setActiveProject` 是 `SettingsRepository` 的新抽象成员，而
`feature/settings/src/test/.../Fakes.kt` 的 `FakeSettingsRepository` 实现了这个接口
——不补 override 就编译不过（全仓 3 份假体：`:feature:chat`、`:feature:settings`、`:app`）。

本期实际守住的边界是「**主源码**零 diff」（§6 红线 3 实测为空），
测试假体多了 17 行（含一条 KDoc 说明为什么必须继承 `writeCalls`/`writeError`/`applyToSnapshot` 三条契约）。

为什么选「扩接口 + 改假体」而不是「另起一套项目专用快照」：
后者要在 DataStore 之外再造一份「同步可读的活动项目」状态，而 `EngineModule` 的
spawn lambda 需要正是**同步、不挂起、不碰磁盘**的读——这条需求 `memTier` 早就解决过一次，
重复实现一份就是 §13 偏差 4 那条老路。计划里那句「settings 一字不动」是我写计划时
没数到接口的三个实现者，属于计划的错，实现层的取舍是对的。

### 8.5 `bbuild.sh` / `p3b-gradle.sh` 在本机已失效，本期新增 `p3c-build.sh`

`bbuild.sh:25` 的 rsync 源写的是 `/root/pi/android-host/`——那是**桌面开发树**的路径。
实测编译机上只有构建镜像 `/root/android-host`（HEAD 停在 `1a4ec50`，是本机 `80f5e13` 的祖先，
无 origin、无未提交内容），桌面树不存在，所以那两个脚本在**任何一侧都跑不起来**。

本期改用 `.tools/p3c-build.sh`，同一件事、三处修正：源路径换成手机真实路径；
`--exclude local.properties`（`rsync --delete` 会抹掉这个 gitignored 文件，
`p3b-gradle.sh` 每次手工重建它是 REPORT-P3A §10 第 0 步那条坑）；
**重试语义**——第一版把整条 gradle 调用包在 `retry` 里，构建失败也会被重试，
一次 flaky 通过就会掩盖真实回归。本轮实测撞上：`DataStoreProbeTest.probe03` 在多次运行里
红过 1 次（后续 6 次含 `--rerun-tasks` 均绿），`retry` 连跑 5 次后最后一遍是绿的，
日志里同时有 FAILED 与 BUILD SUCCESSFUL，**分不清现在到底是什么状态**。
已改成只重试传输层（ssh 退出码 255），gradle 的红当场返回。

### 8.6 目标变更（不是未完成）：P3 的 OTA / onboarding / Play 合规移出路线图

用户 2026-10-09 决定终点是「这台 rooted 自有设备自用闭环」。
方案A §12 里 P3 的出口判据（资产 OTA + 签名校验、国产 ROM onboarding、Play specialUse/dataSafety）
**随之作废**。`AssetSource.kt:7` 那句「P3 OTA 换 OkHttp 实现」的接口留位继续保持原样（冻结区外，未动），
以后若转分发再启用。写进报告是为了以后翻记录时不误读成「做了一半停了」。

### 8.7 `probe03` 是已知抖动（1/7），不是本期引入

`DataStoreProbeTest > probe03 a second store over the same path sees the persisted bytes`
本轮 7 次运行红 1 次，其余全绿（含一次 `--rerun-tasks` 强制重跑）。
该用例本身就是 RECON-P2E §3 结论 2（一个文件只许一个 DataStore 实例）的探针，
抖动方向与「锁释放与协程 join 的时序」一致，与本期改动无因果关系
（`SettingsRepository` 只加了第四个键）。**未深究**，留此记录。

### 8.8 其它偏差

1. **`.gitconfig` 的身份是固定常量**，不能在设置页改：本期红线把 `feature/settings` 主源码锁死。
   需要自定义署名时的出路是用户自己写 `filesDir/home/.gitconfig`（宿主永不覆盖，§3.2）。
2. **项目不能删除**（`ProjectStore` 有意不提供）。`projects/<name>` 是用户的代码，
   宿主无法区分哪些文件是 pi 生成的、哪些是用户拷进来的。
   宁可让孤儿目录留在列表里可辨认，也不做不可逆又不可恢复的事。
3. **本期没装权限闸**。查到 `~/.pi/agent/extensions/` 在机上**不存在**，且
   `PiSpawn.kt:38` 的 argv 带 `--no-extensions` —— 所以 `PermissionDialog` 是**双重死代码**：
   既没有扩展来发 `extension_ui_request{confirm}`，扩展通道本身也被关着。
   pi 的 RPC 模式没有逐工具批准（`agent-session.ts:651`，`docs/security.md:3`
   原文 "it does not ask for approval before every tool call"），
   工具直接执行；实际边界由 OS uid 圈死在宿主沙盒内。自用场景可接受，
   但它意味着 §2 红线 8「用户随时看得清自己排了什么」在**工具面**上目前没有拦截能力。
4. **`AppSettings` 的磁盘兼容性**：老盘上 `active_project` 缺失 → 回落 `default`（`r08`），
   脏值 → 回落（`r09`），所以降级/手改都不会让 spawn lambda 拿到非法值。
   没做 DataMigration——本期不需要，第四键新增不破坏旧三键。
5. **仓库 `origin` 的 URL 里嵌着明文 GitHub token**（`.git/config`）。
   这条与红线 11 相冲突，但属于仓库配置而非本期代码面，**未擅自动**，待用户处理。

---

## 9. 真机验收清单（待用户点头后执行 `p3c-device-smoke.sh`）

| # | 步骤 | 判据 | 观测点 |
|---|---|---|---|
| ① | `pm install -r` 0.1.7 | `dumpsys package` 的 versionCode 从 7 → 8 | `p3c-device-smoke.sh install` |
| ② | 会话页点「新建项目」→ 输入名 → 创建 | `files/projects/<名>/` 出现且**内有 `.git/`** | `… files` 的第 4 段 |
| ③ | 建项目后 | `files/home/.gitconfig` 出现，内容逐字等于 `GitIdentity.DEFAULT_CONTENT` | `… files` |
| ④ | 进聊天页 | 顶栏出现分支 chip，文本为 `main`（新仓库无提交时 `ProcessGitProbe` 走 `No commits yet on main` 分支） | logcat `PiHostGitChip` |
| ⑤ | 让 pi「新建一个文件并 git commit」 | 会话 jsonl 里出现 `toolCall`；`audit/` 目录首次出现 jsonl；`git log` 有署名 `pi host` 的提交 | 回读 jsonl + `… logcat` |

第 ⑤ 条是本期真正的目的：**第一次**让工具通路在设备上跑起来，
顺带一次性覆盖 §8.2 的第 1、2、3 条。
