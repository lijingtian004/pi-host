# RECON-P2G — 模型凭据接入（auth.json 写入 + 设置页 UI）动工前侦察

规格书：`.tools/qoder-task-p2g.md` §5（三条必查项）。侦察方式：只读 `Read`/`Grep` + 编译机状态实测，
冻结区（§2）一条没碰。§3 的方向层结论按规格书**直接采用**，本文件只做「接线形状核对」。

基线 HEAD：`24b55be`（P2F 报告收尾）。冻结区基线 `1f77127` 复核见 §6。

## 1. PathLayout 有没有 homeDir 访问器（§5.1）

**有，现成可用，不需要硬路径。**

- `core/environment/src/main/kotlin/works/earendil/pihost/core/environment/PathLayout.kt:23`
  `const val HOME_DIR_NAME: String = "home"`
- 同文件 `:34` `fun homeDir(ctx: Context): File = File(ctx.filesDir, HOME_DIR_NAME)`
- 同文件 `:6-18` 的目录图已把 `home/` 标成「`$HOME`，pi 的 `~/.pi/agent` 落在这里」——§3 的路径结论与本仓定义同源。

所以 `ModelCredentialStore` 的 `homeDir` 由 `PathLayout.homeDir(ctx)` 供给，落成的文件即
`filesDir/home/.pi/agent/auth.json`，与规格书 §4.1 里 `File(File(homeDir, ".pi"), "agent/auth.json")` 一致。

- **这条通路已在生产里跑过**：`core/engine/.../di/EngineModule.kt:79` 的
  `File(PathLayout.homeDir(ctx), SpawnDiagnosticsSink.FILE_NAME)`（P2F §4.1(c)）就是同一个 `homeDir`，
  真机 spawn 诊断已写进去过 → 目录存在性与权限模型无需再验。
- **红线自查**：`:core:environment` 是冻结区，本项**只读**；不改 `PathLayout`，只在 `:core:engine` 调用它的 public API。

pi 侧同一形状（`.tools/pi-1.0.4`，只读核对，不重做方向层）：

| 事实 | 位置 |
|---|---|
| `getAgentDir()` = `join(homedir(), ".pi", "agent")`（`ENV_PI_CODING_AGENT_DIR` 未设时） | `packages/coding-agent/src/config.ts:605-611`（`:585` 定义该 env 名） |
| `auth.json` = `join(getAgentDir(), "auth.json")` | `packages/coding-agent/src/config.ts:623-625`；RPC 会话侧同一拼法 `src/core/agent-session-services.ts:143` |
| 凭据形状 `ApiKeyCredential { type: "api_key"; key?: string; env?: ProviderEnv }` | `packages/ai/src/auth/types.ts:17-21`（`:36` 注释原文「the shape of today's auth.json」） |
| 读取入口 `AuthStorage(authPath = join(getAgentDir(), "auth.json"))` | `packages/coding-agent/src/core/auth-storage.ts:52`（`:207` 同一默认） |
| 默认模型表 `deepseek→deepseek-v4-pro`、`anthropic→claude-opus-4-8`、`openai→gpt-5.5` | `packages/coding-agent/src/core/model-resolver.ts:20-34`，选择步 `:179-182` |

`key?: string` 是**可选**字段：故 `readConfigured` 的「已配置」判据不能只看键存在，必须
`type == "api_key" && !key.isNullOrBlank()`（§4.1 第 ⑧ 条与此对齐）。

## 2. EngineModule 的 @Provides 现有模式（§5.2）

`core/engine/src/main/kotlin/works/earendil/pihost/core/engine/di/EngineModule.kt`：
`@Module @InstallIn(SingletonComponent::class) object EngineModule`，全部 `@Provides @Singleton`，
需要设备路径的提供者一律把 `@ApplicationContext ctx: Context` 写进**提供者签名**（不是构造参数）：

| 现有提供者 | 行 | 形状 |
|---|---|---|
| `processStarter(@ApplicationContext ctx)` | `:75-89` | ctx → `PathLayout.homeDir(ctx)` 拼文件，再装进核心类 |
| `settingsRepository(@ApplicationContext ctx)` | `:100-103` | `@Singleton` + ctx → `DataStoreSettingsRepository.create(ctx)`（`:92-98` 注释解释「为什么绑在 EngineModule 而不是单开模块」：测试侧一条 `@UninstallModules(EngineModule::class)` 一起换掉引擎面与设置面） |
| `piSpawn(ctx)` / `assetInstaller(ctx)` | `:114-124` | 同上 |
| `sessionSupervisor(scope, starter, spawn, settings, ctx)` | `:127-144` | 混合注入 + `envFor = { EnvFactory.build(ctx, settings.memTier) }`（§2 红线：EnvFactory 11 键不动，本里程碑不碰） |

→ store 照 `settingsRepository` 那条式接入：`@Provides @Singleton fun modelCredentialStore(@ApplicationContext ctx: Context) = ModelCredentialStore(PathLayout.homeDir(ctx))`。

**副作用（必须一起改，否则 :app 建不出图）**：三条测试都用 `@UninstallModules(EngineModule::class)` 整体换假体——

- `app/src/test/kotlin/works/earendil/pihost/MainActivityTest.kt:87`
- `app/src/test/kotlin/works/earendil/pihost/PiEngineServiceWiringTest.kt:41`
- `core/engine/src/test/kotlin/works/earendil/pihost/core/engine/PiEngineServiceTest.kt:163`

替换模块 `app/src/test/.../AppTestDoubles.kt:80-93`（`AppTestEngineModule`）当前只给三条：
`ChatEngine` / `RuntimeInstall` / `SettingsRepository`（`:100-121` 的 `ScriptedSettingsRepository` 就是
P2E 为「新增注入依赖补全图」而写的先例，注释原文「存在的唯一理由是把 P2E 新增的注入依赖补全」）。
`MainActivityTest` 会真进设置路由（`:241-253` 断言 `SettingsTags.SCREEN`/`CARD_VERSION`），
所以 `SettingsViewModel` 多一条注入边 = `AppTestEngineModule` 必须补一条提供者，落 §4.2 那一步同一提交。

## 3. SettingsTags / SettingsStrings / SettingsScreen 的 Section 现有模式（§5.3）

- Section 外壳：`feature/settings/.../ui/SettingsScreen.kt:113-125`
  `private fun Section(tag: String, title: String, content: @Composable () -> Unit)` = `OutlinedCard(fillMaxWidth.padding(12.dp).testTag(tag))` +
  `Column(padding 12, spacedBy 6)` + `titleSmall` 标题一行。
- 挂接点：`:87-98` 顺序 `TierSection → Divider → ParallelSection → Divider → ModelSection → Divider → ProbeCard → Divider → VersionCard`，
  外层 `Column.fillMaxSize().verticalScroll`（`:70`）。新 Section 插在 `ModelSection` 之后、`ProbeCard` 之前（两个模型相关卡片相邻，`VersionCard` 仍在最底，不破坏 `:251` 的 `performScrollTo(CARD_VERSION)`）。
- **section tag 的先例**：`:177` `Section(tag = SettingsTags.SCREEN + "-model", ...)` —— 规格书 §4.2
  「新 tag 前缀照旧（`SCREEN+"-credential"` 系列）」即指这条，于是新 Section 的 tag = `"screen-settings-credential"`。
- tag 表：`ui/SettingsTags.kt`（`@Immutable object`，`:11` `SCREEN = "screen-settings"`，常量 + `fun tier(tier)` 派生 + `init{}` 自检 `:34-37`）。
- 文案表：`ui/SettingsStrings.kt`（`:42` 起 `TITLE/SECTION_*/PROBE_*/VERSION_*` 全 `const val`，带参提示写成 `fun memTierSaved(tier)` `:88`；§4.2 的中文新串进这里）。
- notice 机制：`SettingsViewModel.kt:91-94` 单条 `_notice: MutableStateFlow<String?>`，
  UI 侧 `SettingsScreen.kt:62-67` `LaunchedEffect(notice){ delay(4_000); consumeNotice() }`（`:37` 常量）。
  → 「已保存，新会话生效（对话进行中请停止后重开）」直接复用这条路，不新建第二条提示通道。
- 测试配方：
  - UI：`src/test/.../ui/SettingsScreenTest.kt:41-54` `@Config(sdk=[35], qualifiers="w420dp-h2400dp")` +
    `createAndroidComposeRule<ComponentActivity>()` + 手工构 VM（`:63-80` `rig()`）+ `pump()`（`waitForIdle` → `shadowOf(mainLooper).idle()` → `waitForIdle`）；
  - VM：`SettingsViewModelTest.kt:31-35` `Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))` + `runTest` + `runCurrent()`。

## 4. 两条「规格书没写但必须定」的接线细节

1. **主线程不做磁盘 IO（本仓既有纪律）**：`feature/settings/.../RuntimeInfo.kt:56`/`:73` 的定案原文是
   「实现方**自己负责挪出主线程**（`withContext(Dispatchers.IO)`），VM 侧不做调度决定——假体在那里不挂起时，
   测试就是纯虚拟时钟」（实现见 `:92`/`:94`）。规格书 §4.1 给的 `ModelCredentialStore` 三方法是**阻塞**的，
   而 §4.2 的 VM 又跑在 `viewModelScope`（= 主线程）。若在 VM 里直接 `withContext(Dispatchers.IO)`，
   Robolectric 用例就得等真线程 → 与上面这条纪律冲突。
   → 落法：`:feature:settings` 侧加一层**消费者接口** `ModelCredentialSource`（suspend 面 +
   production 实现 `FileModelCredentialSource` 包 `ModelCredentialStore` 并自己 hop IO），
   绑在 `di/SettingsModule`（照 `:28-30` `runtimeInfoSource` 那条式）。规格书 §4.2 要的「注入 fake store」
   正好就是这个接口的假体；`ModelCredentialStore` 本体一类不改形状（§4.1 逐字照抄）。记入收尾报告偏差清单。
2. **provider 选择控件的形状**：规格书 §4.2 允许「ExposedDropdownMenuBox 或 Dialog 均可」。
   取 **Dialog**：
   - `ExposedDropdownMenuBox`/`ExposedDropdownMenu`/`menuAnchor()` 三代 API 在 material3 1.3→1.5 之间有签名漂移
     （无参 `menuAnchor()` 1.3 起 deprecated、`ExposedDropdownMenu` 新增 `anchor` 参数），
     而编译机 gradle 缓存里同时躺着 `material3-android/1.4.0` 与 `1.5.0-alpha22` 两档（实测 `find` 输出），赌不得；
   - `AlertDialog` 这条路本仓已在 Robolectric 上实证：`feature/chat/.../ui/PermissionDialog.kt:26-27` 用的就是它，
     `ChatScreenUiTest` 的第 ② 组「PermissionDialog 三按钮各自回写」是绿的；`DropdownMenu` 走 `Popup` 窗口，本仓零先例。
   → 用 `OutlinedButton`（展示当前 provider）+ `AlertDialog`（10 行可滚列表，每行一个 tag）。
3. **不新增依赖**：`kotlinx-serialization-json` 已是 `core/engine/build.gradle.kts:48` 的 `implementation`，
   §4.1 的 JSON 操作全在该模块主源集内，`ModelCredentialStore` 的公开签名（`ProviderEntry`）是纯 Kotlin 类型，
   不需要把 serialization 升 `api`；`:feature:settings` 因此不碰 `gradle/libs.versions.toml`（红线：冻结区 + 白名单都不动）。

## 5. 落地顺序与影响面（规格书 §6 对照）

| 步 | 改动文件（预计） | 测试门槛 |
|---|---|---|
| 2 | `core/engine/.../settings/ModelCredentialStore.kt` A / `.../di/EngineModule.kt` M / `core/engine/src/test/.../settings/ModelCredentialStoreTest.kt` A | store 单测 ≥8 |
| 3 | `feature/settings/.../ModelCredentials.kt` A / `SettingsViewModel.kt` M / `di/SettingsModule.kt` M / `ui/SettingsScreen.kt` M / `ui/SettingsTags.kt` M / `ui/SettingsStrings.kt` M / 测试：`Fakes.kt` M、`ui/SettingsScreenTest.kt` M、`SettingsViewModelTest.kt` M、`app/src/test/.../AppTestDoubles.kt` M | settings UI ≥6 |
| 4 | `app/build.gradle.kts` M（3 / 0.1.2）/ `app/src/test/.../ManifestPermissionTest.kt` M（`:75`/`:82-83` 的版本断言随之改 3L / "0.1.2"） | :app 17 基线不回归 |
| 5-6 | 编译机逐模块 + 全量矩阵 + `:app:assembleDebug` + badging + 回传 `dist` | 全绿 |

## 6. 环境与红线基线实测（本轮开工前）

- 编译机 `64.90.11.50`（`bssh.sh` 一次连通）：`df -h /` → `49G 44G **4.5G** 91%`（§8 要求 ≥4G，达标但贴边）；
  `free -m` → `Mem available **3434M**`；矩阵脚本 `/root/matrix.sh` 原样可用（逐模块一次调用 +
  `--no-build-cache` + `pkill KotlinCompileDaemon` + 打包前 `rm` 旧 APK），计数脚本 `/root/tally.py` 同在。
- 本机 `JAVA_HOME=/root/pi/.tools/jdk-21.0.12.1+1`，只跑 `:core:process` / `:core:rpc`（37 基线）。
- 冻结区复核（开工前）：`git diff --stat 1f77127..HEAD -- core/rpc core/process/src/main core/environment docs/protocol.md tools/capture tools/runtime-packaging app/src/main/jniLibs`
  → **无输出**（干净）。另注：同一区间加 `gradle/libs.versions.toml` 会显示 `15 insertions(+)`，
  那是 **P2E** 加 markwon/datastore 留下的，不属于冻结路径（规格书 §7 的冻结清单里没有它），本里程碑同样不动它。
- 基线用例数（P2F 收尾实测，作为「不回归」的对照）：编译机 184（environment 5 / engine 68 / chat 47 / sessions 25 / settings 22 / app 17）+ 本机 37 = 221。

## 7. 结论（可直接照此动工）

1. `homeDir` 用 `PathLayout.homeDir(ctx)`（`:34`），不写硬路径，不动冻结区；
2. store 绑法照 `EngineModule:100-103`，`@Provides @Singleton` + 提供者签名吃 ctx；`:app` 测试图补一条提供者；
3. Section/tag/strings/notice/测试 rig 全部复用 §3 列出的现成模式，新 tag 前缀 `screen-settings-credential-*`；
4. 另加一层 `ModelCredentialSource`（feature 侧接口）守「主线程零磁盘 IO」纪律，provider 选择用 `AlertDialog`；
5. 凭据只进 `auth.json`：不进 DataStore、不进日志、不进快照、不进提交；UI 只显示 provider + 「已配置」，永不回显 key。
