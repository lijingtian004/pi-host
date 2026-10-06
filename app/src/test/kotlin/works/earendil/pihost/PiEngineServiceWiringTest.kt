package works.earendil.pihost

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Looper
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController
import works.earendil.pihost.core.engine.PiEngineService
import works.earendil.pihost.core.engine.di.EngineModule
import works.earendil.pihost.core.engine.engineStartIntent
import works.earendil.pihost.core.engine.engineStopIntent

/**
 * 引擎前台服务在 **app 组件树里**的接线（规格书 §7 app 测试的「启动→通知→sessionStates→STOP→stopSelf」）。
 *
 * 与 `:core:engine` 那 7 条的分工：那边验服务**自身**的语义（三档 FGS 类型、重复订阅、
 * start 抛异常不崩服务…）；这里验的是**宿主**——`HiltTestApplication` + app 的
 * `@Singleton` 组件能否把 `ChatEngine` 假体注入进服务、VM 用的那两条意图能否命中 manifest 里
 * 声明的服务、以及 STOP 通路在 app 图里同样收摊。假体经 [AppTestEngineModule] 换上，
 * 与 `MainActivityTest` 共用同一套 holder/order 配方（见那里的 KDoc）。
 */
@HiltAndroidTest
@UninstallModules(EngineModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = HiltTestApplication::class)
class PiEngineServiceWiringTest {

    @get:Rule(order = -1)
    val swapDoubles = OutermostRule { AppTestHolder.reset() }

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val engine get() = AppTestHolder.engine
    private val notificationManager: NotificationManager
        get() = ctx.getSystemService(NotificationManager::class.java)

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

    private fun ServiceController<PiEngineService>.send(intent: Intent) = withIntent(intent).startCommand(0, 1)

    private fun postedText(): String? = shadowOf(notificationManager)
        .getNotification(PiEngineService.NOTIFICATION_ID)
        ?.let { shadowOf(it).contentText?.toString() }

    // ── ① app 图里的 START：注入生效 → 会话表进通知 ──────────────────────────────

    @Test
    fun `start intent dispatched from the app graph injects the engine and raises the notification`() {
        val controller = newService()
        val service = controller.get()
        assertNotNull("onCreate 必须立刻 startForeground", shadowOf(service).lastForegroundNotification)

        controller.send(engineStartIntent(ctx))
        awaitFor("engine.start 被调") { engine.startCalls.get() == 1 }
        awaitFor("sessionStates 变化进通知文案") { postedText() == PiEngineService.notificationText(1) }
        assertEquals(PiEngineService.notificationText(1), postedText())

        controller.destroy()
        drain()
    }

    // ── ② app 图里的 STOP：shutdownAll → stopSelf → 撤通知 ───────────────────────

    @Test
    fun `stop intent shuts the sessions down and tears the foreground notification down`() {
        val controller = newService()
        controller.send(engineStartIntent(ctx))
        awaitFor("先起一条会话") { engine.startCalls.get() == 1 }

        controller.send(engineStopIntent(ctx))
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

    // ── ③ manifest 契约：VM 发的那两条意图必须命中已声明的 specialUse 服务 ─────────

    @Test
    fun `the service the view model targets is declared in the app manifest with the specialUse type`() {
        val declared: ServiceInfo = ctx.packageManager.getServiceInfo(
            ComponentName(ctx.packageName, PiEngineService::class.java.name),
            PackageManager.GET_META_DATA,
        )
        assertEquals(PiEngineService::class.java.name, declared.name)
        assertTrue(
            "FGS 类型必须声明 specialUse（方案 §3.5 定案，targetSdk 35 起强制）",
            declared.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0,
        )
        // 意图显式带组件：Android 12+ 不许隐式服务意图（VM 侧经 engineStartIntent 构造）
        assertEquals(
            ComponentName(ctx.packageName, PiEngineService::class.java.name),
            engineStartIntent(ctx).component,
        )
        assertEquals(PiEngineService.ACTION_STOP, engineStopIntent(ctx).action)
    }
}
