# P2F 侦察记录（spawn 热修：cwd 修复 + 降级 exec + 全量诊断上屏，动工前）

> 第 0 步产物（规格书 §5 / §6.1）。事实来源全部是**本机/编译机实测输出**，未跑的步骤标注「未执行」。
> 仓库根 = `/root/pi/android-host`（本机）/ `root@64.90.11.50:/root/android-host`（编译机）。
> 起点 HEAD = `373c92c`（P2E 收尾），工作树 clean。
> 编译机连通与容量实测（`bssh.sh run 'hostname; df -h /root; free -m'`，2026-10-06）：
> `ser843493624838` / `/dev/vda1 49G 44G 4.5G 91% /` / `Mem: total 3911 available 3431` →
> §8「磁盘 ≥4G」勉强达标（4.5G），构建前后都要 `df -h` 复查；内存仍决定**逐模块**调用。

## 1. §3.1 错误截断现场核对（行号零漂移）

| 规格书 §3.1 指认 | 实测位置 | 现场原文 | 判定 |
|---|---|---|---|
| 顶栏 take(40) | `feature/chat/src/main/kotlin/works/earendil/pihost/feature/chat/ui/ChatScreen.kt:163` | `is SessionState.Failed -> "失败：${state.cause.take(40)}"` | 吻合，§4.2(d) 不动 |
| Failed 屏 maxLines=6 | `feature/chat/.../ui/InstallGate.kt:58` 分支，文本节点在 `:60-67` | `maxLines = 6` + `overflow = TextOverflow.Ellipsis` + `modifier = Modifier.testTag(ChatTags.INSTALL_CAUSE)` | 吻合 |
| 同分支只有「重试」 | `InstallGate.kt:68-70` | `Button(onClick = onInstall, modifier = Modifier.testTag(ChatTags.INSTALL_RETRY_BUTTON)) { Text("重试") }` | 吻合，无复制键 |
| message 本身完整 | `core/engine/.../SessionSupervisor.kt:258` | `Handshake.SpawnFailure(t.message ?: t.javaClass.name)` | 吻合 |
| spawn 失败文案拼装 | `SessionSupervisor.kt:347`（`describeFailure`） | `is Handshake.SpawnFailure -> "spawn 失败：${handshake.message}"` | 前缀只加 6 字，不截断 |
| Failed 态落状态 | `SessionSupervisor.kt:287` | `setState(id, SessionState.Failed(cause = "$lastCause；stderr: ${stderrTail(id).orEmpty()}"))` | 全文 = IOException 消息 + stderr 尾巴 |
| MainActivityTest 顶栏断言 | `app/src/test/kotlin/works/earendil/pihost/MainActivityTest.kt:455-457` | `engine.setState(SessionState.Failed("spawn 失败：ENOENT"))` → `assertTextEquals("失败：spawn 失败：ENOENT")` | 吻合，保持绿 |

**结论**：`Handshake.SpawnFailure` 携带的就是 libcore 的完整 `IOException` 消息，
errno 在显示层丢失。截断点规格书列了两处，**实测有三处**（见 §1.1）。

### 1.1 规格书未列的第三截断点（本任务必须处理，否则「全文」是假的）

- `feature/chat/src/main/kotlin/works/earendil/pihost/feature/chat/ChatViewModel.kt:811` / `:820`
  `const val CAUSE_MAX_LEN = 240`，`:816` `internal fun String.truncate(limit: Int = CAUSE_MAX_LEN) = if (length <= limit) this else take(limit) + "…"`。
- 调用点 `:189-191`（安装失败）与 `:235-237`（引擎未就绪）都对 cause 套了 `.truncate()`：
  `:236` = `"$reason；stderr: ${...}".truncate()`。**`；stderr: ` 尾巴在 240 之后会被切掉并留一个 `…`**。
- 影响评估：`error=N` 位于 IOException 消息中段（约第 70–90 字符），240 的上限**不会吃掉 errno**，
  但会吃掉 stderr 尾部——而 stderr 正是 §4.2(a) 要求「保留换行、可复制全文」的那部分。
- 测试面核对：`grep -rn "truncate|CAUSE_MAX_LEN|240" feature/chat/src/test/ app/src/test/` 命中仅
  `ChatScreenUiTest.kt:63/67` 与 `MainActivityTest.kt:78/89` 的 `h2400dp` 视口注释，**没有任何用断言锁 240 截断行为**。
  → 放宽 Failed 路径的截断不破坏既有用例。
- 定案：`:189`/`:235` 两处 Failed 构造**不再截断**（`:619` 的 `_retryNote` 保持 truncate，语义是气泡提示，不在本任务面）。
  与 §4.2(a)「去掉 maxLines=6」、§4.2(b)「复制 cause 全文」是同一个目标的三个必要环节。

## 2. §3.2 根因假设现场核对

### 2.1 workingDir 不存在（首要嫌疑）——**实测确认全仓无 mkdirs**

- `core/engine/src/main/kotlin/works/earendil/pihost/core/engine/di/EngineModule.kt:113`
  `workingDir = PathLayout.projectDir(ctx, DEFAULT_PROJECT).absolutePath`。实测原文用常量
  `DEFAULT_PROJECT`（`EngineModule.kt:76` = `"default"`），行号零漂移；`ctx` 由同函数的
  `@ApplicationContext ctx: Context` 提供。
- `core/environment/.../PathLayout.kt:36` `fun projectsDir(ctx) = File(ctx.filesDir, PROJECTS_DIR_NAME)`、
  `:38` `fun projectDir(ctx, name) = File(projectsDir(ctx), name)` —— **纯构造，不建目录**。
- 全仓 `grep -rn "mkdirs()" core/*/src/main feature/*/src/main app/src/main` 实测命中 6 处，**无一与 projects 目录相关**：
  `SettingsRepository.kt:185`（DataStore 文件父目录）、`InstalledRuntime.kt:23`（installed 标记父目录）、
  `AssetInstaller.kt:70/84/88/173`（`runtime.tmp` 树与符号链接）→ **没有任何一处 mkdir `projectsDir`/`projectDir`**。
  佐证：`core/environment/src/test/.../PathLayoutTest.kt` 只断言路径字符串，不断言目录存在；
  `PathLayout.kt:23-24` 常量 `HOME_DIR_NAME="home"` / `PROJECTS_DIR_NAME="projects"` 也印证两者平级、互不派生。
- `core/process/src/main/kotlin/works/earendil/pihost/core/process/ProcessHandle.kt:54`
  `if (workingDir != null) pb.directory(java.io.File(workingDir))` —— ProcessBuilder 子进程 chdir 先于 execve，
  chdir ENOENT 与 execve ENOENT 同 errno=2 同一条消息管道 → 与用户截图（显示 exe 路径的 `error=2`）吻合。
- `SessionSupervisor.kt:87` `private val workingDir: String?` —— **可空性确认**：`@JvmOverloads` 无，
  必填参数，`ProcessStarter.start(command, env, workingDir)` 第三参同样是 `String?`（`ProcessHandle.kt:46`）。
  → §4.1(a) 的 `workingDir?.let { ... }` 写法成立。

### 2.2 降级链可行性核对

- `core/process/.../ProcessHandle.kt:45` `fun interface ProcessStarter { fun start(command: List<String>, env: Map<String, String>, workingDir: String?): ProcessHandle }`
  —— frozen 接口，**`ExecFallbackStarter` 以 `class ... : ProcessStarter` 实现即可**（fun interface 可被普通类实现）。
- `:50` `object JvmProcessFactory : ProcessStarter` = 现 production 实现，无需改动。

### 2.3 已排除项复核（方向层结论，本轮不重做）

- `app/build.gradle.kts:33` `useLegacyPackaging = true`（在 `packaging { jniLibs { ... } }` 内，`:31-36`）→ so 落盘为真实文件，成立。
- ELF INTERP=`/system/bin/linker64`、RUNPATH=`$ORIGIN`：P2F 规格书 §3.2 判定为方向层已完成取证，本轮**未重跑**（标注：未执行）。

## 3. EngineModule processStarter 当前形状（§5 要求核对）

- `core/engine/.../di/EngineModule.kt:57-59`：
  ```kotlin
  @Provides
  @Singleton
  fun processStarter(): ProcessStarter = JvmProcessFactory
  ```
  **无参数** → §4.1(c) 要求 sink 用 ctx，故签名改为 `fun processStarter(@ApplicationContext ctx: Context): ProcessStarter`
  = `ExecFallbackStarter(JvmProcessFactory, HomeDirSpawnDiagnosticsSink(ctx))`。模块内部改动，不动 `SessionSupervisor` 构造签名（红线）。
- 消费点唯一：`EngineModule.kt:100` `starter: ProcessStarter` 注入 `sessionSupervisor(...)`。
- 测试面核对：`grep -rn "processStarter" core/engine/src/test app/src/test` —— `:app`/`:core:engine` 的 Hilt 测试靠
  `@UninstallModules(EngineModule::class)` 整体替换（`MainActivityTest.kt:89` 实证），**没有测试直接绑 production 的 `processStarter()`**
  → 改提供者签名不破坏任何现有用例。

## 4. PI_OFFLINE 判明（§3.6 RECON 项）——**结论：保留，不 gate 模型 API 调用**

只读源码副本 = `/root/pi/.tools/pi-1.0.4`。`grep -rn "PI_OFFLINE" packages --include='*.ts'` 全量命中，
逐处定性（证据行号）：

| 位置 | 行为 | 是否影响模型 API 调用 |
|---|---|---|
| `packages/coding-agent/docs/environment-variables.md:84` | 官方定义：`Disable automatic network activity, including model catalog refreshes` | 定义层面就是「自动网络活动 + 目录刷新」 |
| `packages/coding-agent/src/core/model-runtime.ts:239` | `new ModelRuntime(..., process.env.PI_OFFLINE === undefined)` → 形参 `modelNetworkEnabled`（`:203` 声明 / `:208` 赋值 / `:182` 字段） | **否**：该字段只被 `:243` `refreshFromNetwork` 与 `:850` `allowNetwork: options.allowNetwork ?? this.modelNetworkEnabled` 消费，两者都进 `async refresh(...)`（`:839`）= **模型目录刷新**，不是推理请求 |
| `packages/coding-agent/src/utils/version-check.ts:55` | `if (process.env.PI_OFFLINE) return undefined` → 跳过 pi.dev 版本检查 | 否 |
| `packages/coding-agent/src/core/package-manager.ts:55` | 离线标记（包市场/更新） | 否 |
| `packages/coding-agent/src/utils/tools-manager.ts:15` | 同上（tools 下载） | 否 |
| `packages/coding-agent/src/modes/interactive/interactive-mode.ts:1141` | `if (!process.env.PI_OFFLINE) refreshModelCatalogs(...)` → 目录刷新 | 否（刷新而已） |
| `.../interactive-mode.ts:1256` | `checkForPackageUpdates()` 直接返回 `[]` | 否 |
| `.../interactive-mode.ts:1352` | `reportInstallTelemetry()` 直接 return | 否 |
| `packages/coding-agent/src/modes/interactive/bug-report.ts:56` | 阻止上传 bug report | 否 |
| `packages/coding-agent/src/experimental/radius-auth.ts:36` | `PI_OFFLINE !== undefined` → relay token 不可用（`required` 时抛错） | 仅当走 radius 网关；**本仓 `grep -rn "radius" core/ app/ feature/ --include='*.kt'` 命中 0 条**（实测空），不使用 radius |
| `packages/coding-agent/src/main.ts:576-579` | `--offline` 或 env 真值 → 置 `PI_OFFLINE=1` 并连带 `PI_SKIP_VERSION_CHECK=1` | 否 |
| `packages/coding-agent/src/extensions/llama/index.ts:56` | 注释明示 llama 刷新**不受** PI_OFFLINE 影响 | 否 |

- 关键否证：**推理调用路径不读 PI_OFFLINE**。全 packages grep 命中清单里没有任何完成/流式调用文件
  （`pi-ai` 包 0 命中）；`model-runtime.ts` 的 5 处 `allowNetwork` 全部挂在 `refresh(...)` 上（`:254`/`:602`/`:850`/`:893`/`:939`/`:947`/`:973`/`:982`）。
- 内置模型可用性：`:225-231` 的 `providers` 取自 `builtinProviderCatalog`（随包离线的内建目录），
  `withRemoteCatalog(...)` 只是叠加远端刷新 → PI_OFFLINE 下仍有内建模型可选。
- **处置（§4.1(d)）**：`core/environment/.../EnvFactory.kt:24` `put("PI_OFFLINE", "1")` **保留**，
  `core/environment/src/test/.../EnvFactoryTest.kt:39` `"PI_OFFLINE" to "1"` 期望值**不改**；
  §7 矩阵 environment 仍是 5 用例。
- 遗留风险如实记：PI_OFFLINE 会关掉**远端模型目录刷新**。若后续 P1 判据要求「远端目录里的模型」，
  删除该键即可（一行 + 测试期望一处），非本轮范围。

## 5. §4.2(c) ErrnoHint 接入点核对

- `ChatViewModel.kt` 三处 `ChatUiState.Failed(...)`：`:189`（安装失败）、`:220`（并行上限门）、`:235`（引擎未就绪）。
  §4.2(c) 写的行号一致。**注意 `:220` 是并行上限的中文文案，不含 `error=N`**，`humanize` 对它原样返回（正则不命中）——
  仍按规格书「三处统一过一层」处理，语义无损。
- `ChatUiState.Failed` 定义与 `InstallGate.kt:58` 分支的 `state.cause` 同源，复制键取的就是 `state.cause`。
- `ChatTags.kt` 现有风格：`INSTALL_GATE/INSTALL_BUTTON/INSTALL_RETRY_BUTTON/INSTALL_CAUSE`，值形如 `chat-install-cause`
  → 新增 `const val INSTALL_COPY = "chat-install-copy"`。

## 6. UI 测试可行性预核（§4.4 降级条款的前置判断）

- 本工程 UI 测试配方（`ChatScreenUiTest.kt:63-67` 实证）：`createComposeRule()` + Robolectric 4.16 +
  `@Config(sdk = [35], qualifiers = "w420dp-h2400dp")` + `ui-test-junit4`/`ui-test-manifest` + `isIncludeAndroidResources`
  （`feature/chat/build.gradle.kts:28-32`、`app/build.gradle.kts:43-47`）。
- 剪贴板断言路径：Compose `LocalClipboardManager` 在 Android 实现走 `ClipboardManager.setPrimaryClip`，
  Robolectric 有 `ShadowClipboardManager` 存取 primaryClip → **理论上可断言**；本轮实测结果写进 REPORT
  （若实测不通，按 §4.4 降级为「按钮存在 + 点击不崩 + 全文在语义树」并在报告注明原因）。
- 大字号 cause 的可测性：`heightIn(max = 280.dp)` + `verticalScroll` 后，`assertTextEquals` 只看节点文本、
  与可见行数无关，`onNodeWithText(substring)` 走的是语义树全文 → 去掉 maxLines 后断言「含全文」成立。

## 7. 冻结区与红线自检（动工前基线）

- `git diff --stat 1f77127..HEAD -- core/rpc core/process/src/main core/environment docs/protocol.md tools/capture tools/runtime-packaging`
  → **空输出**（P2E 收尾时已复核，本轮开工再验一次，成立）。
- §8 构建环境沿用 P2E：本机（dshbox）只跑 `:core:process:test`、`:core:rpc:test`；
  JDK21 本机在 `/root/pi/.tools/jdk-21.0.12.1+1`；编译机任务串见 `/root/pi/.tools/bbuild.sh` 的 `GRADLE_OPTS_ENV`
  （含 P2D §3 的 TLS trustStore 修复串，实测必需）。
- 文档落点：规格书 §4.5 写 `docs/RECON-P2F.md`，本仓历史 P2x 文档实际在 `tools/engine-service/`
  （`git ls-files '*.md'` 实测）。**按规格书字面落 `docs/`**（`docs/protocol.md` 是冻结文件，新增同级文件不触碰它），
  偏差记入 REPORT §偏差。

## 8. RECON 结论汇总（指导 §4 落地）

1. 三个截断点全部定位：`ChatScreen.kt:163` take(40)（不动）、`InstallGate.kt:64` maxLines=6（去掉）、
   `ChatViewModel.kt:190/236` `.truncate()`（Failed 路径放宽）。
2. `workingDir` 可空 → mkdirs 用 `?.let`；`ProcessStarter` 是 `fun interface`，`ExecFallbackStarter` 可 class 实现。
3. `EngineModule.processStarter()` 现无参，改带 `@ApplicationContext ctx` 不伤任何测试（Hilt 测试整体 uninstall）。
4. PI_OFFLINE **保留**，证据见 §4 表。
5. 测试基线：engine 50 / chat 40 / app 14 / environment 5，且无人锁定 truncate 行为 → 新增用例不会撞既有断言。
