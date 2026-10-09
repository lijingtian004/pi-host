package works.earendil.pihost.feature.chat

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import works.earendil.pihost.core.rpc.PiJsonIn
import works.earendil.pihost.core.rpc.PiMessage

/**
 * `get_messages` 的回填解析（P3B §3.1，修「切回旧会话历史一片空白」这条 P1）。
 *
 * **为什么这一层住在 `feature/chat` 而不是 `core/rpc`**（§2 红线 2）：
 * 冻结区的 `PiMessage` 只建模到信封层，`content: JsonElement?` 是有意留白的透传口；
 * 深建模（哪些块上屏、什么形状变成哪条转录条目）是**渲染语义**，不是协议语义。
 * 把它写在 core/rpc 就等于让冻结区承担 UI 的演进压力，正与该区「v1.0.4 冻结」的定位相反。
 *
 * 两条纪律：
 * 1. **纯读**：本对象只解析与映射，不发命令、不碰磁盘、不写任何状态；
 * 2. **不抛**：形状不对一律返回 `null`（调用方据此挂「历史加载失败」提示），
 *    单条解码失败只跳过那一条并留 logcat——pi 前向演进多出来的块类型不能把整页历史打死。
 *
 * 映射规则的判据来自 `docs/protocol.md` §5.2（AgentMessage 线格式）与 golden 001/002/008 的实录形状。
 */
object ChatHistory {

    /**
     * `get_messages` 的 response.data（protocol.md §3.6：`{messages: AgentMessage[]}`）→ 消息列表。
     *
     * 判据严格照线格式：`data` 必须是 JsonObject，且 `messages` 必须是 JsonArray；
     * 缺字段、类型漂移、data 为 null → **返回 null**（不是空列表），调用方要区分
     * 「这个会话真的没历史」（空数组）与「回的东西不是我们认识的样子」。
     */
    fun parseMessages(data: JsonElement?): List<PiMessage>? {
        val root = data as? JsonObject ?: return null
        val array = root["messages"] as? JsonArray ?: return null
        return array.mapNotNull { element ->
            if (element !is JsonObject) {
                Log.w(LOG_TAG, "get_messages 里混进非对象消息块，已跳过那一条")
                return@mapNotNull null
            }
            runCatching { PiJsonIn.decodeFromJsonElement(PiMessage.serializer(), element) }
                .onFailure { Log.w(LOG_TAG, "get_messages 单条解码失败（role=${element["role"]}），已跳过那一条", it) }
                .getOrNull()
        }
    }

    /**
     * 消息列表 → 回填用的转录条目列表。
     *
     * id 由调用方的 `nextItemId()` 分配（[idAlloc]）——本对象不持有任何序列状态，
     * 所以同一份消息喂两次得到的是「形状相同、id 各表」的两份结果，可测且无副作用。
     *
     * 四条映射规则（§3.1 原文，逐条有测试）：
     * - `system` → **跳过**：P2H 起 `message_start(system)` 也没上屏，回填与首屏一致才不吓人；
     * - `user` → [TranscriptItem.UserItem]：`content` 两种形状（块数组 / 裸字符串）都吃，
     *   纯空白则丢弃（与 [ChatViewModel.sendPrompt] 的 `isBlank` 守卫同一条判据）；
     * - `assistant` → [TranscriptItem.AssistantItem]（`settled=true`，历史不会再追加）；
     *   `stopReason="error"` 且正文与 thinking 都空 → [TranscriptItem.NoteItem]
     *   （措辞沿用 P2G 的错误渲染前缀「本轮异常：」，用户看到的是同一种东西）；
     * - `toolResult` → [TranscriptItem.ToolItem] 终态：`isError` → [ToolStatus.Error]，否则 [ToolStatus.Ok]。
     *   回填**不记审计**——TOOL_START/END 记的是一次真实执行的开始/结束，把历史重放算进去
     *   就是往审计里编造事件（§3.1 原文「回填不算工具执行」）。
     */
    fun toTranscriptItems(messages: List<PiMessage>, idAlloc: () -> Int): List<TranscriptItem> =
        messages.mapNotNull { message ->
            when (message.role) {
                ROLE_SYSTEM -> null

                ROLE_USER -> contentToText(message.content)
                    .takeIf { it.isNotBlank() }
                    ?.let { TranscriptItem.UserItem(idAlloc(), it) }

                ROLE_ASSISTANT -> assistantItem(message, idAlloc)

                ROLE_TOOL_RESULT -> TranscriptItem.ToolItem(
                    id = idAlloc(),
                    callId = message.toolCallId.orEmpty(),
                    toolName = message.toolName.orEmpty(),
                    status = if (message.isError == true) ToolStatus.Error else ToolStatus.Ok,
                    output = contentToText(message.content),
                )

                else -> null
            }
        }

    private fun assistantItem(message: PiMessage, idAlloc: () -> Int): TranscriptItem {
        val text = contentToText(message.content)
        val thinking = contentToThinking(message.content)
        if (message.stopReason == STOP_REASON_ERROR && text.isEmpty() && thinking.isEmpty()) {
            // errorMessage 是 golden 008 实测字段（provider 500 那一轮），没有就给 stopReason，
            // 两者都缺也留一行「本轮异常：error」，总之不留一条无声消失的历史。
            return TranscriptItem.NoteItem(
                idAlloc(),
                "$ASSISTANT_ERROR_PREFIX${message.errorMessage ?: message.stopReason.orEmpty()}".truncate(),
            )
        }
        return TranscriptItem.AssistantItem(
            id = idAlloc(),
            text = text,
            thinking = thinking,
            settled = true,
        )
    }

    private const val LOG_TAG = "PiHost/ChatHistory"
}

/**
 * `Message.role` 的四个取值（protocol.md §5.2），也是回填的分支判据。
 *
 * 四个常量住在这里而不是 [ChatViewModel] 的 private companion：回填与事件装配
 * 是同一条「role → 条目」规则的两个消费者，两处各写一份字符串迟早会漂。
 */
internal const val ROLE_SYSTEM: String = "system"
internal const val ROLE_USER: String = "user"
internal const val ROLE_ASSISTANT: String = "assistant"
internal const val ROLE_TOOL_RESULT: String = "toolResult"

/** assistant 错误轮的 stopReason 取值（protocol.md §5.2 的七值之一）。 */
internal const val STOP_REASON_ERROR: String = "error"

/** 错误前缀：与 [ChatViewModel] 的 `retryNote` 措辞同源，历史与当下看起来是同一件事。 */
internal const val ASSISTANT_ERROR_PREFIX: String = "本轮异常："

/** 回填失败的占位文案（§3.1 原文点名的字符串，UI/VM 测试按同一常量断言）。 */
internal const val BACKFILL_FAILED_TEXT: String = "历史加载失败"
