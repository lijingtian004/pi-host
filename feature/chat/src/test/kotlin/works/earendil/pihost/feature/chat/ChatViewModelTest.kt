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
import works.earendil.pihost.core.rpc.AssistantMessageEvent
import works.earendil.pihost.core.rpc.PiEvent
import works.earendil.pihost.core.rpc.PiMessage
import works.earendil.pihost.core.rpc.RpcCommand

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
    )

    private fun TestScope.rig(
        engine: FakeChatEngine = FakeChatEngine(),
        installer: FakeRuntimeInstall = FakeRuntimeInstall(),
    ): Rig {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val app = RuntimeEnvironment.getApplication()
        return Rig(app, ChatViewModel(app, engine, installer), engine, installer)
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
