package works.earendil.pihost.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import works.earendil.pihost.core.rpc.ExtensionUiResponse
import works.earendil.pihost.core.rpc.RpcCommand
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [SessionSupervisor] 作为 [ChatEngine] 的适配回归（规格书 §7 末条）。
 *
 * 存在的理由：P2D 把「UI 拿到的引擎面」从 `open` 类继承换成了接口，
 * `SessionSupervisorTest` 那 11 条只验状态机，**没有一条**覆盖 §4.4 新增的成员
 * （`sendUiResponse`）与「supervisor 就是 ChatEngine」这个类型事实。这里补齐：
 * - 类型面：`val engine: ChatEngine = supervisor` 之后，各成员转出去的必须是同一批对象；
 * - 权限面：`sendUiResponse` 经 `scope.launch` → `PiSession.outbox` → 进程 stdin，
 *   线形状逐字节等于 `encodeExtensionUiResponse` 的产物（protocol.md §3.7）；
 * - 失败面：会话未知/已关 → 静默（只 Log.w），**绝不把异常抛回 UI 线程**。
 *
 * **为什么挂 Robolectric**：`sendUiResponse` 的失败分支调 `android.util.Log.w`；
 * 纯 JVM 单测里那是「未 mock 的 android 方法」直接抛（本模块没开 `returnDefaultValues`，
 * 这是故意的——见 RECON-P2D §4）。`@Config(sdk=[35])` 与本工程其余 Android 测试同源。
 *
 * 时序纪律照抄 [SessionSupervisorTest]（其文件头 KDoc）：观察只用 [pump]
 * （真实 `Thread.sleep` + `runCurrent`，绝不跳虚拟时钟），挂起动作一律放
 * `backgroundScope.launch`，不在测试体里直接 await——PiSession 的读写协程在
 * `Dispatchers.IO` 真实线程上，虚拟时钟会抢跑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatEngineAdapterTest {

    private val starters = CopyOnWriteArrayList<FakeStarter>()

    @After
    fun killFakeProcesses() {
        starters.forEach { it.closeAll() }
    }

    /** 一套「真 supervisor + 它的接口面 + 假进程」。engine 与 supervisor 必须是同一个对象。 */
    private class Harness(
        val supervisor: SessionSupervisor,
        val engine: ChatEngine,
        val starter: FakeStarter,
    )

    private fun harnessIn(scope: CoroutineScope, modes: List<PiMode>): Harness {
        val starter = FakeStarter(modes)
        starters.add(starter)
        val supervisor = SessionSupervisor(
            scope = scope,
            starter = starter,
            commandFor = { listOf(NODE, CLI, "--mode", "rpc", "--session-id", it) },
            envFor = { ENV },
            workingDir = WORKING_DIR,
        )
        // 这一行本身就是「SessionSupervisor : ChatEngine」的编译期证据
        return Harness(supervisor, supervisor as ChatEngine, starter)
    }

    private fun TestScope.pump(label: String, timeoutMs: Long = 4_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            testScheduler.runCurrent()
            if (condition()) return
            assertTrue(
                "pump[$label] 在 ${timeoutMs}ms 真实时间内条件未成立",
                System.currentTimeMillis() < deadline,
            )
            Thread.sleep(2)
        }
    }

    /** 起一条会话直到 Ready，返回 sessionId。 */
    private fun TestScope.readyEngine(harness: Harness): String {
        var sessionId: String? = null
        backgroundScope.launch { sessionId = harness.engine.start() }
        pump("start 返回并进 Ready") {
            val id = sessionId
            id != null && harness.engine.sessionStates.value[id] == SessionState.Ready
        }
        return sessionId!!
    }

    private fun ChatEngine.stateOf(sessionId: String): SessionState? = sessionStates.value[sessionId]

    /** 第一条 stdin 行 = 握手 get_state；后续行才是本文件写入的内容。 */
    private fun FakePiProcess.handshakeDone(): Boolean = stdinLines.isNotEmpty()

    // ── 用例 1：接口面 = 同一批对象（不是包装出来的替身）────────────────────────

    @Test
    fun `the supervisor is the chat engine and forwards the very same surfaces`() = runTest {
        val harness = harnessIn(backgroundScope, listOf(PiMode.HandshakeThenHang))
        val sessionId = readyEngine(harness)

        assertSame(
            "engine.sessionStates 必须就是 supervisor 那一个实例（转成新 flow 会打断 Compose 收敛）",
            harness.supervisor.sessionStates,
            harness.engine.sessionStates,
        )
        assertNotNull(harness.engine.events(sessionId))
        assertSame(harness.engine.events(sessionId), harness.engine.events(sessionId))
        // 未知会话：events / stderrTail 一律 null，不抛（VM 侧按 null 走「没有活动会话」分支）
        assertNull(harness.engine.events("nope"))
        assertNull(harness.engine.stderrTail("nope"))
        assertEquals(SessionState.Ready, harness.engine.stateOf(sessionId))
    }

    // ── 用例 2：confirmed=true 的权限应答逐字节进 stdin ──────────────────────────

    @Test
    fun `sendUiResponse with confirmed writes the exact extension_ui_response line`() = runTest {
        val harness = harnessIn(backgroundScope, listOf(PiMode.HandshakeThenHang))
        val sessionId = readyEngine(harness)
        val fake = harness.starter.spawned[0]
        pump("握手行已落地") { fake.handshakeDone() }
        val handshakeLines = fake.stdinLines.size

        harness.engine.sendUiResponse(sessionId, ExtensionUiResponse(id = "req-9", confirmed = true))

        pump("应答行已写进进程 stdin") { fake.stdinLines.size > handshakeLines }
        assertEquals(
            """{"id":"req-9","confirmed":true,"type":"extension_ui_response"}""",
            fake.stdinLines.last(),
        )
        assertEquals(
            "extension_ui_response 无回执：不该多写一行",
            handshakeLines + 1,
            fake.stdinLines.size,
        )
    }

    // ── 用例 3：cancelled=true 只带 cancelled（互斥由 ExtensionUiResponse init 保证）──

    @Test
    fun `sendUiResponse with cancelled writes only the cancelled key`() = runTest {
        val harness = harnessIn(backgroundScope, listOf(PiMode.HandshakeThenHang))
        val sessionId = readyEngine(harness)
        val fake = harness.starter.spawned[0]
        pump("握手行已落地") { fake.handshakeDone() }

        harness.engine.sendUiResponse(sessionId, ExtensionUiResponse(id = "req-3", cancelled = true))

        pump("取消行已写进 stdin") { fake.stdinLines.last().contains("cancelled") }
        assertEquals(
            """{"id":"req-3","cancelled":true,"type":"extension_ui_response"}""",
            fake.stdinLines.last(),
        )
    }

    // ── 用例 4：会话未知 → 静默返回（不抛、不写任何字节）────────────────────────

    @Test
    fun `sendUiResponse on an unknown session is a silent no-op`() = runTest {
        val harness = harnessIn(backgroundScope, listOf(PiMode.HandshakeThenHang))
        val sessionId = readyEngine(harness)
        val fake = harness.starter.spawned[0]
        pump("握手行已落地") { fake.handshakeDone() }
        val before = fake.stdinLines.size

        harness.engine.sendUiResponse(
            sessionId = "00000000-0000-0000-0000-000000000000",
            response = ExtensionUiResponse(id = "ghost", confirmed = true),
        )
        pump("等一拍") { true }
        assertEquals("未知会话不该产生任何线字节", before, fake.stdinLines.size)
    }

    // ── 用例 5：send 代理到活动进程，id 原样回显（protocol.md §3 关联依据）───────

    @Test
    fun `send proxies the command to the live process and echoes the caller id`() = runTest {
        val harness = harnessIn(backgroundScope, listOf(PiMode.HandshakeThenHang))
        val sessionId = readyEngine(harness)
        val fake = harness.starter.spawned[0]
        pump("握手行已落地") { fake.handshakeDone() }

        val done = Job()
        backgroundScope.launch {
            harness.engine.send(sessionId, RpcCommand.Prompt(message = "hi"), id = "ui-1")
            done.complete()
        }
        pump("send 返回且命令落地") { done.isCompleted && fake.stdinLines.last().contains("ui-1") }
        val line = fake.stdinLines.last()
        // 线形状 = encodeCommand 的产物：kotlinx sealed 多态判别字段是 "type"（= @SerialName），
        // 没有 "command" 这个键（RpcCommand.kt 头 KDoc + tools/capture/cmds/001-basic.json 原文）
        assertTrue("必须是指令 prompt：$line", line.contains(""""type":"prompt""""))
        assertTrue("id 必须原样：$line", line.contains(""""id":"ui-1""""))
        assertTrue("message 必须进线：$line", line.contains("hi"))
    }

    // ── 用例 6：会话已关 → 投递失败只记日志，不抛回调用方 ───────────────────────

    @Test
    fun `sendUiResponse after shutdown fails soft instead of throwing at the caller`() = runTest {
        val harness = harnessIn(backgroundScope, listOf(PiMode.HandshakeThenHang))
        val sessionId = readyEngine(harness)

        val stopped = Job()
        backgroundScope.launch {
            harness.engine.stop(sessionId)
            stopped.complete()
        }
        pump("会话已 Stopped 且 stop 返回") {
            stopped.isCompleted && harness.engine.stateOf(sessionId) == SessionState.Stopped
        }

        // 关键：这一行不许抛（outbox 已关 → runCatching + Log.w，见 SessionSupervisor.sendUiResponse）
        harness.engine.sendUiResponse(sessionId, ExtensionUiResponse(id = "late", confirmed = true))
        pump("投递协程已跑完（异常被吞）") { true }
        assertEquals(SessionState.Stopped, harness.engine.stateOf(sessionId))
    }

    private companion object {
        const val NODE = "/data/user/0/works.earendil.pihost/lib/libpi_node.so"
        const val CLI = "/data/user/0/works.earendil.pihost/files/runtime/pi/dist/bundle/cli.js"
        const val WORKING_DIR = "/data/user/0/works.earendil.pihost/files/projects/default"
        val ENV: Map<String, String> = mapOf("HOME" to "/data/user/0/works.earendil.pihost/files/home")
    }
}
