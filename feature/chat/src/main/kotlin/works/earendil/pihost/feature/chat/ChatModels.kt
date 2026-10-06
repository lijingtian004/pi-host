package works.earendil.pihost.feature.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 聊天核心链的 UI 状态与转录条目模型（规格书 §5.1 / §6）。
 *
 * **全部不可变**：LazyColumn 的 item 身份与 Compose 的重组收敛都靠「整条快照替换」，
 * 任何原地 mutate 都会让流式渲染出现「文本半句 + 状态灯不刷新」这类难查抖动。
 * 流式增量只替换末条 [TranscriptItem.AssistantItem]（copy-on-write，§5.1 末条纪律）。
 */

/** 首启/运行期总状态（§5.1 原文四态）。 */
sealed interface ChatUiState {
    /** 运行时未安装（`InstalledRuntime.version` 为 null）。 */
    data object NotInstalled : ChatUiState

    /** 正在安装：[step] 直接透传 [works.earendil.pihost.core.engine.InstallProgress]。 */
    data class Installing(val step: works.earendil.pihost.core.engine.InstallProgress) : ChatUiState

    /** 安装失败或引擎起不来。[cause] 必含可读原因（VM 侧还会拼上 stderr 尾部）。 */
    data class Failed(val cause: String) : ChatUiState

    /** 引擎就绪，[sessionId] = host 生成的 UUID（= argv 里的 `--session-id`）。 */
    data class Ready(val sessionId: String) : ChatUiState
}

/** 轮次状态（§5.1）：决定 InputBar 可用性、Abort 可用性与权限占位。 */
enum class TurnState { Idle, Streaming, PermissionPending }

/** 工具卡状态灯（§6：Running=转圈、Ok=绿点、Error=红点）。 */
enum class ToolStatus { Running, Ok, Error }

/**
 * 待应答的权限请求（§5.2）。
 *
 * [timeoutMs] 是 pi 侧给的总超时（`extension_ui_request.timeout`，单位毫秒，
 * 缺省按 RECON-P2D §5 的实测值 15000）；[remainingMs] 由 VM 的倒计时协程逐秒改写，
 * 弹框直接显示它——倒计时不放 UI 层，否则弹框重组会重置计时。
 */
data class PendingPermission(
    val id: String,
    val method: String,
    val title: String?,
    val message: String?,
    val timeoutMs: Long,
    val remainingMs: Long,
) {
    /** 向上取整的剩余秒数（0 表示已到期）。 */
    val remainingSeconds: Int get() = ((remainingMs + 999) / 1000).toInt().coerceAtLeast(0)
}

/** toolcall 累积记录：Delta 是**原始 JSON 片段**，只拼接，End 才尝试解析（§5.1）。 */
data class ToolCallRecord(
    val callId: String,
    val toolName: String,
    val rawArgs: String,
    val parsedName: String? = null,
)

/** 转录条目（§6 的三类渲染对象）。 */
sealed interface TranscriptItem {
    val id: Int

    /** 用户气泡（右对齐）。 */
    data class UserItem(override val id: Int, val text: String) : TranscriptItem

    /**
     * assistant 回复：thinking 折叠区 + 正文 + toolcall 卡列表。
     *
     * [settled] = 已收到 `text_end`/`message_end`，正文不再追加；
     * 未定稿时流式 delta 仍会往里拼（末条替换）。
     */
    data class AssistantItem(
        override val id: Int,
        val text: String = "",
        val thinking: String = "",
        val toolCalls: List<ToolCallRecord> = emptyList(),
        val settled: Boolean = false,
    ) : TranscriptItem

    /** 工具执行卡（`tool_execution_*` 三事件的落点）。 */
    data class ToolItem(
        override val id: Int,
        val callId: String,
        val toolName: String,
        val status: ToolStatus = ToolStatus.Running,
        val output: String = "",
    ) : TranscriptItem
}

/**
 * `Message.content` 的两态取值（冻结区 `PiMessage.content: JsonElement?`）：
 * 字符串 或 块数组（`{type:"text",text}` / `{type:"thinking",thinking}` /
 * `{type:"toolCall",id,name,arguments}`）。protocol.md §5.2 只保证「块」这一形状，
 * 但 user 消息在抓包里是纯字符串——两种都得吃，否则用户气泡永远是空。
 */
internal fun contentToText(content: JsonElement?): String = when (content) {
    null -> ""
    is JsonPrimitive -> content.contentOrNull.orEmpty()
    is JsonArray -> content.joinToString(separator = "") { element -> blockText(element) }
    is JsonObject -> blockText(content)
    else -> ""
}

/** 从块数组里取 thinking（assistant 消息的推理块）。 */
internal fun contentToThinking(content: JsonElement?): String = when (content) {
    is JsonArray -> content.mapNotNull { block ->
        (block as? JsonObject)?.get("thinking")?.jsonPrimitive?.contentOrNull
    }.joinToString(separator = "")
    else -> ""
}

private fun blockText(element: JsonElement): String = when (element) {
    is JsonPrimitive -> element.contentOrNull.orEmpty()
    is JsonObject -> element["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
    else -> ""
}

/** `tool_execution_*` 的 result/partialResult 取文本（`{content:string|Block[]}`，§4.1）。 */
internal fun resultToText(result: JsonElement?): String = when (result) {
    null -> ""
    is JsonPrimitive -> result.contentOrNull.orEmpty()
    is JsonObject -> contentToText(result["content"]).ifEmpty { result.toString() }
    is JsonArray -> contentToText(result)
    else -> result.toString()
}

/**
 * fenced-code 切段（§6：P2D 正文用 Text + monospace 代码块，完整 markdown 留 P2E）。
 *
 * 只认 ``` 围栏：奇偶交替，围栏内 [CodeSegment.monospace] = true。
 * 没闭合的围栏（流式途中）按「直到文末」处理，避免半句话闪烁。
 */
internal fun splitFencedSegments(text: String): List<TextSegment> {
    if (text.isEmpty()) return emptyList()
    val out = ArrayList<TextSegment>(4)
    var monospace = false
    val buffer = StringBuilder()
    text.lineSequence().forEach { line ->
        if (line.trimStart().startsWith(FENCE)) {
            if (buffer.isNotEmpty()) {
                out.add(TextSegment(buffer.toString(), monospace))
                buffer.setLength(0)
            }
            monospace = !monospace
        } else {
            if (buffer.isNotEmpty()) buffer.append('\n')
            buffer.append(line)
        }
    }
    if (buffer.isNotEmpty()) out.add(TextSegment(buffer.toString(), monospace))
    return out
}

/** 正文的一段（P2D 只有「普通」与「``` 围栏内」两类）。 */
data class TextSegment(val text: String, val monospace: Boolean)

private const val FENCE: String = "```"
