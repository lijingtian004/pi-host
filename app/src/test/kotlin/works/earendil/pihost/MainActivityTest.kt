package works.earendil.pihost

import android.Manifest
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import works.earendil.pihost.core.engine.PiEngineService
import works.earendil.pihost.core.engine.SessionState
import works.earendil.pihost.core.engine.di.EngineModule
import works.earendil.pihost.core.engine.engineStopIntent
import works.earendil.pihost.core.rpc.AssistantMessageEvent
import works.earendil.pihost.core.rpc.PiEvent
import works.earendil.pihost.core.rpc.PiMessage
import works.earendil.pihost.core.rpc.RpcCommand
import works.earendil.pihost.feature.chat.ui.ChatTags

/**
 * app 接线测试（规格书 §7「app 测试 ≥7 条，等价重写 P2C 覆盖」）。
 *
 * **覆盖面对照（§2 红线：不缩水）**：
 * | P2C 的 7 条 | 本文件的等价面 |
 * | --- | --- |
 * | 未安装 → 屏显 not installed | ①cleanSandbox…installGate（`运行时未安装` + 安装按钮） |
 * | 已装 → 版本上屏 | ②installButton…（安装门 → Ready → 顶栏短码 = sessionId 前 8 位） |
 * | renderState 四档 | ⑨topBarShortCode…（Ready/Restarting/Failed/Stopped 四档 + 队列徽标） |
 * | 启动引擎 → FGS 意图 | ②（同一条 ACTION_START 断言，含组件名） |
 * | 测试 RPC → 先订阅再发 + 应答渲染 | ⑥promptRoundTrip…（同一条通路，改为 prompt/流式）+ 探针本体在 `ChatViewModelTest case10` |
 * | 安装失败显因可重试 | ③installFailure… |
 * | 无会话提示 + ACTION_STOP | ⑧stopIntent…（含「安装失败绝不起 FGS」）|
 * 通知分支（P2C 用 grantPermissions 走通）→ ⑦，另加 ④NavHost 占位、⑤权限桥弹框。
 *
 * **三条 Hilt + Robolectric 配方**（RECON-P2D §3 实测）：
 * 1. `@Config(application = HiltTestApplication::class)` + [`@HiltAndroidTest`]
 *    + `@UninstallModules(EngineModule::class)`：真装配（PiSpawn/JvmProcessFactory）
 *    整块换成 [AppTestEngineModule]，于是 Robolectric 里不需要 `nativeLibraryDir` 也能建图；
 * 2. [OutermostRule] 的 `order = -1` 必须比 hiltRule(0)/composeRule(1) 更外层——
 *    `createAndroidComposeRule` 在**规则阶段**就起 Activity，`@Before` 里换假体已经晚了一步；
 * 3. provider 不带 scope（见 [AppTestHolder]），所以每次注入点都重新取值，
 *    拿到的是本用例刚 reset 出来的那一对。
 *
 * **`qualifiers = "w420dp-h2400dp"`**：Robolectric 默认屏 320×470dp，
 * LazyColumn 视口外的条目根本不进组成树，`onNode…` 会报「节点不存在」。
 * 拉长视口后转录/弹框都真正参与布局，`assertIsDisplayed` 才在验渲染而不是验存在。
 *
 * **pump()**：`viewModelScope` 跑在 Robolectric 的 main looper 上，Compose 的 `waitForIdle()`
 * 只保证帧与 recomposition 收敛，不保证 pending 的 main task 跑完；
 * 所以每条用例统一 [pump]（waitForIdle + idle 主 looper）两轮。
 */
@HiltAndroidTest
@UninstallModules(EngineModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = HiltTestApplication::class, qualifiers = "w420dp-h2400dp")
class MainActivityTest {

    @get:Rule(order = -1)
    val swapDoubles = OutermostRule { AppTestHolder.reset() }

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val engine: RecordingChatEngine get() = AppTestHolder.engine
    private val installer: ScriptedRuntimeInstall get() = AppTestHolder.installer

    /** `startForegroundService` 的记录挂在 Application 上（ShadowService 没有 activity 重载）。 */
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun pump() {
        repeat(2) {
            composeRule.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /**
     * 把主 looper 的时钟**往前推**一个 debounce 窗口（§3.3 的 80ms 合并提交）。
     *
     * `idle()` 只跑「已到期」的任务、不前进时钟，而 `viewModelScope` 的 `delay(80)`
     * 在 Robolectric 的 PAUSED looper 上是一条**未来**消息——不推时钟就永远不冲刷，
     * 正文停在空串、markwon 的 AndroidView 压根不创建（实测 renderedBody 为空）。
     * 单独成一只 helper 而不是塞进 [pump]：推进时钟会牵动权限倒计时那条 1s 节拍，
     * 120ms < 1000ms 不会误触发它，但没必要时不必推进。
     */
    private fun pumpDebounceWindow() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DEBOUNCE_PUMP_MS))
        composeRule.waitForIdle()
    }

    /**
     * 宿主视图树里所有 TextView 的真实文本（P2E §4.3 的等价判据）。
     *
     * assistant 正文改由 markwon 经 `AndroidView` 渲染后，**不在 Compose 语义树里**：
     * `onNodeWithText` 只看 Text/InputText/EditableText 语义节点，看不见 interop 里的 TextView，
     * 所以「正文有没有出现在屏幕上」这类断言一律下移到真实视图层
     * （气泡/工具卡/输入框这些 Compose 节点仍按 tag/文本断言，一条没少）。
     */
    private fun renderedBody(): String {
        val out = ArrayList<TextView>(8)
        fun walk(view: View) {
            if (view is TextView) out.add(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(composeRule.activity.window.decorView)
        return out.joinToString(separator = "\n") { it.text?.toString().orEmpty() }
    }

    /** 点安装按钮 → Ready（顶栏出现即代表 uiState 已是 Ready）。 */
    private fun enterReady() {
        composeRule.onNodeWithTag(ChatTags.INSTALL_BUTTON).performClick()
        pump()
        composeRule.onNodeWithTag(ChatTags.TOP_BAR).assertIsDisplayed()
    }

    // ── ① NavHost 的 chat 是起始路由，干净沙箱 = 安装门 ──────────────────────────

    @Test
    fun `chat route is the start destination and a clean sandbox shows the install gate`() {
        composeRule.onNodeWithText(Nav.TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(Nav.ENTRY_SESSIONS).assertIsDisplayed()
        composeRule.onNodeWithText(Nav.ENTRY_SETTINGS).assertIsDisplayed()

        composeRule.onNodeWithText("运行时未安装").assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.INSTALL_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.TOP_BAR).assertDoesNotExist()
        assertEquals("没点安装就绝不该起引擎", 0, engine.startCalls.get())
        assertEquals("没点安装就绝不该装运行时", 0, installer.calls.get())
    }

    // ── ② 安装 → FGS 意图（先于 spawn，§13 偏差 6）→ Ready → 顶栏短码 ──────────────

    @Test
    fun `install button runs the gate, posts the engine foreground service intent and reaches ready`() {
        enterReady()

        assertEquals(1, engine.startCalls.get())
        val intent = shadowOf(app).peekNextStartedService()
        assertTrue("必须发出 FGS 启动意图", intent != null)
        assertEquals(PiEngineService.ACTION_START, intent!!.action)
        assertEquals(PiEngineService::class.java.name, intent.component?.className)

        composeRule.onNodeWithTag(ChatTags.ENGINE_STATUS)
            .assertTextEquals("就绪 ${RecordingChatEngine.SESSION.take(8)}")
        composeRule.onNodeWithTag(ChatTags.TRANSCRIPT).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.INPUT).assertIsEnabled()
    }

    // ── ③ 安装失败：独立文案 + 真原因 + 可重试，且绝不起引擎 ──────────────────────

    @Test
    fun `install failure keeps its own cause and the retry then succeeds`() {
        installer.outcome = ScriptedRuntimeInstall.Outcome.Failure
        composeRule.onNodeWithTag(ChatTags.INSTALL_BUTTON).performClick()
        pump()

        composeRule.onNodeWithText("启动失败").assertIsDisplayed()
        composeRule.onNodeWithText("sha256 mismatch on bin/node").assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.INSTALL_CAUSE).assertIsDisplayed()
        assertEquals("安装失败绝不能起引擎", 0, engine.startCalls.get())
        assertNull("安装失败绝不能发 FGS 意图", shadowOf(app).peekNextStartedService())
        // 失败不得被冲成「未安装」
        composeRule.onNodeWithText("运行时未安装").assertDoesNotExist()

        installer.outcome = ScriptedRuntimeInstall.Outcome.Success
        composeRule.onNodeWithTag(ChatTags.INSTALL_RETRY_BUTTON).performClick()
        pump()
        composeRule.onNodeWithTag(ChatTags.TOP_BAR).assertIsDisplayed()
        assertEquals(2, installer.calls.get())
        assertEquals(1, engine.startCalls.get())
    }

    // ── ④ sessions/settings 占位路由（§13 偏差 2）+ 回退到 chat ──────────────────

    @Test
    fun `placeholder routes render P2E and back navigation returns to chat`() {
        composeRule.onNodeWithText(Nav.ENTRY_SESSIONS).performClick()
        pump()
        composeRule.onNodeWithTag(Nav.TAG_SESSIONS).assertIsDisplayed()
        composeRule.onAllNodesWithText(Nav.PLACEHOLDER)[0].assertIsDisplayed()

        composeRule.onNodeWithText(Nav.ENTRY_SETTINGS).performClick()
        pump()
        composeRule.onNodeWithTag(Nav.TAG_SETTINGS).assertIsDisplayed()

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        pump()
        composeRule.onNodeWithTag(Nav.TAG_SESSIONS).assertIsDisplayed()

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        pump()
        composeRule.onNodeWithText("运行时未安装").assertIsDisplayed()
    }

    // ── ⑤ 权限桥：Ready 屏上弹框，「允许一次」与「拒绝」各自回写 ──────────────────

    @Test
    fun `permission dialog over the ready screen writes allow-once and deny back through the engine`() {
        enterReady()

        // timeout 给到 600s：pump() 会 idle 主 looper，倒计时不该在用例中途把弹框吃掉
        engine.emit(uiRequest("p1", "confirm", timeoutMs = 600_000L))
        pump()
        composeRule.onNodeWithTag(ChatTags.PERMISSION_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithText("method=confirm｜剩余 600s").assertIsDisplayed()
        composeRule.onNodeWithText("pi 请求执行 confirm").assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.INPUT).assertIsNotEnabled()

        composeRule.onNodeWithTag(ChatTags.PERMISSION_ALLOW_ONCE).performClick()
        pump()
        composeRule.onNodeWithTag(ChatTags.PERMISSION_DIALOG).assertDoesNotExist()
        assertEquals(listOf("p1"), engine.uiResponses.map { it.id })
        assertEquals(true, engine.uiResponses.single().confirmed)

        engine.emit(uiRequest("p2", "input", timeoutMs = 600_000L))
        pump()
        composeRule.onNodeWithTag(ChatTags.PERMISSION_DENY).performClick()
        pump()
        assertEquals("p2", engine.uiResponses.last().id)
        assertEquals(true, engine.uiResponses.last().cancelled)
    }

    // ── ⑥ prompt 往返：用户气泡即时入列 → 流式 delta → TurnEnd 解禁输入 ────────────

    @Test
    fun `prompt round trip streams the answer into the transcript and frees the input`() {
        enterReady()

        composeRule.onNodeWithTag(ChatTags.INPUT).performTextInput("跑一下分支命令")
        composeRule.onNodeWithTag(ChatTags.SEND).performClick()
        pump()

        assertEquals(RpcCommand.Prompt(message = "跑一下分支命令"), engine.sent.last().command)
        assertEquals("ui-1", engine.sent.last().id)
        composeRule.onNodeWithText("跑一下分支命令").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.INPUT).assertIsNotEnabled()

        engine.emit(PiEvent.MessageStart(PiMessage(role = "assistant", content = JsonArray(emptyList()))))
        engine.emit(update(AssistantMessageEvent.TextDelta(0, "好的")))
        engine.emit(update(AssistantMessageEvent.TextDelta(0, "，已在 main")))
        pumpDebounceWindow()
        assertTrue("markwon 渲染体必须带出合并后的正文，实为 ${renderedBody()}", renderedBody().contains("好的，已在 main"))

        engine.emit(PiEvent.TurnEnd(message = PiMessage(role = "assistant")))
        pump()
        composeRule.onNodeWithTag(ChatTags.INPUT).assertIsEnabled()

        // 工具卡：状态灯（isError → 红点）与输出区
        engine.emit(PiEvent.ToolExecutionStart(toolCallId = "call-9", toolName = "bash", args = null))
        engine.emit(
            PiEvent.ToolExecutionEnd(
                toolCallId = "call-9",
                toolName = "bash",
                result = JsonObject(mapOf("content" to JsonPrimitive("On branch main"))),
                isError = true,
            ),
        )
        pump()
        composeRule.onNodeWithTag(ChatTags.tool("call-9")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.toolOutput("call-9")).performScrollTo().performClick()
        pump()
        composeRule.onNodeWithText("On branch main").performScrollTo().assertIsDisplayed()
    }

    // ── ⑦ POST_NOTIFICATIONS：Ready 时请求一次（§6 末条）─────────────────────────

    @Config(sdk = [34], qualifiers = "w420dp-h2400dp", application = HiltTestApplication::class)
    @Test
    fun `ready screen requests the notification permission once when it is not granted`() {
        shadowOf(composeRule.activity).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        enterReady()

        val request = shadowOf(composeRule.activity).lastRequestedPermission
        assertTrue("Ready 必须请求一次 POST_NOTIFICATIONS", request != null)
        assertEquals(Manifest.permission.POST_NOTIFICATIONS, request!!.requestedPermissions.single())
        // 「拒绝不阻塞」的可观测面：请求发出后 Ready 屏照常渲染
        composeRule.onNodeWithTag(ChatTags.TOP_BAR).assertIsDisplayed()

        // text_delta 只写「末条 assistant」，没有 TextStart/MessageStart 建条目时不凭空造气泡，
        // 所以脚本按 §4.2 的真实形状走：先 text_start 再 delta。
        engine.emit(update(AssistantMessageEvent.TextStart(0)))
        engine.emit(update(AssistantMessageEvent.TextDelta(0, "通知被拒也不影响这一轮")))
        pumpDebounceWindow()
        assertTrue("通知被拒不阻塞渲染，实为 ${renderedBody()}", renderedBody().contains("通知被拒也不影响这一轮"))
    }

    // ── ⑧ 停止通路：VM 的 abort + 服务侧 ACTION_STOP 的意图构造 ───────────────────

    @Test
    fun `abort is offered only while streaming and the stop intent targets the engine service`() {
        enterReady()

        composeRule.onNodeWithTag(ChatTags.ABORT).assertIsNotEnabled()
        composeRule.onNodeWithTag(ChatTags.INPUT).performTextInput("中止我")
        composeRule.onNodeWithTag(ChatTags.SEND).performClick()
        pump()
        composeRule.onNodeWithTag(ChatTags.ABORT).assertIsEnabled()
        composeRule.onNodeWithTag(ChatTags.ABORT).performClick()
        pump()

        assertEquals(
            listOf(RpcCommand.Prompt(message = "中止我"), RpcCommand.Abort),
            engine.sent.map { it.command },
        )
        // 停止意图的构造契约（服务侧的 STOP→stopSelf 见 PiEngineServiceWiringTest）
        val stop = engineStopIntent(app)
        assertEquals(PiEngineService.ACTION_STOP, stop.action)
        assertEquals(PiEngineService::class.java.name, stop.component?.className)
    }

    // ── ⑨ 顶栏短码四档 + 队列徽标（P2C「renderState 四档」的等价面）────────────────

    @Test
    fun `top bar short code follows the session state and the queue badge counts pending items`() {
        enterReady()
        composeRule.onNodeWithTag(ChatTags.ENGINE_STATUS).assertTextEquals("就绪 aaaaaaaa")
        composeRule.onNodeWithTag(ChatTags.QUEUE_BADGE).assertDoesNotExist()

        engine.setState(SessionState.Restarting(attempt = 2, backoffMs = 4_000L))
        pump()
        composeRule.onNodeWithTag(ChatTags.ENGINE_STATUS).assertTextEquals("重启第 2 次")

        engine.setState(SessionState.Failed("spawn 失败：ENOENT"))
        pump()
        composeRule.onNodeWithTag(ChatTags.ENGINE_STATUS).assertTextEquals("失败：spawn 失败：ENOENT")

        engine.setState(SessionState.Stopped)
        pump()
        composeRule.onNodeWithTag(ChatTags.ENGINE_STATUS).assertTextEquals("已停止")

        engine.setState(SessionState.Ready)
        engine.emit(PiEvent.QueueUpdate(steering = listOf("a"), followUp = listOf("b", "c")))
        pump()
        composeRule.onNodeWithTag(ChatTags.QUEUE_BADGE).assertIsDisplayed()
        composeRule.onNodeWithText("3 queued").assertIsDisplayed()
    }

    private companion object {
        /**
         * 推时钟的步长：略大于 :feature:chat 的 80ms 合并窗口（跨模块拿不到那个 internal 常量，
         * 这里按 §3.3 原文取值 + 40ms 余量；改窗口就得同步改这里，是唯一一处重复的字面量）。
         */
        const val DEBOUNCE_PUMP_MS = 120L

        /** 权限请求（§3.7 形状）；`timeout` 单位毫秒，冻结区 PiEvent.ExtensionUiRequest 原文。 */
        fun uiRequest(id: String, method: String, timeoutMs: Long): PiEvent.ExtensionUiRequest =
            PiEvent.ExtensionUiRequest(
                id = id,
                method = method,
                title = "确认执行",
                message = "pi 请求执行 $method",
                timeout = timeoutMs,
            )

        fun update(event: AssistantMessageEvent): PiEvent.MessageUpdate =
            PiEvent.MessageUpdate(assistantMessageEvent = event)
    }
}
