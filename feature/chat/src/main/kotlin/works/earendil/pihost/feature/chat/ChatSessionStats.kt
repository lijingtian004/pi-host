package works.earendil.pihost.feature.chat

import java.util.Locale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 会话统计条的解析与可视面（P3B §3.4）。
 *
 * 形状取自 §3.3 那次桌面基准探测（`rpc33.jsonl` 的 p13/p19，逐键照抄）；协议表 protocol.md §3.3
 * 只写到「`tokens,cost,contextUsage{tokens,contextWindow,percent}`」这一层，
 * 所以这里的键名与**可选性**都按真实回执定，而不是按表猜：
 *
 * ```
 * {"userMessages":1,"assistantMessages":2,"toolCalls":1,"toolResults":1,"totalMessages":5,
 *  "tokens":{"input":200,"output":40,"cacheRead":0,"cacheWrite":0,"total":240},"cost":0,
 *  "contextUsage":{"tokens":120,"contextWindow":128000,"percent":0.09375}}
 * ```
 *
 * 三条容易踩的（都在这份文件里有对应的用例）：
 * 1. `percent` **已经是百分数**（120/128000 = 0.0009375，字段给的是 0.09375），
 *    再乘一次 100 就把 0.09% 显示成 9.4%；
 * 2. `cost` 在 `get_session_stats` 这层是**裸数字**，而 `message_update.usage.cost` 是
 *    `{input,output,…,total}` **对象**——同一个词两种形状，各认各的（[SessionStatsParser.parseUsage]）；
 * 3. 缺计数键按 0（不至于让整条统计消失），但 `contextUsage` 整块缺失**是另一回事**：
 *    那要显示「ctx 未知」，而不是「0/0 (0%)」——所以 [SessionStats.context] 与
 *    [ContextUsage.percent] 都是可空的一等公民。
 */
data class SessionStats(
    val userMessages: Int = 0,
    val assistantMessages: Int = 0,
    val toolCalls: Int = 0,
    val toolResults: Int = 0,
    val totalMessages: Int = 0,
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val totalTokens: Long = 0,
    val cost: Double = 0.0,
    /** `contextUsage` 整块缺失 → null（未知），与「0 个 token」是两件事。 */
    val context: ContextUsage? = null,
) {
    /** 一行式统计条正文（§3.4 原文的 `tokens | cost | ctx tokens/window (percent%)`）。 */
    fun barText(): String = "$totalTokens tok｜${costText()}｜${context?.barText() ?: "ctx 未知"}"

    /** 展开态的明细行（§3.4 点名的五个计数）。 */
    fun detailsText(): String =
        "user $userMessages｜assistant $assistantMessages｜toolCalls $toolCalls｜" +
            "toolResults $toolResults｜total $totalMessages"

    /** 花费：0 也要说清是 0，留白看起来像「还没加载」。 */
    fun costText(): String = money(cost)

    /** token 明细：合计之外把 in/out/cache 摊开，用户才看得懂「贵在哪」。 */
    fun tokensText(): String =
        "in $input｜out $output｜cacheR $cacheRead｜cacheW $cacheWrite｜合计 $totalTokens"
}

/** `contextUsage{tokens,contextWindow,percent}`。 */
data class ContextUsage(
    val tokens: Long = 0,
    val contextWindow: Long = 0,
    /** **已是百分数**（见 [SessionStats] 注释 1）；缺键 → null。 */
    val percent: Double? = null,
) {
    fun barText(): String = "$tokens/${if (contextWindow <= 0) "?" else contextWindow} (${percentText()})"

    /**
     * 有 `percent` 就用它（pi 的口径）；缺了就按 tokens/window 现算——
     * 只有窗口已知才算得出来，否则一句「—」，不编一个 0% 给用户。
     */
    fun percentText(): String = when {
        percent != null -> formatPercent(percent)
        contextWindow > 0 -> formatPercent(tokens * 100.0 / contextWindow)
        else -> "—"
    }
}

/** 一次 agent 运行里「已经花掉」的用量（§3.4 的实时那格）。 */
data class TurnUsage(
    val input: Long = 0,
    val output: Long = 0,
    val totalTokens: Long = 0,
    val cost: Double = 0.0,
) {
    /** 只在**定稿的消息**之间相加（原因见 [SessionStatsParser.parseUsage] 的注释）。 */
    operator fun plus(other: TurnUsage): TurnUsage = TurnUsage(
        input = input + other.input,
        output = output + other.output,
        totalTokens = totalTokens + other.totalTokens,
        cost = cost + other.cost,
    )

    val isEmpty: Boolean get() = totalTokens == 0L && cost == 0.0

    fun barText(): String = "$totalTokens tok｜${money(cost)}"

    companion object {
        val EMPTY: TurnUsage = TurnUsage()
    }
}

/**
 * 统计条的可视面：每个字段都只有**一个写入口**（对应命令的回执或事件），
 * 没有「发出去了就当它生效」的本地乐观更新——与 §3.3 的 [ModelPickerState] 同一条纪律。
 */
data class StatsUiState(
    /** `get_session_stats` 的快照；null = 还没成功拉到过。 */
    val stats: SessionStats? = null,
    /** 本轮实时用量（`message_update` / `message_end` 喂）。 */
    val live: TurnUsage = TurnUsage.EMPTY,
    /** `get_state.autoCompactionEnabled`；null = 未知，UI 不假装它是「关着」。 */
    val autoCompaction: Boolean? = null,
    /** `compaction_start` / `compaction_end`（或 `get_state.isCompacting`）驱动的「正在压缩」。 */
    val compacting: Boolean = false,
) {
    /** 统计条一行文案；本轮用量没产生就不占地方。 */
    fun barText(): String {
        val head = stats?.barText() ?: "统计未加载"
        return if (live.isEmpty) head else "$head｜本轮 ${live.barText()}"
    }

    /** 展开态里那句「本轮 …」。 */
    fun liveText(): String = "本轮 ${live.barText()}"
}

/** 只服务统计条的解析（§3.4；留在 feature/chat，不进冻结区 core/rpc）。 */
object SessionStatsParser {

    /**
     * `get_session_stats` 的 data → [SessionStats]。
     *
     * 只有一道形状闸：**`tokens` 必须是个对象**。两条真实回执（p13/p19）都带它，
     * 而「整块缺失」只可能是键名漂了或嵌套变了——那种时候按 0 渲染会摆出一排
     * 「0 tok / $0.0000」，把一次协议破坏伪装成一个非常省的会话。
     * 计数键本身缺位则按 0（少一个 `toolResults` 不至于让统计条消失）。
     */
    fun parse(data: JsonElement?): SessionStats? {
        val obj = data as? JsonObject ?: return null
        val tokens = obj["tokens"] as? JsonObject ?: return null
        return SessionStats(
            userMessages = obj.intLike("userMessages"),
            assistantMessages = obj.intLike("assistantMessages"),
            toolCalls = obj.intLike("toolCalls"),
            toolResults = obj.intLike("toolResults"),
            totalMessages = obj.intLike("totalMessages"),
            input = tokens.longLike("input"),
            output = tokens.longLike("output"),
            cacheRead = tokens.longLike("cacheRead"),
            cacheWrite = tokens.longLike("cacheWrite"),
            totalTokens = tokens.longLike("total"),
            cost = obj.doubleLike("cost"),
            context = (obj["contextUsage"] as? JsonObject)?.let {
                ContextUsage(
                    tokens = it.longLike("tokens"),
                    contextWindow = it.longLike("contextWindow"),
                    percent = it.optDouble("percent"),
                )
            },
        )
    }

    /**
     * `message_update.usage` 与 `message_end.message.usage` → [TurnUsage]。
     *
     * 两处形状一致（探测：`{input,output,cacheRead,cacheWrite,totalTokens,cost{…,"total"}}`，
     * `reasoning` 偶尔缺席），所以是**一份实现吃两个来源**；
     * 但**用法不同**：`message_update` 的 usage 是这条消息的**累计快照**
     * （探测里 21 次 message_update 的 usage 一模一样），只取最后一次观测值；
     * 只有 `message_end` 那条是定稿、可以跨消息相加。把前者当增量累加，
     * 一轮下来数字按 delta 条数翻倍——这是 §3.4 最容易写错的一处。
     */
    fun parseUsage(usage: JsonElement?): TurnUsage? {
        val obj = usage as? JsonObject ?: return null
        // 闸：`totalTokens` 或 `input` 至少得有一个。探测里两条都恒在，
        // 全缺就说明这块 JSON 根本不是 usage（比如 `{"level":"off"}` 那种档位的形状）——
        // 认成「0 tok 的一条用量」会把统计条拉回零点，比报不出来更糟
        if (!obj.containsKey("totalTokens") && !obj.containsKey("input")) return null
        // 裸数字形态也认（同一个键在 get_session_stats 那边就是数字）：认成 0 比报错好，
        // 但两条路径都得留用例钉住，否则哪天线形漂了这边静默归零
        val cost = (obj["cost"] as? JsonObject)?.doubleLike("total") ?: obj.doubleLike("cost")
        return TurnUsage(
            input = obj.longLike("input"),
            output = obj.longLike("output"),
            totalTokens = obj.longLike("totalTokens"),
            cost = cost,
        )
    }

    /** `get_state.autoCompactionEnabled`；缺键 → null（未知）。 */
    fun parseAutoCompaction(data: JsonElement?): Boolean? = boolField(data, "autoCompactionEnabled")

    /** `get_state.isCompacting`（探测 p11 有这个键）；缺键 → null，由事件说了算。 */
    fun parseIsCompacting(data: JsonElement?): Boolean? = boolField(data, "isCompacting")

    /**
     * `compaction_end`（协议未列 → 走 [PiEvent.Unknown] 出口）：
     * `{reason,aborted,willRetry,errorMessage?}`（探测 p15：`Nothing to compact (session too small)`）。
     *
     * null = 没有 errorMessage，即压缩成功；非 null 一律**原样上屏**，不加工、不翻译成「请稍后再试」。
     */
    fun parseCompactionEnd(raw: JsonObject?): String? = raw.stringFieldNonBlank("errorMessage")

    /** `compaction_start` 的 `reason`（探测 `{"reason":"manual"}`）：把「手动/自动」说给用户。 */
    fun parseCompactionReason(raw: JsonObject?): String? = raw.stringFieldNonBlank("reason")

    private fun boolField(data: JsonElement?, key: String): Boolean? =
        (data as? JsonObject)
            // 只认真布尔字面量：`"true"`（带引号）在 kotlinx 的 booleanOrNull 里也会被读成 true，
            // 而线上没人这么发——把它当「未知」比当「已开」安全
            ?.let { (it[key] as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull }

    private fun JsonObject?.stringFieldNonBlank(key: String): String? =
        (this?.get(key) as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.takeIf { it.isNotEmpty() }

    private fun JsonObject?.decimal(key: String): Double =
        (this?.get(key) as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0

    private fun JsonObject?.optDouble(key: String): Double? =
        (this?.get(key) as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun JsonObject?.longLike(key: String): Long = decimal(key).toLong()

    private fun JsonObject?.intLike(key: String): Int = decimal(key).toInt()

    private fun JsonObject?.doubleLike(key: String): Double = decimal(key)
}

/** 金额口径统一在这儿：小于 1 块走四位小数，否则两位——避免出现 `$0.0` 或 `$1.23456789`。 */
internal fun money(value: Double): String =
    "$" + String.format(Locale.US, if (kotlin.math.abs(value) < 1.0) "%.4f" else "%.2f", value)

/** 百分数留两位小数（`0.09375` → `0.09%`），整数不留尾巴（`50.00` → `50%`）。 */
internal fun formatPercent(value: Double): String =
    String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.') + "%"
