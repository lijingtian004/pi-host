# REPORT-P3C — 新建项目 + git 可用 + 顶栏分支（versionCode 8 / 0.1.7）

> 日期：2026-10-09 起、2026-10-10 真机验证收尾 ｜ 基线：`80f5e13`（P3B-FIX1）
> 交付：`dist/pi-host-full-debug-0.1.7-p3c.apk`（64,083,363 B，`765bd175…010b8`，含 §8.9b 修复、**未装机**；
> 手机上现在跑的是修复前那次 `86cd5e17…c4a7ac`，§9 的真机证据来自它，详见 §5）
> 任务来源：用户指令「添加 git，允许在应用私有目录新建文件夹」，经 P3C 计划批准（9 个小节 + 红线重划）。
>
> **目标变更**：终点从「消费者上架」改为「这台 rooted 自有设备自用闭环」，
> 故「资产 OTA + 验签」「Play specialUse/dataSafety」移出路线图（目标变更，不是未完成）。
> **但「国产 ROM 后台引导」被我跟着一起砍掉是错的**——真机证明 FGS 会被厂商冻结穿透，
> 它是自用刚需，已恢复进路线图（§8.6）。
>
> **本期结论**：真机验收 ①–⑤ 全通过（§9），工具通路第一次在设备上跑通
> （`note.txt` 由 app 自身 uid 写出、commit `67a321d` 署名 `pi host`、审计首次有 18 行内容）。
> 同一次验证挖出一个 P2A/P2B 遗留的严重缺陷：**覆盖安装后运行时符号链接农场整体悬空**
> （`runtime/bin` 109/109 悬空），后果是 `git clone/pull/push` 与 `gc` 静默失效
> ——首装正常、592 条单测全绿、编译机上永远复现不出来。见 §8.9，P3D 第一优先。

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
| `core/engine` | 147 (skip 2) | **247** (skip 2) | **+100** | ProjectNames 30（含 §8.9b 那组 7 条）、ProjectStore 17、**git 三面 46**（ShortProcessRunner 14 + ProcessGitProbe 22 + GitIdentity 10）、Supervisor 3、SettingsRepo 4 |
| `feature/chat` | 147 | **167** | **+20** | `case73`–`case86`（VM 14）+ `ChatScreenUiTest` 顶栏 6 条 |
| `feature/sessions` | 25 | **38** | **+13** | `v13`–`v20`（8）+ `u04`–`u08`（5） |
| `feature/settings` | 77 | 77 | — | **主源码零 diff** ✓（测试假体见 §8.4） |
| `app` | 24 | **28** | **+4** | chip 缺席冒烟 1、`ProjectStoreWiringTest` 3 |
| **合计** | **462** | **599** | **+137** | `failures=0 errors=0 skip=3`（skip 三条与基线同） |

计数用 `.tools/p3a-count-tests.sh`（经 `p3c-build.sh count` 调用）（只读 `testDebugUnitTest`，纯 JVM 模块读 `test`）。
**口径教训**：本轮第一版计数脚本把 `testReleaseUnitTest` 的 XML 一起加了，Android 模块整整翻倍
（`core/engine` 报 294 而非 147）——与 REPORT-P3B §8.12「矩阵表少算一模块」同一类口径坑，
已改回复用他们的脚本，不再自建第二套计数。

---

## 5. APK 指纹

| 项 | 值 |
|---|---|
| 产物（编译机） | `/root/android-host/app/build/outputs/apk/debug/app-debug.apk` |
| 本机 | `dist/pi-host-full-debug-0.1.7-p3c.apk` |
| 大小 | **64,083,363 B**（含 §8.9b 修复；修复前那次是 63,976,877 B，比 0.1.6 大 48,048 B） |
| sha256 | `765bd175d9ac1361f4f8c7639c5824311b033b259c27150800380afa06e010b8`（**双端逐字节一致**） |
| 清单 | `package=works.earendil.pihost` `versionCode=8` `versionName=0.1.7` `minSdk=26` `targetSdk=35`（`.tools/p3b-axml.py` 解析二进制 AXML） |

**设备上装的与 `dist/` 里现在这份不是同一个字节串**，这点必须写清：

| | sha256 | 状态 |
|---|---|---|
| 手机上现在跑的（§9 那 ①–⑤ 全通过的证据来自它） | `86cd5e17…c4a7ac` | 已装机，**不含 §8.9b 修复** |
| `dist/` 里现在这份 | `765bd175…010b8` | 含修复，**未装机** |

（`dist/` 的文件名沿用同一个，所以上一节那个 `86cd5e17` 已经被覆盖掉了——
它就是 §9 全部真机证据对应的那次构建，指纹记在这里，别再拿 `dist/` 当它的对照。）

**要装这份新包时注意**：`pm install -r` 会**再次触发 §8.9**（农场整体悬空），
所以装机后必须紧接着做一次 §8.9 末尾的恢复（删 `.installed.json` 让它重链），
或者等 P3D §3.1 的自愈逻辑落地。这也顺带给了 §8.9 一个现成的复验机会。

旧产物 `dist/pi-host-full-debug.apk`（0.1.6，sha256 `7e5de17a…`，曾与当时机上 `base.apk`
逐字节一致）**保留不动**，它是「P3B-FIX1 已装机」的证据。

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

### 8.1 真机已执行：工具通路第一次跑通（含一次对用户设备的实际干扰）

**装机**：`pm install -r` 把设备从 0.1.6 升到 0.1.7（`dumpsys package` 实测
`versionCode=7 → 8`、`versionName=0.1.6 → 0.1.7`，`Success`）。
执行前 `am force-stop` 清场，**打断了一次正在使用的会话**；后续驱动又打断过一次。
会话本体在 jsonl 里，重开可续，但这两次干扰是对用户设备的实际改动，记在这里。

**工具通路第一次在设备上跑通**（此前所有期次的 `toolCall` 计数都是 0）。
用 UI 建项目 `3` → 发一条「write 建 note.txt + bash 跑 git add/commit + 贴 git log」，
审计最后一条 `TOOL_END` 的 `resultPreview` 原文：

```
[main (root-commit) 67a321d] p3c
 1 file changed, 1 insertion(+)
 create mode 100644 note.txt
-----
commit 67a321dc3b69131b706a4c7e701468d996f9d26b
Author: pi host <pi-host@localhost>
Date:   Sat Oct 10 00:08:54 2026 +0800

    p3c
```

配套事实（全部实读，非推断）：

| 事实 | 取证 |
|---|---|
| `note.txt` 由 **app 自己的 uid** 写出 | `-rw------- u0_a401:u0_a401 6 Oct 10 00:02`，内容 `hello` |
| 提交真的落地 | `.git/refs/heads/main` = `67a321dc3b69131b706a4c7e701468d996f9d26b`（41 字节 ref 文件） |
| **P3A 的审计 sink 第一次有内容** | `files/audit/audit-2026-10-10.jsonl`，18 行，`TOOL_START`/`TOOL_END` 成对 |
| 会话里工具事件计数不再是 0 | 该 jsonl：`toolCall` 18、`toolResult` 9、12 条 assistant |
| 顶栏 chip 真的渲染出来 | 截图顶栏 `就绪 b5a77e2f | main | 选择模型`；`spawn-diagnostics.log` 有 00:02:00 / 00:02:12 / **00:08:56** 三条探针记录 |
| `AgentSettled` 挂点生效 | 00:08:54 提交 → 00:08:56 探针，即 §3.6「一轮跑完重读」那条路在真机上走通了 |
| `.gitconfig` 被真 git 认领 | author 就是 `pi host <pi-host@localhost>`；且 `HEAD` 是 `ref: refs/heads/main`，而 git 2.56 不配时默认 `master` |

`.gitconfig` 本身：`-rw------- u0_a401:u0_a401 80 字节`，与 `GitIdentity.DEFAULT_CONTENT`
逐字比对一致，mtime 23:42（装在 0.1.7 之后）。它是由 `RuntimeInstallImpl` 的 **`UpToDate`** 分支
播种出来的——**§3.2 特意补第二条成功支的原因就在这**：这台早就装好运行时的机器永远走 `UpToDate`，
只挂 `Installed` 的话这个文件至今不会存在。

**仍然没有证据的**只剩 §8.2 的第 5 条（canonical 在真机挂载形态下的行为）。

### 8.1b 一次误诊的完整记录（方法论，值得留在仓里）

用户报「卡住」。我先给了**冻结**解释，被一句反驳推翻：「当时在前台，前台不会被冻」。
继续量之后两个解释都不成立，真因是中转。全过程：

1. **第一版解释（错）**：`do_freezer_trap` 出现在 app + 两个 `pi` 子进程上 → 判为厂商冻结。
   反证：AMS 侧 `isFrozen=false`、`adj=200`、`cur=FGS`、`mState=ACTIVE`、屏幕亮着；
   而且那三条 wchan 是**用户切回 Termux 之后**才取到的，解释不了截图时刻（00:05）。
   冻结这件事本身是真的（见 §8.1c），但它不是这次「卡住」的原因。
2. **第二版解释（也错）**：以为宿主没收到 `TOOL_END`。反证：审计里那条 `TOOL_END`
   **就是宿主自己写的**——它收到了。
3. **量出来的真相**：每对 `TOOL_START/TOOL_END` 间隔 **0.01–0.07 秒**，工具根本不慢；
   慢的是工具之间：`00:02:57 → 00:06:46` 空 **229 秒**、`00:07:33 → 00:08:52` 空 **78 秒**。
   会话 jsonl 里躺着原因：**`"Connection error."` ×2、`"terminated"` ×1、`stopReason:"error"` ×3**。
   即一次模型请求在 undici 层挂了近 4 分钟才失败。
4. **宿主有没有失职**？查了：`auto_retry_start` 建模正确、在 `KNOWN_EVENT_TYPES` 里
   （`RpcWire.kt:25`）、`ChatViewModel.kt:1169` 会写 `retryNote`、`ChatScreen.kt:197` 会渲染。
   顶栏是空的，说明 **pi 这次没发该事件**。UI 显示的「插一句，或排队追问」就是流式中，
   它没骗人——只是**没有任何"已等待 N 秒"的时间感**（改进见 §8.10）。

教训一句话：**「卡住」有三种截然不同的成因（进程被冻 / 宿主漏事件 / 上游慢），
只有工具级时间戳能把它们分开**。以后遇到「卡住」，第一动作是量
`TOOL_START→TOOL_END` 与工具**之间**的间隔，而不是先看 freezer。

### 8.1c 顺带确认的两个真机事实

- **厂商冻结确实存在**：用户切出 app 后，app 与两个 `pi` 子进程的 `wchan` 全部变成
  `do_freezer_trap`，而 AMS 认为它 `isFrozen=false` 且处于 FGS。这台是 ColorOS/OxygenOS
  （配置里满屏 `mOplus*`）。且 `dumpsys deviceidle whitelist` 里**只有 `com.termux`，没有 pihost**。
  → 方案A §5.2 的第 1 层对策（FGS）在这台机器上被穿透，第 2 层（白名单引导）才是有效的，
  而它正是我上一版按「不上架就不做」砍掉的那一项（见 §8.6 的更正）。
- **`run-as` 不是忠实的 oracle**：以 `run-as`（uid 同为 10401，但域是 `runas_app`、
  且继承 Termux 的挂载视图）跑同一个 `libpi_git.so`，会因
  `/data/data/com.termux/files/usr/etc/gitconfig` 不可达而 `fatal: ... Permission denied`；
  app 自己在它的命名空间里看不到那条路径，同一命令 `rc=0`。
  我一度据此误判「git 读路径被 Termux 前缀残留咬住」，是错的。
  真机上要以 app 身份取证，只能靠 app 自己的产物（`spawn-diagnostics.log`、审计、refs）。

### 8.2 只有真机可验（五条，本期后状态）

1. ~~`git init` 在 SELinux 下能否成功~~ → **已闭合**：`projects/3/.git` 完整
   （`HEAD config index objects refs`），且对照实验证明「失败的 `git init` 什么都不留」
   （在 `scratch` 目录里跑一次 fatal 的 init，事后 `ls` 里没有 `.git`）——
   所以 `.git` 的存在本身就是成功的证据。注意这条闭合的是 **builtin**；
   外部子命令在升级后全废，见 §8.9。
2. ~~git 2.56 是否真认 `$HOME/.gitconfig`~~ → **已闭合**：commit 的 author 是
   `pi host <pi-host@localhost>`，且新仓库默认分支是 `main`（不配时 git 2.56 给 `master`）。
3. ~~`safe.directory` 会不会报警~~ → **对 app 自身不触发**（属主同 uid，实测 builtin 命令 `rc=0`）。
   但**以 root 侧访问同一个仓库会报** `fatal: detected dubious ownership`——
   这条留个话头：P3D 若加「诊断页导出 git 状态」这类 root/其它 uid 的读法，会撞上它。
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

### 8.6 目标变更（不是未完成）：OTA / Play 合规移出路线图——但 onboarding 被我错砍了，现予更正

用户 2026-10-09 决定终点是「这台 rooted 自有设备自用闭环」。
方案A §12 里 P3 的出口判据中，**资产 OTA + 签名校验**与 **Play specialUse/dataSafety**
确实随目标作废。

**「国产 ROM onboarding」不属于合规项，我把它跟着上架一起砍了，判断错了。**
真机证据（§8.1c）：用户一退出 app，app 与两个 `pi` 子进程立刻被塞进 cgroup freezer
（`wchan=do_freezer_trap`），而 AMS 认为它 `isFrozen=false`、`adj=200`、处于 FGS——
**方案A §5.2 的第 1 层对策（FGS 保活）在这台机器上被厂商策略直接穿透**；
而 `dumpsys deviceidle whitelist` 里只有 `com.termux`，**pihost 不在白名单**。
也就是说 §5.2 的第 2 层（电池白名单 + ROM 引导）不是「上架才需要」，
而是**自用长会话能不能活过「切出去回个消息」这件事的唯一保障**。
本期它排 P3D（见 §8.9 的优先级说明）。`AssetSource.kt:7` 那句「P3 OTA 换 OkHttp 实现」的接口留位继续保持原样（冻结区外，未动），
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

### 8.9 【P3D 第一优先】覆盖安装后，运行时符号链接农场整体悬空

这是本期由真机验证挖出来的**最严重缺陷**，而且它不在本期交付面上——
`AssetInstaller` / `RuntimeInstall` 的这段逻辑是 P2A/P2B 的产物，本期只是第一次撞上它。

**触发条件**：任何一次覆盖安装（`pm install -r`）。`/data/app/~~<随机>==/` 每次安装换名字，
而 `runtime/bin` 与 `runtime/libexec` 里存的是**指向旧 nativeLibraryDir 的绝对路径符号链接**；
`.installed.json` 标记没变 → `RuntimeInstall` 判 `UpToDate` → **没有任何东西去重链**。

**实测数据**（我 23:42 装 0.1.7 之后）：

```
旧目录 /data/app/~~cGw9WdYapoY22nt2gQnK9A==   → No such file or directory
新目录 /data/app/~~Cepn8CFX5XRgqLlmTyM4qw==/… → 当前 pm path
runtime/bin      共 109 条链接，悬空 109   ← 100%
runtime/libexec  共 172 条链接，悬空 150
农场 mtime 19:37:18（= 0.1.6 那次装机）；.installed.json 同一时刻，未被刷新
```

**后果（逐条实测，非推测）**：

| 后果 | 证据 |
|---|---|
| git 外部子命令全废 | `error: cannot run gc: No such file or directory` + `error: task 'gc' failed` `rc=1`；同一 uid 同一二进制跑 builtin `status` 则 `rc=0` |
| **不能 clone / fetch / pull / push** | `fatal: unable to find remote helper for 'https'`（`git-remote-https → …/libpi_git_remote_http.so` 也是悬空链接） |
| `SHELL` 指向死路径 | `EnvFactory.kt:27` 设 `SHELL=$runtime/bin/bash`，而 `bin/bash` 就在那 109 条里 |
| coreutils 静默退化成 toybox | PATH 是 `runtime/bin:libDir:/system/bin`，悬空链接 exec 得 ENOENT → bash 落到 `/system/bin`。没有 `rg`，`sed -i`/`grep -P` 的 GNU 写法语义不同 |

最后两条**没有任何报错**，是静默降级。它在本期还间接烧掉了用户的等待时间：
模型在 00:06:46 去跑 `command -v pkg apt apk yum dnf brew pip npm` 摸环境
（对应 §8.1b 里那两段 42s / 78s 的工具间空隙）。

**为什么这条压过本期其它遗留**：它让「git 能被 agent 用起来」在**每一次升级之后**都不成立，
而首装一切正常、592 条单测全绿、编译机上更是永远复现不出来（没有 `/data/app/~~` 这套形态）。
只有「真机 + 覆盖安装」这个组合能暴露它。

**修法（不需要动冻结区）**：农场由 `AssetInstaller`（`core/engine`，非冻结）按 zip manifest 建，所以：

1. **推荐**：`.installed.json` 里记下 `nativeLibraryDir`，`UpToDate` 额外要求它等于当前值；
   不等则**只重建链接**（不重解 250MB 的 `pi.zip`；280 个 `symlink()` 是毫秒级）。
2. 更稳：每次引擎启动幂等重链一遍，顺带吃掉「用户手动清过目录」这类情况。
3. 治本但此路不通：让链接指向稳定路径——这台机器上 `/data/data/<pkg>/lib` **不存在**（实测）。

**你设备上的临时恢复**（会重解运行时；`files/home`、`files/projects` 不受影响）：

```bash
/data/data/com.termux/files/home/su -c \
  "rm -f /data/data/works.earendil.pihost/files/runtime/.installed.json"
# 打开 app 等它装完，再验：
su -c "cd /data/data/works.earendil.pihost/files/runtime/bin && n=0;for l in *;do [ -e \"\$l\" ]||n=\$((n+1));done;echo 悬空\$n"   # 期望 0
```

### 8.9b 【本期已修】跨项目告知在真机上永不触发：data 目录的两张脸

§8.9 那条是环境缺陷，这条是**本期新代码里的真 bug**，由"还有啥要测的"这一问挖出来。

**取证**（同一目录，两种拼法，`readlink -f` 各自解析成自己）：

```
pi 会话头记的 cwd   : /data/data/works.earendil.pihost/files/projects/3
宿主审计记的 cwd    : /data/user/0/works.earendil.pihost/files/projects/3
两条的 st_dev:st_ino : 65097:1388717 = 65097:1388717      ← 同一个目录
readlink -f 两条     : 各自原样返回（不是符号链接，是 bind）
```

于是 `FileProjectsSource.projectOf()` 拿宿主的根（`/data/user/0/…`）去前缀匹配 pi 记的
cwd（`/data/data/…`）**永远不相等** → `projectNameOfCwd` 对每一条真实会话返回 null →
P3C §3.7 那条「这条会话属于项目 X，当前活动项目是 Y」的告知**在真机上永不出现**。
而 `v19` 单测里两侧都用同一串字面量，所以 592 条全绿也照不出来。

**为什么不是「加个 canonical 化」能解决的**：`File.canonicalPath` 底层是 `realpath(3)`，
只解符号链接；bind 挂载对它不可见。所以修法只能是**显式承认这两张脸是同一个键**：
`ProjectNames.foldDataAlias()` 把 `/data/user/<n>/` 折成 `/data/data/`，
`projectNameOfCwd` 两侧都先折叠再比较。选 `/data/data` 作统一目标，因为它是 pi 侧那张、
也是人读日志时更眼熟的一张。

**这条修法的唯一真实风险**是「折叠顺手放宽了归属判断」，所以 `29` 专门钉住：
`com.other` 的同形路径、以及同包 `files2/` 这种多一层别名的路径，都必须返回 null。

新增用例 `24`–`30`（7 条），`:core:engine` 240 → **247**，全模块 **599**。

**它同时解释了 §8.2 第 5 条**：那条我列成「canonical 在真机挂载形态下未证」，
现在证了——而且证出来的结论是「canonical 在这里根本不起作用，别指望它」。

### 8.10 上游慢的时候，界面没有任何时间感


§8.1b 量出来的那次 229 秒停顿里，界面只有一个静态的「插一句，或排队追问」占位。
宿主侧该有而没有的三样东西：

1. **`abort_retry` / `set_auto_retry` 建模了但 UI 没接**（§1 那张「11 条建模却从未发出」的清单里就有它们），
   所以用户既看不到重试策略、也不能干预；这次更是 pi **压根没发** `auto_retry_start`，
   宿主连想显示都没素材。
2. **没有「本轮已等待 Ns」的计时器**。`turnState=Streaming` 是对的，但一个不动的转圈
   和「卡死」在用户眼里没有区别。
3. **没有首字节超时**。pi-ai 层连接挂 4 分钟才失败，宿主对「请求发出但一个 delta 都没收到」
   这件事没有任何阈值与提示。

这三条合起来是一个可用性问题，不是正确性 bug；但对「手机上用编码 agent」这个场景，
它是决定「用户会不会以为坏了然后强杀」的那一项。建议与 §8.9 同批进 P3D。

#### 8.10b 超时这件事的完整现状（两条实测问题问出来的）

**「API 连接超时为什么没提示」——因为两层各有一个洞，叠在一起：**

| 层 | 有没有超时 | 值 | 那次 229 秒为什么没触发 |
|---|---|---|---|
| pi 的 HTTP | **有** | `httpIdleTimeoutMs` 默认 **300000（5 分钟）**（`docs/settings.md:123`）；`retry.provider.timeoutMs` 默认继承它（`:129`） | 229s < 300s，**压根没到线**；最后是底层连接自己抛 `Connection error.` 才结束 |
| pi 的 WS 连接 | 有 | `websocketConnectTimeoutMs` 默认 15000（`:124`） | 不走这条路 |
| 宿主的整轮 | **没有** | 全仓 grep `watchdog\|elapsed\|deadline` 于 `feature/chat/src/main` → **0 命中** | 宿主对「模型在不在回话」既无时限也无计时显示 |

所以「没提示」不是提示逻辑写错了，是**宿主这一层根本没有这个概念**：
它现有的超时只有 spawn 握手 15s（`SessionSupervisor.kt:425`）、优雅关 5s
（`PiSession.kt:190`）、git 探针 3s（本期新增）、权限框倒计时（pi 给的 timeout）。
**结论：不能指望 pi 那条 5 分钟的线，宿主必须自己有「本轮已等待 Ns + 首字节超阈值提示」。**

**「工具有超时限制吗」——默认没有：**

- `bash` 工具的 `timeout` 是**可选参数**，schema 描述原文
  *"Timeout in seconds (optional, **no default timeout**)"*（`core/tools/bash.ts:42`）；
  上限 `MAX_TIMEOUT_MS = 2_147_483_647` ≈ **24.8 天**（`:22`），等于没有上限。
- `write` / `edit`：grep `timeout` → **0 命中**，纯文件操作无超时。
- 也就是说 **pi 跑一个死循环命令，唯一的出口是用户点「中止」**（`abort` 已接、`abort_bash` 未接，见 §1 那张表）。
- 本期宿主自己的 git 探针是**唯一有硬超时的那一处**（3s + 超时 `destroy()`），
  而且它超时后按设计**什么都不显示**（`Unavailable` 不渲染节点），所以不会添乱。

**给 P3D 的三条直接结论**（比 §8.10 原文更具体）：
1. 宿主加「本轮已等待 Ns」计时 + 首字节阈值提示（阈值应显著小于 pi 的 300s，建议 30–60s 起报）；
2. 接上 `abort_bash`——现在 bash 无默认超时，而宿主只能中止**整轮**，不能只中止那条命令；
3. 若要让长命令有兜底，走 `set_auto_retry` / 或在宿主侧对 `bash_execution_update` 静默超时给提示，
   别去改 pi 的参数默认值（红线 1：pi 源码零写入）。

---

## 9. 真机验收清单（五条，本期实交 ①②③④⑤）

| # | 步骤 | 判据 | 结果 |
|---|---|---|---|
| ① | `pm install -r` 0.1.7 | versionCode 7 → 8 | **通过**（`Success`；crash buffer 里无 `works.earendil.pihost`；两条旧 session jsonl 完好，19:38 那条一条没丢） |
| ② | 会话页建项目 | `projects/<名>/` 出现且内有 `.git/` | **通过**（`projects/3/.git` = `HEAD config index objects refs`；`HEAD` = `ref: refs/heads/main`） |
| ③ | 建项目后 | `home/.gitconfig` 逐字等于 `GitIdentity.DEFAULT_CONTENT` | **通过**（`u0_a401:u0_a401` 80 字节；且经 ⑤ 的 commit author 反证 git 真读了它）。注意它**不经②**也能拿到——走的是 `RuntimeInstallImpl` 的 `UpToDate` 分支，见 §3.2 |
| ④ | 进聊天页 | 顶栏出现分支 chip | **通过**（实测顶栏 `就绪 b5a77e2f | main | 选择模型`；`NotARepo` 时不渲染也验到了：`projects/default` 无 `.git` → 无 chip） |
| ⑤ | 让 pi 建文件并 commit | 会话出现 `toolCall`、`audit/` 首次有内容、`git log` 有署名提交 | **通过**（`note.txt` 6 字节由 app uid 写出；审计 18 行；commit `67a321d` author `pi host <pi-host@localhost>`） |

**但同一次真机验证同时挖出了 §8.9 那个覆盖安装缺陷**——所以 ①–⑤ 的「通过」是有保质期的：
再装一次（任何一次 `pm install -r`）之后，②③④ 仍然会过（builtin 不受影响），
而 `git clone` / `pull` / `push` 与 `gc` 会静默失效。这条写在这里，是为了让以后有人
照着这张表复验时不会以为「五条全绿 = 没事」。

### 9.0 §8.9b 的复验（修完之后要能看到变化）

会话页把活动项目切成 `default`，然后点一条属于 `3` 的会话：

- **修前**：无任何提示（`projectOf` 恒返回 null）
- **修后**：出现「这条会话属于项目 3，当前活动项目是 default：继续可用，但新建会话会落在 default」

这条**只能真机验**：单测里两侧字面量一致，永远照不出两张脸的问题（这正是它活到 §8.9b 才被发现的原因）。

### 9.1 复现这张表所需的东西（都已就位）

```bash
# 装机 / 只读回读 / 建项目 / 驱动工具，四个脚本都在 pi/.tools/
p3c-build.sh test :core:engine          # 编译机（替代已失效的 bbuild.sh）
p3c-device-smoke.sh install|files       # 装机与磁盘事实
p3c-device-create.sh                    # UI 建项目（按 text= 定位）
p3c-device-tool.sh                      # 驱动 pi 跑 write+bash+git（ASCII 提示词）
```

三条实测出来的硬约束，写在这里省得下次重踩：
1. 系统命令一律 `PATH=/system/bin`，否则 `uiautomator` 报 `app_process: inaccessible or not found`；
2. Compose 的 `testTag` 在真机树里**不出现**（要 `testTagsAsResourceId`），只能按 `text=` 定位；
3. `input text` **打不了中文**，自动化提示词必须是 ASCII（手打不受影响）。
