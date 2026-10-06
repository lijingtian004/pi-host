package works.earendil.pihost.feature.settings.ui

import androidx.compose.runtime.Immutable
import works.earendil.pihost.core.environment.MemTier

/**
 * 设置屏的测试标签（与 `SessionsTags`/`ChatTags` 同一套路：UI 测试按 tag 定位，文案改动不破测试）。
 */
@Immutable
object SettingsTags {
    const val SCREEN: String = "screen-settings"
    const val TIER_GROUP: String = "settings-tier-group"
    const val BUTTON_REFRESH_INFO: String = "settings-button-refresh-info"

    const val MEM_TIER_LOW: String = "settings-tier-LOW"
    const val MEM_TIER_MID: String = "settings-tier-MID"
    const val MEM_TIER_HIGH: String = "settings-tier-HIGH"

    const val PARALLEL_VALUE: String = "settings-parallel-value"
    const val PARALLEL_DECREMENT: String = "settings-parallel-decrement"
    const val PARALLEL_INCREMENT: String = "settings-parallel-increment"

    const val CARD_VERSION: String = "settings-card-version"
    const val CARD_PROBE: String = "settings-card-probe"
    const val PROBE_RUN: String = "settings-probe-run"
    const val PROBE_CLEAR: String = "settings-probe-clear"
    const val PROBE_TEXT: String = "settings-probe-text"

    const val NOTICE: String = "settings-notice"

    /** 三选一按枚举名打 tag，测试不依赖中文文案。 */
    fun tier(tier: MemTier): String = "settings-tier-${tier.name}"

    init {
        // 与上面三个常量保持一致（Kotlin 侧只有一处真相：tag 名由枚举派生）
        check(MEM_TIER_LOW == tier(MemTier.LOW) && MEM_TIER_MID == tier(MemTier.MID) && MEM_TIER_HIGH == tier(MemTier.HIGH))
    }
}

/** 设置屏的固定文案（规格书 §4.2 + §4.4 那条「下次启动会话生效」提示）。 */
@Immutable
object SettingsStrings {
    const val TITLE: String = "设置"
    const val ACTION_BACK: String = "返回"
    const val ACTION_REFRESH_INFO: String = "重新读取"

    const val SECTION_TIER: String = "node 堆内存档位"
    const val SECTION_PARALLEL: String = "并行会话上限"
    const val SECTION_PROBE: String = "引擎状态探针"
    const val SECTION_VERSION: String = "版本"
    const val SECTION_MODEL: String = "默认模型"

    /** §4.4 原文要求的提示。 */
    const val TIER_HINT: String = "改档位只影响**下次启动的会话**，当前活动会话不受影响。"

    const val PARALLEL_HINT: String = "超过上限时，聊天页的「启动会话」会被拦下并给出提示。"
    const val PARALLEL_VALUE_FORMAT: String = "%d 条"

    const val PROBE_RUN: String = "发送 get_state"
    const val PROBE_CLEAR: String = "清空"
    const val PROBE_IDLE: String = "还没探过。"
    const val PROBE_RUNNING: String = "已发送，等待应答…"
    const val PROBE_NO_SESSION: String = "没有活动会话：请先到聊天页启动一条。"
    const val PROBE_NO_BUS: String = "该会话的事件总线已关闭，探不了。"
    const val PROBE_SEND_FAILED: String = "命令没发出去："
    const val PROBE_TIMEOUT: String = "应答超时（5 秒）：进程可能已经死了。"
    const val PROBE_NOT_RESPONSE: String = "先到的不是 response：协议形状可能变了。"
    const val PROBE_FAILED: String = "pi 返回了 success=false，且没带 error 文本。"
    const val PROBE_ERROR_PREFIX: String = "失败："
    const val PROBE_TRUNCATED_SUFFIX: String = "\n…（已截断，只显前 500 字）"
    const val PROBE_SESSION_SUFFIX: String = "会话 "

    const val VERSION_APP: String = "App"
    const val VERSION_INSTALLED: String = "已装运行时"
    const val VERSION_BUNDLED: String = "随包清单"
    const val VERSION_NOT_INSTALLED: String = "未安装"
    const val VERSION_PENDING: String = "读取中…"
    const val VERSION_MISSING: String = "清单缺失（打包事故）"
    const val VERSION_MISMATCH: String = "下次进聊天页会重装一次"
    const val VERSION_MATCH: String = "已装版本与随包一致"

    const val MODEL_PI_DEFAULT: String = "pi 内置默认（未指定）"
    const val MODEL_HINT: String = "模型切换不在本里程碑（P2E 只读显示）。"

    const val NOTICE_AUTO_DISMISS_HINT: String = "（稍后自动收起）"

    /** 提示文案：写成功给「已保存 + 生效时机」，写盘失败/被回落各自说清。 */
    fun memTierSaved(tier: MemTier): String =
        "已保存：$tier（${tier.nodeOptions}）—— 下次启动会话生效"

    fun memTierUnchanged(tier: MemTier): String = "没写进去，仍是 $tier"

    fun parallelClamped(value: Int): String = "并行上限只能在 1..4，已夹紧到 $value 条"

    const val SETTING_WRITE_FAILED: String = "写入失败：设置保持原值"
}
