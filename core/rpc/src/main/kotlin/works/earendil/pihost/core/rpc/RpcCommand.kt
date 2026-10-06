package works.earendil.pihost.core.rpc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 宿主→服务端命令（protocol.md §3，34 条）。
 *
 * 序列化走 kotlinx sealed 多态：判别字段 "type" = [SerialName]，与线格式一致。
 * 出站用 [PiJsonOut]（encodeDefaults=false）：可选字段缺省不写线，与抓包样本一致。
 * id 关联字段**不在**此处建模——由 [encodeCommand] 注入，避免 34 个类各带一份 id。
 *
 * mode 类参数（steering/followUp 的 "all"|"one-at-a-time" 等）保持 String：
 * 线上字符串集合已冻结，枚举校验属 UI 层职责（参照 set_thinking_level 无服务端
 * 校验的教训，§3.1）。
 */
@Serializable
sealed interface RpcCommand {

    // §3.1 会话与模型
    @Serializable
    @SerialName("get_state")
    data object GetState : RpcCommand

    @Serializable
    @SerialName("set_model")
    data class SetModel(val provider: String, val modelId: String) : RpcCommand

    @Serializable
    @SerialName("cycle_model")
    data object CycleModel : RpcCommand

    @Serializable
    @SerialName("get_available_models")
    data object GetAvailableModels : RpcCommand

    @Serializable
    @SerialName("set_thinking_level")
    data class SetThinkingLevel(val level: String) : RpcCommand

    @Serializable
    @SerialName("cycle_thinking_level")
    data object CycleThinkingLevel : RpcCommand

    @Serializable
    @SerialName("get_available_thinking_levels")
    data object GetAvailableThinkingLevels : RpcCommand

    @Serializable
    @SerialName("set_steering_mode")
    data class SetSteeringMode(val mode: String) : RpcCommand

    @Serializable
    @SerialName("set_follow_up_mode")
    data class SetFollowUpMode(val mode: String) : RpcCommand

    // §3.2 对话与队列
    @Serializable
    @SerialName("prompt")
    data class Prompt(
        val message: String,
        val images: List<String>? = null,
    ) : RpcCommand

    @Serializable
    @SerialName("steer")
    data class Steer(val message: String) : RpcCommand

    @Serializable
    @SerialName("follow_up")
    data class FollowUp(val message: String) : RpcCommand

    @Serializable
    @SerialName("abort")
    data object Abort : RpcCommand

    @Serializable
    @SerialName("clear_queue")
    data object ClearQueue : RpcCommand

    // §3.3 工具与执行
    @Serializable
    @SerialName("bash")
    data class Bash(
        val command: String,
        val excludeFromContext: Boolean? = null,
    ) : RpcCommand

    @Serializable
    @SerialName("abort_bash")
    data object AbortBash : RpcCommand

    @Serializable
    @SerialName("get_session_stats")
    data object GetSessionStats : RpcCommand

    // §3.4 重试
    @Serializable
    @SerialName("set_auto_retry")
    data class SetAutoRetry(val enabled: Boolean) : RpcCommand

    @Serializable
    @SerialName("abort_retry")
    data object AbortRetry : RpcCommand

    // §3.5 会话持久化
    @Serializable
    @SerialName("new_session")
    data class NewSession(val parentSession: String? = null) : RpcCommand

    @Serializable
    @SerialName("switch_session")
    data class SwitchSession(val sessionPath: String) : RpcCommand

    @Serializable
    @SerialName("fork")
    data class Fork(val entryId: String) : RpcCommand

    @Serializable
    @SerialName("clone")
    data object Clone : RpcCommand

    @Serializable
    @SerialName("get_fork_messages")
    data object GetForkMessages : RpcCommand

    @Serializable
    @SerialName("export_html")
    data class ExportHtml(val outputPath: String? = null) : RpcCommand

    // §3.6 查询
    @Serializable
    @SerialName("get_entries")
    data class GetEntries(val since: String? = null) : RpcCommand

    @Serializable
    @SerialName("get_tree")
    data object GetTree : RpcCommand

    @Serializable
    @SerialName("get_messages")
    data object GetMessages : RpcCommand

    @Serializable
    @SerialName("get_last_assistant_text")
    data object GetLastAssistantText : RpcCommand

    @Serializable
    @SerialName("get_commands")
    data object GetCommands : RpcCommand

    @Serializable
    @SerialName("compact")
    data class Compact(val customInstructions: String? = null) : RpcCommand

    @Serializable
    @SerialName("set_auto_compaction")
    data class SetAutoCompaction(val enabled: Boolean) : RpcCommand

    @Serializable
    @SerialName("set_session_name")
    data class SetSessionName(val name: String) : RpcCommand
}

/**
 * extension_ui_request 的应答（§3.7）。普通命令行形状（有 type 字段），
 * 但**不是** RpcCommand——无 response 回执；pi 侧 rpc-mode.ts 在命令分派前
 * 拦截 type=extension_ui_response。故独立建模，走 [encodeExtensionUiResponse]。
 *
 * 同一 uuid 关联；确认/取消二选一；都不给时（超时）服务端自走默认值
 * （confirm→false，§3.7）。
 */
@Serializable
data class ExtensionUiResponse(
    val id: String,
    val confirmed: Boolean? = null,
    val cancelled: Boolean? = null,
) {
    init {
        require((confirmed == null) != (cancelled == null)) {
            "exactly one of confirmed/cancelled required (id=$id)"
        }
    }
}
