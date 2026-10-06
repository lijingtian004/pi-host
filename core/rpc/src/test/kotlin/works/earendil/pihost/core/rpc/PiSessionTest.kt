package works.earendil.pihost.core.rpc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * PiSession 全链（FakeProcess）：解析/背压/优雅关停/destroy 兜底/尾帧冲刷/stderr 环。
 * 方案 §11 测试策略的 FakeProcess 部分落地。
 */
class PiSessionTest {

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun awaitSubscriber(session: PiSession) {
        withTimeout(5_000) { session.eventsSubscriptionCount.first { it > 0 } }
    }

    @Test
    fun `stdout lines parse into typed events with trailing frame flush`() = runBlocking {
        val fake = FakeProcess()
        val scope = newScope()
        val session = PiSession(fake, scope)
        val received = CopyOnWriteArrayList<PiEvent>()
        val collector = launch(Dispatchers.IO) { session.events.collect { received.add(it) } }
        awaitSubscriber(session)
        session.start()

        fake.stdoutWriter.write("{\"type\":\"agent_start\"}\n".toByteArray())
        // 第二帧不带尾 LF：EOF 后 framer.close() 必须冲刷（protocol.md §1）
        fake.stdoutWriter.write("{\"type\":\"turn_start\"}\n{\"type\":\"agent_settled\"}".toByteArray())
        fake.stdoutWriter.flush()
        fake.stdoutWriter.close()
        fake.exit(0)

        assertEquals(0, session.exit.await())
        withTimeout(5_000) { while (received.size < 3) delay(10) }
        assertTrue(received[0] is PiEvent.AgentStart, "got ${received[0]}")
        assertTrue(received[1] is PiEvent.TurnStart, "got ${received[1]}")
        assertTrue(received[2] is PiEvent.AgentSettled, "got ${received[2]}")

        collector.cancel()
        scope.cancel()
    }

    @Test
    fun `send writes framed json with auto id to stdin`() = runBlocking {
        val fake = FakeProcess()
        val scope = newScope()
        val session = PiSession(fake, scope)
        session.start()

        session.send(RpcCommand.GetState)
        session.send(RpcCommand.Prompt("hi"))

        withTimeout(5_000) { while (fake.receivedStdinLines.size < 2) delay(10) }
        val l1 = PiJsonIn.parseToJsonElement(fake.receivedStdinLines[0]) as JsonObject
        assertEquals("get_state", l1["type"]!!.jsonPrimitive.content)
        assertEquals("c-1", l1["id"]!!.jsonPrimitive.content)
        val l2 = PiJsonIn.parseToJsonElement(fake.receivedStdinLines[1]) as JsonObject
        assertEquals("prompt", l2["type"]!!.jsonPrimitive.content)
        assertEquals("hi", l2["message"]!!.jsonPrimitive.content)
        assertEquals("c-2", l2["id"]!!.jsonPrimitive.content)
        assertFalse("images" in l2, "default images must be omitted")

        fake.exit(0)
        scope.cancel()
    }

    @Test
    fun `close drains outbox closes stdin then graceful exit zero`() = runBlocking {
        val fake = FakeProcess(autoExitOnStdinClose = true)
        val scope = newScope()
        val session = PiSession(fake, scope)
        session.start()

        session.send(RpcCommand.Prompt("bye"))
        val code = session.close(gracefulTimeoutMs = 5_000)

        assertEquals(0, code)
        assertTrue(fake.stdinClosed, "stdin must see EOF")
        assertEquals(1, fake.receivedStdinLines.size)
        assertEquals("prompt", (PiJsonIn.parseToJsonElement(fake.receivedStdinLines[0]) as JsonObject)["type"]!!.jsonPrimitive.content)

        scope.cancel()
    }

    @Test
    fun `close falls back to destroy after graceful timeout`() = runBlocking {
        val fake = FakeProcess() // 不自动退：模拟卡死进程
        val scope = newScope()
        val session = PiSession(fake, scope)
        session.start()

        val code = session.close(gracefulTimeoutMs = 150)

        assertEquals(137, code)
        assertTrue(fake.destroyed, "destroy backstop must fire")
        assertTrue(fake.stdinClosed, "stdin EOF was tried first")

        scope.cancel()
    }

    @Test
    fun `stderr drained into 8kb ring keeping tail`() = runBlocking {
        val fake = FakeProcess()
        val scope = newScope()
        val session = PiSession(fake, scope)
        session.start()

        fake.stderrWriter.write("x".repeat(10_000).toByteArray())
        fake.stderrWriter.write("y".repeat(10_000).toByteArray())
        fake.stderrWriter.flush()
        fake.stderrWriter.close()

        val expectedTail = "y".repeat(8_192)
        withTimeout(5_000) { while (session.stderrRing.snapshot() != expectedTail) delay(10) }
        assertEquals(8_192, session.stderrRing.byteCount())

        fake.exit(0)
        scope.cancel()
    }

    @Test
    fun `slow consumer suspends read loop without dropping events`() = runBlocking {
        val fake = FakeProcess()
        val scope = newScope()
        val session = PiSession(fake, scope)
        val received = CopyOnWriteArrayList<PiEvent>()
        // 慢消费者：每事件 5ms —— 超出 256 缓冲容量，SUSPEND 背压必须生效且零丢失
        val collector = launch(Dispatchers.IO) {
            session.events.collect { received.add(it); delay(5) }
        }
        awaitSubscriber(session)
        session.start()

        repeat(300) { fake.stdoutWriter.write("{\"type\":\"agent_start\"}\n".toByteArray()) }
        fake.stdoutWriter.flush()
        fake.stdoutWriter.close()
        fake.exit(0)

        withTimeout(30_000) { while (received.size < 300) delay(20) }
        assertEquals(300, received.size)

        collector.cancel()
        scope.cancel()
    }

    @Test
    fun `malformed lines surface on protocol errors without killing loop`() = runBlocking {
        val fake = FakeProcess()
        val scope = newScope()
        val session = PiSession(fake, scope)
        val received = CopyOnWriteArrayList<PiEvent>()
        val errors = CopyOnWriteArrayList<PiSession.ProtocolError>()
        val c1 = launch(Dispatchers.IO) { session.events.collect { received.add(it) } }
        val c2 = launch(Dispatchers.IO) { session.protocolErrors.collect { errors.add(it) } }
        withTimeout(5_000) {
            session.eventsSubscriptionCount.first { it > 0 }
            session.protocolErrorsSubscriptionCount.first { it > 0 }
        }
        session.start()

        fake.stdoutWriter.write("this is not json\n".toByteArray())
        fake.stdoutWriter.write("{\"type\":\"agent_start\"}\n".toByteArray())
        fake.stdoutWriter.flush()
        fake.stdoutWriter.close()
        fake.exit(0)

        withTimeout(5_000) {
            while (received.size < 1 || errors.size < 1) delay(10)
        }
        assertEquals(1, errors.size)
        assertTrue(errors[0].line.startsWith("this is not json"), "bad line recorded verbatim")
        assertTrue(received[0] is PiEvent.AgentStart, "loop survived bad line")

        c1.cancel(); c2.cancel()
        scope.cancel()
    }

    @Test
    fun `extension ui response goes over stdin as framed line`() = runBlocking {
        val fake = FakeProcess()
        val scope = newScope()
        val session = PiSession(fake, scope)
        session.start()

        session.sendUiResponse(ExtensionUiResponse(id = "u-9", confirmed = true))
        withTimeout(5_000) { while (fake.receivedStdinLines.isEmpty()) delay(10) }

        val obj = PiJsonIn.parseToJsonElement(fake.receivedStdinLines[0]) as JsonObject
        assertEquals("extension_ui_response", obj["type"]!!.jsonPrimitive.content)
        assertEquals("u-9", obj["id"]!!.jsonPrimitive.content)
        assertEquals("true", obj["confirmed"]!!.jsonPrimitive.content)
        assertFalse("cancelled" in obj)

        fake.exit(0)
        scope.cancel()
    }
}
