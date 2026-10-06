# REPORT-P2E — P2 第五里程碑收尾（markwon 渲染 + sessions/settings 两屏 + 多会话 + app 四路由）

规格书：`/root/pi/.tools/qoder-task-p2e.md`。侦察记录：`tools/engine-service/RECON-P2E.md`。
本报告只记**实测结果**与**定案**；未执行的步骤逐条写明「未执行」。所有哈希/字节数/时长
均为编译机与本机真实回显（2026-10-06 21:51Z–22:03Z 窗内）。

---

## 1. git 提交清单（7 笔，严格照 §6 六步顺序）

| # | commit | 前缀 | 内容（对应 §6 步） |
|---|---|---|---|
| 0 | `e091c58` | `docs:` | RECON-P2E.md（§5 六条侦察项，含 markwon 坐标定案、Robolectric AndroidView 实测可行、DataStore 单文件单实例纪律、头读 64KB 与 pi 侧对齐表、sessionPath 推导、磁盘回收实录）——**步骤 1** |
| 1 | `653579d` | `feat:` | P2E 依赖与冒烟件（markwon 三件套 + DataStore；§4.6 冒烟门开）——**步骤 1** 的产物落地件 |
| 2 | `59eab46` | `feat:` | `:feature:sessions` / `:feature:settings` 模块骨架 + DataStore `SettingsRepository` + `envFor` 动态 memTier——**步骤 2** |
| 3 | `a9da000` | `feat:` | 会话列表链（`PiSessionScanner` 头读 + `SessionsViewModel` + Compose 屏），25 用例绿——**步骤 3** |
| 4 | `db4a8ca` | `feat:` | 设置屏（memTier 三选 + 并行 stepper + `get_state` 探针 + 版本卡片），22 用例绿——**步骤 4** |
| 5 | `2c43f17` | `feat:` | chat 正文改 markwon 渲染 + 多会话切换 + `maxParallelSessions` 门——**步骤 5** |
| 6 | `8c81c9f` | `feat:` | app NavHost 四路由全实装 + 聊天页 VM 改 Activity 作用域——**步骤 6** |

未 push（§2 红线）。产物不入 git：APK/`build/` 全部未 `git add`，`git status` 收尾为空（见 §9）。

改动面合计（`git diff --stat 1f77127..HEAD`）：**48 files changed, 6052 insertions(+), 145 deletions(-)**。

### 1.1 红线体检（逐条给证据）

| 红线 | 结论 | 证据 |
|---|---|---|
| 冻结区只读 | **零改动** | `git diff --stat 1f77127..HEAD -- core/rpc core/process/src/main core/environment docs/protocol.md tools/capture tools/runtime-packaging` → 空输出 |
| `ChatEngine` / `RuntimeInstall` / `SessionSupervisor` 签名不变 | **未触碰** | `git diff 1f77127..HEAD -- core/engine/.../ChatEngine.kt …/RuntimeInstall.kt …/SessionSupervisor.kt` → 空输出；门做在 `ChatViewModel` 里（§9 偏差 6） |
| 依赖白名单封闭 | **只加了允许的 4 件** | `libs.versions.toml` 的 P2E 增量 = `io.noties.markwon:core:4.6.2` / `io.noties.markwon:syntax-highlight:4.6.2` / `io.noties:prism4j:2.0.0` / `androidx.datastore:datastore-preferences:1.1.7`；全仓 `grep -rn "okhttp\|espresso\|coil\|markwon-editor\|markwon-html\|WebView\|webkit"` 在 build/toml 里 **0 命中**（仅两处注释写「不引 WebView」） |
| commit 前缀 | 全 `feat:`/`docs:` | 见 §1 表 |
| `git add` 只给明确路径 | 是 | 每笔提交逐个文件路径 `git add`，无 `-A`/`.` |
| 磁盘 ≥4G | 全程守住 | 动工前 4.9G → 矩阵跑完 4.6G（`df -h /` 实录见 §7） |

---

## 2. 测试矩阵（§7 逐项，`--no-build-cache` + 每模块 `cleanTestDebugUnitTest` **强制真跑**）

| 位置 | 任务 | 要求 | 实测 |
|---|---|---|---|
| 本机 | `:core:process:test` + `:core:rpc:test` | 37 绿 | **37 绿**（JvmProcessTest 3 + GoldenReplay 9 + LineFramer 9 + PiDesktopE2e 1 + PiSession 8 + RpcModel 7；failures=0 errors=0 skipped=0，两 `test` 任务均为 `executed` 非 UP-TO-DATE） |
| 编译机 | `:core:environment:testDebugUnitTest` | 5 绿 | **5 绿**（EnvFactoryTest 2 + PathLayoutTest 3），33s |
| 编译机 | `:core:engine:testDebugUnitTest` | 40+ additive | **50 绿**（P2D 40 + `SettingsRepositoryTest` 6 + `DataStoreProbeTest` 4），59s |
| 编译机 | `:feature:chat:testDebugUnitTest` | 23+ additive | **40 绿**（P2D 23 + ChatViewModelTest case14–21 共 8 + `MarkwonRenderTest` 4 + `MarkwonSmokeTest` 4 + ChatScreenUiTest ⑥ 1），47s |
| 编译机 | `:feature:sessions:testDebugUnitTest` | ≥11 绿 | **25 绿**（SessionScannerTest 10 + SessionsViewModelTest 12 + SessionsScreenTest 3），42s |
| 编译机 | `:feature:settings:testDebugUnitTest` | ≥5 绿 | **22 绿**（SettingsViewModelTest 16 + SettingsScreenTest 6），45s |
| 编译机 | `:app:testDebugUnitTest` | 12+ 绿 | **14 绿**（MainActivityTest 11 + PiEngineServiceWiringTest 3），55s |
| 编译机 | `:app:assembleDebug` | 绿 + 体检 + 回传 + sha256 | **BUILD SUCCESSFUL in 32s**（158 tasks：`packageDebug` **executed**，其余 up-to-date——先 `rm` 产物逼真跑），体检见 §3 |

Android 侧总计 **156 用例 0 红**（5+50+40+25+22+14），本机另 37 用例 0 红，
全里程碑 **193 用例**绿。逐模块耗时实录（编译机 UTC）：21:51:03 environment → 21:51:44 engine →
21:52:52 chat → 21:53:48 sessions → 21:54:39 settings → 21:55:33 app → 21:56:37 assembleDebug。

### 2.1 为什么必须逐模块调用（本轮新增的环境结论，未来里程碑照此办）

一次调用串 6 个模块的写法**被 OOM killer 打死**：
`Gradle build daemon disappeared unexpectedly`，`dmesg` 原文
`Out of memory: Killed process 446884 (java) total-vm:5526972kB, anon-rss:1333832kB`，
机器总内存 3911MB。P2D 之所以能一条命令跑完，是那轮测试类少、Robolectric 分叉的 test JVM 没叠上去。
定案（已固化成编译机 `/root/matrix.sh`）：**每模块一次 `gradle --no-daemon`**，
外加 `-Dorg.gradle.jvmargs=-Xmx1100m -Dkotlin.daemon.jvmargs=-Xmx700m`、模块间 `sleep 8` +
`pkill -f KotlinCompileDaemon`。P2D §7 那条「四模块合并跑」的复现命令在本机内存上**不再成立**。

### 2.2 §4.5 要求逐条对账（一条不落）

| §4.5 要求 | 落点 | 用例 |
|---|---|---|
| SessionScannerTest ≥6 | `feature/sessions/src/test/.../SessionScannerTest.kt` | s01–s10（**10**）：正常扫描/缺 header 跳过/name 取最新与清空/目录不存在空表/mtime 降序+文件名升序/>64KB 只读头/切半行丢弃/删除地盘校验 |
| SettingsRepositoryTest ≥3 | `core/engine/src/test/.../SettingsRepositoryTest.kt` | **6**：默认值、写读回、memTier 枚举映射、夹紧区间、快照流、scope 关闭纪律 |
| SessionsViewModelTest ≥5 | `feature/sessions/src/test/.../SessionsViewModelTest.kt` | **12**：加载/空态/switch 命令捕获/new 命令捕获/删除后刷新/无活会话只导航不命令 等 |
| SettingsViewModelTest ≥2 | `feature/settings/src/test/.../SettingsViewModelTest.kt` | **16**：`get_state` 探针命令捕获 + Response 显示、memTier 写回、并行夹紧、超时/失败/无总线各分支 |
| ChatViewModel 多会话 ≥3 | `ChatViewModelTest` case14–21 | **8**：switch 成功重置 transcript / success=false 不动 / 发送异常不动 / 超时不动 / newSession + 占位行自动撤 / 门拦截 + 不起 FGS + 改上限后重试 / 终态不占配额 / 外部滚只清场不发命令 |
| markwon 渲染 UI Test ≥2 | `ui/MarkwonRenderTest.kt` | **4**，且**未走降级预案**：RECON-P2E §2 实测 `AndroidView` 在 Robolectric 可渲染，故按 §4.5 首选路线做真渲染断言（TextView 文本无 `**`/``` 残留 + spans 非空 + 换文本复用同一 view），纯 processor 那条只作为附加一条 |
| app NavHost 四路由可达 ≥2 | `MainActivityTest` ④ / ④b / ④c | **3**：真屏可达 + 回退顺序、路由表自洽、`chat/{sessionId}` 落地且不重生 VM |
| 既有 40+23+12 全绿原样 | engine 50 / chat 40 / app 14 | 全部 ≥ 基线；等价调整见 §2.3 |

### 2.3 等价调整清单（§4.5 末条「等价调整记录」，逐条给出**为什么覆盖面没降**）

1. **`ChatViewModelTest` case03 / case04**：断言前插 `advanceTimeBy(DELTA_DEBOUNCE_MS) + runCurrent()`。
   原因：正文提交时机从「事件到即写」变成「80ms 合并窗口后写」（§3.3）。
   等价性：断言的**值**一个没改，只是把时钟推到提交点之后；「窗口内不提交」由 case20 新增断言补测，净覆盖上升。
2. **`ChatScreenUiTest` ③**：正文判定从 `onNodeWithText(正文文本)` 改为
   `onNodeWithTag(ChatTags.markdown(id)).performScrollTo().assertIsDisplayed()`。
   原因：markwon 输出经 `AndroidView` 挂在真实 `TextView` 上，**不进 Compose 语义树**。
   等价性：「文本内容对不对」的判据整体上移到 `MarkwonRenderTest`（直接读真实 TextView 的 text 与 spans），
   UI 测试只保留「渲染体在位、在视口内」这一它仍能测的面。
3. **`MainActivityTest` ⑥ / ⑦**：同因加 `renderedBody()`（遍历 `activity.window.decorView` 收集 TextView 文本）
   与 `pumpDebounceWindow()`（`shadowOf(mainLooper).idleFor(Duration.ofMillis(120))`）。
   等价性：断言文本内容仍逐字保留（"好的，已在 main" / "通知被拒也不影响这一轮"），只是取值层从语义树换到视图树。
4. **`ChatModels.kt` 删除 `splitFencedSegments` / `TextSegment` / `FENCE`**（P2D 的「Text + ``` 围栏 monospace」占位件）。
   原因：§3.3 的 markwon 上线即取代它，围栏代码块由 `SyntaxHighlightPlugin` 负责。
   等价性：其单测（围栏切段）由 `PiGrammarLocator` 的语法表用例 + `MarkwonRenderTest` 的 fence 渲染断言取代。
5. **`ui/SmokeMarkdownView.kt` 退役**：冒烟配方固化成 `MarkdownBody.kt` 里的 `buildMarkwon(context)`，
   由 `di/MarkwonModule` 与 `:feature:chat` UI 测试共用；`MarkwonSmokeTest` 保留 4 条、改调 `MarkdownBody`。
   等价性：冒烟没有消失，是**从一次性验证件变成生产装配点**（同一份代码既过冒烟测试也进 Hilt 图）。
6. **`MainActivityTest` ④**：占位屏断言（`Nav.PLACEHOLDER` 字样 + `Nav.TAG_SESSIONS/TAG_SETTINGS`）
   换成真屏 tag（`SessionsTags.SCREEN`/`EMPTY`/`FAB_NEW`、`SettingsTags.SCREEN`/`CARD_VERSION`/`TIER_HINT`）。
   回退顺序断言（settings → sessions → chat）**原样保留**；顶栏入口新增 testTag
   是因为屏内标题与入口文案相撞（「会话」「设置」各出现两次），`onNodeWithText` 会命中 2 节点。

### 2.4 §7 之外的额外测试资产（additive，不占要求名额）

- `core/engine/DataStoreProbeTest.kt` 4 条：Robolectric 4.16 + DataStore 1.1.7 的装配探针
  （RECON-P2E §3 三条结论钉成断言，防止未来「多实例同文件」回归）。
- `feature/chat/ui/MarkwonSmokeTest.kt` 4 条：§4.6 冒烟门的常驻版（编译门过了仍留在测试面里）。

---

## 3. APK 体检（§7 末行）

产物：编译机 `app/build/outputs/apk/debug/app-debug.apk` → 回传 `/root/pi/dist/pi-host-full-debug.apk`。

- 字节数 **63,752,089**；`sha256 = 6d70d658e059c52b9cd26d672f20484fe7c7cc6e9afc3af54c1c2945474e1587`
  （回传后本机 `sha256sum` 与编译机同值 → 传输无损）
- 对比 P2D 基线（`REPORT-P2D.md` §3，62,765,189B / `9058771439dbe…`）：**+986,900B**
  = markwon+commonmark+prism4j 的 dex/资源、DataStore 的 dex（含一条新的 `libdatastore_shared_counter.so`）、
  两个 feature 屏（sessions/settings）与 app 四路由的 dex。so 部分见下表，运行时 27 个 so 字节不变。

`/opt/android-sdk/build-tools/35.0.0/aapt2 dump` 实测（RECON-P2D §6 / REPORT-P2D §3 基线逐条）：

| 检查点 | 实测值 |
|---|---|
| package | `name='works.earendil.pihost' versionCode='1' versionName='0.1.0' platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36'` |
| SDK 形状 | `compileSdkVersion='36'` / `targetSdkVersion:'35'`（minSdk 26 不变）→ §9 偏差之外的 P2D §13.7 实证形状保持 |
| `application android:name` | `works.earendil.pihost.PiHostApp`（`@HiltAndroidApp`） |
| `android:extractNativeLibs` | `true`（§2.3/§8：libpi_*.so 必须解压成真实文件才可 `execve`） |
| activity / service | 1 个 `works.earendil.pihost.MainActivity`；`works.earendil.pihost.core.engine.PiEngineService` 带 `foregroundServiceType=0x40000000`（specialUse）+ `property …PROPERTY_SPECIAL_USE_FGS_SUBTYPE="local_ai_coding_agent_engine"` |
| uses-permission | `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` / `POST_NOTIFICATIONS` + `works.earendil.pihost.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（AGP 注入，P2C 起就有）→ **无新增权限** |
| `native-code` | `'arm64-v8a'`（`abiFilters` 未松动） |
| `lib/arm64-v8a/` | **29 个文件** = 运行时 27 个 so 全在位（`comm -23 jniLibs清单 apk清单` = **空集**）+ `libandroidx.graphics.path.so`（material3 传递）+ `libdatastore_shared_counter.so`（**本轮新增**，datastore 1.1.7 传递原生库） |
| `assets/runtime/` | **2 个文件**：`manifest.json` 177,042B + `pi-runtime.zip` 8,215,598B |
| 资产逐字节一致 | APK 内 `manifest.json` = `de05ce57ff2fba25b46af95b000cad6d393d285384e10b1b40d0ed090e7f1fa4`、`pi-runtime.zip` = `abe3366ca0db1636fa9b7be5a5e350bc0f25b30ffa5550435be863c3df47762b`，与 `app/src/main/assets/runtime/` 两个源文件 `sha256sum` **逐字相等** → 打包链未改动、未截断 |
| 测试泄漏 | `aapt2 dump xmltree` 里 `androidx.activity.ComponentActivity` 出现次数 **0** → `ui-test-manifest` 仍只在 `testImplementation`，APK manifest 基线未被污染 |

`stripDebugDebugSymbols` 的「Unable to strip」提示本轮 29 个库（P2D 是 28，多出的正是 datastore 那颗），
同现象、非本轮引入的问题。

**未执行**：真机安装/运行验证（无 arm64 真机；本轮全部证据来自编译机 Robolectric + aapt2 + unzip 体检）。
`isMinifyEnabled` 未开（debug），R8 兼容只到「compile + dex 通过」口径（RECON-P2E §1.5）。

---

## 4. §9 八条偏差的执行确认（逐条给代码落点）

1. **chat 恒为 startDestination** ✅ `AppNavHost.kt` `startDestination = Nav.ROUTE_CHAT`；
   sessions/settings 从顶栏 `Nav.TAG_ENTRY_SESSIONS` / `Nav.TAG_ENTRY_SETTINGS` 进入；
   `MainActivityTest` ① 仍断言「冷启 = 安装门」，④ 断言「两条顶栏入口 + 回退依次回 sessions/chat」。
2. **SessionScanner 只读头 64KB** ✅ `SessionScanner.HEAD_BYTES = 64*1024`（+ `MAX_LINES=200` 第二道上限），
   `RandomAccessFile` 一次读满 `min(size, headBytes)`；头窗读不到的 `name`/`firstMessage` 给 `null` → UI 显示
   「未命名会话」/「（无消息）」。`modified` 恒取 mtime（有意偏差，对齐表在 RECON-P2E §4.2）。
   `s06/s07` 两条用例把「大文件只读头」和「切半行丢弃」钉死。
3. **删除会话 = 删 jsonl，二步确认** ✅ `SessionsViewModel.requestDelete`（第一步只挂确认卡，
   `pendingDelete` 放 VM 而非 `remember`，好让单测钉死「确认前绝不删文件」）→ `deleteSession`（第二步）。
   `PiSessionScanner.deleteBlocking` 只允许删 `sessionsRoot` 地盘内的 `.jsonl`（canonicalFile 前缀校验，拒 `../`/软链逃逸）。
   pi 侧无删除 API（§9 原文），删除后立刻 `refresh()`，磁盘与列表不留中间态。
4. **memTier 只影响后续 spawn，不杀活动会话** ✅ `EngineModule.envFor = { EnvFactory.build(ctx, settings.memTier) }`
   —— 该 lambda 只在 spawn 时被 `SessionSupervisor` 同步调用；没有任何「改档位 → 重启会话」的路径。
   设置屏把生效时机写在界面上（`SettingsStrings.TIER_HINT`，`MainActivityTest` ④ 断言其可见）。
5. **Markwon @Singleton + 主线程 confinement** ✅ `di/MarkwonModule` 提供 `@Singleton`，
   唯一配方是 `MarkdownBody.buildMarkwon(context)`（与冒烟件同源）；
   `MarkdownBody` 绝不 `remember { buildMarkwon() }` 自建实例。
   渲染只发生在 `AndroidView` 的 factory/update（主线程）；`remember(text)` + `appliedText` 双层判等
   保证「文本真变了才 re-parse」。Prism4j 语法表注册一次由 `PiGrammarLocator` 承担。
6. **maxParallelSessions 门在 VM 层** ✅ `ChatViewModel.startEngine()` 开头自查；
   占配额 = `Spawning | Ready | Restarting`（**故意排除** `Stopped`/`Failed`：冻结区 `sessionStates`
   在会话进终态后仍保留条目，算进去会让上限变成「历史会话总数」）。
   拦截后 **连 FGS 意图都不发**（`case18` 用 `assertNull(shadowOf(app).peekNextStartedService())` 钉死），
   改完设置可重试。SessionSupervisor 语义零改动。
7. **OAuth / D2 继续搁置** ✅ 本轮未加任何登录/OAuth 代码；`defaultModel` 三键之一只**读显示**
   （设置屏「默认模型」卡片 + `MODEL_HINT` 写明「模型切换不在本里程碑」）。
8. **usage 只显示 messageCount** ✅ `SessionRowView.meta` = 相对时间 + 消息数 + 体积 + cwd，
   没有 token 聚合；`PiSessionScanner` 的 `messageCount` 是**头窗内**计数（§9 偏差 2 的截断语义），
   这点写进 KDoc 与 `s06` 用例。

---

## 5. 架构定案（规格书未覆盖处，本轮自行决定并落进代码）

RECON-P2E §7 的 1–8 条已定案（依赖坐标、prism4j 语法表自给、mtime、firstMessage 占位、
Scanner 落 feature、Repository 落 core、Markwon 供给点、PullToRefresh + 刷新按钮）。步骤 5/6 新增：

9. **Markwon 经 VM 转交给 UI，而不是 UI 自建**：`ChatViewModel` 注入 `@Singleton Markwon`，
   以 `internal val markwon` 暴露给同模块的 `TranscriptList`/单测。
   `internal` 而非 public 的理由：markwon 是 chat 的 `implementation` 依赖，
   公开签名一旦带上它的类型就是给 `:app` 挖「unresolved reference」坑。
10. **转录的唯一写入点 `mutateTranscript`**：所有 10 处 `_transcript.update` 收拢成一个 inline helper，
    由它单点撤除「会话切换中…」占位行（`NoteItem`）——避免「谁负责清占位」散落在每个事件分支里。
11. **并行门复用 `ChatUiState.Failed(cause)`**，不新增第五态：§5.1 对 Failed 的定义就是
    「引擎起不来 + 可读原因」，门拦下的正是这件事；多一个状态只会让 `ChatScreen` 的 `when` 多一条无新语义的分支。
12. **`chat/{sessionId}` 的 VM 作用域 = Activity**（本轮最重要的一个决定）：
    `AppNavHost` 在 **NavHost 之外**读 `LocalViewModelStoreOwner`（此时它是 Activity，
    进 `composable{}` 后 Navigation 会换成 `NavBackStackEntry`），两条 chat 路由用同一个 owner 建 VM。
    硬约束的理由不是风格：`ChatViewModel.init` 会走 `installAndStart() → engine.start()`，
    条目作用域 = **每从列表页回一次聊天页就多 spawn 一条 pi 进程**，还会去撞 §9 偏差 6 的门。
    `MainActivityTest` ④c 用 `assertEquals(1, engine.startCalls.get())` 把这条钉死。
13. **外部切换的落地钩子 `ChatViewModel.onExternalSessionRoll()`**：sessions 屏已发过 `switch_session`，
    聊天页只复用 `onSessionRolled()` 的清场（turnState/queueDepth/commandResult/retryNote + 占位行），
    **绝不重发命令**；未 Ready 时惰性返回（否则把「运行时未安装」的安装门洗成一条占位行）。
    这是 `ChatViewModel` 的 additive 公开方法，不涉及 `ChatEngine`/`RuntimeInstall`。
14. **顶栏跳转 = `popUpTo(目标自身)` 的 tab 去重**，而不是官方底部导航的 `popUpTo(startDestination)`：
    前者保住 P2D 测试 ④ 的回退形状（settings → sessions → chat），后者会让「列表 → 设置 → 返回」直接跳回聊天页；
    同时 `launchSingleTop` 仍防连点压栈，交替点两个入口的最深栈是 `[chat, sessions, settings]`，有界。
15. **列表点行 = `popUpTo(ROUTE_SESSIONS)`**：聊天页按返回回到**会话列表**（用户直觉），
    而不是直接退出 App。
16. **测试定位一律走 tag，文案只做值断言**：屏内标题/按钮与 app 顶栏入口文案相撞（「会话」「设置」各两处），
    `onNodeWithText` 在真屏上会命中 2 节点；`Nav` 因此新增 `TAG_ENTRY_SESSIONS`/`TAG_ENTRY_SETTINGS`。

---

## 6. 本轮踩到并固化进代码/测试的坑（按代价排序）

1. **Robolectric PAUSED looper 的 `idle()` 不推进时钟** → `viewModelScope` 上的 `delay(80)`
   在 PAUSED 模式下是一条**未来**消息，永不触发；`renderedBody()` 实测为空。
   修法：`shadowOf(looper).idleFor(Duration.ofMillis(120))`（`pumpDebounceWindow()`）。
2. **`Dispatchers.Unconfined` 不实现 `Delay`** → `withTimeoutOrNull(5s)` 在那里落到**真实时钟**，
   虚拟时间测试永远推不到超时。修法：带超时的协程挂 `viewModelScope`（测试 = `UnconfinedTestDispatcher` 虚拟时钟），
   只有 SharedFlow 的 `collect` 用 `Unconfined`（保证「先订阅再发命令」）。`SettingsViewModel` 探针同病同治。
3. **`AndroidView` 内容不入 Compose 语义树** → markwon 渲染的正文 `onNodeWithText` 看不见（§2.3 条 2/3）。
4. **`onNodeWithText` 要求唯一命中**：提示 banner 与安装门的 cause 同文案 → 红在
   `Expected at most 1 node but found 2 nodes`。修法：tag 定位 + `assertTextEquals`。
5. **Kotlin 模板紧跟 CJK**：`"$label失败"` 被解析成标识符 `label失败`（4 处编译错），必须写 `${label}失败`。
6. **DataStore「一个文件只允许一个活实例」**：`IllegalStateException: There are multiple DataStores active
   for the same file`（栈顶 `FileStorage.createConnection(FileStorage.kt:52)`）→ 测试侧
   `scope.cancel()` + `job.join()` 收尾，生产侧 `@Singleton`（RECON-P2E §3）。
7. **turbine 在不继承作用域的测试作用域里会挂** → `:feature:settings` 测试弃用 turbine，
   改 `backgroundScope.launch(UnconfinedTestDispatcher)` 逐值快照。
8. **编译机内存 3.9G → 合并调用被 OOM kill**（§2.1，已固化成逐模块脚本）。
9. **markwon 坐标与 prism4j 语法表**（RECON-P2E §1.1/§1.3）、**`annotations-java5` 重复类必须写在根
   `build.gradle.kts`**（RECON-P2E §1.2）——两条是步骤 1 的踩点，本轮不再重复。
10. `androidx.hilt.navigation.compose.hiltViewModel` 在 hilt-navigation-compose 1.3.0 已**废弃**
    （迁到 `androidx.hilt.lifecycle.viewmodel.compose`）。本轮沿用旧包（编译通过、仅 `w:` 警告），
    **未**为消警告新增白名单外依赖；`:app` 三处路由 + 各 feature 屏同口径。

---

## 7. 复现命令

```bash
# ① 本机 37 基线（强制真跑：cleanTest + --no-build-cache）
cd /root/pi/android-host && JAVA_HOME=/root/pi/.tools/jdk-21.0.12.1+1 \
  /root/pi/.tools/gradle-8.13/bin/gradle --no-daemon --console=plain --no-build-cache \
  :core:process:cleanTest :core:rpc:cleanTest :core:process:test :core:rpc:test

# ② 全量同步（含 .git，排本机 .gradle/.kotlin）
bash /root/pi/.tools/bbuild.sh sync

# ③ 编译机矩阵：**逐模块一次调用**（合并调用会被 OOM killer 打死，见 §2.1）
#    脚本已装在编译机 /root/matrix.sh（= 本机 /tmp/matrix.sh），逐模块 cleanTestDebugUnitTest +
#    testDebugUnitTest + 删 APK 逼 packageDebug 真跑
bash /root/pi/.tools/bbuild.sh raw 'bash /root/matrix.sh > /tmp/matrix.log 2>&1; cat /tmp/matrix.sum; bash /root/tdigest.sh core/environment core/engine feature/chat feature/sessions feature/settings app'

# 单模块手工版（示例）
cd /root/android-host && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=PKCS12" \
  /opt/gradle-8.13/bin/gradle --no-daemon --console=plain \
  -Dorg.gradle.jvmargs=-Xmx1100m -Dkotlin.daemon.jvmargs=-Xmx700m \
  :feature:chat:cleanTestDebugUnitTest :feature:chat:testDebugUnitTest --no-build-cache

# ④ APK + 回传 + 体检
cd /root/android-host && rm -f app/build/outputs/apk/debug/app-debug.apk && \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 /opt/gradle-8.13/bin/gradle --no-daemon --console=plain \
  --no-build-cache :app:assembleDebug
scp -o BatchMode=yes -o UserKnownHostsFile=/root/pi/.tools/kh_buildsrv \
  root@64.90.11.50:/root/android-host/app/build/outputs/apk/debug/app-debug.apk \
  /root/pi/dist/pi-host-full-debug.apk
sha256sum /root/pi/dist/pi-host-full-debug.apk; stat -c %s /root/pi/dist/pi-host-full-debug.apk
#   期望：63752089 / 6d70d658e059c52b9cd26d672f20484fe7c7cc6e9afc3af54c1c2945474e1587
/opt/android-sdk/build-tools/35.0.0/aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk |
  grep -E "^package:|^targetSdkVersion|^native-code|^uses-permission"
/opt/android-sdk/build-tools/35.0.0/aapt2 dump xmltree --file AndroidManifest.xml \
  app/build/outputs/apk/debug/app-debug.apk | grep -E "E: (application|activity|service|property)|foregroundServiceType"
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E "lib/arm64-v8a|assets/runtime"
```

资源实录：矩阵开始时 `df -h /` = 4.9G 余量、`free -m` available ≈3.4G；
矩阵结束时 4.6G 余量（§2 红线 ≥4G 全程成立）。

---

## 8. 遗留问题（如实）

1. **真机未验证**（全程无 arm64 设备）：`sessionPath` 的真实形状按 `EnvFactory` 的 `HOME=filesDir/home`
   推导 + Robolectric 模拟（RECON-P2E §5 已标注）；FGS 在 Android 14/15 的 specialUse 实际豁免行为、
   通知权限弹窗、markwon 在真机字体度量下的排版，均未在设备上跑过。
2. **长按删除的二步确认只到状态层**：Robolectric 下 `performTouchInput` 长按/下拉手势不可靠
   （RECON-P2E §7.8 同一判定），UI 测试覆盖到「点击行 = switch + 导航」，
   `requestDelete` 的「只显示确认卡、不碰磁盘」由 `SessionsViewModelTest` 钉死，确认卡渲染由
   `SessionsScreenTest` 钉死；**手势注入本身未测**。
3. **prism4j 语法表是自给的**：常见语言之外（mermaid 等）走 `PiGrammarLocator.FALLBACK`
   按普通 code-block 显示；D5（代码高亮保真度）继续保持开档、不引 WebView。
4. **`SwitchSession` 后聊天页不重放历史**：切换只清场 + 挂占位（§4.3 的形状），
   从磁盘 jsonl 回填转录需要 pi 侧提供会话内容读取 API（当前只有 jsonl 头窗），故未做。
   用户可见后果：切完会话是空屏，得等新一轮输出。
5. **模型切换 / OAuth / D2**：§9 偏差 7，未做（`defaultModel` 只读显示）。
6. **token usage 聚合**：§9 偏差 8，未做（列表只有 messageCount）。
7. **`defaultModel` 可写但无消费方**：三键之一已持久化，`envFor`/argv 不读它
   （pi 侧的模型选择走 `set_model` 命令，属 P3 的模型切换面）。
8. **hiltViewModel 包迁移警告**（§6 条 10）：未来若要清警告，需要把 `androidx.hilt:hilt-lifecycle-viewmodel-compose`
   加进 §2 白名单并统一改五个模块的 import——留待下一个里程碑定夺。
9. **编译机磁盘长期在 4.6–5.0G 之间**：离 §2 的 4G 红线只剩 <1G 余量；
   下一里程碑动工前按 §8 顺序（`app/build/intermediates` → `runtime-pkg/dl` → `out/stage`）先清。

---

## 9. 交付物清单

| 交付 | 位置 | 状态 |
|---|---|---|
| 代码 | 7 笔提交（§1），树干净 | ✅ |
| 测试 | 本机 37 + Android 156 = **193 用例全绿** | ✅ |
| APK（带运行时资产） | `/root/pi/dist/pi-host-full-debug.apk` | **63,752,089 B**，`sha256=6d70d658e059c52b9cd26d672f20484fe7c7cc6e9afc3af54c1c2945474e1587` |
| 体检 | 本报告 §3 | aapt2 基线逐条过；资产与源文件逐字节等值 |
| 侦察记录 | `tools/engine-service/RECON-P2E.md` | `e091c58` |
| 收尾报告 | 本文件 | `docs:` 提交（§6 步骤 6 末条） |
