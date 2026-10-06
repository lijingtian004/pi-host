# P2D 交付报告 — Compose 聊天核心链（Hilt + :feature:chat + ChatViewModel/ChatScreen + 权限桥）

> 规格书：`/root/pi/.tools/qoder-task-p2d.md`（唯一事实源）。侦察记录：`tools/engine-service/RECON-P2D.md`。
> 本报告所有数字均为**实测**（编译机 `64.90.11.50:/root/android-host`、本机 `/root/pi/android-host`），
> 未执行的步骤显式标注「未执行」。作业顺序按 §9 六步走完，工具链冒烟（§8.1）先于任何业务代码。

## 1. git 提交清单（6 笔，按 §9 顺序）

| # | hash | 标题 | 规模 |
|---|---|---|---|
| 1 | `a5a2eac` | `docs:` P2D 侦察记录（compose/ksp/hilt 冒烟实录、Robolectric 双配方、冻结区事件形状） | 1 file, +207 |
| 2 | `170111d` | `feat:` Compose/Hilt/KSP 工具链基建与 `:feature:chat` 模块骨架 | 12 files, +510/−378 |
| 3 | `91ed1bf` | `feat:` core/engine 接口化（ChatEngine/RuntimeInstall）+ Hilt 布线并删除 EngineRuntime 静态桥 | 13 files, +1000/−183 |
| 4 | `9117cb6` | `feat:` `:feature:chat` 聊天核心链（ChatViewModel 状态机 + Compose 渲染 + 权限桥）+ FakeChatEngine 测试 | 13 files, +2479/−13 |
| 5 | `e433597` | `feat:` app 侧接线 chat 路由（hiltViewModel）+ Hilt 化的 app 测试等价重写 | 4 files, +609/−270 |
| 6 | 本笔 | `docs:` P2D 收尾报告 | 1 file |

工作树收尾状态：`git status --short` 干净（产物一律不入 git：`out/`、`jniLibs/`、`assets/` 由 .gitignore 覆盖）。
提交只按明确路径 `git add`，未使用 `git add -A`；未 push。

## 2. 测试矩阵（§12 逐项，`--no-build-cache` + 每模块 `cleanTestDebugUnitTest` 强制真跑）

| 位置 | 任务 | 要求 | 实测 |
|---|---|---|---|
| 本机 | `:core:process:test` + `:core:rpc:test` | 37 绿（冻结区回归） | **37 全绿**（process 3 + rpc 34，failures=0，skipped=0；BUILD SUCCESSFUL 1m57s，8 tasks executed） |
| 编译机 | `:core:environment:testDebugUnitTest` | 5 绿（回归） | **5 全绿**（EnvFactory 2 + PathLayout 3） |
| 编译机 | `:core:engine:testDebugUnitTest` | 既有 22 + 重写 service ≥6 | **40 全绿** = AssetInstaller 5 + RealRuntimeAssetsInstall 2 + PiSpawn 4 + SessionSupervisor 11（既有 22 原样）+ PiEngineService 7（重写，P2C 的 6 条 + 新增「start 抛异常不崩服务」）+ ChatEngineAdapter 6 + RuntimeInstall 5 |
| 编译机 | `:feature:chat:testDebugUnitTest` | VM ≥9 + Compose ≥3 | **23 全绿** = ChatViewModelTest 13 + ChatScreenUiTest 5 + 冒烟探针 5（`ToolchainProbeTest.kt` 里 ComposeProbe 3 / HiltProbe 1 / HiltComposeProbe 1，按 §8 保留进正式套件） |
| 编译机 | `:app:testDebugUnitTest` | ≥7（等价覆盖 P2C + NavHost） | **12 全绿** = MainActivityTest 9 + PiEngineServiceWiringTest 3 |
| 编译机 | `:app:assembleDebug` | BUILD SUCCESSFUL + 体检 + 回传 + sha256 | **BUILD SUCCESSFUL in 57s**（21 executed / 91 up-to-date），体检见 §3 |

编译机整轮计量：`--no-build-cache` + 4 个 `cleanTestDebugUnitTest` 前置 + 4 个 test 任务，
一条命令 **BUILD SUCCESSFUL in 3m48s / 156 actionable tasks（25 executed）**；
四个 `*testDebugUnitTest` 在执行清单里都是 **executed**（不是 UP-TO-DATE），即真跑。
本机 37 + 编译机 80 = **117 用例，0 失败 0 跳过**。

冻结区（`core/rpc/`、`core/process/src/main/`、`core/environment/`、`docs/protocol.md`、
`tools/capture/`、`tools/runtime-packaging/`）**一行未改**，实证命令与结果：
`git diff 63923a1..HEAD -- core/rpc core/process/src/main core/environment docs/protocol.md tools/capture tools/runtime-packaging`
→ **空输出**（`63923a1` = P2C 收尾提交，本轮起点）；且 `:core:process:test`/`:core:rpc:test` 37 基线原样绿。

### 2.1 P2C 覆盖面等价对照（§2 红线「不得缩水」：启动→通知→stop→探针→安装门→状态渲染）

| P2C 的断言 | P2D 落点 |
|---|---|
| 未安装 → 屏显 not installed | `MainActivityTest."chat route is the start destination and a clean sandbox shows the install gate"`（安装门 + 未点安装绝不起引擎） |
| 已装 → 版本上屏 | `MainActivityTest."install button runs the gate…"`（安装门 → Ready → 顶栏短码 = sessionId 前 8 位） |
| `renderState()` 四档 | `MainActivityTest."top bar short code follows the session state…"`（Ready/Restarting/Failed/Stopped + 队列徽标）+ `ChatViewModelTest case11` |
| 启动引擎 → FGS 意图 | `MainActivityTest` 同条（`ACTION_START` + 组件名）+ `ChatViewModelTest case01` |
| 测试 RPC → 先订阅再发 + 应答不入转录 | `ChatViewModelTest case10`（`probeState()` 发 `get_state`，id 匹配的 Response 只进 `commandResult`，不进 transcript）—— UI 层「测试 RPC」按钮随纯 View 壳退役，覆盖面迁到 VM |
| 安装失败显因、可重试、绝不起 FGS | `ChatViewModelTest case02` + `MainActivityTest."install failure keeps its own cause and the retry then succeeds"` |
| 无会话提示 + `ACTION_STOP` | `MainActivityTest."abort is offered only while streaming…"`（abort 矩阵 + STOP 意图构造）+ `PiEngineServiceWiringTest."stop intent shuts the sessions down…"`（shutdownAll → stopSelf → 撤通知）+ `PiEngineServiceTest` 全 7 条 |
| 通知权限分支 | `MainActivityTest."ready screen requests the notification permission once…"`（`@Config(sdk=[34])`，`shadowOf(activity).lastRequestedPermission.requestedPermissions` 实测命中 `POST_NOTIFICATIONS`，且拒绝不阻塞渲染） |

新增（P2C 没有的 P2D 主交付）：流式装配 3 条（case03/04/05：正文、thinking、toolcall 累积 + isError 红灯）、
权限桥 4 条（case06/07/08/09：允许一次、本会话记忆、拒绝 + TurnEnd 抢跑补 cancelled 恰好一次、超时定时器）、
bash 增量归属（case13）、引擎起不来带 stderr（case12）、Compose 渲染 5 条、prompt 往返 1 条。

## 3. APK 体检

产物：`编译机 app/build/outputs/apk/debug/app-debug.apk` → 回传 `/root/pi/dist/pi-host-full-debug.apk`。

- 字节数 **62,765,189**；`sha256=9058771439dbe5559a3c26dda275c953cb0d12f6c2de2a2bc245e3915527acf7`
- 对比冒烟基线（RECON-P2D §1，62,665,134B）：+100,055B = 真实 ChatScreen/VM 与 app 测试接线后的 dex 增量；
  对比 P2C 的 50.8MB：+11.9MB（Compose runtime + material3 + Hilt/Dagger 的 dex 与资源，so 部分字节不变）

`/opt/android-sdk/build-tools/35.0.0/aapt2 dump` 实测（RECON-P2D §6 基线逐条）：

| 检查点 | 实测值 |
|---|---|
| package | `name='works.earendil.pihost' versionCode='1' versionName='0.1.0'` |
| SDK 形状 | `compileSdkVersion='36'` / `platformBuildVersionName='16'`，`targetSdkVersion:'35'`，`sdkVersion` 未变（minSdk 26）→ §13.7 的实证形状 |
| `application android:name` | `works.earendil.pihost.PiHostApp`（本轮新增检查点，`@HiltAndroidApp` 已接管） |
| activity / service | 1 个 `works.earendil.pihost.MainActivity`；`works.earendil.pihost.core.engine.PiEngineService` 带 `foregroundServiceType=0x40000000`（specialUse）+ `property android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE="local_ai_coding_agent_engine"` |
| uses-permission | `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` / `POST_NOTIFICATIONS` 三条不变 + `works.earendil.pihost.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（AGP 自动注入，P2C 也带） |
| `lib/arm64-v8a/` | **28 个文件** = 运行时 27 个 so 全部在位（`comm -23 jniLibs清单 apk清单` = **空集**）+ `libandroidx.graphics.path.so`（material3 的传递原生库） |
| `assets/runtime/` | **2 个文件**：`manifest.json` 177,042B + `pi-runtime.zip` 8,215,598B（与 P2C 同尺寸，逐字节一致） |
| native-code | `'arm64-v8a'`（`abiFilters` 未松动） |
| 测试泄漏 | `aapt2 dump xmltree` 里 `androidx.activity.ComponentActivity` 出现次数 **0** → `ui-test-manifest` 只在 `testImplementation`，APK manifest 基线未被污染 |

`stripDebugDebugSymbols` 提示 28 个库「Unable to strip」（debug APK 里 .so 已 strip 过 / 无调试段），
与 P2B/P2C 同现象，非本轮引入。

## 4. §13 七条偏差的执行确认

1. **markwon 延至 P2E**：`AssistantItem` 正文 = `Text` + ``` 围栏切段（`splitFencedSegments`，
   `core` 侧 `TextSegment.monospace` → `FontFamily.Monospace` + `surfaceVariant` 灰底）。未引 markwon。
   流式途中未闭合的围栏按「直到文末」处理，不闪半句。
2. **sessions/settings/onboarding 占位**：`NavHost` 三条路由，chat 为 `startDestination`；
   两个占位 composable 各一个居中 `Text("P2E")`（`Nav.PLACEHOLDER`），入口是顶栏「会话/设置」两个
   `TextButton`（不用 `Icons.*`，免得拖 `material-icons-core` 出白名单）。无 SessionScanner、无 DataStore。
3. **「本会话」权限记忆 = app 侧策略**：`ChatViewModel.rememberedMethods: Set<String>`，key=`method`，
   随 VM 销毁即失效；线协议仍只有 `confirmed`/`cancelled`（`ExtensionUiResponse` 的 init 互斥约束原样）。
   `ChatViewModelTest case07` 验「同 method 二次请求自动 confirmed 且不弹框」。
4. **`ChatEngine`/`RuntimeInstall` 接口 additive 进 core/engine**：`SessionSupervisor` 直接实现 `ChatEngine`
   （语义零变化，退避/握手/状态机测试 11 条原样绿），`RuntimeInstallImpl` 包 `AssetInstaller`；
   `open` 修饰与 `EngineRuntime` 静态桥一并删除（P2C 遗留 #6 落地）。
   `sendUiResponse` 按 §4.4 是**非 suspend**，实现里由 `scope.launch` 投递（冻结区 `PiSession` 那条是 suspend，未改）。
5. **单会话**：`engine.start()` 只在 VM 的启动流程里调一次（`case01`/`case02` 断言 `startCalls==1`）；
   多会话与 `maxParallelSessions` 留 P2E。
6. **FGS 启动时点**：`installAndStart()` 在 `engine.start()` **之前** `startForegroundService(engineStartIntent(ctx))`；
   `case01` 与 `MainActivityTest` 都断言这条意图（action + 组件名），安装失败路径断言「绝不再有意图」。
7. **compileSdk 36 / targetSdk 35**：实证链是 `androidx.activity:activity-compose:1.11.0` →
   `androidx.activity:activity:1.11.0` 要求 compileSdk ≥ 36（AGP 校验原文记录在 RECON-P2D §1 第 1 轮）；
   `:app` + `:feature:chat`（其余 Android 模块随之）升 36，**targetSdk 恒 35**，APK 体检行已实证。

## 5. 工具链版本实录（RECON-P2D §1.1，`gradle :app:dependencies --configuration debugRuntimeClasspath` 原文摘录）

```
androidx.compose:compose-bom:2025.09.01
├─ androidx.compose.ui:ui -> 1.9.2
├─ androidx.compose.material3:material3 -> 1.4.0
└─ androidx.compose.ui:ui-test-manifest -> 1.9.2
androidx.activity:activity-compose:1.11.0（拖入 activity:1.11.0 = compileSdk 36 的元凶）
androidx.navigation:navigation-compose:2.9.0
androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1 -> 2.9.4（传递约束抬高，声明未变）
androidx.lifecycle:lifecycle-runtime-compose:2.9.1 -> 2.9.4（同上）
com.google.dagger:hilt-android:2.57.1（dagger / hilt-core / dagger-lint-aar 同代）
androidx.hilt:hilt-navigation-compose:1.3.0
org.jetbrains.kotlin:kotlin-stdlib:2.2.20
```

- Kotlin **2.2.20** + Compose 编译器插件 `org.jetbrains.kotlin.plugin.compose`（版本 == Kotlin，
  Kotlin 2.x 起 `kotlinCompilerExtensionVersion` 已作废）+ AGP **8.13.0** + Gradle **8.13** + JDK 21。
- KSP **2.2.20-2.0.4**（§8.2：`symbol-processing-gradle-plugin` maven-metadata 在 2.2.20 线上给
  2.0.2/2.0.3/2.0.4，取最新稳定；Hilt 2.57.1 走 KSP 2.x）。
- Robolectric **4.16** + `hilt-android-testing:2.57.1`：**可行，未触发 §8.3 的降级方案**。
  实测要点：该 aar 里**没有 `HiltTestRunner`**，正确配方是 `RobolectricTestRunner`
  + `@Config(application = HiltTestApplication::class)` + `HiltAndroidRule(order=0)`
  + `@UninstallModules(EngineModule::class)`；`kspTest(hilt-compiler)` 生成测试图。
- **超出 §2 白名单的新条目：无**（多出的只有 lifecycle 2.9.1→2.9.4 的传递抬高与
  `androidx.graphics:graphics-path` 的原生库，都不是我新增的声明）。

### 5.1 本轮踩到并固化进代码/测试的四个坑

1. **StateFlow conflation**：`FakeRuntimeInstall` 若一路狂写 `progress`，`ChatViewModelTest case01`
   实测只收到**空列表**。解法 = `stepPauseMs`（步进之间 `delay`，虚拟时钟推进）+ 收尾再挂一拍
   （调用方返回后立刻 `cancel` 镜像收集器，否则丢末档 `Done`）。
2. **Robolectric 默认屏 320×470dp**：`LazyColumn` 视口外条目根本不进组成树，
   `onNodeWithTag(...)` 报「节点不存在」，`performScrollTo()` 也救不了（滚动目标本身没被组合）。
   解法 = `@Config(qualifiers = "w420dp-h2400dp")`（UI 与 app 两侧测试都加）。
3. **`setContent` 只能一次**：同一 `composeRule` 第二次 `setContent` 抛
   `ComponentActivity…has already set content`；安装门三档改为 `mutableStateOf` 驱动。
4. **`assertTextEquals` 对 `OutlinedTextField` 会聚合 placeholder**：实测拿到
   `[给 pi 发一条消息]`，草稿清空的可观测面改为「发送键回到禁用」。

## 6. 遗留问题（如实）

1. **真机 E2E 未执行**：按规格书 §1，一轮对话 + bash + git 分支名的真机验证由方向层在手机通道
   放行后另行执行，不属本里程碑。本轮全部证据都是 Robolectric + 假体。
2. **`collectAsState` 而非 `collectAsStateWithLifecycle`**：后者要求 `LocalLifecycleOwner` 是
   LifecycleOwner，`createComposeRule()` 的宿主不保证（RECON-P2D §4）。语义损失 = 后台不暂停采集；
   `viewModelScope.onCleared` 已取消事件收集，P2E 在真 Activity 宿主下可换回。
3. **`NotificationGate` 只由 :app 测试覆盖**：`:feature:chat` 的 UI 用例注入空 lambda（同一原因）。
   「一次进入只请求一次」的 `requested` 闩已实现，但「系统弹窗真的只弹一次」在 Robolectric 里验不了。
4. **权限记忆粒度 = method**：同 `method` 不同 `message` 也会自动放行（§13.3 定案的直接后果）。
   P2E 若要按工具/命令粒度，需要 pi 侧在 `extension_ui_request` 里带更多结构化字段。
5. **探针（`get_state`）在 UI 上无入口**：P2C 的「测试 RPC」按钮随纯 View 壳退役，
   `ChatViewModel.probeState()` 保留并被 `case10` 覆盖；P2E 的 settings 屏可以把它做成可见入口。
6. **`ChatUiState.Failed.cause` 截断长度（`truncate()`）** 是唯一失败可读面；
   stderr 尾部的 8KB 环目前只拼在 VM 侧字符串里，没做「点开展开」的 UI（P2E 可与 markwon 一并处理）。
7. **磁盘**：编译机 `df -h /` 在构建过程中 2.2G→2.3G 余量（96% 使用率），本轮靠 §11 的回收顺序
   （`app/build/intermediates` → `runtime-pkg/dl` → `out/stage`）过一次 KSP 缓存损坏
   （`Storage corrupted … kspCaches/debug/symbolLookups/lookups.tab_i` + daemon 消失，
   根因是磁盘打满；清 `build/kspCaches` 后复跑绿）。下轮建议先回收再动工。
8. **多实例安全**：`AppTestHolder`/`EngineTestHolder` 是测试用的进程级可变 holder，
   provider 故意不带 scope（`@Singleton` 组件在 Robolectric 沙箱里跨用例复用），
   所以 `OutermostRule(order=-1)` 的时机是**契约**——把它挪进 `@Before` 会晚一步（Activity 已在规则阶段起来）。

## 7. 复现命令

```bash
# 本机 37 基线（强制真跑）
JAVA_HOME=/root/pi/.tools/jdk-21.0.12.1+1 /root/pi/.tools/gradle-8.13/bin/gradle --no-daemon --console=plain \
  --no-build-cache :core:process:cleanTest :core:rpc:cleanTest :core:process:test :core:rpc:test

# 全量同步（含 .git，§11 的 P2C 模式；只排本机 .gradle/.kotlin）
cd /root/pi/android-host && rsync -az --exclude='.gradle/' --exclude='.kotlin/' \
  -e "ssh -o BatchMode=yes -o UserKnownHostsFile=/root/pi/.tools/kh_buildsrv -o StrictHostKeyChecking=accept-new \
      -o ConnectTimeout=25 -o ServerAliveInterval=15" ./ root@64.90.11.50:/root/android-host/

# 编译机四模块测试（--no-build-cache + cleanTest* 强制真跑；3m48s 实测）
cd /root/android-host && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=PKCS12" \
  /opt/gradle-8.13/bin/gradle --no-daemon --console=plain --no-build-cache \
  :core:environment:cleanTestDebugUnitTest :core:engine:cleanTestDebugUnitTest \
  :feature:chat:cleanTestDebugUnitTest :app:cleanTestDebugUnitTest \
  :core:environment:testDebugUnitTest :core:engine:testDebugUnitTest \
  :feature:chat:testDebugUnitTest :app:testDebugUnitTest

# APK（先删产物逼 packageDebug 真跑）+ 回传 + 体检
cd /root/android-host && rm -f app/build/outputs/apk/debug/app-debug.apk && \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 /opt/gradle-8.13/bin/gradle --no-daemon --console=plain \
  --no-build-cache :app:assembleDebug
scp -o BatchMode=yes -o UserKnownHostsFile=/root/pi/.tools/kh_buildsrv \
  root@64.90.11.50:/root/android-host/app/build/outputs/apk/debug/app-debug.apk \
  /root/pi/dist/pi-host-full-debug.apk
sha256sum /root/pi/dist/pi-host-full-debug.apk; stat -c %s /root/pi/dist/pi-host-full-debug.apk
/opt/android-sdk/build-tools/35.0.0/aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk | grep -E "^package:|^targetSdkVersion|^native-code|^uses-permission"
/opt/android-sdk/build-tools/35.0.0/aapt2 dump xmltree --file AndroidManifest.xml app/build/outputs/apk/debug/app-debug.apk | grep -E "E: (application|activity|service|property)|foregroundServiceType"
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E "lib/arm64-v8a|assets/runtime"
```
