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

    /**
     * P3C §3.6：顶栏的 git 分支 chip。
     *
     * 这颗 chip **只在目录真的是仓库时才存在**（`NotARepo` / `Unavailable` / 在飞 / `null`
     * 一律不渲染节点）。这不是省事，是为了不破既有断言：
     * `topBarShowsEngineShortCodeAndQueueBadge()` 用 `onNodeWithTag(...)` + `assertTextEquals`
     * 定位顶栏那几行字，一个**常驻**的新节点会多出一份可匹配文本，
     * 而「时有时无」的节点在没有 git 时根本不在树里。
     */
    const val GIT_CHIP = "chat-git-chip"

    /** §3.2：点徽标展开的队列明细（steering 在前、followUp 在后，逐条一个 tag）。 */
    const val QUEUE_DETAILS = "chat-queue-details"
    const val QUEUE_ITEM_PREFIX = "chat-queue-item"

    /** §3.3：顶栏模型 chip 与它的选择器对话框（列表 + 轮换 + 思考档白名单）。 */
    const val MODEL_CHIP = "chat-model-chip"
    const val MODEL_DIALOG = "chat-model-dialog"
    const val MODEL_ITEM_PREFIX = "chat-model-item"
    const val MODEL_CYCLE = "chat-model-cycle"
    const val MODEL_REFRESH = "chat-model-refresh"
    const val THINKING_ITEM_PREFIX = "chat-thinking-item"
    const val THINKING_CYCLE = "chat-thinking-cycle"

    /** §3.4：一行式统计条 + 展开明细 + 压缩入口 + 自动压缩开关。 */
    const val STATS_BAR = "chat-stats-bar"
    const val STATS_REFRESH = "chat-stats-refresh"
    const val STATS_DETAILS = "chat-stats-details"
    const val COMPACT_BUTTON = "chat-compact-button"
    const val COMPACT_DIALOG = "chat-compact-dialog"
    const val COMPACT_CONFIRM = "chat-compact-confirm"
    const val COMPACT_CANCEL = "chat-compact-cancel"
    const val AUTO_COMPACT_SWITCH = "chat-auto-compact-switch"
    const val TRANSCRIPT = "chat-transcript"
    const val INPUT = "chat-input"
    const val SEND = "chat-send"

    /** §3.2：流式期间的主按钮换文案（「发送」↔「插入」）但**不换 tag**——tag 定位点击，文案定位档位。 */
    const val FOLLOW_UP = "chat-follow-up"
    const val CLEAR_QUEUE = "chat-clear-queue"
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
    fun queueItem(index: Int) = "$QUEUE_ITEM_PREFIX-$index"

    /** §3.3：选择器里逐条模型 / 逐条思考档（下标即列表顺序，测试按同一顺序定位）。 */
    fun modelItem(index: Int) = "$MODEL_ITEM_PREFIX-$index"
    fun thinkingItem(index: Int) = "$THINKING_ITEM_PREFIX-$index"

    fun tool(callId: String) = "$TOOL_PREFIX-$callId"
    fun toolOutput(callId: String) = "$TOOL_PREFIX-$callId$TOOL_OUTPUT_SUFFIX"
}

/** 权限三选一回调的形状（VM 的 `answerPermission` 有默认参数，函数引用不可直接传递）。 */
typealias PermissionAnswer = (allowOnce: Boolean, allowSession: Boolean, deny: Boolean) -> Unit
