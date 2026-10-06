package works.earendil.pihost.core.rpc

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 线编解码唯一出入口（protocol.md §1/§3/§4.1）。
 *
 * 入站：LineFramer 分帧出的行（已剥行尾 \r）→ [parsePiEvent] → [PiEvent]。
 * 出站：[encodeCommand] / [encodeExtensionUiResponse] → 含结尾 LF 的单行，
 * 可直接写子进程 stdin。
 *
 * 空行（LineFramer 保留空帧）由调用方跳过，本层不做特判。
 */

/** protocol.md §4.1 冻结的事件 type 全集；集合外的 type → [PiEvent.Unknown]。 */
val KNOWN_EVENT_TYPES: Set<String> = setOf(
    "response", "agent_start", "agent_end", "agent_settled", "turn_start", "turn_end",
    "message_start", "message_end", "message_update",
    "tool_execution_start", "tool_execution_update", "tool_execution_end",
    "bash_execution_update", "session_info_changed", "queue_update",
    "extension_ui_request", "auto_retry_start", "auto_retry_end", "entry_appended",
)

/**
 * 单行 → [PiEvent]。
 *
 * 已知 type 严格解码：形状漂移抛 [SerializationException]，让 golden 回放
 * 测试显式暴露协议变化（宁可测试红，不可运行时静默吞字段）。
 * 未知 type → [Unknown] 保留原文，绝不打断读循环。
 */
fun parsePiEvent(line: String): PiEvent {
    val obj = PiJsonIn.parseToJsonElement(line) as? JsonObject
        ?: throw SerializationException("rpc line is not a JSON object: ${line.take(120)}")
    val type = obj["type"]?.jsonPrimitive?.contentOrNull
        ?: throw SerializationException("rpc line has no type field: ${line.take(120)}")
    if (type !in KNOWN_EVENT_TYPES) return PiEvent.Unknown(obj)
    return PiJsonIn.decodeFromJsonElement(PiEvent.serializer(), obj)
}

/**
 * 命令 → 单行（含结尾 LF）。id 非空时注入（response 原样回显，§3）；
 * 可选字段缺省不写线（PiJsonOut.encodeDefaults=false，与抓包一致）。
 */
fun encodeCommand(id: String?, command: RpcCommand): String {
    val base = PiJsonOut.encodeToJsonElement(RpcCommand.serializer(), command) as JsonObject
    val full = if (id == null) base else JsonObject(base + ("id" to JsonPrimitive(id)))
    return full.toString() + "\n"
}

/** extension_ui_response → 单行（含结尾 LF）。非 RpcCommand（无应答回执），独立出口（§3.7）。 */
fun encodeExtensionUiResponse(response: ExtensionUiResponse): String {
    val base = PiJsonOut.encodeToJsonElement(ExtensionUiResponse.serializer(), response) as JsonObject
    return JsonObject(base + ("type" to JsonPrimitive("extension_ui_response"))).toString() + "\n"
}
