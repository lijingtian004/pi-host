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

    // ── §4.2 模型接入（新 tag 前缀照旧：`SCREEN + "-credential"` 系列，同 `:177` 的 `-model` 先例）──

    const val CREDENTIAL_SECTION: String = "screen-settings-credential"
    const val CREDENTIAL_PROVIDER_BUTTON: String = "screen-settings-credential-provider"
    const val CREDENTIAL_PICKER: String = "screen-settings-credential-picker"
    const val CREDENTIAL_KEY_FIELD: String = "screen-settings-credential-key"
    const val CREDENTIAL_SAVE: String = "screen-settings-credential-save"
    const val CREDENTIAL_REFRESH: String = "screen-settings-credential-refresh"
    const val CREDENTIAL_EMPTY: String = "screen-settings-credential-empty"

    /** 三选一按枚举名打 tag，测试不依赖中文文案。 */
    fun tier(tier: MemTier): String = "settings-tier-${tier.name}"

    /** 下拉里的一个 provider 行（tag 用 providerId，文案改展示名也不破测试）。 */
    fun credentialProviderItem(providerId: String): String =
        "screen-settings-credential-provider-item-$providerId"

    /** 已配置列表的一行。 */
    fun credentialRow(providerId: String): String = "screen-settings-credential-row-$providerId"

    /** 已配置一行的清除按钮。 */
    fun credentialClear(providerId: String): String = "screen-settings-credential-clear-$providerId"

    // ── P2H §3.2 自定义中转（tag 前缀照 §3.2 原文：`SCREEN + "-relay"` 系列）─────────

    const val RELAY_SECTION: String = "screen-settings-relay"
    const val RELAY_ID_FIELD: String = "screen-settings-relay-id"
    const val RELAY_URL_FIELD: String = "screen-settings-relay-url"
    const val RELAY_MODELS_FIELD: String = "screen-settings-relay-models"
    const val RELAY_KEY_FIELD: String = "screen-settings-relay-key"
    const val RELAY_API_BUTTON: String = "screen-settings-relay-api"
    const val RELAY_API_PICKER: String = "screen-settings-relay-api-picker"
    const val RELAY_SAVE: String = "screen-settings-relay-save"
    const val RELAY_REFRESH: String = "screen-settings-relay-refresh"
    const val RELAY_EMPTY: String = "screen-settings-relay-empty"
    const val RELAY_DEFAULT_BADGE: String = "screen-settings-relay-default-badge"

    /** 下拉里的一项（tag 用 api 原值，改展示名不破测试）。 */
    fun relayApiItem(api: String): String = "$RELAY_API_BUTTON-item-$api"

    /** 已配置中转的一行。 */
    fun relayRow(providerId: String): String = "$RELAY_SECTION-row-$providerId"

    /** 行里的「设为默认」。 */
    fun relaySetDefault(providerId: String): String = "$RELAY_SECTION-set-default-$providerId"

    /** 行里的「清除」。 */
    fun relayClear(providerId: String): String = "$RELAY_SECTION-clear-$providerId"

    /** 行尾的密钥状态（§2：只有「已配置 / 无可用密钥」两种字，永无明文）。 */
    fun relayRowKey(providerId: String): String = "$RELAY_SECTION-key-$providerId"

    init {
        // 与上面三个常量保持一致（Kotlin 侧只有一处真相：tag 名由枚举派生）
        check(MEM_TIER_LOW == tier(MemTier.LOW) && MEM_TIER_MID == tier(MemTier.MID) && MEM_TIER_HIGH == tier(MemTier.HIGH))
        // 同理：credential 系列的公共前缀必须就是规格书 §4.2 写的 `SCREEN + "-credential"`
        check(CREDENTIAL_SECTION == "$SCREEN-credential")
        check(CREDENTIAL_PROVIDER_BUTTON == "$CREDENTIAL_SECTION-provider")
        check(CREDENTIAL_KEY_FIELD == "$CREDENTIAL_SECTION-key")
        check(CREDENTIAL_SAVE == "$CREDENTIAL_SECTION-save")
        // P2H §3.2 点名的一致性：relay 系列的前缀必须是 `SCREEN + "-relay"`
        check(RELAY_SECTION == "$SCREEN-relay")
        check(RELAY_ID_FIELD == "$RELAY_SECTION-id")
        check(RELAY_URL_FIELD == "$RELAY_SECTION-url")
        check(RELAY_MODELS_FIELD == "$RELAY_SECTION-models")
        check(RELAY_KEY_FIELD == "$RELAY_SECTION-key")
        check(RELAY_API_BUTTON == "$RELAY_SECTION-api")
        check(RELAY_SAVE == "$RELAY_SECTION-save")
        check(RELAY_REFRESH == "$RELAY_SECTION-refresh")
        check(RELAY_EMPTY == "$RELAY_SECTION-empty")
        check(RELAY_DEFAULT_BADGE == "$RELAY_SECTION-default-badge")
        // 三个按 id 派生的函数与固定段名不能撞车：撞了就会让「点这行的清除」打到别的节点
        check(relayClear("x") != relaySetDefault("x") && relayRow("x") != relayClear("x"))
        check(relayApiItem("x") != RELAY_API_BUTTON)
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

    // ── §4.2 模型接入 ──────────────────────────────────────────────────────────────

    const val SECTION_CREDENTIAL: String = "模型接入"

    /** 生效时机说清楚：pi 只在 spawn 时读一次凭据（§3 定案），热加载不做，页面也不许暗示它会做。 */
    const val CREDENTIAL_HINT: String = "选提供方、填 API 密钥，写进 pi 的 auth.json；已在对话里的会话不吃新凭据。"

    const val CREDENTIAL_PROVIDER_LABEL: String = "提供方"
    const val CREDENTIAL_KEY_LABEL: String = "API 密钥"
    const val CREDENTIAL_SAVE: String = "保存"
    const val CREDENTIAL_CLEAR: String = "清除"

    /** 手工重读 auth.json（用户可能在文件管理器里动过，或换了台设备）。 */
    const val CREDENTIAL_REFRESH: String = "重读列表"
    const val CREDENTIAL_PICKER_TITLE: String = "选择提供方"
    const val CREDENTIAL_PICKER_CANCEL: String = "取消"
    const val CREDENTIAL_NONE: String = "还没配置任何提供方。"

    /** 已配置一行的两种状态（§2 红线：只显示状态，永不回显 key）。 */
    const val CREDENTIAL_CONFIGURED: String = "已配置"
    const val CREDENTIAL_NO_KEY: String = "无可用密钥"

    /** §4.2 原文那条 notice。 */
    const val CREDENTIAL_SAVED: String = "已保存，新会话生效（对话进行中请停止后重开）"

    const val CREDENTIAL_BLANK_KEY: String = "密钥是空的，没有写入。"

    fun credentialCleared(providerId: String): String =
        "已清除 $providerId，新会话生效（对话进行中请停止后重开）"

    /** 写盘/撤销失败：原因只来自 IOException 的 message（内容是路径，不含凭据，见 VM 的 reasonOf）。 */
    fun credentialWriteFailed(reason: String): String = "写入失败：$reason"

    // ── P2H §3.2 自定义中转 ────────────────────────────────────────────────────────

    const val SECTION_RELAY: String = "自定义中转（OpenAI 兼容）"

    const val RELAY_HINT: String = "写进 pi 的 models.json：填地址与模型 id，新会话即可用（对话进行中的会话不受影响）。"

    const val RELAY_ID_LABEL: String = "中转标识"
    const val RELAY_ID_HINT: String = "自己起个名字，例：my-relay（写进 models.json 的 providers 键名）"
    const val RELAY_URL_LABEL: String = "接口地址"

    /**
     * §3.4 要求报告点名那条提示的**落点**：它就在设置页 baseUrl 输入框的 supporting text 上，
     * 由 [RelaySection] 用 `Text(..., style = bodySmall)` 渲染（不是 placeholder、不是 tooltip）。
     * 为什么必须写这句：pi 把 `baseUrl` 当作**完整前缀**再拼 `/chat/completions`，
     * 只填 `https://relay.example.com` 会打到根路径上（`provider-composer.ts:289` 那条链没有默认路径）。
     */
    const val RELAY_URL_HINT: String = "要带路径前缀，例：https://relay.example.com/v1（pi 只补 /chat/completions）"

    const val RELAY_MODELS_LABEL: String = "模型 ID 列表"
    const val RELAY_MODELS_HINT: String = "逗号分隔多个，例：deepseek-chat,gpt-4o"
    const val RELAY_KEY_LABEL: String = "API 密钥"

    /** §3.2 那条语义要在屏幕上说出来，否则用户会以为留空 = 清空。 */
    const val RELAY_KEY_HINT: String = "留空 = 沿用已存密钥（只改地址或模型时不用重填）"

    const val RELAY_API_LABEL: String = "接口协议"
    const val RELAY_API_PICKER_TITLE: String = "选择接口协议"
    const val RELAY_API_PICKER_CANCEL: String = "取消"

    const val RELAY_SAVE: String = "保存中转"
    const val RELAY_REFRESH: String = "重读列表"
    const val RELAY_SET_DEFAULT: String = "设为默认"
    const val RELAY_CLEAR: String = "清除"
    const val RELAY_NONE: String = "还没配置任何中转站。"
    const val RELAY_DEFAULT_BADGE: String = "默认"

    /** 行尾那个模型数（§3.2 原文的行格式：`providerId · baseUrl · N 个模型`）。 */
    const val RELAY_MODEL_COUNT: String = "%d 个模型"

    /** §3.2 原文的两条 notice。 */
    const val RELAY_SAVED: String = "已保存，新会话生效"
    const val RELAY_DEFAULT_SET: String = "默认模型已钉选，新会话直接用它"

    const val RELAY_BLANK_ID: String = "中转标识是空的，没有写入。"
    const val RELAY_BLANK_URL: String = "接口地址是空的，没有写入：pi 收到没有 baseUrl 的 provider 会直接报错。"
    const val RELAY_BLANK_MODELS: String = "模型列表是空的，没有写入：至少要填一个模型 id。"

    /** 行里没有模型 id 时点不动「设为默认」，说清为什么。 */
    const val RELAY_NO_MODEL: String = "这条中转没有模型 id，钉不了默认。"

    fun relayCleared(providerId: String): String = "已清除 $providerId，新会话生效"

    /** 中转写盘失败：措辞与凭据通路一致，原因同样只来自 IOException 的 message（只含路径）。 */
    fun relayWriteFailed(reason: String): String = "写入失败：$reason"

    const val NOTICE_AUTO_DISMISS_HINT: String = "（稍后自动收起）"

    /** 提示文案：写成功给「已保存 + 生效时机」，写盘失败/被回落各自说清。 */
    fun memTierSaved(tier: MemTier): String =
        "已保存：$tier（${tier.nodeOptions}）—— 下次启动会话生效"

    fun memTierUnchanged(tier: MemTier): String = "没写进去，仍是 $tier"

    fun parallelClamped(value: Int): String = "并行上限只能在 1..4，已夹紧到 $value 条"

    const val SETTING_WRITE_FAILED: String = "写入失败：设置保持原值"
}
