package works.earendil.pihost.core.rpc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import works.earendil.pihost.core.process.JvmProcessFactory
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 桌面 e2e（方案 §11）：PiSession × 真实 node + pi v1.0.4 bundle + mock provider。
 *
 * 环境门控：node / pi bundle / 8787 mock provider / capture home 任一缺失即 skip
 * （CI 或无抓包环境的机器不受影响）。跑通即证明——
 * 分帧、解析、单 writer 写入、ready 探测（首条 response）、prompt→agent_settled
 * 全链路对真实 pi 二进制成立。
 */
class PiDesktopE2eTest {

    private val cli = File("/root/pi/.capture/node_modules/@earendil-works/pi-coding-agent/dist/cli.js")
    private val home = File("/root/pi/.capture/home")
    private val work = File("/root/pi/.capture/work")

    private fun mockUp(): Boolean = try {
        Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", 8787), 2_000); true }
    } catch (_: Exception) {
        false
    }

    private fun environmentReady(): Boolean =
        cli.isFile && home.isDirectory && System.getenv("PATH")?.let { true } ?: true && mockUp() &&
            ProcessBuilder("node", "--version").start().waitFor() == 0

    @Test
    fun `real pi rpc roundtrip get_state prompt settle and graceful exit`() = runBlocking {
        assumeTrue(environmentReady())

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val process = JvmProcessFactory.start(
            command = listOf("node", cli.absolutePath, "--mode", "rpc"),
            env = mapOf(
                "HOME" to home.absolutePath,
                "PI_SKIP_VERSION_CHECK" to "1",
                "NO_COLOR" to "1",
            ),
            workingDir = work.absolutePath,
        )
        val session = PiSession(process, scope)

        val textDeltas = StringBuilder()
        val seen = ArrayList<PiEvent>(256)
        val collector = launch(Dispatchers.IO) {
            session.events.collect { e ->
                seen.add(e)
                if (e is PiEvent.MessageUpdate) {
                    val ame = e.assistantMessageEvent
                    if (ame is AssistantMessageEvent.TextDelta) textDeltas.append(ame.delta)
                }
            }
        }
        withTimeout(5_000) { session.eventsSubscriptionCount.first { it > 0 } }
        session.start()

        try {
            // ready = 首条 response（protocol.md §2：无 ready 信号，以 get_state 探测）
            val id1 = session.nextId()
            session.send(RpcCommand.GetState, id1)
            val ready = withTimeout(30_000) {
                session.events.first { it is PiEvent.Response && it.id == id1 } as PiEvent.Response
            }
            assertEquals("get_state", ready.command)
            assertTrue(ready.success, "get_state failed: ${ready.error}")

            // prompt → mock 场景 ^echo hello → "hello from mock model" → agent_settled
            val id2 = session.nextId()
            session.send(RpcCommand.Prompt("echo hello"), id2)
            val promptAck = withTimeout(10_000) {
                session.events.first { it is PiEvent.Response && it.id == id2 } as PiEvent.Response
            }
            assertTrue(promptAck.success, "prompt failed: ${promptAck.error}")

            withTimeout(60_000) {
                session.events.first { it is PiEvent.AgentSettled }
            }
            assertTrue(seen.any { it is PiEvent.AgentStart }, "AgentStart missing")
            assertTrue(seen.any { it is PiEvent.AgentEnd }, "AgentEnd missing")
            assertTrue(
                textDeltas.contains("hello from mock model"),
                "mock reply not streamed, got: $textDeltas",
            )

            // 优雅退出：stdin EOF → exit 0（protocol.md §2）
            val code = session.close(gracefulTimeoutMs = 15_000)
            assertEquals(0, code, "stderr tail: ${session.stderrRing.snapshot().takeLast(500)}")
        } finally {
            collector.cancel()
            scope.cancel()
        }
    }
}
