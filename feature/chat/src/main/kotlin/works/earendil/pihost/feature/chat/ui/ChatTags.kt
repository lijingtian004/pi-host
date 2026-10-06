package works.earendil.pihost.feature.chat.ui

/**
 * 测试锚点（规格书 §6/§7：Compose UI 测试靠 tag 定位，文案会变，tag 不会）。
 *
 * 命名规则：`chat-*` 为结构节点，`tool-<callId>-*` / `user-<id>` 为逐条节点（前缀常量 + 后缀）。
 */
object ChatTags {
    const val SCREEN = "chat-screen"
    const val INSTALL_GATE = "chat-install-gate"
    const val INSTALL_BUTTON = "chat-install-button"
    const val INSTALL_RETRY_BUTTON = "chat-retry-button"
    const val INSTALL_CAUSE = "chat-install-cause"

    /** P2F §4.2(b)：Failed 屏的「复制详情」（把未截断的 cause 全文送进剪贴板）。 */
    const val INSTALL_COPY = "chat-install-copy"
    const val TOP_BAR = "chat-top-bar"
    const val ENGINE_STATUS = "chat-engine-status"
    const val QUEUE_BADGE = "chat-queue-badge"
    const val TRANSCRIPT = "chat-transcript"
    const val INPUT = "chat-input"
    const val SEND = "chat-send"
    const val ABORT = "chat-abort"
    const val PERMISSION_DIALOG = "chat-permission-dialog"
    const val PERMISSION_ALLOW_ONCE = "chat-allow-once"
    const val PERMISSION_ALLOW_SESSION = "chat-allow-session"
    const val PERMISSION_DENY = "chat-deny"
    const val NOTICE = "chat-notice"

    /** 逐条节点前缀（调用方拼 `-$id`）。 */
    const val USER_PREFIX = "chat-user"
    const val ASSISTANT_PREFIX = "chat-assistant"
    const val TOOL_PREFIX = "chat-tool"
    const val TOOL_OUTPUT_SUFFIX = "-output"

    /** §4.3：assistant 正文的 markwon 渲染节点（与气泡容器分开打 tag，好定位「渲染体在场」）。 */
    const val MARKDOWN_SUFFIX = "-md"

    /** §4.3：会话切换的「加载中」占位行。 */
    const val NOTE_PREFIX = "chat-note"

    fun user(id: Int) = "$USER_PREFIX-$id"
    fun assistant(id: Int) = "$ASSISTANT_PREFIX-$id"
    fun thinking(id: Int) = "$ASSISTANT_PREFIX-$id-thinking"
    fun markdown(id: Int) = "$ASSISTANT_PREFIX-$id$MARKDOWN_SUFFIX"
    fun note(id: Int) = "$NOTE_PREFIX-$id"
    fun tool(callId: String) = "$TOOL_PREFIX-$callId"
    fun toolOutput(callId: String) = "$TOOL_PREFIX-$callId$TOOL_OUTPUT_SUFFIX"
}

/** 权限三选一回调的形状（VM 的 `answerPermission` 有默认参数，函数引用不可直接传递）。 */
typealias PermissionAnswer = (allowOnce: Boolean, allowSession: Boolean, deny: Boolean) -> Unit
