package works.earendil.pihost.core.rpc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 服务端→宿主的每一行（protocol.md §1：response 与事件两类，帧格式同构）。
 * 统一为一个 sealed 层级，UI 层按类型过滤。
 *
 * 解码入口 [parsePiEvent]（RpcWire.kt）：判别字段 = "type"（kotlinx 默认
 * classDiscriminator，恰好与线格式一致）；未知 type → [Unknown] 保留原文，
 * 不抛异常打断读循环——pi 前向演进的新事件只能走这条兜底路径。
 *
 * 字段以 docs/protocol.md（v1.0.4 实测冻结版）为准；已知 type 严格解码，
 * 形状漂移抛 SerializationException，由 golden 回放测试显式暴露。
 */
@Serializable
sealed interface PiEvent {

    /** 对命令的应答（§1）。id 由客户端提供、原样回显；parse 错误应答无 id。 */
    @Serializable
    @SerialName("response")
    data class Response(
        val id: String? = null,
        val command: String,
        val success: Boolean,
        val data: JsonObject? = null,
        val error: String? = null,
    ) : PiEvent

    @Serializable
    @SerialName("agent_start")
    data object AgentStart : PiEvent

    /** §4.1：messages 为整轮 AgentMessage 投影，willRetry 由 auto-retry 驱动。 */
    @Serializable
    @SerialName("agent_end")
    data class AgentEnd(
        val messages: List<PiMessage> = emptyList(),
        val willRetry: Boolean = false,
    ) : PiEvent

    /** 一轮 prompt 的全部工作（含重试/队列消费）结束——下一条 prompt 的门槛（§2）。 */
    @Serializable
    @SerialName("agent_settled")
    data object AgentSettled : PiEvent

    @Serializable
    @SerialName("turn_start")
    data object TurnStart : PiEvent

    @Serializable
    @SerialName("turn_end")
    data class TurnEnd(
        val message: PiMessage,
        val toolResults: List<PiMessage> = emptyList(),
    ) : PiEvent

    @Serializable
    @SerialName("message_start")
    data class MessageStart(val message: PiMessage) : PiEvent

    @Serializable
    @SerialName("message_end")
    data class MessageEnd(val message: PiMessage) : PiEvent

    /** §4.2：assistantMessageEvent 为流式增量；usage 累计 token。 */
    @Serializable
    @SerialName("message_update")
    data class MessageUpdate(
        val usage: JsonElement? = null,
        val assistantMessageEvent: AssistantMessageEvent? = null,
    ) : PiEvent

    @Serializable
    @SerialName("tool_execution_start")
    data class ToolExecutionStart(
        val toolCallId: String,
        val toolName: String,
        val args: JsonElement? = null,
    ) : PiEvent

    @Serializable
    @SerialName("tool_execution_update")
    data class ToolExecutionUpdate(
        val toolCallId: String,
        val toolName: String,
        val args: JsonElement? = null,
        val partialResult: JsonElement? = null,
    ) : PiEvent

    /** 权限拒绝（扩展 block）路径：isError=true，result.content 带 block reason（§6）。 */
    @Serializable
    @SerialName("tool_execution_end")
    data class ToolExecutionEnd(
        val toolCallId: String,
        val toolName: String,
        val result: JsonObject,
        val isError: Boolean = false,
    ) : PiEvent

    @Serializable
    @SerialName("bash_execution_update")
    data class BashExecutionUpdate(
        val id: String? = null,
        val delta: String,
    ) : PiEvent

    /** §4.1：name 可为 undefined（序列化丢弃）——故可选。 */
    @Serializable
    @SerialName("session_info_changed")
    data class SessionInfoChanged(val name: String? = null) : PiEvent

    /** 每次入队发一次，内容=当前队列快照（§3.2）。 */
    @Serializable
    @SerialName("queue_update")
    data class QueueUpdate(
        val steering: List<String>,
        val followUp: List<String>,
    ) : PiEvent

    /** 权限桥根基（§3.7）：method ∈ confirm|select|input。无回执，应答走 RpcCommand.kt 的 ExtensionUiResponse。 */
    @Serializable
    @SerialName("extension_ui_request")
    data class ExtensionUiRequest(
        val id: String,
        val method: String,
        val title: String? = null,
        val message: String? = null,
        val timeout: Long? = null,
    ) : PiEvent

    /** §3.4：delayMs = baseDelayMs * 2^(attempt-1) 封顶 60s。 */
    @Serializable
    @SerialName("auto_retry_start")
    data class AutoRetryStart(
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Long,
        val errorMessage: String? = null,
    ) : PiEvent

    @Serializable
    @SerialName("auto_retry_end")
    data class AutoRetryEnd(
        val success: Boolean,
        val attempt: Int,
        val finalError: String? = null,
    ) : PiEvent

    /** 重试时为 context_edit{targetId,replacement:null}——失败尝试从模型投影删除（§4.1）。 */
    @Serializable
    @SerialName("entry_appended")
    data class EntryAppended(val entry: JsonObject) : PiEvent

    /** 未知 type 的兜底：保留原始 JSON，不抛。 */
    @Serializable
    @SerialName("__unknown")
    data class Unknown(val raw: JsonObject) : PiEvent
}

/**
 * Message 信封（§5.2）：role 区分 system/user/assistant/toolResult。
 * P1 只建模信封层；content 块（Text|Thinking|ToolCall|Image）的深建模属 P2
 * （UI 渲染层需求），以 JsonElement 透传不丢信息。
 */
@Serializable
data class PiMessage(
    val role: String,
    val content: JsonElement? = null,
    val stopReason: String? = null,
    val usage: JsonElement? = null,
    val responseId: String? = null,
    /** 毫秒时间戳（user/toolResult 携带）。 */
    val timestamp: Long? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val isError: Boolean? = null,
    /** assistant 错误轮（stopReason:"error"）携带。 */
    val errorMessage: String? = null,
    val sections: JsonObject? = null,
    val details: JsonElement? = null,
)

/**
 * assistantMessageEvent（§4.2）：message_update 的流式增量负载。
 * 判别字段同为 "type"，多态机制与 [PiEvent] 一致。
 *
 * toolcall_delta 的 delta 是**原始 JSON 串片段**（模型的分片输出），
 * 消费方需自行拼接后再解析成 toolCall——此处不做隐式重组。
 */
@Serializable
sealed interface AssistantMessageEvent {
    @Serializable
    @SerialName("text_start")
    data class TextStart(val contentIndex: Int) : AssistantMessageEvent

    @Serializable
    @SerialName("text_delta")
    data class TextDelta(val contentIndex: Int, val delta: String) : AssistantMessageEvent

    @Serializable
    @SerialName("text_end")
    data class TextEnd(val contentIndex: Int, val content: String) : AssistantMessageEvent

    @Serializable
    @SerialName("thinking_start")
    data class ThinkingStart(val contentIndex: Int) : AssistantMessageEvent

    @Serializable
    @SerialName("thinking_delta")
    data class ThinkingDelta(val contentIndex: Int, val delta: String) : AssistantMessageEvent

    @Serializable
    @SerialName("thinking_end")
    data class ThinkingEnd(val contentIndex: Int, val content: String) : AssistantMessageEvent

    @Serializable
    @SerialName("toolcall_start")
    data class ToolcallStart(
        val contentIndex: Int,
        val id: String,
        val toolName: String,
    ) : AssistantMessageEvent

    @Serializable
    @SerialName("toolcall_delta")
    data class ToolcallDelta(val contentIndex: Int, val delta: String) : AssistantMessageEvent

    @Serializable
    @SerialName("toolcall_end")
    data class ToolcallEnd(
        val contentIndex: Int,
        val toolCall: JsonObject,
    ) : AssistantMessageEvent

    /** §4.2：reason ∈ "stop" 等；message 为完成后的整条 assistant 消息投影。 */
    @Serializable
    @SerialName("done")
    data class Done(val reason: JsonElement? = null, val message: JsonElement? = null) : AssistantMessageEvent

    /** §4.2：reason ∈ "aborted"|"error"。 */
    @Serializable
    @SerialName("error")
    data class Error(val reason: String? = null, val error: JsonElement? = null) : AssistantMessageEvent

    /** 未知 AME 变体兜底：保留原文。解码路径见 RpcWire（未知 type 在此层级抛出，P2 再放宽）。 */
    @Serializable
    @SerialName("__unknown_am")
    data class UnknownAm(val raw: JsonObject) : AssistantMessageEvent
}
