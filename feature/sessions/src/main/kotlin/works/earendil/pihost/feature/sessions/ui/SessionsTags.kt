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
}

/** 行的第二行：cwd + 消息数 + 体积（§4.1 要展示「消息数」，§13 偏差 8：usage 从简）。 */
@Immutable
data class SessionRowView(
    val title: String,
    val subtitle: String,
    val meta: String,
)
