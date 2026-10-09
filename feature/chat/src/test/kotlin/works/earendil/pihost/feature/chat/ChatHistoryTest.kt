package works.earendil.pihost.feature.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import works.earendil.pihost.core.rpc.PiMessage

/**
 * `ChatHistory` 的解析与映射（P3B §3.1，编号风格沿用 `:feature:settings` 的 `tNN`/`iNN`：
 * 一个文件一条序列，跨文件不撞号，矩阵里也好按前缀归属）。
 *
 * **为什么要 Robolectric**：本类的失败路径写 `android.util.Log`，纯 JVM 下那是
 * 「not mocked」的 RuntimeException；本模块没有开 `returnDefaultValues`
 * （开了就等于允许「忘记 mock」的假绿），所以按既有配方可走：Robolectric + `@Config(sdk = [35])`。
 *
 * 输入形状全部照 `docs/protocol.md` §5.2 与 golden 001/002/008 的实录，不自己发明字段。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatHistoryTest {

    // ── parseMessages：四类 role 各一条 ────────────────────────────────────────────

    @Test
    fun `h01 system message keeps its sections envelope`() {
        val data = buildJsonObject {
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", "")
                            put("timestamp", 1_791_243_083_183L)
                            putJsonObject("sections") { put("preamble", "You are an expert coding assistant") }
                        },
                    )
                },
            )
        }

        val messages = ChatHistory.parseMessages(data)
        assertNotNull("system 是合法形状，不该判成 null", messages)
        assertEquals(1, messages!!.size)
        assertEquals("system", messages[0].role)
        assertEquals("", contentToText(messages[0].content))
        assertNotNull("sections 是 P2H 首轮 message_start(system) 的那块，信封层要留住", messages[0].sections)
    }

    @Test
    fun `h02 user message with text blocks decodes`() {
        val messages = ChatHistory.parseMessages(
            envelope(buildJsonObject {
                put("role", "user")
                put("content", textArray("echo hello"))
                put("timestamp", 1_791_243_083_180L)
            }),
        )!!

        assertEquals(1, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("echo hello", contentToText(messages[0].content))
        assertEquals("时间戳是 user/toolResult 携带的毫秒值", 1_791_243_083_180L, messages[0].timestamp)
    }

    @Test
    fun `h03 assistant message keeps stopReason usage and responseId`() {
        val messages = ChatHistory.parseMessages(
            envelope(buildJsonObject {
                put("role", "assistant")
                put(
                    "content",
                    buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", "hello from mock model") })
                        add(buildJsonObject { put("type", "thinking"); put("thinking", "先想一下") })
                    },
                )
                put("stopReason", "stop")
                putJsonObject("usage") { put("input", 100); put("output", 20); put("totalTokens", 120) }
                put("responseId", "chatcmpl-mock-w0xzpvd8")
                put("timestamp", 1_791_243_083_886L)
                // golden 002 实录里还有 api/provider/model/rawStopReason/thinkingLevel 五个信封外字段：
                // PiJsonIn 的 ignoreUnknownKeys 必须把它们咽下去，而不是让整页历史解不出来。
                put("api", "openai-completions")
                put("provider", "mock")
                put("model", "mock-1")
            }),
        )!!

        assertEquals(1, messages.size)
        val assistant = messages[0]
        assertEquals("stop", assistant.stopReason)
        assertEquals("chatcmpl-mock-w0xzpvd8", assistant.responseId)
        assertNotNull("usage 是 §3.4 统计条的原料之一，信封层不能丢", assistant.usage)
        assertEquals("hello from mock model", contentToText(assistant.content))
        assertEquals("先想一下", contentToThinking(assistant.content))
    }

    @Test
    fun `h04 toolResult message keeps callId toolName and isError`() {
        val messages = ChatHistory.parseMessages(
            envelope(buildJsonObject {
                put("role", "toolResult")
                put("toolCallId", "call_mock_git")
                put("toolName", "bash")
                put("content", textArray("master\n"))
                put("isError", false)
                put("timestamp", 1_791_243_126_982L)
            }),
        )!!

        assertEquals(1, messages.size)
        val result = messages[0]
        assertEquals("call_mock_git", result.toolCallId)
        assertEquals("bash", result.toolName)
        assertEquals(false, result.isError)
        assertEquals("master\n", contentToText(result.content))
    }

    // ── parseMessages：边界与形状 ──────────────────────────────────────────────────

    @Test
    fun `h05 an empty messages array is a list of zero, not a shape failure`() {
        val messages = ChatHistory.parseMessages(buildJsonObject { put("messages", buildJsonArray { }) })
        assertNotNull("空历史是合法答案（新建会话就是），不能和「形状不对」混成一个 null", messages)
        assertEquals(0, messages!!.size)
    }

    @Test
    fun `h06 every wrong-shaped envelope returns null instead of throwing`() {
        assertNull("data 缺省", ChatHistory.parseMessages(null))
        assertNull(
            "data 不是对象",
            ChatHistory.parseMessages(JsonPrimitive("get_messages")),
        )
        assertNull(
            "messages 键缺失",
            ChatHistory.parseMessages(buildJsonObject { put("entries", buildJsonArray { }) }),
        )
        assertNull(
            "messages 不是数组（golden 里从没这样，但线上一漂就得当场判死）",
            ChatHistory.parseMessages(buildJsonObject { put("messages", buildJsonObject { put("role", "user") }) }),
        )
    }

    @Test
    fun `h07 one un-decodable entry is skipped without taking the rest of the page down`() {
        val messages = ChatHistory.parseMessages(
            envelope(
                // 缺 role（PiMessage 的唯一必填字段）= 解码必抛；旁边那条必须活着
                buildJsonObject { put("content", textArray("我没有 role")) },
                buildJsonObject { put("role", "user"); put("content", "还活着") },
                JsonPrimitive("这不是消息对象"),
            ),
        )

        assertNotNull("整体形状是对的，就不该因为单条坏数据把整页判空", messages)
        assertEquals(1, messages!!.size)
        assertEquals("user", messages[0].role)
        assertEquals("裸字符串 content 也要能读出来", "还活着", contentToText(messages[0].content))
    }

    // ── toTranscriptItems：映射规则 ────────────────────────────────────────────────

    @Test
    fun `h08 all four roles map to the transcript items the chat page already knows`() {
        val messages = ChatHistory.parseMessages(
            envelope(
                buildJsonObject { put("role", "system"); put("content", "preamble") },
                buildJsonObject { put("role", "user"); put("content", textArray("跑一下分支命令")) },
                buildJsonObject {
                    put("role", "assistant")
                    put("content", textArray("已在 main 分支"))
                    put("stopReason", "stop")
                },
                buildJsonObject {
                    put("role", "toolResult")
                    put("toolCallId", "call-1")
                    put("toolName", "bash")
                    put("content", textArray("On branch main"))
                    put("isError", true)
                },
            ),
        )!!

        var allocated = 0
        val items = ChatHistory.toTranscriptItems(messages) { ++allocated }

        assertEquals("system 不上屏，三条才对", 3, items.size)
        assertEquals("id 只发给真正上屏的条目", 3, allocated)

        assertEquals(
            TranscriptItem.UserItem(id = 1, text = "跑一下分支命令"),
            items[0],
        )
        assertEquals(
            TranscriptItem.AssistantItem(id = 2, text = "已在 main 分支", settled = true),
            items[1],
        )
        val tool = items[2] as TranscriptItem.ToolItem
        assertEquals("call-1", tool.callId)
        assertEquals("bash", tool.toolName)
        assertEquals("isError=true 的历史结果 = 红灯（回填是终态，不会是 Running）", ToolStatus.Error, tool.status)
        assertEquals("On branch main", tool.output)
    }

    @Test
    fun `h09 a successful toolResult maps to the ok light`() {
        val items = ChatHistory.toTranscriptItems(
            listOf(
                PiMessage(
                    role = "toolResult",
                    content = textArray("一行小结果"),
                    toolCallId = "call-ok",
                    toolName = "read",
                    isError = false,
                ),
            ),
        ) { 7 }

        val tool = items.single() as TranscriptItem.ToolItem
        assertEquals(ToolStatus.Ok, tool.status)
        assertEquals(7, tool.id)
        assertEquals("一行小结果", tool.output)
    }

    @Test
    fun `h10 user content in both block-array and bare-string shapes yields one bubble`() {
        val fromBlocks = ChatHistory.toTranscriptItems(
            listOf(PiMessage(role = "user", content = textArray("第一段", "第二段"))),
        ) { 1 }
        val fromString = ChatHistory.toTranscriptItems(
            listOf(PiMessage(role = "user", content = JsonPrimitive("裸字符串"))),
        ) { 2 }

        assertEquals("多个 text 块拼成一条气泡", TranscriptItem.UserItem(1, "第一段第二段"), fromBlocks.single())
        assertEquals(TranscriptItem.UserItem(2, "裸字符串"), fromString.single())
    }

    @Test
    fun `h11 an empty user message never becomes a bubble`() {
        val items = ChatHistory.toTranscriptItems(
            listOf(
                PiMessage(role = "user", content = JsonArray(emptyList())),
                PiMessage(role = "user", content = JsonPrimitive("   ")),
            ),
        ) { 1 }
        assertTrue("空文本不入列（与 sendPrompt 的 isBlank 守卫同一判据）", items.isEmpty())
    }

    @Test
    fun `h12 an errored assistant round with no body renders as the existing error note`() {
        val messages = ChatHistory.parseMessages(
            envelope(
                buildJsonObject {
                    put("role", "assistant")
                    put("content", buildJsonArray { })
                    put("stopReason", "error")
                    put("errorMessage", "500: {\"message\":\"mock provider hard failure\"}")
                },
            ),
        )!!

        val items = ChatHistory.toTranscriptItems(messages) { 1 }
        val note = items.single() as TranscriptItem.NoteItem
        assertTrue("措辞沿用 P2G 的「本轮异常：」，实为 ${note.text}", note.text.startsWith("本轮异常："))
        assertTrue(note.text.contains("mock provider hard failure"))
    }

    @Test
    fun `h13 an errored assistant round that still has text keeps the text bubble`() {
        val items = ChatHistory.toTranscriptItems(
            listOf(
                PiMessage(
                    role = "assistant",
                    content = textArray("写到一半就断了"),
                    stopReason = "error",
                    errorMessage = "aborted",
                ),
            ),
        ) { 1 }

        val assistant = items.single() as TranscriptItem.AssistantItem
        assertEquals("有正文就按正文渲染，错误信息不能把内容吞掉", "写到一半就断了", assistant.text)
        assertTrue("历史条目一律终态", assistant.settled)
    }

    @Test
    fun `h14 thinking blocks land in the assistant thinking slot and toolCall blocks are not text`() {
        val items = ChatHistory.toTranscriptItems(
            listOf(
                PiMessage(
                    role = "assistant",
                    content = buildJsonArray {
                        add(buildJsonObject { put("type", "thinking"); put("thinking", "先看 git 状态") })
                        add(buildJsonObject { put("type", "text"); put("text", "已在 main") })
                        // §5.2 的 toolCall 块：没有 text 字段，拼正文时必须贡献空串而不是 JSON 原文
                        add(buildJsonObject { put("type", "toolCall"); put("id", "call-1"); put("name", "bash") })
                    },
                    stopReason = "toolUse",
                ),
            ),
        ) { 1 }

        val assistant = items.single() as TranscriptItem.AssistantItem
        assertEquals("先看 git 状态", assistant.thinking)
        assertEquals("已在 main", assistant.text)
    }

    @Test
    fun `h15 unknown roles and system-only histories yield nothing`() {
        val items = ChatHistory.toTranscriptItems(
            listOf(
                PiMessage(role = "system", content = JsonPrimitive("只有系统提示")),
                PiMessage(role = "developer", content = JsonPrimitive("未来形态")),
            ),
        ) { 1 }
        assertTrue("跳过不是失败：空列表 + 一个 id 都不许分配", items.isEmpty())
    }

    // ── 造数据 helper ──────────────────────────────────────────────────────────────

    private fun envelope(vararg messages: JsonElement): JsonObject = buildJsonObject {
        put("messages", buildJsonArray { messages.forEach { add(it) } })
    }

    private fun textArray(vararg texts: String): JsonArray =
        buildJsonArray { texts.forEach { add(buildJsonObject { put("type", "text"); put("text", it) }) } }
}
