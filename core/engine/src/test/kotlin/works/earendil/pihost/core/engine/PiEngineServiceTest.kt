package works.earendil.pihost.core.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import works.earendil.pihost.core.engine.di.EngineModule
import works.earendil.pihost.core.rpc.ExtensionUiResponse
import works.earendil.pihost.core.rpc.PiEvent
import works.earendil.pihost.core.rpc.RpcCommand
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 引擎假体宿主（**不是**架构的一部分，只是测试注入口）。
 *
 * P2C 靠 `EngineRuntime.supervisor = stub` 这个静态可写字段做注入；Hilt 化之后
 * 静态桥删了，等价通路是「`@UninstallModules(EngineModule::class)` + 测试模块绑假体」。
 * 假体必须**每个用例新建**（否则 startCalls/通知文案会跨用例串味），
 * 而 Hilt 的 `@Singleton` 组件在 Robolectric 沙箱里跨用例复用，所以这里用 holder：
 * 绑定故意**不加 @Singleton**——每次服务 onCreate 注入都会重新调用 provider，
 * 拿到的就是 @Before 刚塞进去的那个新假体。
 */
internal object EngineTestHolder {
    var engine: RecordingChatEngine = RecordingChatEngine()

    fun reset() {
        engine = RecordingChatEngine()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object EngineTestModule {
    /** 无 scope：见 [EngineTestHolder] 的 provider 时机说明。 */
    @Provides
    fun chatEngine(): ChatEngine = EngineTestHolder.engine

    /** 本模块没有 RuntimeInstall 的消费方，绑上只为「图完整」——少一个 MissingBindings 变量。 */
    @Provides
    @Singleton
    fun runtimeInstall(): RuntimeInstall = FakeRuntimeInstall()
}

/** 安装面假体：PiEngineService 用不到，留给「图里必须存在」这条保险。 */
internal class FakeRuntimeInstall : RuntimeInstall {
    override val progress: StateFlow<InstallProgress> = MutableStateFlow(InstallProgress.Idle)
    override suspend fun ensureInstalled(): Boolean = true
}

/**
 * [ChatEngine] 假体：只记「服务到底调了什么」，绝不碰进程。
 *
 * 覆盖面按规格书 §7「P2C 的 PiEngineServiceTest 6 条等价重写」来定：
 * 服务只用 `sessionStates / start / shutdownAll`，其余成员是接口逼迫的**存在性**实现，
 * 一律记数或返回固定值，被服务调到就是测试红（见 [unexpected]）。
 */
internal class RecordingChatEngine : ChatEngine {
    val states = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    override val sessionStates: StateFlow<Map<String, SessionState>> get() = states

    val startCalls = AtomicInteger(0)
    val shutdownCalls = AtomicInteger(0)
    val stoppedIds = CopyOnWriteArrayList<String>()
    val sentCommands = CopyOnWriteArrayList<String>()
    val uiResponses = CopyOnWriteArrayList<ExtensionUiResponse>()

    /** 置非 null 则 [start] 抛它：验「引擎起不来不崩服务」。 */
    var startError: Throwable? = null
    var sessionId: String = "stub-session"

    private val bus = MutableSharedFlow<PiEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    override suspend fun start(): String {
        startCalls.incrementAndGet()
        startError?.let { throw it }
        return sessionId
    }

    override suspend fun stop(sessionId: String) {
        stoppedIds.add(sessionId)
    }

    override suspend fun shutdownAll() {
        shutdownCalls.incrementAndGet()
    }

    override fun events(sessionId: String): SharedFlow<PiEvent> = bus

    override suspend fun send(sessionId: String, command: RpcCommand, id: String?): String {
        sentCommands.add(command.javaClass.simpleName)
        return id ?: "r-1"
    }

    override fun sendUiResponse(sessionId: String, response: ExtensionUiResponse) {
        uiResponses.add(response)
    }

    override fun stderrTail(sessionId: String): String? = null

    /** 服务绝不该走的通路：一旦被调，测试直接红（比「断言它是 0」更早定位）。 */
    fun unexpected(): String =
        if (sentCommands.isNotEmpty()) "服务不该发命令：$sentCommands" else ""
}

/**
 * PiEngineService（Hilt 化重写，规格书 §4.2.4 / §7）。
 *
 * 配方 = RECON-P2D §3 实测过的那条：`@HiltAndroidTest` +
 * **`@RunWith(RobolectricTestRunner::class)`**（hilt-android-testing 2.57.1 里
 * 根本没有 `HiltTestRunner` 这个类，别照抄旧文档）+
 * `@Config(application = HiltTestApplication::class)` + `HiltAndroidRule`
 * + `@UninstallModules(EngineModule::class)` 换假体。
 *
 * 线程模型与 P2C 一致：观察协程在 `Dispatchers.Default`（真实线程），
 * 通知更新一律 `mainHandler.post` 回主线程 → [drain]（idle 主 looper）+ [awaitFor]。
 *
 * 与 P2C 的覆盖面对照（一条都不许缩水）：
 * 1 START→通知+start ✓ 2 三档 FGS 类型 ✓ 3 sessionStates→文案 ✓（并加验「只订阅一次」）
 * 4 STOP→shutdownAll+stopSelf+撤通知 ✓ 5「无 supervisor」→ Hilt 下不可达，
 * 等价面改成「意图无 action / null 意图 → stopSelf 且不派发动作」✓
 * 6 通知动作指回 ACTION_STOP ✓ 新增 7 start 抛异常不崩服务 ✓
 */
@UninstallModules(EngineModule::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = HiltTestApplication::class)
class PiEngineServiceTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    private val ctx: Context
        get() = RuntimeEnvironment.getApplication()

    private val notificationManager: NotificationManager
        get() = ctx.getSystemService(NotificationManager::class.java)

    @Before
    fun freshEngine() {
        EngineTestHolder.reset()
    }

    @After
    fun clearHolder() {
        EngineTestHolder.reset()
    }

    private val engine: RecordingChatEngine
        get() = EngineTestHolder.engine

    private fun drain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitFor(label: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            drain()
            if (condition()) return
            assertTrue("awaitFor[$label] 5s 真实时间内未成立", System.currentTimeMillis() < deadline)
            Thread.sleep(5)
        }
    }

    private fun newService(): ServiceController<PiEngineService> =
        Robolectric.buildService(PiEngineService::class.java).create()

    /** 实测（RECON-P2C §1）：ServiceController 只有 startCommand(flags, startId)，意图要 withIntent 先塞。 */
    private fun ServiceController<PiEngineService>.sendStart(intent: Intent) =
        withIntent(intent).startCommand(0, 1)

    private fun posted(): Notification? =
        shadowOf(notificationManager).getNotification(PiEngineService.NOTIFICATION_ID)

    private fun postedText(): String? = posted()?.let { shadowOf(it).contentText?.toString() }

    private fun postedTitle(): String? = posted()?.let { shadowOf(it).contentTitle?.toString() }

    private fun channel(): NotificationChannel =
        shadowOf(notificationManager).notificationChannels
            .single { it.id == PiEngineService.CHANNEL_ID }

    // ── 用例 1：START → 注入生效 + FGS 通知已 post + engine.start() 被调 ──────────

    @Test
    fun `start action posts the foreground notification and calls engine start`() {
        val controller = newService()
        val service = controller.get()

        // 走到这里没抛 UninitializedPropertyAccessException，本身就是「Hilt 字段注入已生效」的证据；
        // 再确认此刻拿到的确实是本用例新建的那个假体（start 还没派发）。
        assertEquals("假体必须是 @Before 刚新建的", 0, engine.startCalls.get())
        val foreground = shadowOf(service).lastForegroundNotification
        assertNotNull("onCreate 必须立刻 startForeground", foreground)
        assertEquals(PiEngineService.TITLE, shadowOf(foreground!!).contentTitle)
        assertEquals(PiEngineService.notificationText(0), shadowOf(foreground).contentText)
        assertEquals(
            "注入的必须是本用例的假体",
            0,
            engine.startCalls.get(),
        )

        controller.sendStart(engineStartIntent(ctx))
        awaitFor("engine.start 被调") { engine.startCalls.get() == 1 }
        awaitFor("通知已投递到 NotificationManager") { posted() != null }
        assertEquals(PiEngineService.TITLE, postedTitle())
        assertEquals(
            "通道必须存在且 importance=LOW",
            NotificationManager.IMPORTANCE_LOW,
            channel().importance,
        )
        assertEquals("", engine.unexpected())
        controller.destroy()
        drain()
    }

    // ── 用例 2：三档 FGS 类型分流（纯函数，绕开 protected 影子方法）──────────────

    @Test
    fun `foreground service type follows the api level`() {
        val service = newService().get()
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.foregroundTypeForApi(35))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.foregroundTypeForApi(34))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST, service.foregroundTypeForApi(33))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST, service.foregroundTypeForApi(29))
        assertNull("26..28 只有两参重载", service.foregroundTypeForApi(28))
        assertNull(service.foregroundTypeForApi(26))
    }

    // ── 用例 3：sessionStates 变化 → 通知文案随会话数走；重复 START 不重复订阅 ────

    @Test
    fun `notification text follows the session count and observes the engine only once`() {
        val controller = newService()
        controller.sendStart(engineStartIntent(ctx))
        awaitFor("观察协程已订阅") { engine.states.subscriptionCount.value == 1 }

        engine.states.value = mapOf(
            "a" to SessionState.Spawning,
            "b" to SessionState.Ready,
        )
        awaitFor("通知文案变成 2 个会话") { postedText() == "2 个会话" }
        assertEquals(PiEngineService.notificationText(2), postedText())

        engine.states.value = mapOf("b" to SessionState.Stopped)
        awaitFor("通知文案变成 1 个会话") { postedText() == "1 个会话" }

        // 第二次 START：仍只有一条观察协程（observeJob 复用），但会再 start 一次
        controller.sendStart(engineStartIntent(ctx))
        awaitFor("两次 start 都派发") { engine.startCalls.get() == 2 }
        assertEquals("重复 start command 不得重复订阅", 1, engine.states.subscriptionCount.value)
        controller.destroy()
        drain()
        awaitFor("onDestroy 取消观察协程") { engine.states.subscriptionCount.value == 0 }
    }

    // ── 用例 4：STOP → shutdownAll 被调，然后 stopSelf + 撤通知 ──────────────────

    @Test
    fun `stop action shuts every session down then stops self`() {
        val controller = newService()
        controller.sendStart(engineStartIntent(ctx))
        awaitFor("先起一条会话") { engine.startCalls.get() == 1 }
        engine.states.value = mapOf("a" to SessionState.Ready)
        awaitFor("通知就位") { postedText() == "1 个会话" }

        controller.sendStart(engineStopIntent(ctx))
        awaitFor("shutdownAll 被调") { engine.shutdownCalls.get() == 1 }
        awaitFor("服务自我停止") { shadowOf(controller.get()).isStoppedBySelf }
        assertTrue(
            "STOP 必须以 STOP_FOREGROUND_REMOVE 撤通知",
            shadowOf(controller.get()).notificationShouldRemoved,
        )
        assertEquals("STOP 之后不该再 start 新会话", 1, engine.startCalls.get())
        controller.destroy()
        drain()
    }

    // ── 用例 5（P2C「无 supervisor」用例的等价面）：无 action → stopSelf 且不派发 ──

    @Test
    fun `intent without a known action stops self without dispatching anything`() {
        val controller = newService()

        // ① 带组件但不带 action
        controller.sendStart(Intent(ctx, PiEngineService::class.java))
        awaitFor("服务自我停止") { shadowOf(controller.get()).isStoppedBySelf }
        assertEquals(0, engine.startCalls.get())
        assertEquals(0, engine.shutdownCalls.get())

        // ② null 意图（系统重试/START_STICKY 形状）也必须落在同一分支
        controller.startCommand(0, 2)
        drain()
        assertEquals("未知意图不该派发任何引擎动作", 0, engine.startCalls.get())
        assertEquals(0, engine.shutdownCalls.get())
        controller.destroy()
        drain()
    }

    // ── 用例 6：通知动作「停止引擎」→ PendingIntent 指回本服务 ACTION_STOP ────────

    @Test
    fun `notification exposes the stop-engine action pointing at this service`() {
        val controller = newService()
        controller.sendStart(engineStartIntent(ctx))
        awaitFor("通知就位") { posted() != null }

        val notification = posted()!!
        assertEquals(1, notification.actions.size)
        assertEquals(PiEngineService.STOP_LABEL, notification.actions[0].title.toString())
        assertTrue("FGS 通知必须 ongoing", (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)

        val saved = shadowOf(controller.get().stopEngineIntent()).savedIntent
        assertEquals(PiEngineService.ACTION_STOP, saved.action)
        assertEquals(PiEngineService::class.java.name, saved.component!!.className)
        controller.destroy()
        drain()
    }

    // ── 用例 7：engine.start() 抛异常 → 服务不崩、不 stopSelf、通知仍在 ──────────

    @Test
    fun `a failing engine start is swallowed by the service and keeps the notification`() {
        engine.startError = IllegalStateException("spawn 失败：nativeLibraryDir 为空")
        val controller = newService()
        controller.sendStart(engineStartIntent(ctx))
        awaitFor("start 被派发（并抛了）") { engine.startCalls.get() == 1 }
        // runCatching 之后没有 stopSelf 的路径，服务必须还活着
        drain()
        assertFalse("引擎失败不许把服务自己也停掉", shadowOf(controller.get()).isStoppedBySelf)
        assertNotNull("FGS 通知必须还在（撤通知 = 崩溃前兆）", posted())
        assertEquals(0, engine.shutdownCalls.get())
        controller.destroy()
        drain()
    }
}
