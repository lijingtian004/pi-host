# P2D 侦察记录（Compose 聊天核心链动工前）

> 第 0 步产物（规格书 §8）。事实来源全部是**本机/编译机实测输出**，未跑的步骤标注「未执行」。
> 冒烟顺序按 §8.1 执行：toml + `:feature:chat` 骨架 + app Compose/Hilt 化 → 编译机 `:app:assembleDebug`
> → 绿了才写业务代码（现已绿，见 §1）。探针测试文件 = `feature/chat/src/test/.../ToolchainProbeTest.kt`
> （5 条用例，全部保留进正式套件）。

## 1. 工具链冒烟（§8.1，最重要的一条）

编译机 `/root/android-host` 实测（`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 /opt/gradle-8.13/bin/gradle
--no-daemon --console=plain`，带 §3 的 TLS 修复串）：

| 轮次 | 任务 | 结果 | 用时 |
|---|---|---|---|
| 1 | `:app:assembleDebug`（compileSdk 35） | **FAILED** — AGP 依赖校验：`androidx.activity:activity{,-ktx,-compose}:1.11.0` requires compileSdk ≥ 36 | 29s |
| 2 | `:app:assembleDebug`（`:app` + `:feature:chat` 升 36） | **BUILD SUCCESSFUL**，108 tasks（50 executed / 4 from-cache / 54 up-to-date） | 4m 9s |
| 3 | `:feature:chat:testDebugUnitTest`（Hilt + Compose 探针） | **BUILD SUCCESSFUL**，5 条探针用例全绿 | 1m 56s |

冒烟 APK（步骤 2 产物，含服务器上的 jniLibs/assets）：`62,665,134` 字节，
`sha256=2115d8d37fdf5946dbf4adc244b1e2f55748467c8943dcc344fd48f5e591f6d3`（步骤 2 的 62,665,233 字节
是 ui-test-manifest 还挂在 debugImplementation 时的形状，见 §6）。
对比 P2C 的 50.8MB：**+11.8MB** = Compose runtime + material3 + Hilt/Dagger + kotlin-stdlib 扩展的
dex 与资源（`useLegacyPackaging` 未变，so 部分字节不变）。

### 1.1 实际解析版本实录（`gradle :app:dependencies --configuration debugRuntimeClasspath` 原文）

```
+--- androidx.compose:compose-bom:2025.09.01
+--- androidx.compose.ui:ui -> 1.9.2
+--- androidx.compose.material3:material3 -> 1.4.0
+--- androidx.compose.ui:ui-test-manifest -> 1.9.2
+--- androidx.activity:activity-compose:1.11.0          （拖入 androidx.activity:activity:1.11.0 = 36 的元凶）
+--- androidx.navigation:navigation-compose:2.9.0
+--- androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1 -> 2.9.4   （被传递约束抬高）
+--- androidx.lifecycle:lifecycle-runtime-compose:2.9.1 -> 2.9.4     （同上）
+--- com.google.dagger:hilt-android:2.57.1              （dagger:2.57.1 / hilt-core:2.57.1 / dagger-lint-aar:2.57.1）
+--- androidx.hilt:hilt-navigation-compose:1.3.0        （拖入 androidx.hilt:hilt-lifecycle-viewmodel-compose:1.3.0）
+--- org.jetbrains.kotlin:kotlin-stdlib:2.2.20
```

- **规格书 §2 白名单全部命中**，多出来的是 `lifecycle 2.9.1 → 2.9.4` 的**传递约束抬高**（不是我新增条目：
  声明仍是 2.9.1，Gradle 取最高）。`androidx.graphics:graphics-path` 的 `libandroidx.graphics.path.so`
  是 material3 的传递原生库（见 §6 的 so 计数）。
- Compose 编译器插件 = `org.jetbrains.kotlin.plugin.compose` **版本 == Kotlin 2.2.20**（同一版本线，
  不再单独选 `kotlinCompilerExtensionVersion`——Kotlin 2.x 起该 DSL 已作废）。
- AGP 8.13.0 + Gradle 8.13 + JDK 21（编译机 `/usr/lib/jvm/java-21-openjdk-amd64`）不变。

## 2. ksp 版本（§8.2）

编译机 `curl -s https://repo1.maven.org/maven2/com/google/devtools/ksp/symbol-processing-gradle-plugin/maven-metadata.xml`：
Kotlin 2.2.20 线上有 `2.2.20-2.0.2 / 2.2.20-2.0.3 / 2.2.20-2.0.4`（另有 Beta/RC，排除）
→ **取 `2.2.20-2.0.4`**，与 Hilt 2.57.1 的 ksp 代际要求（KSP 2.x）相符；冒烟已用 `ksp(hilt-compiler)`
生成 Dagger 组件通过（`:app:hiltJavaCompileDebug` 有 deprecation 提示，无害）。

## 3. @HiltAndroidTest + Robolectric 4.16 配方（§8.3）

**可行，不需要降级方案。**实测两点：

1. `hilt-android-testing:2.57.1` 的 aar 里**没有 `HiltTestRunner` 这个类**（编译机 `unzip` 列 class 实测：
   只有 `HiltAndroidRule` / `HiltAndroidTest` / `HiltTestApplication` / `BindValue` / `BindValueIntoSet` /
   `BindValueIntoMap` / `BindElementsIntoSet` / `UninstallModules` / `CustomTestApplication` /
   `SkipTestInjection` / `OnComponentReadyRunner` / `MarkThatRulesRanRule` / `TestInstallIn`）。
   → Robolectric 路径的 runner 只能是 **`RobolectricTestRunner`**，靠
   `@Config(application = HiltTestApplication::class)` 换应用、靠 `@HiltAndroidTest` + `HiltAndroidRule` 建图。
2. 生效配方（`HiltProbeTest` 实测绿）：

```kotlin
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = HiltTestApplication::class)
class X { @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this); @Inject lateinit var p: ProbeApi
          @Test fun t() { hiltRule.inject(); ... } }
```

- 构建侧需要 `testImplementation(hilt-android-testing)` + **`kspTest(hilt-compiler)`**（不是 ksp 主源集）；
  测试源集里的 `@Module @InstallIn(SingletonComponent::class)` 与 `@BindValue` 都能进测试图。
- `:feature:chat` 是 library 模块、生产代码没有 `@HiltAndroidApp`，Hilt 2.57.1 依然能为
  `@HiltAndroidTest` 生成测试组件（探针实测通过）——app 模块有 `PiHostApp` 时同方，
  `@UninstallModules(EngineModule::class)` 留给 app 测试（步骤 5）。
- **规则顺序**：`@get:Rule(order = 0)` Hilt、`order = 1` Compose（`HiltComposeProbeTest` 实测绿，
  注入值能进 Compose 树）。

## 4. Compose UI test under Robolectric（§8.4）

**可行。**配方 = `testImplementation(ui-test-junit4)` + `testImplementation(ui-test-manifest)` +
`testOptions.unitTests.isIncludeAndroidResources = true`，规则用 `createComposeRule()`（不需要
`createAndroidComposeRule`，因此不引 appcompat 主题）。`ComposeProbeTest` 3 条用例实测覆盖
§6/§7 要用的全部交互姿势，全绿：

| 姿势 | 结果 |
|---|---|
| `setContent` + 语义树查询（`onNodeWithTag` / `onNodeWithText` / `assertIsDisplayed`） | ✅（节点 bounds 实测 320×470dp，Robolectric 默认屏） |
| `OutlinedTextField` + `performTextInput("hello")` → `onValueChange` 回写 | ✅ |
| `TextButton` + `performClick()` → 计数 Text 变化 | ✅ |
| `LazyColumn` + `onNode(hasScrollToIndexAction()).performScrollToIndex(79)` → 屏外条目 `assertIsDisplayed` | ✅ |

已知限制（实测出来的坑，正式用例必须避开）：

- `assertTextEquals(...)` 只匹配**该节点自己**的 Text 语义，**不聚合子节点**——在 Box/Column 容器上
  断言子文本会失败（第一轮探针就是这么挂的：`Failed to assert (Text + EditableText = [probe-ok])，
  Node #2 ... Has 1 child`）。容器一律 `testTag` + `assertIsDisplayed`，文本走 `onNodeWithText`。
- 没有真键盘/真窗口：软键盘遮挡、`isTestRunnerWillSetLayoutToWrapContent`、IME 相关断言一律不做。
- 无 `activity` 句柄（`createComposeRule()` 给的是 `ComposeContentTestRule`）；要测 Activity 本体就
  在 app 侧用 Robolectric `buildActivity` + 直接调 composable 的纯函数，不靠 compose rule 拿 activity。
- 每条 Compose 用例 ~1-3s（`android-all` jar 已缓存，无下载）。

## 5. 冻结区精读：事件/命令形状（§8.5，FakeChatEngine 脚本与 VM 装配的唯一依据）

抄自 `core/rpc/src/main/kotlin/works/earendil/pihost/core/rpc/PiEvent.kt`（禁改，只读）
与 `docs/protocol.md` §3.7/§4.1/§4.2/§5，golden 原文取自 `core/rpc/src/test/resources/golden/*.jsonl`。

- `MessageUpdate(usage: JsonElement? = null, assistantMessageEvent: AssistantMessageEvent? = null)`
  —— **两个字段都可空**，`assistantMessageEvent` 为 null 时是纯 usage 更新，VM 必须跳过。
- `AssistantMessageEvent` 变体（`PiEvent.kt:195-252`，字段名精确）：
  `TextStart(contentIndex:Int)` / `TextDelta(contentIndex:Int, delta:String)` /
  `TextEnd(contentIndex:Int, content:String)` / `ThinkingStart(contentIndex)` /
  `ThinkingDelta(contentIndex, delta)` / `ThinkingEnd(contentIndex, content)` /
  `ToolcallStart(contentIndex, id:String, toolName:String)` /
  `ToolcallDelta(contentIndex, delta:String)` ← **原始 JSON 串片段，累积到 End 才解析** /
  `ToolcallEnd(contentIndex, toolCall:JsonObject)` / `Done(reason:JsonElement?, message:JsonElement?)` /
  `Error(reason:String?, error:JsonElement?)` / `UnknownAm(raw:JsonObject)`。
  → 无 `contentIndex` 之外的 id/name 在 Delta 上，toolcall 记录必须按 `contentIndex` 归槽。
- `MessageStart(message: PiMessage)` / `MessageEnd(message: PiMessage)`；
  `PiMessage(role:String, content:JsonElement?, stopReason:String?, usage:JsonElement?, responseId:String?,
  timestamp:Long?, toolCallId:String?, toolName:String?, isError:Boolean?, errorMessage:String?,
  sections:JsonObject?, details:JsonElement?)`。
  **`content` 形状两态**（golden 002 实测）：`role:"system"` → `content:""`（字符串！）；
  `role:"user"` → `content:[{"type":"text","text":"run the branch command"}]`；
  `role:"assistant"` start 时 `content:[]`、end 时 `[{type:text,text},{type:toolCall,id,name,arguments}]`；
  另有 `role:"toolResult"`（带 toolCallId/toolName/isError）——**P2D 不入 transcript**。
  → VM 解析必须 `JsonArray`/`JsonPrimitive` 两路都走，不能假设数组。
- `ToolExecutionStart(toolCallId:String, toolName:String, args:JsonElement?)` /
  `ToolExecutionUpdate(toolCallId, toolName, args:JsonElement?, partialResult:JsonElement?)` /
  `ToolExecutionEnd(toolCallId, toolName, result:JsonObject, isError:Boolean = false)`
  （字段名是 **`toolCallId`** 不是 `callId`；`result.content` 又是块数组）。
- `BashExecutionUpdate(id:String? = null, delta:String)`。
- `TurnStart` 是 **data object**（无字段）；`TurnEnd(message:PiMessage, toolResults:List<PiMessage> = emptyList())`；
  `AgentSettled` / `AgentStart` 是 data object；`AgentEnd(messages:List<PiMessage>, willRetry:Boolean)`。
- `QueueUpdate(steering:List<String>, followUp:List<String>)` —— queueDepth = `steering.size + followUp.size`
  （golden 006：`{"steering":["..."],"followUp":[]}` → 1）。
- `ExtensionUiRequest(id:String, method:String, title:String?, message:String?, timeout:Long?)`；
  **`timeout` 单位 = 毫秒**（protocol.md §3.7 原文样本 `"timeout":15000`，golden 005 全量同值）；
  `method ∈ confirm|select|input`。
- `Response(id:String?, command:String, success:Boolean, data:JsonObject?, error:String?)`；
  `id` 由客户端提供、原样回显，VM 用「`id == 自己发的 requestId`」筛命令结果。
- 命令面（`RpcCommand.kt`）：`Prompt(message:String, images:List<String>? = null)`、
  `Steer(message)`、`Abort`（data object，`@SerialName("abort")`）、`GetState`（data object）。
  `ExtensionUiResponse(id:String, confirmed:Boolean? = null, cancelled:Boolean? = null)` ——
  **不是 RpcCommand**，`init` 里 `require((confirmed==null)!=(cancelled==null))`：
  两个字段同时给或同时不给都直接抛，「拒绝」必须是 `cancelled=true`（不是 `confirmed=false`）。
- **回写通路的 suspend 差异**：`PiSession.sendUiResponse(response)` 是 `suspend`
  （`PiSession.kt:99`，写 outbox），而规格书 §4.4 的 `ChatEngine.sendUiResponse` 签名是**非 suspend 的 `fun`**
  → 适配层只能在 `SessionSupervisor` 内部 `scope.launch { session.sendUiResponse(...) }`。
  本侦察按规格书签名实现，发射失败（会话已无进程）走 `IllegalStateException` → 记 log，不抛给 UI。

## 6. aapt2 体检基线（§8.6）

编译机 `/opt/android-sdk/build-tools/35.0.0/aapt2 dump`（步骤 3 的 `:app:assembleDebug` 产物）：

- `package name='works.earendil.pihost' versionCode=1 versionName=0.1.0`；
  **`compileSdkVersion=36`（platformBuildVersionName='16'）/ `targetSdkVersion=35`** —— §13 偏差 7 的实证形状。
- `xmltree AndroidManifest.xml`：`E: application` → `E: activity`（1 个）→ `E: service`
  → `E: property`（`android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`），`foregroundServiceType=0x40000000`
  （= specialUse）。**application `android:name` = `.PiHostApp`**（本轮新增的检查点）。
- `uses-permission`：`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` / `POST_NOTIFICATIONS`
  三条不变 + `works.earendil.pihost.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（AGP 为 API 33+ 自动注入，
  P2C 也带，非新增）。
- `lib/arm64-v8a/` **28 个文件** = jniLibs 的 **27 个运行时 so 全部在位**（`comm` 逐名比对：
  「只在 jniLibs」为空集）+ `libandroidx.graphics.path.so` 1 个（material3 传递的 AAR 原生库）。
  → §9.6 的断言口径：**27 个运行时 so 名字集合 ⊆ APK**，并额外报告第 28 条的出处。
- `assets/runtime/` **2 个文件**：`manifest.json`(177,042B) + `pi-runtime.zip`(8,215,598B)。
- **实测坑**：`ui-test-manifest` 若挂 `debugImplementation`，会把 `androidx.activity.ComponentActivity`
  （带 4 个 intent-filter）合进 **debug APK 的 AndroidManifest**，破坏「activity 不变」基线
  （那一版 APK 是 62,665,233 字节 / sha256 2115d8d3…）。
  → 两个模块都改成**只 `testImplementation`**；Robolectric 用得到（单测合并 manifest 仍含该 activity，
  探针 5 条照样绿），APK 回到 1 activity（62,665,134 字节）。

## 7. compileSdk 取值的定案（§3 预授权 → 实际取值）

- **`:app` = 36、`:feature:chat` = 36**（硬性：androidx.activity 1.11.0 的 AGP 校验，见 §1 第 1 轮原文）。
- **`:core:engine` / `:core:environment` 保持 35**：它们不碰 compose/activity，
  §3 的预授权是「允许升」，不是「必须升」；少一个模块的重编就少一分 §2 红线（P2C 基线测试）的风险。
  跨模块 compileSdk 不一致 AGP 不报错（冒烟 + 探针两轮实测）。
- `targetSdk` 全仓恒 35；`minSdk` 恒 26。
- 服务器 SDK 已装 `platforms/android-34,35,36`，`build-tools` 只有 `34.0.0/35.0.0`
  —— AGP 8.13 默认 buildTools 走 35.0.0，**不需要 36.0.0**（实测 assembleDebug 绿）。

## 8. EngineRuntime 删除影响面（§4.2.4 的清单）

`grep -rn "EngineRuntime" --include=*.kt` 实测 5 个文件：
`core/engine/.../EngineRuntime.kt`（本体，删）、`core/engine/.../PiEngineService.kt`（改 `@Inject`）、
`core/engine/src/test/.../PiEngineServiceTest.kt`（6 条等价重写）、
`app/src/main/.../MainActivity.kt`（已在冒烟轮改成 `@AndroidEntryPoint` + `setContent`）、
`app/src/test/.../MainActivityTest.kt`（7 条等价重写）。
→ 步骤 2 的冒烟已经把两个 app 侧引用点摘干净，步骤 3 只剩 engine 内部 4 处。

## 9. 作业环境实录

- 磁盘：编译机 `/` 用 47G / 余 **2.2G**（每轮 gradle 前 `df -h /` 打印）。新依赖下载后
  `/root/.gradle/caches/modules-2` 从 1.5G 起继续涨；`app/build` 417M、`tools` 141M、
  `/root/runtime-pkg/dl` 52M 是 §11 的回收顺序候选（本轮**未执行**回收，2.2G 余量够用）。
- rsync：按 §11 用全量 `rsync -az`（含 `.git`，只排 `.gradle/`、`.kotlin/` 两个本机构建缓存目录，
  免得把本机绝对路径的配置铺到远端）；**不带 `--delete`**，远端的 `app/src/main/jniLibs`、
  `app/src/main/assets` 不会被本机缺失搞丢。
- TLS：`JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts ...` 修复串
  全程带（每轮日志首部 `Picked up JAVA_TOOL_OPTIONS` 实证生效）；本轮没有出现 trustAnchors 报错。
- Robolectric `android-all` for sdk 35 已缓存（探针首跑 14s 是 compose 首次初始化，不是下载）。
