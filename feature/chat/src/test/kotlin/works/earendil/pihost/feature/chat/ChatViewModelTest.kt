package works.earendil.pihost.feature.chat

import android.app.Application
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import works.earendil.pihost.core.engine.InstallProgress
import works.earendil.pihost.core.engine.PiEngineService
import works.earendil.pihost.core.engine.SessionState
import works.earendil.pihost.core.engine.settings.AppSettings
import works.earendil.pihost.core.rpc.AssistantMessageEvent
import works.earendil.pihost.core.rpc.PiEvent
import works.earendil.pihost.core.rpc.PiMessage
import works.earendil.pihost.core.rpc.RpcCommand
import works.earendil.pihost.feature.chat.ui.buildMarkwon

/**
 * ChatViewModel 状态机 + 事件装配（规格书 §7 交付物 D 的 VM 侧 ≥9 条，实交 12 条）。
 *
 * **时序配方（与 SessionSupervisorTest 的关键差异）**：这里没有真实 IO 线程——
 * 引擎与安装面都是假体，`viewModelScope` 经 `Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))`
 * **就地执行**。于是：
 * - `installAndStart()` / `sendPrompt()` 调用返回时状态已经跑完（不挂起 → 无需 pump）；
 * - 权限倒计时用 `delay()`，走 testScheduler 的**虚拟时钟**，用例显式 [advanceTimeBy] 推进；
 * - 捕获「过程」用 [record]（Unconfined 收集 = 逐值同步入列），
 *   避开 StateFlow 在标准测试派发器上的 conflation 合并（RECON-P2D §4 同一条坑）。
 *
 * 事件脚本的形状全部取自 RECON-P2D §5（冻结区 PiEvent.kt 逐字段抄录），
 * 例如 `ToolExecution*` 的字段名是 **toolCallId** 而不是 callId。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatViewModelTest {

    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    // ── 装配 helper ─────────────────────────────────────────────────────────────

    private class Rig(
        val app: Application,
        val vm: ChatViewModel,
        val engine: FakeChatEngine,
        val installer: FakeRuntimeInstall,
        val settings: FakeSettingsRepository,
    )

    private fun TestScope.rig(
        engine: FakeChatEngine = FakeChatEngine(),
        installer: FakeRuntimeInstall = FakeRuntimeInstall(),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
    ): Rig {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val app = RuntimeEnvironment.getApplication()
        // markwon 走真实装配（buildMarkwon 与 Hilt 的 @Singleton 同一配方）：VM 只转交它，
        // 但构造点必须与 production 一致，否则「渲染层换实现」这类回归在这里测不出来。
        return Rig(
            app,
            ChatViewModel(app, engine, installer, settings, buildMarkwon(app)),
            engine,
            installer,
            settings,
        )
    }

    /** 逐值快照（Unconfined 收集 → 每次 `value=` 都同步入列，不被 conflation 吞）。 */
    private fun <T> TestScope.record(flow: StateFlow<T>): CopyOnWriteArrayList<T> {
        val seen = CopyOnWriteArrayList<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { seen.add(it) } }
        return seen
    }

    /** 走一遍「安装 → FGS → start → 订阅」，返回 sessionId。 */
    private fun TestScope.ready(rig: Rig): String {
        rig.vm.installAndStart()
        runCurrent()
        val state = rig.vm.uiState.value
        assertTrue("期望 Ready，实际 $state", state is ChatUiState.Ready)
        return (state as ChatUiState.Ready).sessionId
    }

    private fun Rig.assistant(): TranscriptItem.AssistantItem =
        vm.transcript.value.filterIsInstance<TranscriptItem.AssistantItem>().last()

    private fun Rig.tool(callId: String): TranscriptItem.ToolItem =
        vm.transcript.value.filterIsInstance<TranscriptItem.ToolItem>().first { it.callId == callId }

    private fun Rig.lastUser(): TranscriptItem.UserItem =
        vm.transcript.value.filterIsInstance<TranscriptItem.UserItem>().last()

    // ── 用例 1：installAndStart 走完 Installing→Ready，并发出 FGS 启动意图 ────────

    @Test
    fun `case01 installAndStart walks every install step then reaches Ready and raises the FGS`() = runTest {
        // stepPauseMs>0 是给这条用例的**必要装置**：StateFlow 只保最新值，
        // 假体若一路狂写 progress，观察者只会看到最后一档（实测拿到空列表）。
        // 真实 AssetInstaller 的每档之间都有 IO 挂起，这里用虚拟时钟把它演出来。
        val rig = rig(installer = FakeRuntimeInstall(stepPauseMs = 10L))
        val states = record(rig.vm.uiState)
        assertEquals("未装运行时 = 安装门", ChatUiState.NotInstalled, rig.vm.uiState.value)

        rig.vm.installAndStart()
        repeat(8) {
            advanceTimeBy(10)
            runCurrent()
        }
        val state = rig.vm.uiState.value
        assertTrue("期望 Ready，实际 $state", state is ChatUiState.Ready)
        val sessionId = (state as ChatUiState.Ready).sessionId

        assertEquals(FakeChatEngine.SESSION, sessionId)
        assertEquals(1, rig.engine.startCalls.get())
        val steps = states.filterIsInstance<ChatUiState.Installing>().map { it.step }
        assertEquals(
            "进度必须逐级透传，实为 $steps",
            listOf(
                InstallProgress.Idle,
                InstallProgress.Extracting(1, 2),
                InstallProgress.Extracting(2, 2),
                InstallProgress.Verifying(2, 2),
                InstallProgress.Finalizing,
                InstallProgress.Done,
            ),
            steps,
        )
        assertEquals(ChatUiState.Ready(sessionId), states.last())

        // §13 偏差 6：通知先于 spawn —— startForegroundService 必须发出且指向 PiEngineService/ACTION_START
        val intent = shadowOf(rig.app).peekNextStartedService()
        assertNotNull("VM 必须 startForegroundService 起引擎前台服务", intent)
        assertEquals(PiEngineService.ACTION_START, intent.action)
        assertEquals(PiEngineService::class.java.name, intent.component?.className)
    }

    // ── 用例 2：安装失败 → Failed(cause) 可重试，二次成功 ─────────────────────────

    @Test
    fun `case02 install failure surfaces a retryable Failed and the second attempt succeeds`() = runTest {
        val installer = FakeRuntimeInstall(outcome = FakeRuntimeInstall.Outcome.Failure)
        val rig = rig(installer = installer)

        rig.vm.installAndStart()
        runCurrent()
        val failed = rig.vm.uiState.value
        assertTrue("期望 Failed，实际 $failed", failed is ChatUiState.Failed)
        failed as ChatUiState.Failed
        assertTrue("cause 必须带真实失败原因，实为 ${failed.cause}", failed.cause.contains("sha256 mismatch"))
        assertEquals("安装失败绝不能起引擎", 0, rig.engine.startCalls.get())

        // 重试按钮的等价调用：starting 闩必须已放开
        installer.outcome = FakeRuntimeInstall.Outcome.Success
        val sessionId = ready(rig)
        assertEquals(ChatUiState.Ready(sessionId), rig.vm.uiState.value)
        assertEquals(2, installer.calls.get())
        assertEquals(1, rig.engine.startCalls.get())
    }

    // ── 用例 3：prompt → Streaming → TextDelta×2 → TurnEnd → Idle，用户气泡即时入列 ─

    @Test
    fun `case03 prompt streams deltas into the last assistant item and TurnEnd frees the input`() = runTest {
        val rig = rig()
        ready(rig)
        val turns = record(rig.vm.turnState)

        rig.vm.sendPrompt("跑一下分支命令")
        runCurrent()

        // 用户气泡**即时**入列（不等 pi 的 message_start 回声）
        assertEquals("跑一下分支命令", rig.lastUser().text)
        assertEquals(TurnState.Streaming, rig.vm.turnState.value)
        assertEquals(RpcCommand.Prompt(message = "跑一下分支命令"), rig.engine.sent.last().command)

        // pi 的 user 回声紧随其后：不得复制气泡（§5.1 去重）
        rig.engine.emit(PiEvent.MessageStart(PiMessage(role = "user", content = textBlocks("跑一下分支命令"))))
        assertEquals(1, rig.vm.transcript.value.count { it is TranscriptItem.UserItem })

        rig.engine.emit(PiEvent.MessageStart(PiMessage(role = "assistant", content = JsonArray(emptyList()))))
        rig.engine.emit(update(AssistantMessageEvent.TextDelta(0, "好的")))
        rig.engine.emit(update(AssistantMessageEvent.TextDelta(0, "，已切到 main")))
        // §3.3 等价调整：正文 delta 不再逐条落 transcript，而是攒 80ms 后一次提交
        // （合并语义与「拼进末条 assistant」这条断言完全同值，只是提交时刻被推迟了一个窗口）。
        advanceTimeBy(DELTA_DEBOUNCE_MS)
        runCurrent()
        assertEquals("两段 delta 必须拼进末条 assistant", "好的，已切到 main", rig.assistant().text)

        rig.engine.emit(PiEvent.TurnEnd(message = PiMessage(role = "assistant")))
        runCurrent()
        assertEquals(TurnState.Idle, rig.vm.turnState.value)
        assertTrue("TurnEnd 后末条必须定稿", rig.assistant().settled)
        assertEquals(listOf(TurnState.Idle, TurnState.Streaming, TurnState.Idle), turns.toList())
    }

    // ── 用例 4：thinking 累积进折叠区数据（与正文分槽）────────────────────────────

    @Test
    fun `case04 thinking deltas accumulate separately from the body`() = runTest {
        val rig = rig()
        ready(rig)
        rig.engine.emit(PiEvent.MessageStart(PiMessage(role = "assistant", content = JsonArray(emptyList()))))
        rig.engine.emit(update(AssistantMessageEvent.ThinkingStart(0)))
        rig.engine.emit(update(AssistantMessageEvent.ThinkingDelta(0, "先看 git 状态")))
        rig.engine.emit(update(AssistantMessageEvent.ThinkingDelta(0, "再切分支")))
        rig.engine.emit(update(AssistantMessageEvent.ThinkingEnd(0, "ignored")))
        rig.engine.emit(update(AssistantMessageEvent.TextDelta(0, "正文")))
        advanceTimeBy(DELTA_DEBOUNCE_MS) // §3.3：正文 delta 走 80ms 合并窗口（thinking 不走）
        runCurrent()

        val item = rig.assistant()
        assertEquals("先看 git 状态再切分支", item.thinking)
        assertEquals("正文", item.text)

        // message_end 的投影只在「没吃到 delta」时回填，不得截断已流式拼好的正文
        rig.engine.emit(
            PiEvent.MessageEnd(
                PiMessage(role = "assistant", content = textBlocks("投影正文")),
            ),
        )
        runCurrent()
        assertEquals("正文", rig.assistant().text)
        assertTrue(rig.assistant().settled)
    }

    // ── 用例 5：toolcall 两段 Delta 累积 + End 解析；ToolExecutionEnd(isError) 亮红灯 ─

    @Test
    fun `case05 toolcall deltas concatenate until End and a failed execution lights the card red`() = runTest {
        val rig = rig()
        ready(rig)
        rig.engine.emit(PiEvent.MessageStart(PiMessage(role = "assistant", content = JsonArray(emptyList()))))
        rig.engine.emit(update(AssistantMessageEvent.ToolcallStart(0, "call-1", "bash")))
        rig.engine.emit(update(AssistantMessageEvent.ToolcallDelta(0, """{"comm""")))
        rig.engine.emit(update(AssistantMessageEvent.ToolcallDelta(0, """and":"git branch"}""")))
        rig.engine.emit(
            update(AssistantMessageEvent.ToolcallEnd(0, buildJsonObject { put("name", "bash"); put("id", "call-1") })),
        )
        runCurrent()

        val record = rig.assistant().toolCalls.last()
        assertEquals("call-1", record.callId)
        assertEquals("bash", record.toolName)
        assertEquals("""{"command":"git branch"}""", record.rawArgs)
        assertEquals("bash", record.parsedName?.trim('"'))

        rig.engine.emit(PiEvent.ToolExecutionStart(toolCallId = "call-1", toolName = "bash"))
        assertEquals(ToolStatus.Running, rig.tool("call-1").status)
        rig.engine.emit(
            PiEvent.ToolExecutionUpdate(
                toolCallId = "call-1",
                toolName = "bash",
                partialResult = buildJsonObject { put("content", "fatal: not a git repository") },
            ),
        )
        assertTrue(
            "partialResult 必须追加进输出区",
            rig.tool("call-1").output.contains("fatal: not a git repository"),
        )
        rig.engine.emit(
            PiEvent.ToolExecutionEnd(
                toolCallId = "call-1",
                toolName = "bash",
                isError = true,
                result = buildJsonObject {
                    put("content", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "exit 128") })))
                },
            ),
        )
        runCurrent()
        val tool = rig.tool("call-1")
        assertEquals(ToolStatus.Error, tool.status)
        assertEquals("result 整块快照非空则以它为准", "exit 128", tool.output)

        // 成功路径：isError=false → Ok 绿灯
        rig.engine.emit(PiEvent.ToolExecutionStart(toolCallId = "call-2", toolName = "read"))
        rig.engine.emit(
            PiEvent.ToolExecutionEnd(
                toolCallId = "call-2",
                toolName = "read",
                isError = false,
                result = buildJsonObject { put("content", "ok-body") },
            ),
        )
        runCurrent()
        assertEquals(ToolStatus.Ok, rig.tool("call-2").status)
    }

    // ── 用例 6：权限弹框 → 允许一次 → confirmed 回写 → 回 Streaming → TurnEnd → Idle ─

    @Test
    fun `case06 permission request blocks the input and allow-once writes a confirmed response`() = runTest {
        val rig = rig()
        ready(rig)
        rig.vm.sendPrompt("ls")
        runCurrent()
        assertEquals(TurnState.Streaming, rig.vm.turnState.value)

        rig.engine.emit(uiRequest(id = "u1", method = "confirm", message = "允许执行 bash？"))
        runCurrent()
        assertEquals(TurnState.PermissionPending, rig.vm.turnState.value)
        val pending = rig.vm.permissionRequest.value
        assertNotNull("extension_ui_request 必须挂出弹框态", pending)
        assertEquals("u1", pending!!.id)
        assertEquals("confirm", pending.method)
        assertEquals("允许执行 bash？", pending.message)
        assertEquals("timeout 缺省 = protocol §3.7 的 15000ms", 15_000L, pending.remainingMs)
        assertEquals(15, pending.remainingSeconds)

        rig.vm.answerPermission(allowOnce = true)
        runCurrent()
        assertNull(rig.vm.permissionRequest.value)
        assertEquals(TurnState.Streaming, rig.vm.turnState.value)
        val answer = rig.engine.uiResponses.single()
        assertEquals("u1", answer.id)
        assertEquals(true, answer.confirmed)
        assertNull("confirmed 与 cancelled 互斥", answer.cancelled)
        assertFalse("「允许一次」不该污染本会话记忆", rig.vm.remembered.contains("confirm"))

        rig.engine.emit(PiEvent.TurnEnd(message = PiMessage(role = "assistant")))
        runCurrent()
        assertEquals(TurnState.Idle, rig.vm.turnState.value)

        // 全 false = 非法调用：忽略，不应答、不清弹框
        rig.engine.emit(uiRequest(id = "u2", method = "confirm"))
        runCurrent()
        rig.vm.answerPermission()
        runCurrent()
        assertEquals("u2", rig.vm.permissionRequest.value?.id)
        assertEquals(1, rig.engine.uiResponses.size)
    }

    // ── 用例 7：本会话记忆 → 同 method 二次请求自动 confirmed 且不弹框 ─────────────

    @Test
    fun `case07 session memory auto-confirms the next request for the same method without a dialog`() = runTest {
        val rig = rig()
        ready(rig)
        rig.engine.emit(uiRequest(id = "u1", method = "confirm"))
        runCurrent()
        assertEquals(TurnState.PermissionPending, rig.vm.turnState.value)
        rig.vm.answerPermission(allowSession = true)
        runCurrent()
        assertTrue(rig.vm.remembered.contains("confirm"))

        val requests = record(rig.vm.permissionRequest)
        rig.engine.emit(uiRequest(id = "u2", method = "confirm"))
        runCurrent()

        assertNull("已记下的 method 不许再弹框", rig.vm.permissionRequest.value)
        assertEquals("整个过程只出现一次弹框态", listOf<PendingPermission?>(null), requests.toList())
        assertEquals(listOf("u1", "u2"), rig.engine.uiResponses.map { it.id })
        assertTrue("两条都必须是 confirmed", rig.engine.uiResponses.all { it.confirmed == true })

        // 记忆 key=method：不同 method 仍要问（§13 偏差 3）
        rig.engine.emit(uiRequest(id = "u3", method = "select"))
        runCurrent()
        assertEquals("u3", rig.vm.permissionRequest.value?.id)
        assertEquals(TurnState.PermissionPending, rig.vm.turnState.value)
    }

    // ── 用例 8：拒绝 → cancelled；TurnEnd 抢跑 → 补 cancelled 恰好一次 ─────────────

    @Test
    fun `case08 deny writes cancelled and a TurnEnd racing the dialog backfills exactly one cancelled`() = runTest {
        val rig = rig()
        ready(rig)

        rig.engine.emit(uiRequest(id = "d1", method = "confirm"))
        runCurrent()
        rig.vm.answerPermission(deny = true)
        runCurrent()
        val denied = rig.engine.uiResponses.single()
        assertEquals("d1", denied.id)
        assertEquals("拒绝必须是 cancelled，不是 confirmed=false", true, denied.cancelled)
        assertNull(denied.confirmed)

        // 弹框还挂着而 TurnEnd 先到（§5.2 兜底）
        rig.engine.emit(uiRequest(id = "d2", method = "confirm"))
        runCurrent()
        assertEquals(TurnState.PermissionPending, rig.vm.turnState.value)
        rig.engine.emit(PiEvent.TurnEnd(message = PiMessage(role = "assistant")))
        runCurrent()
        assertNull(rig.vm.permissionRequest.value)
        assertEquals(TurnState.Idle, rig.vm.turnState.value)
        val backfills = rig.engine.uiResponses.filter { it.id == "d2" }
        assertEquals("抢跑兜底只能补发一条", 1, backfills.size)
        assertEquals(true, backfills.single().cancelled)

        // AgentEnd 走同一条纪律；随后 AgentSettled 不得二次补发
        rig.engine.emit(uiRequest(id = "d3", method = "confirm"))
        runCurrent()
        rig.engine.emit(PiEvent.AgentEnd(messages = emptyList()))
        rig.engine.emit(PiEvent.AgentSettled)
        runCurrent()
        assertEquals(1, rig.engine.uiResponses.count { it.id == "d3" })
        assertEquals(TurnState.Idle, rig.vm.turnState.value)
    }

    // ── 用例 9：倒计时到点自动 cancelled（虚拟时钟）；应答后停表不重复发 ───────────

    @Test
    fun `case09 the timeout timer cancels the request once on the virtual clock`() = runTest {
        val rig = rig()
        ready(rig)
        rig.engine.emit(uiRequest(id = "t1", method = "confirm", timeout = 2_000L))
        runCurrent()
        assertEquals(2_000L, rig.vm.permissionRequest.value?.remainingMs)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals("倒计时逐秒改写", 1_000L, rig.vm.permissionRequest.value?.remainingMs)
        assertTrue(rig.engine.uiResponses.isEmpty())

        advanceTimeBy(1_001)
        runCurrent()
        assertNull(rig.vm.permissionRequest.value)
        val answer = rig.engine.uiResponses.single()
        assertEquals("t1", answer.id)
        assertEquals(true, answer.cancelled)
        assertEquals(TurnState.Streaming, rig.vm.turnState.value)

        // 应答之后表已停：再跳 10s 虚拟时间也不会有第二条应答
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(1, rig.engine.uiResponses.size)
    }

    // ── 用例 10：Abort 可用性矩阵 + Response(id 匹配) 只进命令通道、不入 transcript ─

    @Test
    fun `case10 abort is gated by turnState and matched responses stay out of the transcript`() = runTest {
        val rig = rig()
        ready(rig)

        // Idle：abort 直接无效（§6 按钮本就禁用，VM 再兜一层）
        rig.vm.abort()
        runCurrent()
        assertTrue("Idle 时不该发出任何命令", rig.engine.sent.isEmpty())

        rig.vm.sendPrompt("hello")
        runCurrent()
        val promptId = rig.engine.sent.last().id
        rig.vm.abort()
        runCurrent()
        assertEquals(RpcCommand.Abort, rig.engine.sent.last().command)

        val before = rig.vm.transcript.value.size
        // 别人家的 response（id 不匹配）：不进通道、不进转录
        rig.engine.emit(PiEvent.Response(id = "not-mine", command = "prompt", success = true))
        runCurrent()
        assertNull(rig.vm.commandResult.value)
        assertEquals(before, rig.vm.transcript.value.size)

        rig.engine.emit(PiEvent.Response(id = promptId, command = "prompt", success = true))
        runCurrent()
        assertEquals(promptId, rig.vm.commandResult.value?.id)
        assertEquals("response 绝不能变成转录条目", before, rig.vm.transcript.value.size)

        // 探针（P2C「测试 RPC」按钮的等价面）：get_state 也走同一条命令通道
        rig.engine.emit(PiEvent.TurnEnd(message = PiMessage(role = "assistant")))
        runCurrent()
        rig.vm.probeState()
        runCurrent()
        assertEquals(RpcCommand.GetState, rig.engine.sent.last().command)
        rig.engine.emit(PiEvent.Response(id = rig.engine.sent.last().id, command = "get_state", success = true))
        runCurrent()
        assertEquals("get_state", rig.vm.commandResult.value?.command)

        // 本地发送失败（没有活动进程）→ 错误进 commandResult，不炸 UI
        rig.engine.sendError = IllegalStateException("会话没有活动 pi 进程")
        rig.vm.sendPrompt("再来一条")
        runCurrent()
        val failure = rig.vm.commandResult.value
        assertNotNull("发送失败必须可见", failure)
        assertFalse(failure!!.success)
        assertEquals("prompt", failure.command)
    }

    // ── 用例 11：queue_update 徽标 + sessionStates 变化进顶栏状态源 ───────────────

    @Test
    fun `case11 queue depth badge follows queue_update and engine status follows sessionStates`() = runTest {
        val rig = rig()
        val sessionId = ready(rig)
        assertEquals(0, rig.vm.queueDepth.value)
        assertEquals(SessionState.Ready, rig.vm.engineStatus.value)

        rig.engine.emit(PiEvent.QueueUpdate(steering = listOf("a", "b"), followUp = listOf("c")))
        runCurrent()
        assertEquals("queueDepth = steering + followUp", 3, rig.vm.queueDepth.value)
        rig.engine.emit(PiEvent.QueueUpdate(steering = emptyList(), followUp = emptyList()))
        runCurrent()
        assertEquals(0, rig.vm.queueDepth.value)

        rig.engine.setState(SessionState.Restarting(attempt = 2, backoffMs = 4_000L))
        runCurrent()
        assertEquals(SessionState.Restarting(2, 4_000L), rig.vm.engineStatus.value)
        assertEquals(sessionId, (rig.vm.uiState.value as ChatUiState.Ready).sessionId)

        // auto_retry_* 只改提示条，不新开转录条目
        val items = rig.vm.transcript.value.size
        rig.engine.emit(PiEvent.AutoRetryStart(attempt = 1, maxAttempts = 3, delayMs = 2_000L))
        runCurrent()
        assertEquals("重试 1/3（2000ms 后）", rig.vm.retryNote.value)
        assertEquals(items, rig.vm.transcript.value.size)
        rig.engine.emit(PiEvent.AutoRetryEnd(success = true, attempt = 1))
        runCurrent()
        assertNull(rig.vm.retryNote.value)
    }

    // ── 用例 12：引擎起不来 → Failed(cause 含 stderrTail)；bash delta 落进 Running 工具卡 ─

    @Test
    fun `case12 a failing engine start lands in Failed carrying the stderr tail`() = runTest {
        val engine = FakeChatEngine().apply {
            startState = SessionState.Failed("spawn 失败：ENOENT")
            stderr = "Error: Cannot find module 'pi/dist/bundle/cli.js'"
        }
        val rig = rig(engine = engine)

        rig.vm.installAndStart()
        runCurrent()
        val failed = rig.vm.uiState.value
        assertTrue("期望 Failed，实际 $failed", failed is ChatUiState.Failed)
        failed as ChatUiState.Failed
        assertTrue("cause 必须含 Failed.cause：${failed.cause}", failed.cause.contains("ENOENT"))
        assertTrue("cause 必须拼上 stderr 尾部：${failed.cause}", failed.cause.contains("Cannot find module"))
        assertEquals(1, engine.startCalls.get())

        // start 抛异常（退避用完之外的崩溃面）同样进 Failed，且不留下半开状态
        val thrower = FakeChatEngine().apply { startError = IllegalStateException("spawn 直接炸") }
        val rig2 = rig(engine = thrower)
        rig2.vm.installAndStart()
        runCurrent()
        assertTrue(rig2.vm.uiState.value is ChatUiState.Failed)
    }

    /** bash 流的 delta 归槽（§5.1 BashExecutionUpdate → 追加输出）。 */
    @Test
    fun `case13 bash execution deltas land on the newest running tool card`() = runTest {
        val rig = rig()
        ready(rig)
        rig.engine.emit(PiEvent.ToolExecutionStart(toolCallId = "b1", toolName = "bash"))
        rig.engine.emit(PiEvent.BashExecutionUpdate(id = null, delta = "step 1\n"))
        rig.engine.emit(PiEvent.BashExecutionUpdate(id = "b1", delta = "step 2"))
        runCurrent()
        assertEquals("id=null 落到最后一条 Running 卡", "step 1\nstep 2", rig.tool("b1").output)

        // 空 delta 与未知 id 都不该改动转录
        val snapshot = rig.vm.transcript.value
        rig.engine.emit(PiEvent.BashExecutionUpdate(id = "nope", delta = "x"))
        rig.engine.emit(PiEvent.BashExecutionUpdate(id = "b1", delta = ""))
        runCurrent()
        assertEquals(snapshot, rig.vm.transcript.value)
    }

    // ── 用例 14：§4.3 switchSession 成功回执后才清场，并挂「加载中」占位 ──────────

    @Test
    fun `case14 switchSession waits for its own response before wiping the transcript`() = runTest {
        val rig = rig()
        ready(rig)
        // 先攒一点上一条会话的内容（切换失败时这条快照必须原样还在）
        rig.engine.emit(PiEvent.MessageStart(PiMessage(role = "user", content = textBlocks("上一条会话的问题"))))
        val before = rig.vm.transcript.value

        val path = "/files/home/.pi/agent/sessions/--p--/a.jsonl"
        rig.vm.switchSession(path)
        runCurrent()

        val sent = rig.engine.sent.last()
        assertEquals("命令必须就是 switch_session(路径)", RpcCommand.SwitchSession(path), sent.command)
        assertNotNull("必须自带 requestId 才能认出这条回执", sent.id)
        assertEquals("回执未定之前不许清场", before, rig.vm.transcript.value)

        // 别人的回执不算数（id 不匹配）
        rig.engine.emit(PiEvent.Response(id = "not-mine", command = "switch_session", success = true))
        runCurrent()
        assertEquals(before, rig.vm.transcript.value)

        rig.engine.emit(PiEvent.Response(id = sent.id, command = "switch_session", success = true))
        runCurrent()

        val rolled = rig.vm.transcript.value
        assertEquals("切换成功 = 清空 + 一条占位", 1, rolled.size)
        val note = rolled.single()
        assertTrue("占位必须是 NoteItem，实为 $note", note is TranscriptItem.NoteItem)
        assertEquals(SWITCHING_NOTE_TEXT, (note as TranscriptItem.NoteItem).text)
        assertEquals(TurnState.Idle, rig.vm.turnState.value)
        assertNull("成功路径不该留提示", rig.vm.notice.value)

        // 真内容到了 → 占位行由写入点单点撤除（§4.3）
        rig.engine.emit(PiEvent.MessageStart(PiMessage(role = "assistant", content = JsonArray(emptyList()))))
        runCurrent()
        assertTrue(rig.vm.transcript.value.none { it is TranscriptItem.NoteItem })
        assertEquals(1, rig.vm.transcript.value.size)
    }

    // ── 用例 15：切换失败（success=false / 本地发送失败 / 超时）一律不动转录 ───────

    @Test
    fun `case15 a failed session switch keeps the transcript and reports the reason`() = runTest {
        val rig = rig()
        ready(rig)
        rig.vm.sendPrompt("留在原会话的问题")
        runCurrent()
        val before = rig.vm.transcript.value
        assertEquals(1, before.size)

        // ① pi 回了 success=false
        rig.vm.switchSession("/files/home/.pi/agent/sessions/--p--/gone.jsonl")
        runCurrent()
        val firstId = rig.engine.sent.last().id
        rig.engine.emit(PiEvent.Response(id = firstId, command = "switch_session", success = false, error = "会话文件不存在"))
        runCurrent()
        assertEquals("失败必须原样保留内容", before, rig.vm.transcript.value)
        assertTrue("提示要带 pi 给的原因，实为 ${rig.vm.notice.value}", rig.vm.notice.value!!.contains("会话文件不存在"))

        // ② 本地发送就抛（没有活动进程）
        rig.vm.consumeNotice()
        rig.engine.sendError = IllegalStateException("no active pi process")
        rig.vm.switchSession("/files/home/.pi/agent/sessions/--p--/b.jsonl")
        runCurrent()
        assertEquals(before, rig.vm.transcript.value)
        assertTrue(rig.vm.notice.value!!.contains("no active pi process"))

        // 闩放开了：清掉错误后还能再发（sendError 若卡住 switching 就永远发不出第二条）
        rig.engine.sendError = null
        val sendsBefore = rig.engine.sent.size
        rig.vm.switchSession("/files/home/.pi/agent/sessions/--p--/c.jsonl")
        runCurrent()
        assertEquals("上一条失败后 switching 闩必须已放开", sendsBefore + 1, rig.engine.sent.size)
    }

    // ── 用例 16：切换超时（虚拟时钟推到 SESSION_ROLL_TIMEOUT_MS）─────────────────

    @Test
    fun `case16 an unanswered switch times out without touching the transcript`() = runTest {
        val rig = rig()
        ready(rig)
        rig.engine.emit(PiEvent.MessageStart(PiMessage(role = "assistant", content = textBlocks("原样留着"))))
        val before = rig.vm.transcript.value

        rig.vm.switchSession("/files/home/.pi/agent/sessions/--p--/slow.jsonl")
        runCurrent()
        assertEquals("超时前同样不许清场", before, rig.vm.transcript.value)

        // 超时跑在 viewModelScope 上（RECON-P2E §5：Unconfined 不实现 Delay，虚拟时钟推不到它）
        advanceTimeBy(SESSION_ROLL_TIMEOUT_MS)
        runCurrent()
        assertTrue("超时要给可读提示，实为 ${rig.vm.notice.value}", rig.vm.notice.value!!.contains("超时"))
        assertEquals(before, rig.vm.transcript.value)
        assertTrue(rig.vm.transcript.value.none { it is TranscriptItem.NoteItem })
    }

    // ── 用例 17：newSession 走同一条流程；占位行有自动撤身时限 ────────────────────

    @Test
    fun `case17 newSession rolls the transcript and the loading note expires on its own`() = runTest {
        val rig = rig()
        ready(rig)
        rig.engine.emit(PiEvent.MessageStart(PiMessage(role = "user", content = textBlocks("旧问题"))))

        rig.vm.newSession()
        runCurrent()
        assertEquals(RpcCommand.NewSession(), rig.engine.sent.last().command)

        val sentId = rig.engine.sent.last().id
        rig.engine.emit(PiEvent.Response(id = sentId, command = "new_session", success = true))
        runCurrent()
        assertEquals(listOf(SWITCHING_NOTE_TEXT), rig.vm.transcript.value.filterIsInstance<TranscriptItem.NoteItem>().map { it.text })

        // pi 什么都不发（新会话本来就是空的）→ 时限一到占位行自己退场，不留死行
        advanceTimeBy(SWITCH_NOTE_TIMEOUT_MS)
        runCurrent()
        assertTrue("时限到了必须撤干净", rig.vm.transcript.value.isEmpty())
    }

    // ── 用例 18：maxParallelSessions 门（§4.3 / §13 偏差 6：VM 层自查，不碰引擎）──

    @Test
    fun `case18 the parallel gate blocks start and raises no foreground service intent`() = runTest {
        val engine = FakeChatEngine()
        engine.setStateFor("another-session", SessionState.Ready)
        val rig = rig(engine = engine, settings = FakeSettingsRepository(AppSettings(maxParallelSessions = 1)))

        rig.vm.installAndStart()
        runCurrent()

        val state = rig.vm.uiState.value
        assertTrue("超限应落 Failed（引擎起不来 + 可读原因），实际 $state", state is ChatUiState.Failed)
        assertEquals("门必须在 spawn 之前拦住", 0, engine.startCalls.get())
        assertNull("连 FGS 意图都不该发（§13 偏差 6 的次序反过来做就是空起一个前台服务）", shadowOf(rig.app).peekNextStartedService())
        assertTrue("提示要写明上限，实为 ${rig.vm.notice.value}", rig.vm.notice.value!!.contains("并行上限 1"))

        // 上限放宽后重试即通过（starting 闩已放开）
        rig.vm.consumeNotice()
        rig.settings.setMaxParallel(2)
        val sessionId = ready(rig)
        assertEquals(FakeChatEngine.SESSION, sessionId)
        assertEquals(1, engine.startCalls.get())
    }

    // ── 用例 19：终态会话不占配额（Stopped/Failed 已经没有 pi 进程）───────────────

    @Test
    fun `case19 terminal sessions do not occupy the parallel quota`() = runTest {
        val engine = FakeChatEngine()
        engine.setStateFor("dead", SessionState.Stopped)
        engine.setStateFor("crashed", SessionState.Failed("退避额度耗尽"))
        val rig = rig(engine = engine, settings = FakeSettingsRepository(AppSettings(maxParallelSessions = 2)))

        assertEquals(FakeChatEngine.SESSION, ready(rig))
        assertTrue(rig.vm.uiState.value is ChatUiState.Ready)

        // 第三条才撞门：Ready(本会话) + Ready(另一条) = 2 已达上限
        engine.setStateFor("second", SessionState.Ready)
        val blocked = ChatViewModel(
            rig.app,
            engine,
            FakeRuntimeInstall(),
            FakeSettingsRepository(AppSettings(maxParallelSessions = 2)),
            buildMarkwon(rig.app),
        )
        blocked.installAndStart()
        runCurrent()
        assertTrue("两条在跑 + 上限 2 必须拦住，实际 ${blocked.uiState.value}", blocked.uiState.value is ChatUiState.Failed)
        assertEquals("被拦住的这一次不许碰引擎", 1, engine.startCalls.get())
    }

    // ── 用例 20：§3.3 正文 delta 的 80ms 合并窗口与强制冲刷 ──────────────────────

    @Test
    fun `case20 text deltas merge over the debounce window and flush ahead of other events`() = runTest {
        val rig = rig()
        ready(rig)
        val emissions = record(rig.vm.transcript)

        rig.engine.emit(PiEvent.MessageStart(PiMessage(role = "assistant", content = JsonArray(emptyList()))))
        runCurrent()
        rig.engine.emit(update(AssistantMessageEvent.TextDelta(0, "一")))
        rig.engine.emit(update(AssistantMessageEvent.TextDelta(0, "二")))
        runCurrent()
        assertEquals("窗口未到不提交", "", rig.assistant().text)

        advanceTimeBy(DELTA_DEBOUNCE_MS)
        runCurrent()
        assertEquals("一个窗口一次提交", "一二", rig.assistant().text)

        // 非 delta 事件先到 = 立刻冲刷（否则 text_end 的定稿会跑到未提交文字前面）
        rig.engine.emit(update(AssistantMessageEvent.TextDelta(0, "三")))
        runCurrent()
        assertEquals("窗口未到的三条仍在上一次的值上", "一二", rig.assistant().text)
        rig.engine.emit(update(AssistantMessageEvent.TextEnd(0, "ignored")))
        runCurrent()
        assertEquals("冲刷先于定稿", "一二三", rig.assistant().text)
        assertTrue(rig.assistant().settled)

        // 「一个窗口一次提交」的可观测证据：正文只提交过两次（不是三条 delta 各提交一次）
        val committed = emissions
            .mapNotNull { it.filterIsInstance<TranscriptItem.AssistantItem>().lastOrNull()?.text }
            .filter { it.isNotEmpty() }
            .distinct()
        assertEquals(listOf("一二", "一二三"), committed)
    }

    // ── 用例 21：§4.4 外部（sessions 屏）已切完 → 本页只清场、绝不重发命令 ─────────

    /**
     * 钉三件事：
     * 1. **未 Ready 时惰性**：安装门/失败态下清场会把「运行时未安装」洗成一条占位行，
     *    用户就再也按不到安装按钮了 —— 必须原样不动；
     * 2. **不发命令**：`switch_session` 已由 `:feature:sessions` 发过，这里再发就是同一目标切两遍；
     * 3. **不占 `switching` 闩**：外部滚完之后，本页自己的 `switchSession` 仍要能发出去。
     */
    @Test
    fun `case21 an external session roll clears without sending and is inert before Ready`() = runTest {
        // ① NotInstalled：一行都不许动
        val rig = rig()
        assertEquals("装配后的初态就是安装门", ChatUiState.NotInstalled, rig.vm.uiState.value)
        rig.vm.onExternalSessionRoll()
        runCurrent()
        assertTrue(rig.vm.transcript.value.isEmpty())
        assertNull(rig.vm.notice.value)
        assertEquals(0, rig.engine.sent.size)

        // ② Ready：清场 + 挂占位，零命令
        ready(rig)
        rig.engine.emit(PiEvent.MessageStart(PiMessage(role = "user", content = textBlocks("上一条会话的问题"))))
        runCurrent()
        rig.engine.emit(update(AssistantMessageEvent.TextStart(0)))
        rig.engine.emit(update(AssistantMessageEvent.TextDelta(0, "写了一半")))
        runCurrent()
        assertEquals("草稿还压在窗口里", "", rig.assistant().text)

        rig.vm.onExternalSessionRoll()
        runCurrent()

        assertEquals("外部滚不发任何 RPC", 0, rig.engine.sent.size)
        val rolled = rig.vm.transcript.value
        assertEquals("上一条的内容整条换掉，只剩占位", 1, rolled.size)
        val note = rolled.single()
        assertTrue("占位必须是 NoteItem，实为 $note", note is TranscriptItem.NoteItem)
        assertEquals(SWITCHING_NOTE_TEXT, (note as TranscriptItem.NoteItem).text)
        assertEquals(TurnState.Idle, rig.vm.turnState.value)
        assertEquals(0, rig.vm.queueDepth.value)
        assertNull(rig.vm.commandResult.value)
        assertNull("成功路径不该留提示", rig.vm.notice.value)

        // ③ 闩没被吃掉：本页自己发起的切换照常发得出去（而且恰好一条）
        val path = "/files/home/.pi/agent/sessions/--p--/b.jsonl"
        rig.vm.switchSession(path)
        runCurrent()
        assertEquals(listOf(RpcCommand.SwitchSession(path)), rig.engine.sent.map { it.command })
    }

    private companion object {
        fun update(event: AssistantMessageEvent): PiEvent =
            PiEvent.MessageUpdate(assistantMessageEvent = event)

        fun textBlocks(vararg texts: String): JsonElement =
            JsonArray(texts.map { buildJsonObject { put("type", "text"); put("text", it) } })

        fun uiRequest(
            id: String,
            method: String,
            title: String? = null,
            message: String? = null,
            timeout: Long? = null,
        ): PiEvent = PiEvent.ExtensionUiRequest(
            id = id,
            method = method,
            title = title ?: "确认执行",
            message = message,
            timeout = timeout,
        )
    }
}
