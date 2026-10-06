package works.earendil.pihost

import android.app.Activity
import android.app.Application
import android.content.Context
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import works.earendil.pihost.core.engine.AssetSource
import works.earendil.pihost.core.engine.EngineRuntime
import works.earendil.pihost.core.engine.PiEngineService
import works.earendil.pihost.core.engine.SessionState
import works.earendil.pihost.core.engine.SessionSupervisor
import works.earendil.pihost.core.environment.PathLayout
import works.earendil.pihost.core.rpc.PiEvent
import works.earendil.pihost.core.rpc.RpcCommand
import java.io.IOException
import java.io.InputStream

/** 安装失败注入源：manifest 都读不到，`openRuntime` 被调到就是实现错误。 */
private class BrokenAssetSource : AssetSource {
    override fun openManifest(): InputStream = throw IOException(BROKEN_CAUSE)

    override fun openRuntime(): InputStream = throw AssertionError("manifest 都没读到，不该打开 zip")
}

private const val BROKEN_CAUSE: String = "资产缺失：runtime/manifest.json（测试注入）"

/**
 * MainActivity 接线（既有 2 条用例逐字保留 + 新增 5 条）。
 *
 * 与 P2A/P2B 唯一的适配点（RECON §7）：`setContentView` 从裸 TextView 换成
 * `LinearLayout{TextView, Button×3, TextView}`，所以 [versionTextView] 多剥一层容器；
 * 两条既有断言的**文本内容一个字符都没改**——首行仍是
 * `pi-android host (P2 shell)\nruntime: <版本>`。
 *
 * 时序：Activity 的状态回显一律 `mainHandler.post` 回主线程，
 * 所以测试用 [drain]（idle 主 looper）+ [awaitFor]（等真实线程）推进，
 * 500ms 轮询在 PAUSED looper 下不会自动跑，不影响断言确定性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {

    /** `Shadows.shadowOf` 没有 `shadowOf(Context)` 重载，取 started services 必须是 Application。 */
    private val app: Application
        get() = RuntimeEnvironment.getApplication()

    private val ctx: Context
        get() = app

    @Before
    fun clearSandbox() {
        PathLayout.runtimeDir(ctx).deleteRecursively()
        EngineRuntime.reset()
        assertFalse(ctx.filesDir.resolve("runtime").exists())
    }

    @After
    fun releaseRuntime() {
        // Activity.onDestroy 是 protected，测试不能直接调——统一由 controller 收尾，
        // 停掉 500ms 轮询与探针/安装协程，避免跨用例串扰。
        controller?.destroy()
        controller = null
        EngineRuntime.reset()
    }

    private var controller: ActivityController<MainActivity>? = null

    /** 记录调用 + 自带会话表/事件总线的 stub（本工程无 Mockito，靠 open 继承）。 */
    private class StubSupervisor : SessionSupervisor(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        starter = { _, _, _ -> throw AssertionError("测试不该 spawn 真进程") },
        commandFor = { listOf("stub") },
        envFor = { emptyMap() },
        workingDir = null,
    ) {
        val states = MutableStateFlow<Map<String, SessionState>>(emptyMap())
        override val sessionStates: StateFlow<Map<String, SessionState>> get() = states

        private val bus = MutableSharedFlow<PiEvent>(
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )

        override fun events(sessionId: String): SharedFlow<PiEvent> = bus

        override fun eventSubscriptions(sessionId: String): StateFlow<Int> = bus.subscriptionCount

        var sends = 0
        var lastRequestId: String? = null

        /** **发命令那一刻**总线上的订阅者数量：证明「先订阅再发」真的成立，而不是靠推断。 */
        var subscribersAtSend = -1

        fun subscribersNow(): Int = bus.subscriptionCount.value

        override suspend fun send(sessionId: String, command: RpcCommand, id: String?): String {
            sends++
            lastRequestId = id
            subscribersAtSend = bus.subscriptionCount.value
            bus.emit(
                PiEvent.Response(
                    id = id,
                    command = "get_state",
                    success = true,
                    data = JsonObject(mapOf("messageCount" to JsonPrimitive(0))),
                ),
            )
            return id ?: "c-1"
        }
    }

    private fun launch(): MainActivity {
        val created = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller = created
        return created.get()
    }

    private fun versionTextView(): TextView {
        val content = launch().findViewById<FrameLayout>(android.R.id.content)
        return (content.getChildAt(0) as LinearLayout).getChildAt(0) as TextView
    }

    private fun drain() {
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun awaitFor(activity: MainActivity, label: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            drain()
            if (condition()) return
            assertTrue(
                "awaitFor[$label] 5s 内未成立，屏显=「${activity.statusView.text}」",
                System.currentTimeMillis() < deadline,
            )
            Thread.sleep(5)
        }
    }

    /** 预置安装标记：跳过 50MB 解包，让「启动引擎」走 initialize→FGS 分支（RECON §7 取舍）。 */
    private fun presetMarker(version: String = "2026.10.06-1") {
        val marker = PathLayout.installedMarker(ctx)
        marker.parentFile!!.mkdirs()
        marker.writeText("""{"runtimeVersion":"$version"}""")
    }

    // ── 既有 2 条（文本断言原样保留）────────────────────────────────────────────

    @Test
    fun `reports runtime not installed on a clean sandbox`() {
        assertEquals("pi-android host (P2 shell)\nruntime: not installed", versionTextView().text.toString())
    }

    @Test
    fun `reports the installed runtime version from the marker`() {
        presetMarker("2026.10.06-1")
        assertEquals(
            "pi-android host (P2 shell)\nruntime: 2026.10.06-1",
            versionTextView().text.toString(),
        )
    }

    // ── 新增 1：未安装 → renderState() 直出「运行时未安装」───────────────────────

    @Test
    fun `renderState reports the missing runtime before anything else`() {
        val activity = launch()
        EngineRuntime.reset()
        activity.renderState()
        assertEquals(MainActivity.STATUS_NOT_INSTALLED, activity.statusView.text.toString())

        // 装上运行时但没 initialize → 下一档文案
        presetMarker()
        activity.renderState()
        assertEquals(MainActivity.STATUS_NOT_INITIALIZED, activity.statusView.text.toString())

        // initialize 后空闲
        EngineRuntime.supervisor = StubSupervisor()
        activity.renderState()
        assertEquals(MainActivity.STATUS_IDLE, activity.statusView.text.toString())

        // 有会话 → 逐条列出状态
        (EngineRuntime.supervisor as StubSupervisor).states.value = mapOf(
            "11111111-2222-3333-4444-555555555555" to SessionState.Ready,
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" to SessionState.Restarting(2, 4_000L),
        )
        activity.renderState()
        val text = activity.statusView.text.toString()
        assertTrue("应含两条会话：$text", text.contains("会话 2"))
        assertTrue("Ready → 就绪：$text", text.contains("11111111=就绪"))
        assertTrue("Restarting 带次数与退避：$text", text.contains("aaaaaaaa=重启第2次(退避4000ms)"))
    }

    // ── 新增 2：点「启动引擎」→ initialize + startForegroundService(ACTION_START) ──

    @Test
    fun `start engine button initializes and posts the foreground service start intent`() {
        presetMarker()
        val stub = StubSupervisor()
        EngineRuntime.supervisor = stub
        val activity = launch()
        shadowOf(activity).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        activity.startButton.performClick()
        drain()

        val started = shadowOf(app).nextStartedService
        assertNotNull("必须发出 FGS 启动意图", started)
        assertEquals(PiEngineService.ACTION_START, started!!.action)
        assertEquals(PiEngineService::class.java.name, started.component!!.className)
        // 已注入 stub → initialize 必须幂等复用，不新建 supervisor
        assertEquals(stub, EngineRuntime.supervisor)
        assertEquals(MainActivity.STATUS_IDLE, activity.statusView.text.toString())
    }

    // ── 新增 3：点「测试 RPC」→ 先订阅再发 get_state → 应答渲染进状态行 ───────────

    @Test
    fun `probe button sends get_state and renders the matching response`() {
        presetMarker()
        val stub = StubSupervisor()
        stub.states.value = mapOf("s-ready" to SessionState.Ready)
        EngineRuntime.supervisor = stub
        val activity = launch()

        activity.probeButton.performClick()
        awaitFor(activity, "应答已渲染") {
            activity.statusView.text.startsWith(MainActivity.PROBE_PREFIX)
        }
        assertEquals(1, stub.sends)
        // 关键断言：命令发出时收集协程已在总线上（1 个订阅者），应答不会掉进无订阅的窗口
        assertEquals("send 前必须已有订阅者", 1, stub.subscribersAtSend)
        assertTrue(
            "requestId 由 UI 侧生成并回显：${stub.lastRequestId}",
            stub.lastRequestId == "ui-1",
        )
        val text = activity.statusView.text.toString()
        assertTrue("应答 JSON 落地：$text", text.contains("\"command\":\"get_state\""))
        assertTrue("应答 id 必须与请求一致：$text", text.contains("ui-1"))
    }

    // ── 新增 4：安装失败必须显因、可重试、不起 FGS，且与「未安装/空闲」可区分 ─────

    @Test
    fun `install failure shows the real cause and stays retryable`() {
        val activity = launch()
        activity.assetSourceFactory = { BrokenAssetSource() }

        activity.startButton.performClick()
        drain()
        assertTrue("点击后必须先进安装态并置灰", !activity.startButton.isEnabled)
        awaitFor(activity, "失败原因上屏") {
            activity.statusView.text.startsWith(MainActivity.STATUS_INSTALL_FAILED_PREFIX)
        }

        val text = activity.statusView.text.toString()
        assertTrue("屏上必须有真实原因：$text", text.contains(BROKEN_CAUSE))
        assertTrue("必须提示可重试：$text", text.contains("重试"))
        assertTrue("失败后按钮恢复可点", activity.startButton.isEnabled)
        assertNull("安装失败绝不能起 FGS", shadowOf(app).nextStartedService)
        assertTrue("首行仍是未安装：${activity.versionView.text}", "${activity.versionView.text}".contains("not installed"))

        // renderState 不得把失败冲成「未安装」或「空闲」
        activity.renderState()
        val after = activity.statusView.text.toString()
        assertTrue(after.startsWith(MainActivity.STATUS_INSTALL_FAILED_PREFIX))
        assertNotEquals(MainActivity.STATUS_NOT_INSTALLED, after)
        assertNotEquals(MainActivity.STATUS_IDLE, after)

        // 重试：运行时这次就位（预置 marker）→ 陈旧失败位必须清除并顺势起引擎。
        // 这里塞 stub 而非走真实 initialize：真实 spawn 通路归「start engine button」用例，
        // Robolectric 的 applicationInfo.nativeLibraryDir 为 null，真 initialize 会（正确地）拒绝。
        presetMarker("retry-1")
        val retryStub = StubSupervisor()
        EngineRuntime.supervisor = retryStub
        activity.startButton.performClick()
        drain()
        awaitFor(activity, "重试后失败文案清除") {
            !activity.statusView.text.startsWith(MainActivity.STATUS_INSTALL_FAILED_PREFIX)
        }
        val restarted = shadowOf(app).nextStartedService
        assertNotNull("重试成功必须发出 FGS 启动意图", restarted)
        assertEquals(PiEngineService.ACTION_START, restarted!!.action)
    }

    // ── 新增 5：无 Ready 会话时「测试 RPC」只给提示；「停止引擎」发 ACTION_STOP ────

    @Test
    fun `probe without a ready session explains itself and stop posts the stop intent`() {
        presetMarker()
        val stub = StubSupervisor()
        stub.states.value = mapOf("spawning" to SessionState.Spawning)
        EngineRuntime.supervisor = stub
        val activity = launch()

        activity.probeButton.performClick()
        drain()
        val hint = activity.statusView.text.toString()
        assertTrue("应提示没有活动会话：$hint", hint.startsWith("测试 RPC：没有活动会话"))
        assertEquals(0, stub.sends)

        activity.stopButton.performClick()
        drain()
        val stopped = shadowOf(app).nextStartedService
        assertNotNull(stopped)
        assertEquals(PiEngineService.ACTION_STOP, stopped!!.action)
        assertEquals(PiEngineService::class.java.name, stopped.component!!.className)
    }
}
