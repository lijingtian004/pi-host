# P2C 交付报告 — 进程生命周期子系统（SessionSupervisor + PiEngineService + EngineRuntime + MainActivity 接线）

任务源：`/root/pi/.tools/qoder-task-p2c.md`；侦察记录：`tools/engine-service/RECON-P2C.md`。
构建机 `root@64.90.11.50`（无 Android SDK 的本机只跑 `:core:process:test` / `:core:rpc:test`）。
下面每个数字都是命令真实回显，缓存命中（UP-TO-DATE / FROM-CACHE）一律作废重跑过。

---

## 1. git 提交清单（4 笔，分逻辑）

| hash | 标题 | 规模 |
|---|---|---|
| `81f2625` | docs: P2C 侦察记录（FGS 影子面、Response 线形状、runTest 时钟陷阱） | 1 file, +141 |
| `5493145` | feat: core/engine SessionSupervisor 状态机与 PiSpawn argv 构造 | 6 files, +1230/-1 |
| `04c82f5` | feat: PiEngineService specialUse 前台服务与 EngineRuntime 静态桥 | 5 files, +552/-8 |
| `338d07f` | feat: MainActivity 接线安装→FGS→RPC 探针→停机全链 | 4 files, +681/-22 |
| （本报告与 RECON 附录）| docs: P2C 收尾（复跑计量 + Robolectric 4.16 实测限制） | 2 files |

主要新增文件：
`core/engine/.../SessionSupervisor.kt`（379 行）、`PiSpawn.kt`（48）、`PiEngineService.kt`（202）、
`EngineRuntime.kt`（76）、`core/engine/src/test/.../SessionSupervisorTest.kt`（481）、
`FakePiProcess.kt`（247）、`PiEngineServiceTest.kt`（246）、`MainActivity.kt`（+374）、
`MainActivityTest.kt`（+316）、`app/src/main/AndroidManifest.xml`（+16）。

## 2. 测试矩阵

### 本机（`--no-build-cache` + `cleanTest` 强制真跑，`BUILD SUCCESSFUL`）

| 类 | 用例 | failures | errors | skipped |
|---|---|---|---|---|
| `JvmProcessTest` | 3 | 0 | 0 | 0 |
| `GoldenReplayTest` | 9 | 0 | 0 | 0 |
| `LineFramerTest` | 9 | 0 | 0 | 0 |
| `PiDesktopE2eTest` | 1 | 0 | 0 | 0 |
| `PiSessionTest` | 8 | 0 | 0 | 0 |
| `RpcModelTest` | 7 | 0 | 0 | 0 |
| **合计** | **37** | **0** | **0** | **0** |

### 编译机（`--no-build-cache` 三模块 clean+test，`BUILD SUCCESSFUL in 53s`）

| 模块 | 类 | 用例 | failures/errors/skipped |
|---|---|---|---|
| app | `MainActivityTest` | 7 | 0/0/0 |
| core/engine | `SessionSupervisorTest` | 11 | 0/0/0 |
| core/engine | `PiEngineServiceTest` | 6 | 0/0/0 |
| core/engine | `PiSpawnTest` | 4 | 0/0/0 |
| core/engine | `AssetInstallerTest`（P2B 红线） | 5 | 0/0/0 |
| core/engine | `RealRuntimeAssetsInstallTest`（P2B 红线） | 2 | 0/0/0 |
| core/environment | `EnvFactoryTest`（红线） | 2 | 0/0/0 |
| core/environment | `PathLayoutTest`（红线） | 3 | 0/0/0 |
| **合计** | | **40** | **0/0/0** |

红线保持：P2B 的 12 条（AssetInstaller 5 + RealRuntime 2 + EnvFactory 2 + PathLayout 3）与
P2A/P2B 既有 2 条 MainActivity 首屏文案断言**一字未改**（只调整了 `getChildAt` 取子视图的 helper 形状）。

## 3. APK 体检

```
/root/pi/dist/pi-host-full-debug.apk
size   = 53397894 bytes
sha256 = 4d6d12178dd676f3b6fbf1fdd3d6b126531f23150ef213ccab0c87e96bb35df7
```
编译机 `app/build/outputs/apk/debug/app-debug.apk` 的 sha256 与字节数**逐字一致**（回传未损坏）。
构建命令：`:app:assembleDebug` → `BUILD SUCCESSFUL`。

`aapt2 dump xmltree --file AndroidManifest.xml`（`/opt/android-sdk/build-tools/34.0.0/aapt2`）：

- `uses-permission` line 12 = `android.permission.FOREGROUND_SERVICE`
- `uses-permission` line 13 = `android.permission.FOREGROUND_SERVICE_SPECIAL_USE`
- `uses-permission` line 14 = `android.permission.POST_NOTIFICATIONS`
- `android:extractNativeLibs = true`（ Termux 那套 `.so` 必须落盘才能 exec）
- `<service>` = `works.earendil.pihost.core.engine.PiEngineService`，`exported=false`，
  `android:foregroundServiceType=0x40000000`（= `specialUse`）
- `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
  android:value="local_ai_coding_agent_engine">`
- `MainActivity` `exported=true`

`unzip -l` 载荷清点：`lib/arm64-v8a/` **27 个 `.so`**（含 `libpi_node.so` 47257744B、
`libpi_icudata.so` 33162264B）；`assets/runtime/manifest.json` **177042B**、
`assets/runtime/pi-runtime.zip` **8215598B**。

## 4. §12 六条偏差的执行确认

1. **`--resume` 作废 → `--session-id` + `--no-extensions`**：`PiSpawn.kt:36-37` 逐 flag 独立 argv 元素；
   `sessionId` 由 host 生成 UUID，首次 spawn 也带（`SessionSupervisorTest` 从 argv 反解 `--session-id` 断言），
   不经 `get_state` 回读。`PiSpawnTest` 4 条锁死 argv 形状与顺序。
2. **`Running(pid)` 并入 `Ready`**：`SessionSupervisor.kt:41` 只有 `data object Ready`，
   KDoc :31 写明"握手成功且进程存活"；状态机里没有任何 pid 字段。
3. **不引 Hilt/Compose**：`PiEngineService.onStartCommand` 从 `EngineRuntime.supervisor` 静态桥取值
   （null → `stopSelf()` + `START_NOT_STICKY`）；`MainActivity` 用 `Handler` 轮询（`POLL_INTERVAL_MS=500`）+ 纯 View。
4. **`AndroidProcessFactory` 不进 core/process**：`EngineRuntime.initialize()` 直接复用 `JvmProcessFactory`，
   `PiSpawn` 落 `core/engine`；`core/process/src/main` 零改动（见提交清单 diffstat）。
5. **重启上限 = 4 次 spawn**：`backoffMs=[1000,4000,16000]`，`SessionSupervisor.kt:260`
   `if (restartsDone >= backoffMs.size)` → `Failed`；耗尽时 cause 串 stderr 尾部（:261 `"$lastCause；stderr: …"`）。
   用户主动停 → `stopRequested` → `Stopped` 不重启（`case` 用例断言 `invocationCount()` 恰好 1/2 次）。
6. **握手 15s 超时与 error response 同走退避，且先优雅 close**：`DEFAULT_HANDSHAKE_TIMEOUT_MS = 15_000L`（:375），
   失败路径统一 `session.close(gracefulCloseTimeoutMs)`（:314）→ 超时才 `destroy()`；
   `case11` 用 `PiMode.SilentHang` 明确验证"先 `stdinClosed=true` 且尚未 `destroyed`，再跳到 destroy 兜底、退出码 137"。

## 5. 复核三处功能缺陷的修复落点

- **安装失败吞因**（原 `MainActivity.kt:149`）：`installThenStart()` 用 `runCatching` + `describeOutcome()`
  保留三类信息——抛异常时 `Log.e(TAG, …, throwable)` 并把 `SimpleName: message` 存进
  `internal var installFailure`；`InstallResult.Failed` 存 `result.message`；成功仍调 `renderVersion()`。
  失败时**不发** `ACTION_START`（测试 `assertNull(nextStartedService)`），按钮恢复可点，重试成功后 `installFailure` 清空。
- **探针"先订阅再发命令"未被验证**（原 `:198`）：新增 `SessionSupervisor.eventSubscriptions()` 暴露
  `MutableSharedFlow.subscriptionCount`；`MainActivity.kt` 在 `SUBSCRIBE_TIMEOUT_MS=1000` 内等计数增长才 `send`，
  未注册则直接给"订阅未注册…未发送命令"。`MainActivityTest` 的 stub 记录 `subscribersAtSend`，
  断言 `assertEquals(1, stub.subscribersAtSend)`——把推断升级成断言。
- **`renderState()` 分不清安装失败与 idle**（原 `:283`）：优先级显式化为
  `installFailure → "运行时安装失败：<原因>（可点「启动引擎」重试）"` → `未安装` → `未初始化` → `idle（无活动会话）` → 会话列表；
  测试同时断言失败文案以 `STATUS_INSTALL_FAILED_PREFIX` 开头、且不等于 `STATUS_NOT_INSTALLED`/`STATUS_IDLE`。

## 6. 遗留问题（如实，含覆盖不到的部分）

1. **真机端到端未做**：手机通道报 `E_GATE_HOST_DENIED`，无法安装/启动。因此"FGS 在 API 34+ 真不被系统杀掉"
   "`libpi_node.so` 能被 zygote 之外的 execve 拉起"这两条只有单测/静态证据（aapt2 + unzip 清单），没有运行时证据。
2. **前台服务类型断言降级为纯函数测试**：`ShadowService.getForegroundServiceType()` 是 protected，
   Robolectric 里读不到运行时类型 → 只能测 `foregroundTypeForApi(sdk)` 的三档返回值 + manifest 里 `0x40000000`；
   真机 `dumpsys activity services` 未采集。
3. **通知对象正文不回读**：android-35 jar 的 `Notification` 无 public `text`，动作 `Action.intent` 不可见 →
   动作标题是从真通知对象 `notification.actions[0].title` 读的（`PiEngineServiceTest.kt:237`），
   但挂在 Action 上的那个 PendingIntent 无法回读，只能对**同源工厂** `stopEngineIntent()`
   （`PiEngineService.kt:122-123` 用的就是它）产出的意图断言 `shadowOf(...).savedIntent`（测试 :240）；
   `setSilent` 在该 jar 不存在，故未静默。
4. **`renderEvent()` 用 `PiJsonOut` 重序列化**，展示的是解码后的规范 JSON，不是 pi 线上原始字节；
   真要"原样回显"需要 PiSession 侧保留 raw line（P2D 决定）。
5. **探针只测 `get_state`**：写命令（`prompt`/`steer`/`abort`）在 UI 层没有入口，属 P2D。
6. **`EngineRuntime` 静态桥 + `SessionSupervisor` 的 `open` 修饰是为测试开的口子**：
   Hilt 化（§12.3）后要删掉 `@Volatile var supervisor` 与 `open`，改用构造注入。
7. **多会话并发未测**：`SessionSupervisor` 支持 N 个 entry，但 UI 只起 1 个；
   通知里"会话数"在真机上没验证过。
8. **`--session-id` 的恢复语义只到 argv 层**：崩溃重启后同 UUID 是否真的续写同一 session 文件，
   需要真机或桌面 pi 侧验证（本机 `core/rpc` golden 不含跨进程恢复用例）。

## 7. 复现命令

```bash
# 本机 37 基线（强制真跑）
JAVA_HOME=/root/pi/.tools/jdk-21.0.12.1+1 /root/pi/.tools/gradle-8.13/bin/gradle --no-daemon --console=plain \
  --no-build-cache :core:process:cleanTest :core:rpc:cleanTest :core:process:test :core:rpc:test

# 同步 → 编译机 40 用例 + assemble（rsync 无 --delete，本机没有的 jniLibs/assets 不会破坏远端产物）
cd /root/pi/android-host && rsync -az --exclude='.git' \
  -e "ssh -o BatchMode=yes -o UserKnownHostsFile=/root/pi/.tools/kh_buildsrv -o StrictHostKeyChecking=accept-new \
      -o ConnectTimeout=25 -o ServerAliveInterval=15" ./ root@64.90.11.50:/root/android-host/

/root/pi/.tools/bssh.sh run 'cd /root/android-host && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  /opt/gradle-8.13/bin/gradle --no-daemon --console=plain --no-build-cache \
  :app:cleanTestDebugUnitTest :core:engine:cleanTestDebugUnitTest :core:environment:cleanTestDebugUnitTest \
  :app:testDebugUnitTest :core:engine:testDebugUnitTest :core:environment:testDebugUnitTest'

/root/pi/.tools/bssh.sh run 'cd /root/android-host && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  /opt/gradle-8.13/bin/gradle --no-daemon --console=plain :app:assembleDebug'

# 回传 + 校验
/root/pi/.tools/bssh.sh get /root/android-host/app/build/outputs/apk/debug/app-debug.apk \
  /root/pi/dist/pi-host-full-debug.apk
sha256sum /root/pi/dist/pi-host-full-debug.apk; stat -c %s /root/pi/dist/pi-host-full-debug.apk
```
