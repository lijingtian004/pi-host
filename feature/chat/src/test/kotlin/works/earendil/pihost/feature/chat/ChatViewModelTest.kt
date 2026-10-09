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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
import works.earendil.pihost.core.engine.audit.AuditEntry
import works.earendil.pihost.core.engine.audit.AuditKind
import works.earendil.pihost.core.engine.audit.AuditSink
import works.earendil.pihost.core.engine.audit.FileAuditSink
import works.earendil.pihost.core.engine.audit.NoopAuditSink
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
        /** P3A §3.2：审计假体（默认 [NoopAuditSink] = 既有用例一字不改）。 */
        val audit: AuditSink,
    )

    private fun TestScope.rig(
        engine: FakeChatEngine = FakeChatEngine(),
        installer: FakeRuntimeInstall = FakeRuntimeInstall(),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        audit: AuditSink = NoopAuditSink,
    ): Rig {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val app = RuntimeEnvironment.getApplication()
        // markwon 走真实装配（buildMarkwon 与 Hilt 的 @Singleton 同一配方）：VM 只转交它，
        // 但构造点必须与 production 一致，否则「渲染层换实现」这类回归在这里测不出来。
        return Rig(
            app,
            ChatViewModel(app, engine, installer, settings, buildMarkwon(app), audit),
            engine,
            installer,
            settings,
            audit,
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

    /**
     * 走一整圈「发命令 → pi 晚到的回执 → VM 处理回执」。
     *
     * 中间那次 [FakeChatEngine.deliverScripted] 是必须的：真机的 `response` 一定晚于 `send` 返回
     * （pi 在另一条线程读管道），假体把这条时序如实搬过来（细节见 FakeChatEngine.scriptedOutbox 的 KDoc），
     * 所以脚本回执要由测试在这一刻投进总线。投的过程中如果 VM 又发了命令
     * （切换成功 → 立刻回填），那条回执也在同一次调用里接着投出去——一条因果链一次走完。
     */
    private fun TestScope.pumpScripted(rig: Rig) {
        runCurrent()
        rig.engine.deliverScripted()
        runCurrent()
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
        assertEquals(0, rig.vm.queueState.value.depth)
        assertEquals(SessionState.Ready, rig.vm.engineStatus.value)

        rig.engine.emit(PiEvent.QueueUpdate(steering = listOf("a", "b"), followUp = listOf("c")))
        runCurrent()
        assertEquals("depth = steering + followUp", 3, rig.vm.queueState.value.depth)
        rig.engine.emit(PiEvent.QueueUpdate(steering = emptyList(), followUp = emptyList()))
        runCurrent()
        assertEquals(0, rig.vm.queueState.value.depth)

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
     * 2. **不代发 `switch_session`**：切换命令已由 `:feature:sessions` 发过，这里再发就是同一目标切两遍。
     *    P3B §3.1 之后本页**会**补一条只读 `get_messages`（回填历史），所以判据收紧成
     *    「外部滚只发回填这一条、且发的是 GetMessages」——列表页的命令仍然一条都不多；
     * 3. **不占 `switching` 闩**：外部滚完之后，本页自己的 `switchSession` 仍要能发出去。
     */
    @Test
    fun `case21 an external session roll clears without sending and is inert before Ready`() = runTest {
        // switch_session 给了回执：③ 那段要走「切换成功 → 清场 → 再回填一条」的完整路径
        val engine = FakeChatEngine().scriptReply("switch_session")
        val rig = rig(engine = engine)
        // ① NotInstalled：一行都不许动
        assertEquals("装配后的初态就是安装门", ChatUiState.NotInstalled, rig.vm.uiState.value)
        rig.vm.onExternalSessionRoll()
        runCurrent()
        assertTrue(rig.vm.transcript.value.isEmpty())
        assertNull(rig.vm.notice.value)
        assertEquals(0, rig.engine.sent.size)

        // ② Ready：清场 + 挂占位 + 一条只读回填
        ready(rig)
        rig.engine.emit(PiEvent.MessageStart(PiMessage(role = "user", content = textBlocks("上一条会话的问题"))))
        runCurrent()
        rig.engine.emit(update(AssistantMessageEvent.TextStart(0)))
        rig.engine.emit(update(AssistantMessageEvent.TextDelta(0, "写了一半")))
        runCurrent()
        assertEquals("草稿还压在窗口里", "", rig.assistant().text)

        rig.vm.onExternalSessionRoll()
        runCurrent()

        assertEquals(
            "外部滚只补一条只读回填，切换命令归列表页",
            listOf(RpcCommand.GetMessages),
            rig.engine.sent.map { it.command },
        )
        val rolled = rig.vm.transcript.value
        assertEquals("上一条的内容整条换掉，只剩占位", 1, rolled.size)
        val note = rolled.single()
        assertTrue("占位必须是 NoteItem，实为 $note", note is TranscriptItem.NoteItem)
        assertEquals(SWITCHING_NOTE_TEXT, (note as TranscriptItem.NoteItem).text)
        assertEquals(TurnState.Idle, rig.vm.turnState.value)
        assertEquals(0, rig.vm.queueState.value.depth)
        assertNull(rig.vm.commandResult.value)
        assertNull("成功路径不该留提示", rig.vm.notice.value)

        // ③ 闩没被吃掉：本页自己发起的切换照常发得出去，且切换成功后照例再补一条回填
        val path = "/files/home/.pi/agent/sessions/--p--/b.jsonl"
        rig.vm.switchSession(path)
        pumpScripted(rig)
        assertEquals(
            "回填（外部滚）→ 切换 → 回填（切换成功）：一条不多一条不少",
            listOf(RpcCommand.GetMessages, RpcCommand.SwitchSession(path), RpcCommand.GetMessages),
            rig.engine.sent.map { it.command },
        )
    }

    // ── 用例 22（P2F §4.4）：spawn cause 含 error=13 → Failed.cause 追加 EACCES 提示 ──

    @Test
    fun `case22 an errno 13 spawn failure is humanized into the Failed cause`() = runTest {
        val engine = FakeChatEngine().apply {
            startState = SessionState.Failed(
                "spawn 失败：Cannot run program \"/data/user/0/works.earendil.pihost/lib/libpi_node.so\": " +
                    "error=13, Permission denied",
            )
            stderr = "failed to exec: errno 13"
        }
        val rig = rig(engine = engine)

        rig.vm.installAndStart()
        runCurrent()
        val failed = rig.vm.uiState.value as ChatUiState.Failed

        assertTrue("必须保留原始 errno 线索：${failed.cause}", failed.cause.contains("error=13"))
        assertTrue("要出现 errno 13 的人话提示：${failed.cause}", failed.cause.contains("errno 13"))
        assertTrue("要说明降级已自动试过：${failed.cause}", failed.cause.contains("linker64"))
        assertTrue("stderr 尾巴不能被挤掉：${failed.cause}", failed.cause.contains("failed to exec"))
    }

    // ── 用例 23（P2F §4.4）：error=2 → ENOENT 提示；无 errno 的文案原样直通 ──────────

    @Test
    fun `case23 errno 2 gets the ENOENT hint and errno-less causes stay untouched`() = runTest {
        val enoent = FakeChatEngine().apply {
            startState = SessionState.Failed(
                "spawn 失败：Cannot run program \"/data/user/0/works.earendil.pihost/lib/libpi_node.so\": " +
                    "error=2, No such file or directory",
            )
        }
        val first = rig(engine = enoent)
        first.vm.installAndStart()
        runCurrent()
        val failed = first.vm.uiState.value as ChatUiState.Failed
        assertTrue("要出现 ENOENT 提示：${failed.cause}", failed.cause.contains("ENOENT"))
        assertTrue("提示要同时点到两种可能（exe / 工作目录）：${failed.cause}", failed.cause.contains("工作目录"))

        // 反例：不含 `error=N` 的失败（如引擎抛普通异常）一个字都不许多
        val plain = FakeChatEngine().apply { startError = IllegalStateException("引擎起不来") }
        val second = rig(engine = plain)
        second.vm.installAndStart()
        runCurrent()
        val untouched = second.vm.uiState.value as ChatUiState.Failed
        assertFalse("无 errno 不得追加提示：${untouched.cause}", untouched.cause.contains("errno"))
    }

    // ── 用例 24–31：P3A §3.2 审计三挂点 ────────────────────────────────────────

    @Test
    fun `case24 tool start writes one entry with args verbatim and the engine working dir`() = runTest {
        val audit = FakeAuditSink()
        val rig = rig(audit = audit)
        val session = ready(rig)

        rig.engine.emit(
            PiEvent.ToolExecutionStart(
                toolCallId = "call-9",
                toolName = "bash",
                args = buildJsonObject {
                    put("command", "git status --porcelain")
                    put("timeout", 30)
                },
            ),
        )
        runCurrent()

        val entry = audit.entries.single()
        assertEquals(AuditKind.TOOL_START, entry.kind)
        assertEquals("call-9", entry.toolCallId)
        assertEquals("bash", entry.toolName)
        // 「原样序列化」= JsonElement.toString()，不解析、不重排、不加空格
        assertEquals("""{"command":"git status --porcelain","timeout":30}""", entry.argsJson)
        assertNull("start 阶段没有成败概念，isError 必须是 null 而不是 false", entry.isError)
        assertNull("start 阶段没有结果", entry.resultPreview)
        assertEquals(session, entry.sessionId)
        assertTrue(
            "cwd 取的是生产装配喂给 SessionSupervisor 的同一条 projectDir：${entry.cwd}",
            entry.cwd.endsWith("/files/projects/default"),
        )
        assertTrue("时间戳得是个当下值", entry.ts > 0)
    }

    @Test
    fun `case25 tool end writes isError plus a preview clamped to 4096 characters`() = runTest {
        val audit = FakeAuditSink()
        val rig = rig(audit = audit)
        ready(rig)

        rig.engine.emit(
            PiEvent.ToolExecutionEnd(
                toolCallId = "call-9",
                toolName = "bash",
                isError = true,
                result = buildJsonObject { put("content", "E".repeat(FileAuditSink.RESULT_PREVIEW_MAX + 700)) },
            ),
        )
        runCurrent()

        val entry = audit.entries.single()
        assertEquals(AuditKind.TOOL_END, entry.kind)
        assertEquals(true, entry.isError)
        assertEquals(FileAuditSink.RESULT_PREVIEW_MAX, entry.resultPreview?.length)
        assertEquals("argsJson 在 end 上留空（args 只在 start 出现一次）", "", entry.argsJson)

        // 短结果原样进 preview，不做任何加工
        val shortAudit = FakeAuditSink()
        val second = rig(audit = shortAudit)
        ready(second)
        shortAudit.entries.clear()
        second.engine.emit(
            PiEvent.ToolExecutionEnd(
                toolCallId = "call-s", toolName = "read", isError = false,
                result = buildJsonObject { put("content", "一行小结果") },
            ),
        )
        runCurrent()
        val tail = shortAudit.entries.single()
        assertEquals("一行小结果", tail.resultPreview)
        assertEquals(false, tail.isError)
    }

    @Test
    fun `case26 allow once and allow session both audit as confirmed exactly once`() = runTest {
        val audit = FakeAuditSink()
        val rig = rig(audit = audit)
        ready(rig)

        rig.engine.emit(uiRequest(id = "p1", method = "confirm", timeout = 5_000L))
        runCurrent()
        rig.vm.answerPermission(allowOnce = true)
        runCurrent()

        val once = audit.all(AuditKind.PERMISSION).single()
        assertEquals("p1", once.toolCallId)
        assertEquals("permission", once.toolName)
        assertEquals("confirmed", once.argsJson)

        // 「本会话」= 线协议同形（§13 偏差 3），审计措辞也同形，不造第四个词
        rig.engine.emit(uiRequest(id = "p2", method = "exec", timeout = 5_000L))
        runCurrent()
        rig.vm.answerPermission(allowSession = true)
        runCurrent()
        assertEquals(listOf("p1", "p2"), audit.all(AuditKind.PERMISSION).map { it.toolCallId })
        assertEquals(listOf("confirmed", "confirmed"), audit.all(AuditKind.PERMISSION).map { it.argsJson })

        // p3：method 已被记住 → 自动放行，也留一条 confirmed
        rig.engine.emit(uiRequest(id = "p3", method = "exec", timeout = 5_000L))
        runCurrent()
        assertEquals(3, audit.all(AuditKind.PERMISSION).size)
        assertEquals("confirmed", audit.all(AuditKind.PERMISSION).last().argsJson)
        assertEquals("自动放行不该弹框", null, rig.vm.permissionRequest.value)
    }

    @Test
    fun `case27 deny audits as cancelled and a racing TurnEnd backfills cancelled not timeout`() = runTest {
        val audit = FakeAuditSink()
        val rig = rig(audit = audit)
        ready(rig)

        rig.engine.emit(uiRequest(id = "d1", method = "confirm", timeout = 5_000L))
        runCurrent()
        rig.vm.answerPermission(deny = true)
        runCurrent()
        assertEquals(listOf("cancelled"), audit.all(AuditKind.PERMISSION).map { it.argsJson })

        // TurnEnd 抢跑（§5.2 兜底）：仍是 cancelled——「超时」这个词只属于倒计时到点
        rig.engine.emit(uiRequest(id = "d2", method = "confirm", timeout = 5_000L))
        runCurrent()
        rig.engine.emit(PiEvent.TurnEnd(message = PiMessage(role = "assistant")))
        runCurrent()
        assertEquals(
            listOf("cancelled", "cancelled"),
            audit.all(AuditKind.PERMISSION).map { it.argsJson },
        )
        assertEquals("兜底补发恰好一次", 1, audit.all(AuditKind.PERMISSION).count { it.toolCallId == "d2" })
    }

    @Test
    fun `case28 the expiring timer audits cancelled-timeout on the virtual clock`() = runTest {
        val audit = FakeAuditSink()
        val rig = rig(audit = audit)
        ready(rig)

        rig.engine.emit(uiRequest(id = "t1", method = "confirm", timeout = 2_000L))
        runCurrent()
        assertTrue(audit.all(AuditKind.PERMISSION).isEmpty())

        advanceTimeBy(2_001)
        runCurrent()

        val entry = audit.all(AuditKind.PERMISSION).single()
        assertEquals("t1", entry.toolCallId)
        assertEquals("cancelled-timeout", entry.argsJson)
        assertEquals("到点之后不再补第二条", 1, audit.all(AuditKind.PERMISSION).size)
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(1, audit.all(AuditKind.PERMISSION).size)
    }

    @Test
    fun `case29 auditing does not disturb the conversation state machine`() = runTest {
        val audit = FakeAuditSink()
        val rig = rig(audit = audit)
        ready(rig)

        rig.vm.sendPrompt("跑个 git")
        runCurrent()
        assertEquals(TurnState.Streaming, rig.vm.turnState.value)
        rig.engine.emit(PiEvent.ToolExecutionStart(toolCallId = "c1", toolName = "bash", args = buildJsonObject { put("command", "git log") }))
        rig.engine.emit(
            PiEvent.ToolExecutionEnd(
                toolCallId = "c1", toolName = "bash", isError = false,
                result = buildJsonObject { put("content", "commit abc") },
            ),
        )
        rig.engine.emit(PiEvent.TurnEnd(message = PiMessage(role = "assistant")))
        runCurrent()

        // 审计条目收了 3 条（start/end + 无权限），状态机与转录该怎样还怎样
        assertEquals(2, audit.entries.size)
        assertEquals(TurnState.Idle, rig.vm.turnState.value)
        assertEquals(ToolStatus.Ok, rig.tool("c1").status)
        assertEquals("commit abc", rig.tool("c1").output)
        assertEquals(1, rig.engine.sent.count { it.command is RpcCommand.Prompt })
        assertNull(rig.vm.permissionRequest.value)
    }

    @Test
    fun `case30 a sink that throws RuntimeException does not take the conversation down`() = runTest {
        // 红线用例：AuditSink 契约是「必须不抛」，这里**故意违约**，验 VM 那道单点闸真的挡住
        val audit = FakeAuditSink().apply { boom = RuntimeException("假体违约：模拟第三方 sink 抛异常") }
        val rig = rig(audit = audit)
        val session = ready(rig)

        rig.engine.emit(
            PiEvent.ToolExecutionStart(
                toolCallId = "boom-1", toolName = "bash", args = buildJsonObject { put("command", "ls") },
            ),
        )
        runCurrent()

        // ① 事件收集协程还活着：同一条总线上的后续事件照样进得来
        rig.engine.emit(
            PiEvent.ToolExecutionEnd(
                toolCallId = "boom-1", toolName = "bash", isError = false,
                result = buildJsonObject { put("content", "ok") },
            ),
        )
        runCurrent()
        assertEquals("违约之后工具卡仍按序定稿", ToolStatus.Ok, rig.tool("boom-1").status)

        // ② 用户动作路径同样不受牵连：权限应答继续发到线协议
        rig.engine.emit(uiRequest(id = "boom-2", method = "confirm", timeout = 5_000L))
        runCurrent()
        rig.vm.answerPermission(allowOnce = true)
        runCurrent()
        assertEquals(1, rig.engine.uiResponses.count { it.id == "boom-2" })
        assertEquals(true, rig.engine.uiResponses.last().confirmed)

        // ③ VM 本体仍在 Ready，会话 id 没被洗掉
        assertEquals(ChatUiState.Ready(session), rig.vm.uiState.value)
        assertEquals(TurnState.Streaming, rig.vm.turnState.value)
    }

    @Test
    fun `case31 the default noop sink path stays harmless`() = runTest {
        // rig 不传 audit ⇒ 构造参数默认值 NoopAuditSink：既有用例的形状（23 条）全走这条路
        val rig = rig()
        val session = ready(rig)

        rig.engine.emit(PiEvent.ToolExecutionStart(toolCallId = "n1", toolName = "read", args = buildJsonObject { put("path", "a") }))
        rig.engine.emit(uiRequest(id = "n2", method = "confirm", timeout = 5_000L))
        runCurrent()
        rig.vm.answerPermission(deny = true)
        rig.engine.emit(PiEvent.TurnEnd(message = PiMessage(role = "assistant")))
        runCurrent()

        assertEquals(ChatUiState.Ready(session), rig.vm.uiState.value)
        assertEquals("只发了 start、没发 end，卡片当然停在 Running", ToolStatus.Running, rig.tool("n1").status)
        assertEquals(TurnState.Idle, rig.vm.turnState.value)
        assertEquals(1, rig.engine.uiResponses.size)
    }

    // ── 用例 32–39：P3B §3.1 转录回填（修「切回旧会话历史一片空白」的 P1）───────────

    /**
     * 回填的触发点：清场之后立刻一条 `get_messages`。
     *
     * 这一条钉的是**根因**：pi 的 switchSession 不重放任何历史事件，App 侧清完场没人补，
     * 所以「清场」与「问历史」必须绑在同一个函数里（`onSessionRolled`），
     * 少一次调用 = bug 原样复发；多发一次 = 每次切换都白读一遍盘。
     */
    @Test
    fun `case32 a successful switch immediately asks pi for the history of the new session`() = runTest {
        val engine = FakeChatEngine().scriptReply("switch_session")
        val rig = rig(engine = engine)
        ready(rig)

        rig.vm.switchSession("/files/home/.pi/agent/sessions/--p--/old.jsonl")
        pumpScripted(rig)

        val backfill = rig.engine.sent.last()
        assertEquals("清完场必须紧跟着问一次历史", RpcCommand.GetMessages, backfill.command)
        assertNotNull("回填也要带 requestId，否则认不出哪条回执属于它", backfill.id)
        assertEquals("一次切换恰好一次回填", 1, engine.sent.count { it.command == RpcCommand.GetMessages })
        assertEquals(
            "回执未定之前，屏幕上还是那条占位行",
            listOf(SWITCHING_NOTE_TEXT),
            rig.vm.transcript.value.filterIsInstance<TranscriptItem.NoteItem>().map { it.text },
        )
    }

    @Test
    fun `case33 backfilled history replaces the loading note in transcript order`() = runTest {
        val engine = FakeChatEngine()
            .scriptReply("switch_session")
            .scriptReply("get_messages", data = historyEnvelope())
        val audit = FakeAuditSink()
        val rig = rig(engine = engine, audit = audit)
        ready(rig)

        rig.vm.switchSession("/files/home/.pi/agent/sessions/--p--/old.jsonl")
        pumpScripted(rig)

        val items = rig.vm.transcript.value
        assertTrue(
            "历史到了就不该再挂着「会话切换中…」",
            items.none { it is TranscriptItem.NoteItem },
        )
        // 逐条比形状+正文，不硬编 id：占位行本身也要消耗一个 nextItemId()，
        // 把 id 写死等于把「回填前挂过几行占位」这一实现细节钉进断言。
        assertEquals(
            "system 不上屏，其余按线格式顺序落进转录",
            listOf(
                "UserItem" to "跑一下分支命令",
                "AssistantItem" to "已在 main 分支",
                "ToolItem" to "On branch main",
            ),
            items.map { item ->
                item::class.simpleName to when (item) {
                    is TranscriptItem.UserItem -> item.text
                    is TranscriptItem.AssistantItem -> item.text
                    is TranscriptItem.ToolItem -> item.output
                    else -> ""
                }
            },
        )
        val assistant = items[1] as TranscriptItem.AssistantItem
        assertTrue("回填的 assistant 是终态，后面的 delta 不许往历史里写", assistant.settled)
        val tool = items[2] as TranscriptItem.ToolItem
        assertEquals("call-1", tool.callId)
        assertEquals(ToolStatus.Ok, tool.status)
        assertEquals("回填不记审计：历史重放不是第二次执行", 0, audit.entries.size)
    }

    @Test
    fun `case34 a rejected get_messages leaves an empty page with a readable failure note`() = runTest {
        val engine = FakeChatEngine()
            .scriptReply("switch_session")
            .scriptReply("get_messages", success = false, error = "会话文件不存在")
        val rig = rig(engine = engine)
        ready(rig)

        rig.vm.switchSession("/files/home/.pi/agent/sessions/--p--/gone.jsonl")
        pumpScripted(rig)

        assertEquals(
            listOf(BACKFILL_FAILED_TEXT),
            rig.vm.transcript.value.filterIsInstance<TranscriptItem.NoteItem>().map { it.text },
        )
        assertEquals("除了回填这一条，什么都没往线路上发", 2, engine.sent.size)
    }

    @Test
    fun `case35 a response with an unknown shape is a failure, not a crash and not an empty history`() = runTest {
        val engine = FakeChatEngine()
            .scriptReply("switch_session")
            .scriptReply("get_messages", data = buildJsonObject { put("entries", JsonArray(emptyList())) })
        val rig = rig(engine = engine)
        ready(rig)

        rig.vm.switchSession("/files/home/.pi/agent/sessions/--p--/weird.jsonl")
        pumpScripted(rig)

        assertEquals(
            "形状不对要说「加载失败」，不能说成空历史（那是另一种真相）",
            listOf(BACKFILL_FAILED_TEXT),
            rig.vm.transcript.value.filterIsInstance<TranscriptItem.NoteItem>().map { it.text },
        )
        assertEquals(TurnState.Idle, rig.vm.turnState.value)
    }

    @Test
    fun `case36 a backfill racing a fresh prompt appends history instead of overwriting it`() = runTest {
        val engine = FakeChatEngine().scriptReply("switch_session")
        val rig = rig(engine = engine)
        ready(rig)

        rig.vm.switchSession("/files/home/.pi/agent/sessions/--p--/old.jsonl")
        pumpScripted(rig)
        val backfillId = engine.sent.last().id

        // 用户没等历史就打字发出去了（真机上这是最常见的动作）
        rig.vm.sendPrompt("新问题不等历史")
        runCurrent()
        rig.engine.emit(update(AssistantMessageEvent.TextStart(0)))
        rig.engine.emit(update(AssistantMessageEvent.TextDelta(0, "正在流式的一半")))
        runCurrent()

        rig.engine.emit(
            PiEvent.Response(id = backfillId, command = "get_messages", success = true, data = historyEnvelope()),
        )
        runCurrent()

        val texts = rig.vm.transcript.value.map {
            when (it) {
                is TranscriptItem.UserItem -> it.text
                is TranscriptItem.AssistantItem -> it.text
                else -> "«${it::class.simpleName}»"
            }
        }
        assertEquals(
            "刚发的字与正在流式的一行都不许被历史抹掉（§3.1 的 append 语义）",
            listOf("新问题不等历史", "正在流式的一半", "跑一下分支命令", "已在 main 分支", "«ToolItem»"),
            texts,
        )
        assertEquals(TurnState.Streaming, rig.vm.turnState.value)
    }

    /**
     * 真机上那条 P1 的**主路径**：从 sessions 屏点行进来，命令是列表页发的，
     * 本页只清场（`onExternalSessionRoll`），所以回填也必须挂在这条路上——
     * 只在 [ChatViewModel.rollSession] 里补历史等于没修。
     */
    @Test
    fun `case37 an external roll backfills without sending a second switch_session`() = runTest {
        val engine = FakeChatEngine().scriptReply("get_messages", data = historyEnvelope())
        val rig = rig(engine = engine)
        ready(rig)

        rig.vm.onExternalSessionRoll()
        pumpScripted(rig)

        assertEquals(
            "本页只发一条只读回填，切换命令归列表页",
            listOf(RpcCommand.GetMessages),
            engine.sent.map { it.command },
        )
        assertEquals(3, rig.vm.transcript.value.size)
        assertTrue(rig.vm.transcript.value.none { it is TranscriptItem.NoteItem })
    }

    @Test
    fun `case38 a brand new session answers zero messages and the page stays clean`() = runTest {
        val engine = FakeChatEngine()
            .scriptReply("new_session")
            .scriptReply("get_messages", data = buildJsonObject { put("messages", buildJsonArray { }) })
        val rig = rig(engine = engine)
        ready(rig)

        rig.vm.newSession()
        pumpScripted(rig)

        assertTrue("空历史是合法答案：不留占位行、也不报「加载失败」", rig.vm.transcript.value.isEmpty())
    }

    /**
     * 连切两条会话：上一条 `get_messages` 的回执晚到，**绝不许**把旧会话的历史
     * 糊到新会话的转录上（回填只认最新那个 requestId）。
     */
    @Test
    fun `case39 a late backfill from the previous session is ignored`() = runTest {
        val engine = FakeChatEngine().scriptReply("switch_session").scriptReply("switch_session")
        val rig = rig(engine = engine)
        ready(rig)

        rig.vm.switchSession("/files/home/.pi/agent/sessions/--p--/first.jsonl")
        pumpScripted(rig)
        val staleId = engine.sent.last().id // 第一条 get_messages 的 requestId

        rig.vm.switchSession("/files/home/.pi/agent/sessions/--p--/second.jsonl")
        pumpScripted(rig)
        val freshId = engine.sent.last().id

        engine.emit(
            PiEvent.Response(
                id = staleId, command = "get_messages", success = true,
                data = buildJsonObject {
                    put("messages", buildJsonArray { add(messageEnvelope("user", "旧会话的历史")) })
                },
            ),
        )
        runCurrent()
        assertTrue("晚到的旧回执一条都不许上屏", rig.vm.transcript.value.none { it is TranscriptItem.UserItem })

        engine.emit(
            PiEvent.Response(
                id = freshId, command = "get_messages", success = true,
                data = buildJsonObject {
                    put("messages", buildJsonArray { add(messageEnvelope("user", "这一条才是当前的")) })
                },
            ),
        )
        runCurrent()
        assertEquals(listOf("这一条才是当前的"), rig.vm.transcript.value.filterIsInstance<TranscriptItem.UserItem>().map { it.text })
    }

    // ── 用例 40–48：P3B §3.2 流式插话（steer / follow_up / 队列可视化）─────────────

    /**
     * 插话的出站形状 + 三条「不许做」：不改 turnState、不上用户气泡、不占切换闩。
     *
     * 这一条同时钉住 §2 红线 8 的实现边界：VM 里只有「把用户打好的字发出去」这一个动作，
     * 没有任何一处看到事件就自动 steer 的路径。
     */
    @Test
    fun `case40 steer sends the user text verbatim and leaves the turn state alone`() = runTest {
        val rig = rig()
        ready(rig)
        rig.vm.sendPrompt("先跑个构建")
        runCurrent()
        assertEquals(TurnState.Streaming, rig.vm.turnState.value)
        val before = rig.vm.transcript.value

        rig.vm.steer("改成跑 test 就行")
        runCurrent()

        assertEquals(
            "插话必须带用户原话，一个字都不许改",
            RpcCommand.Steer("改成跑 test 就行"),
            rig.engine.sent.last().command,
        )
        assertEquals("插话不新开 turnState 档", TurnState.Streaming, rig.vm.turnState.value)
        assertEquals("插话不上用户气泡（内容归 queue_update 说）", before, rig.vm.transcript.value)
        assertEquals("插话恰好一条", 1, rig.engine.sent.count { it.command is RpcCommand.Steer })
    }

    @Test
    fun `case41 followUp targets the follow_up queue`() = runTest {
        val rig = rig()
        ready(rig)

        rig.vm.followUp("这轮完了再看覆盖率")
        runCurrent()

        assertEquals(RpcCommand.FollowUp("这轮完了再看覆盖率"), rig.engine.sent.last().command)
        assertEquals("追问也不是新问题：turnState 停在 Idle", TurnState.Idle, rig.vm.turnState.value)
        assertTrue(rig.vm.transcript.value.isEmpty())
    }

    @Test
    fun `case42 blank interjections are dropped and an unready engine says why`() = runTest {
        // ① 未 Ready：只给提示，一条都不发
        val cold = rig()
        cold.vm.steer("引擎还没起来")
        cold.vm.followUp("同上")
        runCurrent()
        assertEquals("未就绪不许碰线路", 0, cold.engine.sent.size)
        assertTrue(
            "两次失败只留最后一条提示，但必须说清是同一个原因：${cold.vm.notice.value}",
            cold.vm.notice.value?.contains("引擎尚未就绪") == true,
        )

        // ② Ready 之后空文本：静不吃（与 sendPrompt 的 isBlank 同一判据）
        val rig = rig()
        ready(rig)
        rig.vm.steer("   ")
        rig.vm.followUp("")
        runCurrent()
        assertEquals("空白插话一条都不许发", 0, rig.engine.sent.size)
        assertNull("空白不是故障，不该留提示", rig.vm.notice.value)
    }

    /**
     * `queue_update` → [QueueState]：两列各表各的，且**整表替换**。
     *
     * 钉「整表替换」的理由：pi 每次入队都发全量（protocol.md §4.1「内容=当前队列」），
     * 本地做增量的话，一条漏收的事件就会让徽标永久漂着一条不存在的内容。
     */
    @Test
    fun `case43 queue_update fills both columns and replaces them wholesale`() = runTest {
        val rig = rig()
        ready(rig)
        assertEquals(QueueState.EMPTY, rig.vm.queueState.value)

        rig.engine.emit(PiEvent.QueueUpdate(steering = listOf("改成跑 test", "别碰 main"), followUp = listOf("再看覆盖率")))
        runCurrent()
        val first = rig.vm.queueState.value
        assertEquals(listOf("改成跑 test", "别碰 main"), first.steering)
        assertEquals(listOf("再看覆盖率"), first.followUp)
        assertEquals("深度是派生值，不是第二个真相源", 3, first.depth)

        rig.engine.emit(PiEvent.QueueUpdate(steering = emptyList(), followUp = emptyList()))
        runCurrent()
        assertEquals("队列空了要整表换掉，不留上一条的残影", QueueState.EMPTY, rig.vm.queueState.value)
    }

    /**
     * `clear_queue` 只发命令；队列面貌仍然只由 `queue_update` 决定。
     *
     * 这一条钉的是「不许拿回执当队列真相」：`clear_queue` 的 response 是
     * `{steering:[],followUp:[]}`（§3.2：返回**清空后**剩余，可能已被消费），
     * 把它直接当队列写进状态，就会与紧随其后的 queue_update 撞上——
     * 两条都「对」，但界面上会出现一次闪烁的错值。
     */
    @Test
    fun `case44 clearQueue sends the command and lets queue_update own the picture`() = runTest {
        val engine = FakeChatEngine().scriptReply(
            "clear_queue",
            data = buildJsonObject {
                put("steering", JsonArray(emptyList()))
                put("followUp", JsonArray(emptyList()))
            },
        )
        val rig = rig(engine = engine)
        ready(rig)
        rig.engine.emit(PiEvent.QueueUpdate(steering = listOf("a"), followUp = listOf("b", "c")))
        runCurrent()

        rig.vm.clearQueue()
        pumpScripted(rig)

        assertEquals(RpcCommand.ClearQueue, rig.engine.sent.last().command)
        assertEquals(
            "回执不改队列状态：还是 queue_update 那三条",
            QueueState(listOf("a"), listOf("b", "c")),
            rig.vm.queueState.value,
        )
        assertNotNull("回执仍然进 commandResult（探针面板看得见）", rig.vm.commandResult.value)
        assertEquals("clear_queue", rig.vm.commandResult.value!!.command)

        rig.engine.emit(PiEvent.QueueUpdate(steering = emptyList(), followUp = emptyList()))
        runCurrent()
        assertEquals("pi 说空了才真空", QueueState.EMPTY, rig.vm.queueState.value)
    }

    @Test
    fun `case45 both mode switches carry the frozen wire values`() = runTest {
        val rig = rig()
        ready(rig)

        rig.vm.setSteeringMode(MODE_ALL)
        rig.vm.setFollowUpMode(MODE_ONE_AT_A_TIME)
        runCurrent()

        assertEquals(
            listOf(RpcCommand.SetSteeringMode("all"), RpcCommand.SetFollowUpMode("one-at-a-time")),
            rig.engine.sent.map { it.command },
        )
    }

    /**
     * 插话与正在流式的那一条**互不打扰**：steer 之后 delta 照常往同一个气泡里续。
     *
     * 这是 §3.2 最贴近真机使用的一条动作：构建跑到一半看到方向错了，插一句之后
     * 屏幕上那半截回答不能消失、不能重复、不能被换成队列内容。
     */
    @Test
    fun `case46 steering does not disturb the assistant that is still streaming`() = runTest {
        val engine = FakeChatEngine().scriptReply("steer", data = buildJsonObject { put("disposition", "queued") })
        val rig = rig(engine = engine)
        ready(rig)
        rig.vm.sendPrompt("跑长任务")
        runCurrent()
        engine.emit(update(AssistantMessageEvent.TextStart(0)))
        engine.emit(update(AssistantMessageEvent.TextDelta(0, "已经跑了一半")))
        runCurrent()

        rig.vm.steer("慢着，别动 main")
        pumpScripted(rig)
        engine.emit(update(AssistantMessageEvent.TextDelta(0, "，接着流")))
        advanceTimeBy(DELTA_DEBOUNCE_MS + 10)
        runCurrent()

        assertEquals("已经跑了一半，接着流", rig.assistant().text)
        assertEquals(TurnState.Streaming, rig.vm.turnState.value)
        assertEquals(
            "disposition 回执只进 commandResult，不上转录",
            "queued",
            (rig.vm.commandResult.value?.data?.get("disposition") as? JsonPrimitive)?.content,
        )
        assertTrue(
            "插话内容不冒充用户气泡",
            rig.vm.transcript.value.none { it is TranscriptItem.UserItem && it.text == "慢着，别动 main" },
        )
    }

    @Test
    fun `case47 a session roll empties the queue view`() = runTest {
        val rig = rig()
        ready(rig)
        rig.engine.emit(PiEvent.QueueUpdate(steering = listOf("留在旧会话的插话"), followUp = emptyList()))
        runCurrent()
        assertEquals(1, rig.vm.queueState.value.depth)

        rig.vm.onExternalSessionRoll()
        runCurrent()
        assertEquals("队列属于上一条会话，清场必须一起清", QueueState.EMPTY, rig.vm.queueState.value)
    }

    @Test
    fun `case48 a steering send failure surfaces as a failed command result`() = runTest {
        val rig = rig()
        ready(rig)
        rig.engine.sendError = IllegalStateException("没有活动的 pi 进程")

        rig.vm.steer("插不进去了")
        runCurrent()

        val failure = rig.vm.commandResult.value
        assertNotNull("发送失败必须看得见", failure)
        assertFalse(failure!!.success)
        assertEquals("线名要对得上（探针面板按它归类）", "steer", failure.command)
        assertEquals("失败不许把队列写出内容", QueueState.EMPTY, rig.vm.queueState.value)
    }

    // ── §3.3 运行时模型切换（case49 起，形状逐条照 REPORT-P3B §2 探测实录）─────────

    /**
     * 点 chip → 三条命令一起发（列表 + 思考档白名单 + 当前态）。
     *
     * 钉「三条」而不是「一条」：探测 p03 证明 `set_model` 的回执**不带** thinkingLevel，
     * 而换模型会重置档位（p02→p04：`["off"]` 变五档）。只发 `get_available_models`
     * 的选择器会在切完模型后摆着一列非法档位。
     */
    @Test
    fun `case49 refreshModels asks for the catalog the whitelist and the current state`() = runTest {
        val rig = rig()
        ready(rig)

        rig.vm.refreshModels()
        runCurrent()

        val wire = rig.engine.sent.map { wireName(it.command) }
        assertEquals(
            listOf("get_available_models", "get_available_thinking_levels", "get_state"),
            wire.takeLast(3),
        )
        assertEquals("首点之前一条都没发过（§3.3：不主动拉列表）", 3, wire.size)
        assertEquals("列表拉取不碰转录", emptyList<TranscriptItem>(), rig.vm.transcript.value)
    }

    @Test
    fun `case50 the probed catalog lands as two selectable models and the chip says so`() = runTest {
        val rig = rig()
        ready(rig)
        rig.engine
            .scriptReply("get_available_models", data = modelsEnvelope())
            .scriptReply("get_state", data = stateEnvelope("mock-1", "Mock Model 1", thinkingLevel = "off"))
        rig.vm.refreshModels()
        pumpScripted(rig)

        val state = rig.vm.modelPicker.value
        assertEquals(listOf("mock/mock-1", "mock/mock-2"), state.models.map { it.wireKey })
        assertTrue("拉到过就要置位，chip 才不会把「没拉」说成「没有」", state.fetched)
        assertEquals(
            "当前模型只能来自 get_state 的 model 键（列表里没有 current 标记）",
            "mock/mock-1",
            state.current?.wireKey,
        )
        assertEquals("Mock Model 1 · 思考 off", state.chipText())
    }

    @Test
    fun `case51 a shape-drift catalog keeps the chip honest and reports why`() = runTest {
        val rig = rig()
        ready(rig)
        rig.engine.scriptReply("get_available_models", data = buildJsonObject { put("model", "拼错了") })
        rig.vm.refreshModels()
        pumpScripted(rig)

        val state = rig.vm.modelPicker.value
        assertTrue("形状不对不能假装「拉到了空列表」", state.models.isEmpty())
        assertEquals("列表形状不对不该把 fetched 置位", CHIP_PICK, state.chipText())
        assertTrue("但必须说一句为什么：${rig.vm.notice.value}", rig.vm.notice.value?.contains("形状不对") == true)
    }

    @Test
    fun `case52 setModel sends both wire fields and adopts the bare model payload`() = runTest {
        val rig = rig()
        ready(rig)
        rig.engine
            .scriptReply("set_model", data = modelObject("mock-2", "Mock Model 2", reasoning = true, contextWindow = 200_000))
            .scriptReply("get_available_thinking_levels", data = levelsEnvelope("off", "minimal", "low", "medium", "high"))
            .scriptReply("get_state", data = stateEnvelope("mock-2", "Mock Model 2", thinkingLevel = "off"))

        rig.vm.setModel("mock", "mock-2")
        pumpScripted(rig)

        assertEquals(
            "provider 与 modelId 都是线字段，少一个 pi 就回 Model not found",
            RpcCommand.SetModel("mock", "mock-2"),
            rig.engine.sent.first { it.command is RpcCommand.SetModel }.command,
        )
        val state = rig.vm.modelPicker.value
        assertEquals("mock/mock-2", state.current?.wireKey)
        assertEquals(
            "换模型后必须重问白名单与当前态（探测 p03→p05）",
            listOf("off", "minimal", "low", "medium", "high"),
            state.thinkingLevels,
        )
        assertEquals("off", state.thinkingLevel)
    }

    @Test
    fun `case53 a rejected set_model leaves the chip on the model pi is still using`() = runTest {
        val rig = rig()
        ready(rig)
        rig.engine.scriptReply("get_state", data = stateEnvelope("mock-1", "Mock Model 1", thinkingLevel = "off"))
        rig.vm.refreshModels()
        pumpScripted(rig)
        val before = rig.vm.modelPicker.value

        rig.engine.scriptReply("set_model", success = false, error = "Model not found: mock/no-such-model")
        rig.vm.setModel("mock", "no-such-model")
        pumpScripted(rig)

        assertEquals("失败不许改高亮（探测 p08）", before.current, rig.vm.modelPicker.value.current)
        assertTrue("但要说清是哪一步失败：${rig.vm.notice.value}", rig.vm.notice.value?.contains("模型切换失败") == true)
        assertEquals(
            "失败之后不再追发刷新命令",
            1,
            rig.engine.sent.count { it.command == RpcCommand.GetAvailableThinkingLevels },
        )
    }

    @Test
    fun `case54 cycleModel adopts the wrapped model and its thinking level in one shot`() = runTest {
        val rig = rig()
        ready(rig)
        rig.engine
            .scriptReply("cycle_model", data = cycleEnvelope("mock-2", "Mock Model 2", thinkingLevel = "medium"))
            .scriptReply("get_available_thinking_levels", data = levelsEnvelope("off", "minimal", "low", "medium", "high"))

        rig.vm.cycleModel()
        pumpScripted(rig)

        val state = rig.vm.modelPicker.value
        assertEquals(RpcCommand.CycleModel, rig.engine.sent.first { it.command is RpcCommand.CycleModel }.command)
        assertEquals("mock/mock-2", state.current?.wireKey)
        assertEquals(
            "cycle_model 的回执自带 thinkingLevel（探测 p07），不必再发 get_state",
            "medium",
            state.thinkingLevel,
        )
        assertEquals("但白名单跟着模型走，仍要重问一次", 1, rig.engine.sent.count { it.command == RpcCommand.GetAvailableThinkingLevels })
    }

    @Test
    fun `case55 thinking level commands carry the whitelist value and a bare response changes nothing`() = runTest {
        val rig = rig()
        ready(rig)
        rig.engine.scriptReply("get_available_thinking_levels", data = levelsEnvelope("off", "high"))
        rig.vm.refreshThinkingLevels()
        pumpScripted(rig)
        assertEquals(listOf("off", "high"), rig.vm.modelPicker.value.thinkingLevels)

        // 探测 p09：对 reasoning:false 的模型设 "high" 也回 success，且**没有 data、没有事件**
        rig.engine.scriptReply("set_thinking_level")
        rig.vm.setThinkingLevel("high")
        pumpScripted(rig)
        assertNull("无回执载荷又没有事件时，chip 不许编一个档位出来", rig.vm.modelPicker.value.thinkingLevel)
        assertEquals(RpcCommand.SetThinkingLevel("high"), rig.engine.sent.last().command)

        // 探测 p06：cycle_thinking_level 回 {level}
        rig.engine.scriptReply("cycle_thinking_level", data = buildJsonObject { put("level", "high") })
        rig.vm.cycleThinkingLevel()
        pumpScripted(rig)
        assertEquals("high", rig.vm.modelPicker.value.thinkingLevel)
    }

    /**
     * §3.3 的 `model_change` 决策落点：模型切换**没有**运行时事件（探测里 set_model/cycle_model
     * 成功之后事件流一条都没有），所以 chip 只靠回执；而思考档**有**事件
     * （`thinking_level_changed`），它不在 protocol.md §4 的事件表里 → 冻结区给的是
     * [PiEvent.Unknown]，本页在 feature/chat 里认领（[ChatViewModel.onUnknownEvent]）。
     */
    @Test
    fun `case56 the thinking level event arrives through the unknown channel`() = runTest {
        val rig = rig()
        ready(rig)

        rig.engine.emit(
            PiEvent.Unknown(
                buildJsonObject {
                    put("type", "thinking_level_changed")
                    put("level", "medium")
                },
            ),
        )
        runCurrent()
        assertEquals("medium", rig.vm.modelPicker.value.thinkingLevel)

        rig.engine.emit(
            PiEvent.Unknown(
                buildJsonObject {
                    put("type", "some_future_event")
                    put("level", "不要认")
                },
            ),
        )
        runCurrent()
        assertEquals("未知类型一律忽略：猜不出语义的事件上屏比不上屏更糟", "medium", rig.vm.modelPicker.value.thinkingLevel)
    }

    @Test
    fun `case57 the probe panel get_state does not hijack the model chip`() = runTest {
        val rig = rig()
        ready(rig)
        rig.engine.scriptReply("get_state", data = stateEnvelope("mock-2", "Mock Model 2", thinkingLevel = "high"))

        rig.vm.probeState()
        pumpScripted(rig)

        assertEquals("探针那条 get_state 只进 commandResult", ModelPickerState(), rig.vm.modelPicker.value)
        assertEquals("get_state", rig.vm.commandResult.value?.command)
        assertTrue(rig.vm.commandResult.value!!.success)
    }

    @Test
    fun `case58 a session roll drops the previous session model snapshot`() = runTest {
        val rig = rig()
        ready(rig)
        rig.engine
            .scriptReply("get_available_models", data = modelsEnvelope())
            .scriptReply("get_available_thinking_levels", data = levelsEnvelope("off"))
            .scriptReply("get_state", data = stateEnvelope("mock-1", "Mock Model 1", thinkingLevel = "off"))
        rig.vm.refreshModels()
        pumpScripted(rig)
        assertEquals("Mock Model 1 · 思考 off", rig.vm.modelPicker.value.chipText())

        rig.engine.scriptReply("switch_session")
        rig.engine.scriptReply("get_messages", data = buildJsonObject { put("messages", JsonArray(emptyList())) })
        rig.vm.switchSession("/files/home/.pi/agent/sessions/--p--/other.jsonl")
        pumpScripted(rig)

        assertEquals("上一条会话的模型不是这一条的", ModelPickerState(), rig.vm.modelPicker.value)
        assertEquals(
            "清场不额外发命令（chip 等用户点）",
            1,
            rig.engine.sent.count { it.command is RpcCommand.SwitchSession },
        )
    }

    @Test
    fun `case59 an untracked response never touches the picker`() = runTest {
        val rig = rig()
        ready(rig)
        // 别的消费者（sessions 屏 / 扩展）发的命令，id 不在 VM 的账上
        rig.engine.emit(
            PiEvent.Response(
                id = "someone-else-1",
                command = "get_available_models",
                success = true,
                data = modelsEnvelope(),
            ),
        )
        runCurrent()
        assertEquals("只认自己发出去的 id（§5.1 同一条纪律）", ModelPickerState(), rig.vm.modelPicker.value)
    }

    @Test
    fun `case60 model commands before Ready say the engine is not up and send nothing`() = runTest {
        val rig = rig()
        rig.vm.refreshModels()
        rig.vm.setModel("mock", "mock-1")
        rig.vm.cycleModel()
        rig.vm.setThinkingLevel("high")
        runCurrent()
        assertEquals(0, rig.engine.sent.size)
        assertTrue("提示要说清原因：${rig.vm.notice.value}", rig.vm.notice.value?.contains("引擎尚未就绪") == true)
    }

    // ── §3.4 会话统计条（P3B）───────────────────────────────────────────────────────

    /**
     * `refreshStats()` → 一条 `get_session_stats`，回执落进统计面。
     *
     * 「统计未加载」这句必须在第一次成功回执之前就在：§3.4 的验收看的是「有没有那一行」，
     * 而一行 0 tok / 0% 会让用户以为会话真的没花钱。
     */
    @Test
    fun `case61 refreshStats asks once and lands the probed payload`() = runTest {
        val rig = rig()
        ready(rig)
        assertEquals("统计未加载", rig.vm.stats.value.barText())
        assertNull(rig.vm.stats.value.stats)

        rig.engine.scriptReply("get_session_stats", data = probeStatsData())
        rig.vm.refreshStats()
        pumpScripted(rig)

        assertEquals("get_session_stats", wireName(rig.engine.sent.last().command))
        assertEquals(
            "240 tok｜${money(0.0)}｜120/128000 (0.09%)",
            rig.vm.stats.value.barText(),
        )
        assertEquals(5, rig.vm.stats.value.stats?.totalMessages)
    }

    @Test
    fun `case62 a malformed stats reply keeps the previous numbers and says why`() = runTest {
        val rig = rig()
        ready(rig)
        rig.engine.scriptReply("get_session_stats", data = probeStatsData())
        rig.vm.refreshStats()
        pumpScripted(rig)
        val first = rig.vm.stats.value.stats
        assertNotNull(first)

        rig.engine.scriptReply("get_session_stats", data = buildJsonObject { put("tokens", "不是对象") })
        rig.vm.refreshStats()
        pumpScripted(rig)

        assertEquals("形状不对就留着上一版：统计条宁可旧，不可假", first, rig.vm.stats.value.stats)
        assertTrue("但要有一句为什么：${rig.vm.notice.value}", rig.vm.notice.value?.contains("形状不对") == true)
    }

    /**
     * 自动刷新的触发点：规格书写「TurnEnd **或** AgentSettled」，这里取 AgentSettled。
     *
     * 探测实录里一次运行有 2 条 `turn_end` 而只有 1 条 `agent_settled`——挂在 TurnEnd 上
     * 等于每轮多问一次同一个数；pi 说「settled」的时候这一轮的 token 才算真花完。
     */
    @Test
    fun `case63 agent_settled refreshes stats exactly once while turn_end does not`() = runTest {
        val rig = rig()
        ready(rig)
        val statsWire = "get_session_stats"

        rig.engine.emit(PiEvent.TurnEnd(message = PiMessage(role = "assistant", content = textBlocks("一轮完了"))))
        runCurrent()
        rig.engine.emit(PiEvent.TurnEnd(message = PiMessage(role = "assistant", content = textBlocks("又一轮"))))
        runCurrent()
        assertEquals("TurnEnd 不问统计", 0, rig.engine.sent.count { wireName(it.command) == statsWire })

        rig.engine.emit(PiEvent.AgentSettled)
        runCurrent()
        assertEquals(1, rig.engine.sent.count { wireName(it.command) == statsWire })
    }

    /**
     * `message_update.usage` 是**累计快照**，不是增量。
     *
     * 探测：一条回答吐了 21 次 message_update，21 次的 usage 一模一样（`{100,20,…,120}`）。
     * 逐条相加会显示 2520 tok——这条用例就是钉住那个错误的。
     */
    @Test
    fun `case64 streaming usage replaces the live number instead of summing snapshots`() = runTest {
        val rig = rig()
        ready(rig)

        repeat(3) {
            rig.engine.emit(
                PiEvent.MessageUpdate(
                    usage = probeUsageData(),
                    assistantMessageEvent = AssistantMessageEvent.TextDelta(0, "正文"),
                ),
            )
        }
        runCurrent()

        assertEquals(120L, rig.vm.stats.value.live.totalTokens)
        assertEquals(100L, rig.vm.stats.value.live.input)
        assertEquals("本轮 120 tok｜${money(0.0)}", rig.vm.stats.value.liveText())
    }

    /** 定稿的消息进「本轮之和」，同时把 in-flight 那份快照丢掉（同一条消息不许算两遍）。 */
    @Test
    fun `case65 a finalized message joins the sum and drops the in-flight snapshot`() = runTest {
        val rig = rig()
        ready(rig)

        rig.engine.emit(PiEvent.MessageUpdate(usage = probeUsageData(), assistantMessageEvent = null))
        runCurrent()
        assertEquals(120L, rig.vm.stats.value.live.totalTokens)

        rig.engine.emit(PiEvent.MessageEnd(PiMessage(role = "assistant", usage = probeUsageData())))
        runCurrent()
        assertEquals("定稿之后还是 120，不是 240", 120L, rig.vm.stats.value.live.totalTokens)

        // 第二条消息（工具回路里 pi 会连发多条 assistant）
        rig.engine.emit(PiEvent.MessageUpdate(usage = probeUsageData(totalTokens = 60), assistantMessageEvent = null))
        runCurrent()
        assertEquals(180L, rig.vm.stats.value.live.totalTokens)

        rig.engine.emit(
            PiEvent.MessageEnd(
                PiMessage(role = "assistant", usage = probeUsageData(totalTokens = 60)),
            ),
        )
        runCurrent()
        assertEquals(180L, rig.vm.stats.value.live.totalTokens)

        // 非 assistant 的消息（toolResult/user）没有 usage，不进和
        rig.engine.emit(PiEvent.MessageEnd(PiMessage(role = "toolResult", content = textBlocks("输出"))))
        runCurrent()
        assertEquals(180L, rig.vm.stats.value.live.totalTokens)
    }

    /** `agent_start` 是一次新回答的边界：上一条的 token 不能挂在这一条上。 */
    @Test
    fun `case66 agent_start resets the live usage of the previous run`() = runTest {
        val rig = rig()
        ready(rig)
        rig.engine.scriptReply("get_session_stats", data = probeStatsData())
        rig.vm.refreshStats()
        pumpScripted(rig)
        rig.engine.emit(PiEvent.MessageEnd(PiMessage(role = "assistant", usage = probeUsageData())))
        runCurrent()
        assertEquals(120L, rig.vm.stats.value.live.totalTokens)

        rig.engine.emit(PiEvent.AgentStart)
        runCurrent()
        assertEquals(TurnUsage.EMPTY, rig.vm.stats.value.live)
        assertEquals(
            "清的是本轮之和，已经拿到的会话快照一个键都不动",
            240L,
            rig.vm.stats.value.stats?.totalTokens,
        )
    }

    /**
     * 压缩的两条运行时事件（协议未列 → [PiEvent.Unknown]）驱动「正在压缩」，
     * 且 `compaction_end` 之后**重问一次统计**——ctx% 的真相只在 `get_session_stats` 里。
     */
    @Test
    fun `case67 compaction events flip the flag and the end asks for fresh stats`() = runTest {
        val rig = rig()
        ready(rig)

        rig.engine.emit(PiEvent.Unknown(probeCompactionStart()))
        runCurrent()
        assertTrue(rig.vm.stats.value.compacting)

        rig.engine.emit(PiEvent.Unknown(probeCompactionEnd()))
        runCurrent()
        assertFalse(rig.vm.stats.value.compacting)
        assertEquals(
            "压缩结束要重问统计（§3.7 验收第 4 条靠这一步）",
            1,
            rig.engine.sent.count { wireName(it.command) == "get_session_stats" },
        )
    }

    /** 压缩失败的那句话由 pi 写好（探测 p15），本页原样上屏、不动任何数字。 */
    @Test
    fun `case68 a rejected compact reports pi's own words and mutates nothing`() = runTest {
        val rig = rig()
        ready(rig)
        rig.engine.scriptReply("get_session_stats", data = probeStatsData())
        rig.vm.refreshStats()
        pumpScripted(rig)
        val before = rig.vm.stats.value

        rig.engine.scriptReply("compact", success = false, error = "Nothing to compact (session too small)")
        rig.vm.compactContext()
        pumpScripted(rig)

        assertEquals("compact", wireName(rig.engine.sent.last().command))
        assertTrue("提示要带 pi 的原话：${rig.vm.notice.value}", rig.vm.notice.value?.contains("Nothing to compact") == true)
        assertEquals("失败不改统计面任何一个字段", before, rig.vm.stats.value)
    }

    /**
     * 开关的两个动作：`set_auto_compaction(enabled)` + 紧跟一条 `get_state`。
     *
     * 探测 p16/p18：`set_auto_compaction` 的回执**没有 data**，真值只在
     * `get_state.autoCompactionEnabled` 里 → 按下不等于生效，状态必须由 get_state 落地。
     */
    @Test
    fun `case69 the auto-compaction switch is written by get_state not by the press`() = runTest {
        val rig = rig()
        ready(rig)
        assertNull(rig.vm.stats.value.autoCompaction)

        rig.engine.scriptReply("get_state", data = probeStateFlags(autoCompaction = true))
        rig.vm.setAutoCompaction(true)
        pumpScripted(rig)

        assertEquals(
            listOf("set_auto_compaction", "get_state"),
            rig.engine.sent.map { wireName(it.command) }.takeLast(2),
        )
        assertEquals(true, rig.vm.stats.value.autoCompaction)

        // 再点一次但不给回执：开关必须还停在 pi 上次说的那个值上
        rig.vm.setAutoCompaction(false)
        runCurrent()
        assertEquals(
            "回执没到之前不许把「按过」当成「已生效」",
            true,
            rig.vm.stats.value.autoCompaction,
        )
        assertEquals(
            "两次点击都照原值发出去了（最后一次是 false，与 get_state 的落点无关）",
            listOf(true, false),
            rig.engine.sent.mapNotNull { (it.command as? RpcCommand.SetAutoCompaction)?.enabled },
        )
    }

    /** `get_state.isCompacting` 是事件之外的兜底（漏一条事件也不该永远挂着「正在压缩」）。 */
    @Test
    fun `case70 get_state can correct the compacting flag too`() = runTest {
        val rig = rig()
        ready(rig)
        rig.engine.emit(PiEvent.Unknown(probeCompactionStart()))
        runCurrent()
        assertTrue(rig.vm.stats.value.compacting)

        rig.engine.scriptReply("get_state", data = probeStateFlags(compacting = false))
        rig.vm.refreshModelState()
        pumpScripted(rig)
        assertFalse(rig.vm.stats.value.compacting)
    }

    /** 会话滚了：统计与本轮用量一起归零（上一条会话的 token 对这一条是错数据）。 */
    @Test
    fun `case71 rolling the session wipes stats and the accumulated live usage`() = runTest {
        val rig = rig()
        ready(rig)
        rig.engine.scriptReply("get_session_stats", data = probeStatsData())
        rig.vm.refreshStats()
        pumpScripted(rig)
        rig.engine.emit(PiEvent.MessageEnd(PiMessage(role = "assistant", usage = probeUsageData())))
        runCurrent()
        assertNotNull(rig.vm.stats.value.stats)
        assertEquals(120L, rig.vm.stats.value.live.totalTokens)

        rig.vm.newSession()
        runCurrent()
        val rollId = rig.engine.sent.last().id
        rig.engine.emit(PiEvent.Response(id = rollId, command = "new_session", success = true))
        runCurrent()

        assertEquals(StatsUiState(), rig.vm.stats.value)
        assertEquals("统计未加载", rig.vm.stats.value.barText())
    }

    /** 引擎还没起来时的三条统计命令：一句「引擎尚未就绪」，一条都不许发。 */
    @Test
    fun `case72 stats commands before Ready complain once and send nothing`() = runTest {
        val rig = rig()

        rig.vm.refreshStats()
        rig.vm.compactContext()
        rig.vm.setAutoCompaction(true)
        runCurrent()

        assertEquals(0, rig.engine.sent.size)
        assertTrue("提示要说清原因：${rig.vm.notice.value}", rig.vm.notice.value?.contains("引擎尚未就绪") == true)
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

        /** 一条 user 消息的线格式外壳（§3.1 里 toolResult/system 各自的形状见 ChatHistoryTest）。 */
        fun messageEnvelope(role: String, text: String): JsonObject =
            buildJsonObject { put("role", role); put("content", textBlocks(text)) }

        /** golden 001/002 拼出来的四 role 历史（system + user + assistant + toolResult）。 */
        fun historyEnvelope(): JsonObject = buildJsonObject {
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", "") })
                    add(messageEnvelope("user", "跑一下分支命令"))
                    add(
                        buildJsonObject {
                            put("role", "assistant")
                            put("content", textBlocks("已在 main 分支"))
                            put("stopReason", "stop")
                        },
                    )
                    add(
                        buildJsonObject {
                            put("role", "toolResult")
                            put("toolCallId", "call-1")
                            put("toolName", "bash")
                            put("content", textBlocks("On branch main"))
                            put("isError", false)
                        },
                    )
                },
            )
        }
        /**
         * 探测形状的回执：本体一律转发到 [ProbeFixtures.kt]。
         *
         * 这里保留一层同名转发而不是让用例直接叫 `probeModelsData()`，是为了让 case49–case60
         * 读起来仍然像「这条用例自己的夹具」，同时**只有一份**线协议形状表——UI 侧
         * （ChatScreenUiTest §3.3）吃的是同一份，改一处两边一起红，不会出现「VM 绿、UI 绿、
         * 而真表早就漂了」那种最贵的假绿。
         */
        fun modelsEnvelope(): JsonObject = probeModelsData()

        /** 一条 model 对象：`models[]` 的元素、`set_model` 的整个 data、`cycle_model.model`、`get_state.model` 同形。 */
        fun modelObject(id: String, name: String, reasoning: Boolean, contextWindow: Int = 128_000): JsonObject =
            probeModelObject(id, name, reasoning, contextWindow)

        /** 探测 p02/p04 的 `get_available_thinking_levels` data。 */
        fun levelsEnvelope(vararg levels: String): JsonObject = probeLevelsData(*levels)

        /** 探测 p11 的 `get_state` data（`model` + `thinkingLevel`，其余键照探测留着）。 */
        fun stateEnvelope(id: String, name: String, thinkingLevel: String): JsonObject =
            probeStateData(id, name, thinkingLevel)

        /** 探测 p07 的 `cycle_model` data（外层 `model` + `thinkingLevel` + `isScoped`）。 */
        fun cycleEnvelope(id: String, name: String, thinkingLevel: String): JsonObject =
            probeCycleData(id, name, thinkingLevel)
    }
}
