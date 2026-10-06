# P2C 侦察记录（进程生命周期子系统动工前）

> 第 0 步产物。事实来源全部是**本机/编译机实测输出**，未跑的步骤标注「未执行」。
> 规格书 §8 的 5 项 + 动工前必须定案的 3 个额外坑。

## 1. Robolectric 4.16 对 FGS 的影子支持面（§8.1）

证据 = 编译机 `/root/.gradle/caches/modules-2/files-2.1/org.robolectric/shadows-framework/4.16/…/shadows-framework-4.16.jar`
经 `javap` 逐类核对：

| 断言点 | 支持情况 | 方法签名（实测） |
|---|---|---|
| `startForeground` 三参重载 | **支持** | `ShadowService.startForeground(int, Notification, int)`（protected @Implementation） |
| 记录 foregroundServiceType | **支持** | `ShadowService.getForegroundServiceType()` → int（可直接断言 == `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`） |
| 取最近一次通知 | 支持 | `ShadowService.getLastForegroundNotification()` / `getLastForegroundNotificationId()` / `isLastForegroundNotificationAttached()` |
| `stopForeground(int)` | 支持 | 影子实现了 `stopForeground(boolean)` 与 `stopForeground(int)` 两个 @Implementation |
| `stopSelf` | 支持 | `ShadowService.isStoppedBySelf()` / `getStopSelfId()` |
| 通知投递记录 | 支持 | `ShadowNotificationManager.getNotification(int)`、`getNotification(String,int)`、`getAllNotifications()`、`getNotificationChannel(String)`、`getNotificationChannels()` |
| `shadowOf(activity).grantPermissions(...)` | **支持**（但不在 `ShadowActivity` 自身） | `ShadowActivity extends ShadowContextThemeWrapper extends ShadowContextWrapper`，`grantPermissions(String...)` / `denyPermissions(String...)` 定义在 **ShadowContextWrapper**，故 `shadowOf(activity).grantPermissions(POST_NOTIFICATIONS)` 可直接调用 |
| `requestPermissions` | 支持 | `ShadowActivity.requestPermissions(String[],int)` + `getLastRequestedPermission()`（`ShadowActivity$PermissionsRequest`） |

坑与降级姿势：
- `android.app.ServiceInfo` 不存在——常量在 **`android.content.pm.ServiceInfo`**：实测
  `FOREGROUND_SERVICE_TYPE_MANIFEST = -1`、`FOREGROUND_SERVICE_TYPE_SPECIAL_USE = 1073741824`
  （`javap -constants -cp /opt/android-sdk/platforms/android-35/android.jar android.content.pm.ServiceInfo`）。
- `Service.startForeground` 在 android-35 平台 jar 里只有两个重载（`finally` 修饰）：
  `(int, Notification)` 与 `(int, Notification, int)`；三参是 API 29 起，minSdk 26 必须 `SDK_INT >= Q` 分流，
  26..28 走两参。**API 34 的 `ForegroundServiceParams` 重载在 android-35 jar 里不存在**（javap 实测只有两条），
  所以本里程碑只用 int 型三参重载，不用 params 版。
- Robolectric 不校验 POST_NOTIFICATIONS 与 FGS 类型权限（无 SecurityException 路径），
  因此测试可稳定断言到「type 级别」，无需降级。若真机上 `startForeground` 抛
  `SecurityException`（缺 `FOREGROUND_SERVICE_SPECIAL_USE` 权限），属 P2D 真机验证范围。

## 2. Response 事件形状 / get_state 线形状 / golden 模板（§8.2）

- **variant 全名**：`PiEvent.Response`（`@SerialName("response")`），字段实测（`PiEvent.kt:25-31`）：
  `id: String? = null`、`command: String`、`success: Boolean`、`data: JsonObject? = null`、`error: String? = null`。
  即**判成功的字段是 `success`，不是名为 result 的字段**；负载在 `data`。
  → 握手判定式：`event is PiEvent.Response && event.id == expectedId && event.success`；
  `event.success == false` 视为失败（`error` 文本进 cause），不等超时（§12.6）。
- **未知 type 的兜底是 `PiEvent.Unknown`**（`__unknown`），形状漂移走 `SerializationException`
  → `PiSession.protocolErrors`（不进 `events`）。握手收集器只看 `events`，坏行不会误判成 Ready。
- **`RpcCommand.GetState` 经 `encodeCommand` 的实际 JSON**（`RpcWire.kt:48-52` + `PiJsonOut.encodeDefaults=false`
  + `PiSessionTest.kt:70-72` 实测断言）：`{"type":"get_state","id":"c-1"}` + `\n`。
  键序 = `type` 在前（多态判别字段）、`id` 在后（`base + ("id" to …)`）。
  `PiSession.nextId()` 从 `c-1` 起（`AtomicLong(0)` 先 increment），**同一会话每次 spawn 的新 PiSession 都从 c-1 重新计数**。
- **golden 模板原文**（FakePiProcess 回放行来源，规格书写 `001.jsonl`，实际文件名是 `001-basic.jsonl`）：
  - 成功（`core/rpc/src/test/resources/golden/001-basic.jsonl:1`，整行 1 条，摘掉超长 data 后仍保留全部必需字段）：
    `{"id":"g1","type":"response","command":"get_state","success":true,"data":{…,"sessionId":"01a10e68-…","messageCount":0,"pendingMessageCount":0}}`
  - 失败（`001-basic.jsonl:25` / `003-errors-bash.jsonl:2`）：
    `{"id":"q1","type":"response","command":"quit","success":false,"error":"Unknown command: quit"}`
  - FakePiProcess 的策略：**模板整行照抄进测试常量**，把 `"id":"g1"` 子串替换成从 stdin 行里解析到的 id
    （正则 `"id"\s*:\s*"([^"]+)"`），失败模板把 `success` 改 false 并附 `error` 字段。
    `PiJsonIn.ignoreUnknownKeys = true`（`PiJson.kt:14`）保证模板里的多余字段（model/cost 等）不影响解码。

## 3. core/rpc → core/process 的暴露方式（§8.3）

实测 `core/rpc/build.gradle.kts:16`：`api(project(":core:process"))` —— 是 **api**。
→ 传递暴露本可让 `core/engine` 编译到 `ProcessHandle/ProcessStarter/JvmProcessFactory`，
但 `SessionSupervisor` 直接引用这三个类型（不是仅出现在 API 签名里），按「直接使用的依赖要显式声明」
在 `core/engine/build.gradle.kts` **同时**写 `api(project(":core:rpc"))` 与 `api(project(":core:process"))`，
避免 AGP/Kotlin 后续把隐式传递编译判为错误。测试侧另加
`testImplementation(libs.kotlinx.coroutines.test)`（toml 已有，§2 允许）。

## 4. StderrRing 公开快照方法（§8.4）

`core/rpc/…/StderrRing.kt:46` → **`snapshot(): String`**（宽松 UTF-8 解码，按写入顺序返回尾部内容）；
计数用 `byteCount()`（:43）、清空 `clear()`（:60）。`PiSession.stderrRing` 是 public val（:75），
故 `stderrTail(sessionId)` = `session.stderrRing.snapshot()`。环容量 `PiSession.DEFAULT_STDERR_RING_BYTES = 8 * 1024`。

## 5. aapt2 体检命令与期望（§8.5）

编译机 aapt2 实测可用：`/root/android-sdk/build-tools/{34.0.0,35.0.0,36.0.0}/aapt2`
（`local.properties` 的 `sdk.dir=/root/android-sdk`；`/opt/android-sdk` 是另一份，P2B 的
`assemble-apk.sh` 找的就是 `/root/android-sdk/build-tools/*/aapt2`）。

```
aapt2 dump xmltree --file AndroidManifest.xml app-debug.apk
```
断言四条（P2B 的 app-debug.apk 实测输出形如 `A: http://schemas.android.com/apk/res/android:name(0x01010003)="…" (Raw: "…")`）：
1. `E: service` 节点 + `android:name(0x01010003)="works.earendil.pihost.core.engine.PiEngineService"`；
2. `android:foregroundServiceType(0x0101059f)="specialUse"`（库模块类必须全限定名，`.` 前缀不可用）；
3. service 下 `E: property` + `android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"`、
   `android:value="local_ai_coding_agent_engine"`；
4. 三条 `E: uses-permission`：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS`。
so/资产继续用 P2B 的判据：`unzip -Z1` 数 `lib/arm64-v8a/*.so` == 27、`assets/runtime/{manifest.json,pi-runtime.zip}` 在、
`extractNativeLibs=true`。

## 6. 额外坑 A：runTest 的虚拟时钟会**抢先**触发 15s 握手超时（本机实测）

规格书 §5.4 说「runTest/backgroundScope 注入，delay 全虚拟」。这条在**真实 IO 线程**参与的
场景里是陷阱，已在本机 `/tmp/probe` 用同一版本依赖（kotlinx-coroutines-test-jvm 1.10.2 + JDK 21）实测：

```
A outcome=null virtualNow=15000 realElapsed=4ms      ← withTimeoutOrNull(15_000) 抢在真实线程（30ms）之前触发
B result=ok   virtualNow=0    realElapsed=33ms      ← 阻塞 pump（Thread.sleep + runCurrent）：拿到结果且时钟没跳
C before=0 after=0 completed=false                   ← advanceUntilIdle 对空队列不跳时钟
```

结论与测试姿势（写进 `SessionSupervisorTest` 的 helper）：
- `PiSession` 的 read/write/exit 协程钉在 `Dispatchers.IO`（`PiSession.kt:84-88`）= **真实线程**；
  runTest 在测试体挂起、任务队列空时会自动把虚拟时钟推到下一个定时器 → 15s 握手超时先 fire，
  成功路径被误判成失败。**所以测试里绝不允许「挂起等待」**。
- 姿势：`async { supervisor.start() }` + **阻塞式 pump**（`Thread.sleep(2)` 让 IO 线程投递 →
  `testScheduler.runCurrent()` 只跑已排队任务、不跳时钟）直到条件成立；
  虚拟退避用 `advanceTimeBy(backoff + 1)` **显式**跳（值取自观察到的 `Restarting(attempt, backoffMs)`），
  握手超时用 `advanceTimeBy(handshakeTimeoutMs + 1)` 显式跳。
- `gracefulCloseTimeoutMs` 在多数用例注入小值（默认 5s 的定时器会被 `advanceTimeBy(backoff+1)` 跳过，
  不跳则 pump 无法推进），断言只针对状态与 starter 调用次数，不针对该值。
- watcher 协程注入 `backgroundScope`（终态后由 supervisor 自己取消），否则 runTest 收尾会因
  「后台协程未结束」报错。

## 7. 额外坑 B：MainActivityTest 既有 2 用例的 `launch()` helper 必须适配新布局

既有 helper 取 `content.getChildAt(0) as TextView`（`MainActivityTest.kt:26-31`）。§7 要把根视图换成
`LinearLayout`（版本 TextView + 3 按钮 + 状态 TextView），直接放大会 `ClassCastException`。
取舍：**保留两用例的名称与断言文本原样不动**（红线要求），只把 helper 改成
`(content.getChildAt(0) as LinearLayout).getChildAt(0) as TextView`，断言字符串一字不改；
首屏版本 TextView 的文本继续是 `"pi-android host (P2 shell)\nruntime: $version"`（P2B 的出口判据文案不漂移）。

## 8. 额外坑 C：产物资产只在编译机树上

本机 `/root/pi/android-host/app/src/main/{jniLibs,assets}` **不存在**（P2B 的 job A/B 产物，
`.gitignore` 覆盖，不入 git）；编译机实测在位：`ls app/src/main/jniLibs/arm64-v8a | wc -l` = **27**、
`app/src/main/assets/runtime/{manifest.json(177042B),pi-runtime.zip(8215598B)}`。
规格书 §9.7 的 rsync 是 `rsync -az`（**无 --delete**），故本机没有这两目录不会把远端产物删掉——
继续按规格书命令执行，assemble 前在编译机断言 27/2 即可。
编译机磁盘 `df -h /` = **2.2G 余量**（与 P2B REPORT 一致）；回收顺序 `app/build/intermediates`
→ `/root/runtime-pkg/dl`(52M) → `out/stage`(25M)。

## 9. 其它核对到的事实（动工前提）

- `PiSession.close(gracefulTimeoutMs)` **幂等**（`outbox.close()` + `exit.await()`，`PiSession.kt:108-115`），
  但 `start()` 二次调用抛 `IllegalStateException`（:83 `check`）→ Supervisor 每个进程实例只准 start 一次。
- 优雅退出 = 关 outbox → writeLoop 关 stdin → `exit` 自然完成；Android 上 `destroy()` 即 SIGKILL，
  无 SIGTERM 通路（`ProcessHandle.kt` KDoc）→ 超时兜底只能 destroy。
- `JvmProcessFactory` 用 `ProcessBuilder`，Android 上直接可用（§12.4 定案），`core/process` 零改动。
- 本机基线（刚刚复跑，非 UP-TO-DATE 假绿）：`:core:process:test` = 3 用例、`:core:rpc:test` = 34 用例，
  合计 **37 全绿、0 failures/0 errors**，`BUILD SUCCESSFUL`。
- 冻结区文件（`core/rpc`、`core/process/src/main`、`core/environment`、`docs/protocol.md`、`tools/capture`、
  `tools/runtime-packaging`）本次只读；`core/engine` 既有文件仅 `build.gradle.kts` 允许动（加依赖）。
