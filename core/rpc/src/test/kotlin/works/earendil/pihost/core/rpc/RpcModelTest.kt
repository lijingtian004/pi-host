package works.earendil.pihost.core.rpc

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 协议模型对 golden 语料（001–008，~414 行）的全量验证：
 * 每一行都必须严格解码为类型化 [PiEvent]，Unknown 计数必须为 0——
 * 这是「模型覆盖冻结协议全集」的直接证明；同时校验出站编码形状。
 */
class RpcModelTest {

    private fun goldenDir(): File = File(javaClass.classLoader.getResource("golden")!!.toURI())

    private fun goldenFiles(): List<File> =
        goldenDir().listFiles { f -> f.extension == "jsonl" }!!.sortedBy { it.name }

    @Test
    fun `golden corpus parses fully typed with zero Unknown`() {
        val seen = mutableMapOf<String, Int>()
        val unknown = mutableListOf<String>()
        goldenFiles().forEach { file ->
            file.readLines().filter { it.isNotBlank() }.forEach { line ->
                when (val e = parsePiEvent(line)) {
                    is PiEvent.Unknown -> unknown += "${file.name}: type=${e.raw["type"]} line=${line.take(90)}"
                    else -> seen.merge(e::class.simpleName ?: "?", 1, Int::plus)
                }
            }
        }
        assertTrue(unknown.isEmpty(), "unknown events:\n${unknown.joinToString("\n")}")
        val expected = setOf(
            "Response", "AgentStart", "AgentEnd", "AgentSettled", "TurnStart", "TurnEnd",
            "MessageStart", "MessageEnd", "MessageUpdate", "ToolExecutionStart",
            "ToolExecutionEnd", "BashExecutionUpdate", "QueueUpdate", "ExtensionUiRequest",
            "AutoRetryStart", "AutoRetryEnd", "EntryAppended", "SessionInfoChanged",
        )
        val missing = expected - seen.keys
        assertTrue(missing.isEmpty(), "unexercised event classes: $missing\nseen: $seen")
        println("event distribution: $seen")
    }

    @Test
    fun `assistant message event variants decode across corpus`() {
        val variants = mutableMapOf<String, Int>()
        goldenFiles().forEach { file ->
            file.readLines().filter { it.isNotBlank() }.forEach { line ->
                val e = parsePiEvent(line)
                if (e is PiEvent.MessageUpdate && e.assistantMessageEvent != null) {
                    variants.merge(e.assistantMessageEvent::class.simpleName ?: "?", 1, Int::plus)
                }
            }
        }
        val expected = setOf(
            "TextStart", "TextDelta", "TextEnd",
            "ThinkingStart", "ThinkingDelta", "ThinkingEnd",
            "ToolcallStart", "ToolcallDelta", "ToolcallEnd",
        )
        val missing = expected - variants.keys
        assertTrue(missing.isEmpty(), "unexercised AME variants: $missing\nseen: $variants")
        println("AME distribution: $variants")
    }

    @Test
    fun `response invariants hold across corpus`() {
        var successes = 0
        var failures = 0
        goldenFiles().forEach { file ->
            file.readLines().filter { it.isNotBlank() }.forEach { line ->
                val e = parsePiEvent(line)
                if (e is PiEvent.Response) {
                    if (e.success) {
                        successes++
                        assertEquals(null, e.error, "success response carries error: $line")
                    } else {
                        failures++
                        assertTrue(e.error != null, "failure response lacks error: $line")
                    }
                }
            }
        }
        assertTrue(successes > 50, "too few success responses: $successes")
        // 语料内冻结的失败应答恰为 5 条（quit 未知命令、export_html、bad model、bad fork entry、bad clone）
        assertTrue(failures >= 5, "too few failure responses: $failures")
    }

    @Test
    fun `command encoding matches frozen wire shape`() {
        assertEquals("{\"type\":\"get_state\"}\n", encodeCommand(null, RpcCommand.GetState))

        val promptObj = PiJsonIn.parseToJsonElement(
            encodeCommand("c-1", RpcCommand.Prompt("hello")).removeSuffix("\n"),
        ) as JsonObject
        assertEquals("prompt", promptObj["type"]!!.jsonPrimitive.content)
        assertEquals("hello", promptObj["message"]!!.jsonPrimitive.content)
        assertEquals("c-1", promptObj["id"]!!.jsonPrimitive.content)
        assertTrue("images" !in promptObj, "default images must be omitted")

        val withImages = PiJsonIn.parseToJsonElement(
            encodeCommand(null, RpcCommand.Prompt("look", images = listOf("file:///a.png"))).removeSuffix("\n"),
        ) as JsonObject
        assertTrue("images" in withImages)
        assertTrue("id" !in withImages)

        val bashObj = PiJsonIn.parseToJsonElement(
            encodeCommand(null, RpcCommand.Bash("ls -la")).removeSuffix("\n"),
        ) as JsonObject
        assertEquals("bash", bashObj["type"]!!.jsonPrimitive.content)
        assertEquals("ls -la", bashObj["command"]!!.jsonPrimitive.content)
        assertTrue("excludeFromContext" !in bashObj, "default excludeFromContext must be omitted")
    }

    @Test
    fun `extension ui response encoding and validation`() {
        val okObj = PiJsonIn.parseToJsonElement(
            encodeExtensionUiResponse(ExtensionUiResponse(id = "u-1", confirmed = true)).removeSuffix("\n"),
        ) as JsonObject
        assertEquals("extension_ui_response", okObj["type"]!!.jsonPrimitive.content)
        assertEquals("u-1", okObj["id"]!!.jsonPrimitive.content)
        assertEquals("true", okObj["confirmed"]!!.jsonPrimitive.content)
        assertTrue("cancelled" !in okObj)

        val cancelObj = PiJsonIn.parseToJsonElement(
            encodeExtensionUiResponse(ExtensionUiResponse(id = "u-2", cancelled = true)).removeSuffix("\n"),
        ) as JsonObject
        assertEquals("true", cancelObj["cancelled"]!!.jsonPrimitive.content)
        assertTrue("confirmed" !in cancelObj)

        assertThrows(IllegalArgumentException::class.java) { ExtensionUiResponse(id = "x") }
        assertThrows(IllegalArgumentException::class.java) {
            ExtensionUiResponse(id = "x", confirmed = true, cancelled = true)
        }
    }

    @Test
    fun `unknown event type falls back without throwing`() {
        val e = parsePiEvent("""{"type":"future_event","payload":{"a":1}}""")
        assertTrue(e is PiEvent.Unknown)
        assertEquals("future_event", (e as PiEvent.Unknown).raw["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `malformed or drifted lines throw SerializationException`() {
        assertThrows(SerializationException::class.java) { parsePiEvent("") }
        assertThrows(SerializationException::class.java) { parsePiEvent("not json") }
        assertThrows(SerializationException::class.java) { parsePiEvent("""{"no_type":1}""") }
        // 已知 type 的形状漂移必须抛（严格解码，协议漂移交由 golden 测试显式暴露）
        assertThrows(SerializationException::class.java) {
            parsePiEvent("""{"type":"queue_update","steering":"not-an-array"}""")
        }
        assertThrows(SerializationException::class.java) {
            parsePiEvent("""{"type":"response"}""")
        }
    }
}
