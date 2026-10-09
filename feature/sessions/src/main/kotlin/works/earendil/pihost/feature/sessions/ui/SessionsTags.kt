package works.earendil.pihost.feature.sessions.ui

import androidx.compose.runtime.Immutable

/**
 * 会话列表屏的测试标签（与 `:feature:chat` 的 ChatTags 同一套路：
 * UI 测试靠 tag 定位，字符串常量集中一处，改文案不破测试）。
 */
@Immutable
object SessionsTags {
    const val SCREEN: String = "screen-sessions"
    const val LIST: String = "sessions-list"
    const val EMPTY: String = "sessions-empty"
    const val LOADING: String = "sessions-loading"
    const val FAILURE: String = "sessions-failure"
    const val NOTICE: String = "sessions-notice"
    const val ROW: String = "sessions-row"
    const val FAB_NEW: String = "sessions-fab-new"
    const val BUTTON_SETTINGS: String = "sessions-button-settings"
    const val BUTTON_REFRESH: String = "sessions-button-refresh"
    const val DELETE_DIALOG: String = "sessions-delete-dialog"
    const val DELETE_CONFIRM: String = "sessions-delete-confirm"
    const val DELETE_CANCEL: String = "sessions-delete-cancel"

    // ── P3C §3.7：项目条与「新建项目」卡 ──────────────────────────────────────
    //
    // `FAB_NEW` 已被「新建会话」占着，本期**不动它**（动它等于把旧语义改名，
    // `SessionsScreenTest` 里 v/u 两代用例全要重写，而语义本来没错）。
    // 新建项目走 BUTTON_NEW_PROJECT 这一条独立入口。

    const val BUTTON_NEW_PROJECT: String = "sessions-button-new-project"
    const val CARD_NEW_PROJECT: String = "sessions-card-new-project"
    const val FIELD_PROJECT_NAME: String = "sessions-field-project-name"
    const val CHECK_PROJECT_GIT: String = "sessions-check-project-git"
    const val BUTTON_PROJECT_CREATE: String = "sessions-button-project-create"
    const val BUTTON_PROJECT_CANCEL: String = "sessions-button-project-cancel"
    const val ROW_PROJECT: String = "sessions-row-project"
    const val LABEL_ACTIVE_PROJECT: String = "sessions-active-project"

    /** 项目行带名字后缀，UI 测试可以精确点到某一个项目。 */
    fun project(name: String): String = "$ROW_PROJECT:$name"

    /** 行标签带路径后缀，UI 测试可以精确定位某一条会话。 */
    fun row(sessionPath: String): String = "$ROW:${sessionPath.substringAfterLast('/')}"
}

/** 会话列表屏的固定文案。 */
@Immutable
object SessionsStrings {
    const val TITLE: String = "会话"
    const val ENTRY_SETTINGS: String = "设置"
    const val ACTION_REFRESH: String = "刷新"
    const val ACTION_NEW: String = "新建会话"
    const val LOADING: String = "扫描中…"
    const val REFRESHING_SUFFIX: String = "（刷新中）"
    const val FAILURE_PREFIX: String = "扫描失败："
    const val NOTICE_PREFIX: String = "提示："

    const val EMPTY_TITLE: String = "还没有会话"
    const val EMPTY_BODY: String = "pi 的会话存在这台设备的私有目录里，App 不复制一份。\n" +
        "先在聊天页启动会话，或直接新建一条。"

    const val NO_MESSAGES: String = "（无消息）"
    const val NAME_FALLBACK: String = "未命名会话"

    const val DELETE_TITLE: String = "删除这条会话？"
    const val DELETE_BODY: String = "这会直接删掉 pi 存储里的那个 .jsonl 文件，不可撤销。\n" +
        "如果它正是当前会话正在写入的文件，pi 侧的后续行为不作保证。"
    const val DELETE_CONFIRM: String = "删除"
    const val DELETE_CANCEL: String = "取消"

    /** 时间相对化的单位词（纯函数 [SessionsTime.relative] 用，见文件末）。 */
    const val JUST_NOW: String = "刚刚"
    const val MINUTES_AGO: String = "分钟前"
    const val HOURS_AGO: String = "小时前"
    const val DAYS_AGO: String = "天前"
    const val FUTURE: String = "（时钟超前）"

    // ── P3C §3.7：项目条与新建项目卡 ──────────────────────────────────────────

    const val PROJECTS_TITLE: String = "项目"
    const val PROJECT_ACTIVE: String = "活动"
    const val BUTTON_NEW_PROJECT_LABEL: String = "新建项目"
    const val PROJECT_FIELD_LABEL: String = "项目名"
    const val PROJECT_FIELD_HINT: String = "字母、数字、中文与 - _ . ；不能以点开头"
    const val PROJECT_CHECK_GIT: String = "同时初始化 git 仓库"
    const val PROJECT_CREATE: String = "创建"
    const val PROJECT_CANCEL: String = "取消"

    /**
     * 非法名文案。
     *
     * **故意不提具体规则的细节**：真正的判据在 `ProjectNames.validate`，
     * 这里把规则复述一遍就会在规则改动时变成一句假话（同一个坑见 P3B §3.3 的思考档白名单：
     * UI 只说「不合规则」，具体清单永远以服务端/校验器为准）。
     */
    const val PROJECT_INVALID_NAME: String = "项目名不合法（见上方提示）"

    /** 空列表时的引导：`projects/` 没被建过是首启的正常形状，不该显示成错误。 */
    const val PROJECTS_EMPTY: String = "还没有项目，新会话会落在 default 里"
}

/** 行的第二行：cwd + 消息数 + 体积（§4.1 要展示「消息数」，§13 偏差 8：usage 从简）。 */
@Immutable
data class SessionRowView(
    val title: String,
    val subtitle: String,
    val meta: String,
)
