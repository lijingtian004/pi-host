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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController
import java.util.concurrent.atomic.AtomicInteger

/**
 * PiEngineService（规格书 §6.4 的 4 条 + 通知形状/类型分流 2 条，Robolectric sdk=35）。
 *
 * 注入方式 = 规格书原文「reset 后手工塞一个记录调用的 stub」：
 * 本工程不引 Mockito（§2 红线），所以 [SessionSupervisor] 对外 API 标 `open`，
 * 用 [StubSupervisor] 继承并只覆盖服务真正用到的三件事（sessionStates / start / shutdownAll）。
 *
 * 两处**实测出来的取舍**（编译机 javap，见 RECON §1 补充）：
 * - `ServiceController` 只有 `startCommand(int flags, int startId)`，意图要经 `withIntent()` 先塞；
 * - `ShadowService.getForegroundServiceType()` 与 `getNotificationChannel(String)` 都是
 *   **protected**（影子方法按 Robolectric 惯例），测试读不到 →
 *   FGS 类型改断纯函数 [PiEngineService.foregroundTypeForApi]，
 *   通道改从 public 的 `getNotificationChannels()` 里找。
 *
 * 线程模型：服务的观察协程跑在 `Dispatchers.Default`（真实线程），通知更新一律
 * `mainHandler.post` 回主线程 → 测试用 [drain]（idle 主 looper）+ [awaitFor] 确定性推进。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PiEngineServiceTest {

    private val ctx: Context
        get() = RuntimeEnvironment.getApplication()

    private val notificationManager: NotificationManager
        get() = ctx.getSystemService(NotificationManager::class.java)

    @Before
    fun clearEngineRuntime() {
        EngineRuntime.reset()
    }

    @After
    fun releaseEngineRuntime() {
        EngineRuntime.reset()
    }

    /** 只覆盖服务用到的三个成员；spawn 通路一律炸，确保服务不会偷偷起进程。 */
    private class StubSupervisor : SessionSupervisor(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        starter = { _, _, _ -> throw AssertionError("PiEngineService 不该直接 spawn 进程") },
        commandFor = { listOf("stub") },
        envFor = { emptyMap() },
        workingDir = null,
    ) {
        val states = MutableStateFlow<Map<String, SessionState>>(emptyMap())

        override val sessionStates: StateFlow<Map<String, SessionState>> get() = states

        val startCalls = AtomicInteger(0)
        val shutdownCalls = AtomicInteger(0)

        override suspend fun start(): String {
            startCalls.incrementAndGet()
            return "stub-session"
        }

        override suspend fun shutdownAll() {
            shutdownCalls.incrementAndGet()
        }
    }

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

    /** 实测：ServiceController 只有 startCommand(flags, startId)，意图必须 withIntent 先塞。 */
    private fun ServiceController<PiEngineService>.sendStart(intent: Intent) =
        withIntent(intent).startCommand(0, 1)

    private fun posted(): Notification? =
        shadowOf(notificationManager).getNotification(PiEngineService.NOTIFICATION_ID)

    private fun postedText(): String? = posted()?.let { shadowOf(it).contentText?.toString() }

    private fun postedTitle(): String? = posted()?.let { shadowOf(it).contentTitle?.toString() }

    // ── 用例 1：START → FGS 通知已 post，supervisor.start() 被调 ──────────────────

    @Test
    fun `start action posts the foreground notification and calls supervisor start`() {
        val stub = StubSupervisor()
        EngineRuntime.supervisor = stub
        val controller = newService()
        val shadowService = shadowOf(controller.get())

        val foreground = shadowService.lastForegroundNotification
        assertNotNull("onCreate 必须立刻 startForeground", foreground)
        assertEquals(PiEngineService.TITLE, shadowOf(foreground!!).contentTitle)
        assertEquals(PiEngineService.notificationText(0), shadowOf(foreground).contentText)
        assertEquals(PiEngineService.NOTIFICATION_ID, shadowService.lastForegroundNotificationId)

        controller.sendStart(engineStartIntent(ctx))
        awaitFor("supervisor.start 被调") { stub.startCalls.get() == 1 }
        awaitFor("通知已投递到 NotificationManager") { posted() != null }
        assertEquals(PiEngineService.TITLE, postedTitle())
        assertEquals(
            "通道必须存在且 importance=LOW",
            NotificationManager.IMPORTANCE_LOW,
            channel().importance,
        )
        controller.destroy()
        drain()
    }

    private fun channel(): NotificationChannel =
        shadowOf(notificationManager).notificationChannels
            .single { it.id == PiEngineService.CHANNEL_ID }

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

    // ── 用例 3：sessionStates 变化 → 通知文案随会话数走 ─────────────────────────

    @Test
    fun `notification text follows the session count`() {
        val stub = StubSupervisor()
        EngineRuntime.supervisor = stub
        val controller = newService()
        controller.sendStart(engineStartIntent(ctx))
        awaitFor("观察协程已订阅并发过 start") { stub.startCalls.get() == 1 }

        stub.states.value = mapOf(
            "a" to SessionState.Spawning,
            "b" to SessionState.Ready,
        )
        awaitFor("通知文案变成 2 个会话") { postedText() == "2 个会话" }
        assertEquals(PiEngineService.notificationText(2), postedText())

        stub.states.value = mapOf("b" to SessionState.Stopped)
        awaitFor("通知文案变成 1 个会话") { postedText() == "1 个会话" }
        controller.destroy()
        drain()
    }

    // ── 用例 4：STOP → shutdownAll 被调，然后 stopSelf + 撤通知 ──────────────────

    @Test
    fun `stop action shuts every session down then stops self`() {
        val stub = StubSupervisor()
        EngineRuntime.supervisor = stub
        val controller = newService()
        controller.sendStart(engineStartIntent(ctx))
        awaitFor("先起一条会话") { stub.startCalls.get() == 1 }
        stub.states.value = mapOf("a" to SessionState.Ready)
        awaitFor("通知就位") { postedText() == "1 个会话" }

        controller.sendStart(engineStopIntent(ctx))
        awaitFor("shutdownAll 被调") { stub.shutdownCalls.get() == 1 }
        awaitFor("服务自我停止") { shadowOf(controller.get()).isStoppedBySelf }
        assertTrue(
            "STOP 必须以 STOP_FOREGROUND_REMOVE 撤通知",
            shadowOf(controller.get()).notificationShouldRemoved,
        )
        assertEquals("STOP 之后不该再 start 新会话", 1, stub.startCalls.get())
        controller.destroy()
        drain()
    }

    // ── 用例 5：没 initialize（supervisor==null）→ stopSelf，不抛 ────────────────

    @Test
    fun `start action with no supervisor stops self instead of throwing`() {
        assertNull(EngineRuntime.supervisor)
        val controller = newService()
        controller.sendStart(engineStartIntent(ctx))
        drain()
        assertTrue("必须立刻 stopSelf", shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
        drain()
    }

    // ── 用例 6：通知动作「停止引擎」→ PendingIntent 指回本服务 ACTION_STOP ────────

    @Test
    fun `notification exposes the stop-engine action pointing at this service`() {
        EngineRuntime.supervisor = StubSupervisor()
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
}
