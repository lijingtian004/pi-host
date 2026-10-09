package works.earendil.pihost.feature.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §3.4 会话统计的解析与文案（编号 `stNN`，与 §3.3 的 `pNN`、§3.1 的 `hNN` 同一套编号风格）。
 *
 * 这一份用例的存在理由几乎全是「**别把探测到的形状当显然**」：
 * `percent` 到底是不是百分数、`cost` 到底是数字还是对象、`contextUsage` 整块缺失该说
 * 「0」还是「未知」——三个问题在 protocol.md 里都没有答案，只在
 * `/root/pi/.capture/probe33/rpc33.jsonl`（p13/p19/message_update/compaction_*）里有。
 */
class SessionStatsTest {

    @Test
    fun `st01 the probed get_session_stats payload parses end to end`() {
        val stats = SessionStatsParser.parse(probeStatsData())
        assertEquals(1, stats?.userMessages)
        assertEquals(2, stats?.assistantMessages)
        assertEquals(1, stats?.toolCalls)
        assertEquals(1, stats?.toolResults)
        assertEquals(5, stats?.totalMessages)
        assertEquals(200L, stats?.input)
        assertEquals(40L, stats?.output)
        assertEquals(240L, stats?.totalTokens)
        assertEquals(0.0, stats?.cost ?: -1.0, 0.0)
        assertEquals(120L, stats?.context?.tokens)
        assertEquals(128_000L, stats?.context?.contextWindow)
        assertEquals(0.09375, stats?.context?.percent ?: -1.0, 0.0)
    }

    @Test
    fun `st02 percent is already a percent - multiplying by 100 again would lie`() {
        // 探测：120/128000 = 0.0009375，而字段给的是 0.09375 → 它**已经**是百分数。
        // 若按「小数」理解再 ×100，这条会话会被报成用了 9.4% 的上下文。
        assertEquals("0.09%", ContextUsage(120L, 128_000L, 0.09375).percentText())
        assertEquals("50%", ContextUsage(64_000L, 128_000L, 50.0).percentText())
        assertEquals("0%", ContextUsage(0L, 128_000L, 0.0).percentText())
    }

    @Test
    fun `st03 a missing contextUsage is unknown, not zero`() {
        // tokens 在、contextUsage 整块缺席：计数照默认 0，但 ctx 那一格必须说「未知」
        val noCtx = SessionStatsParser.parse(
            buildJsonObject {
                put("totalMessages", 3)
                putJsonObject("tokens") { }
            },
        )
        assertEquals(3, noCtx?.totalMessages)
        assertNull("整块缺失 → null，统计条要能说「ctx 未知」", noCtx?.context)
        assertEquals("0 tok｜\$0.0000｜ctx 未知", noCtx?.barText())

        val zeroWindow = ContextUsage(tokens = 120L, contextWindow = 0L, percent = null)
        assertEquals("窗口未知时不算百分比，也不编一个 0% 出来", "120/? (—)", zeroWindow.barText())
    }

    @Test
    fun `st04 a drifted shape is null while absent counts default to zero`() {
        assertNull(SessionStatsParser.parse(null))
        assertNull(SessionStatsParser.parse(probeLevelData("off")))
        // 键名漂了（tokens 不再是对象）→ 整条按「形状不对」处理，而不是渲染一排 0
        assertNull(SessionStatsParser.parse(buildJsonObject { put("tokens", "不是对象") }))
        val sparse = SessionStatsParser.parse(
            buildJsonObject {
                put("cost", 0.5)
                putJsonObject("tokens") { put("total", 7) }
            },
        )
        assertEquals(
            "计数键缺位只影响那一格，不该让整条统计消失",
            SessionStats(totalTokens = 7L, cost = 0.5),
            sparse,
        )
    }

    @Test
    fun `st05 percent is recomputed from tokens and window when the field is absent`() {
        val recomputed = SessionStatsParser.parse(probeStatsData(percent = null))
        assertNull(recomputed?.context?.percent)
        assertEquals("120/128000 (0.09%)", recomputed?.context?.barText())
    }

    @Test
    fun `st06 usage cost is a nested object here but a bare number in get_session_stats`() {
        val usage = SessionStatsParser.parseUsage(probeUsageData(costTotal = 0.0031))
        assertEquals(100L, usage?.input)
        assertEquals(20L, usage?.output)
        assertEquals(120L, usage?.totalTokens)
        assertEquals(0.0031, usage?.cost ?: 0.0, 0.0)

        val bare = SessionStatsParser.parseUsage(buildJsonObject {
            put("totalTokens", 42)
            put("cost", 0.25)
        })
        assertEquals("同名词两种形状：对象取 total，裸数字直接取", 0.25, bare?.cost ?: 0.0, 0.0)
        assertEquals(42L, bare?.totalTokens)
    }

    @Test
    fun `st07 usage tolerates the absent reasoning key and rejects non-objects`() {
        assertEquals(
            TurnUsage(100L, 20L, 120L, 0.0),
            SessionStatsParser.parseUsage(probeUsageData(withReasoning = false)),
        )
        assertNull(SessionStatsParser.parseUsage(null))
        assertNull(SessionStatsParser.parseUsage(probeLevelData("off")))
    }

    @Test
    fun `st08 turn usage sums only across finalized messages`() {
        val a = TurnUsage(input = 100L, output = 20L, totalTokens = 120L, cost = 0.001)
        val b = TurnUsage(input = 50L, output = 10L, totalTokens = 60L, cost = 0.002)
        val sum = a + b
        assertEquals(TurnUsage(150L, 30L, 180L, 0.003), sum)
        assertTrue(TurnUsage.EMPTY.isEmpty)
        assertFalse(sum.isEmpty)
        assertEquals("120 tok｜${money(0.001)}", a.barText())
    }

    @Test
    fun `st09 get_state carries the two stats switches and absence stays unknown`() {
        assertEquals(true, SessionStatsParser.parseAutoCompaction(probeStateFlags(autoCompaction = true)))
        assertEquals(false, SessionStatsParser.parseAutoCompaction(probeStateFlags(autoCompaction = false)))
        assertNull(SessionStatsParser.parseAutoCompaction(probeStateFlags()))
        assertNull(SessionStatsParser.parseAutoCompaction(null))
        assertEquals(true, SessionStatsParser.parseIsCompacting(probeStateFlags(compacting = true)))
        assertNull(SessionStatsParser.parseIsCompacting(buildJsonObject { put("isCompacting", "true") }))
    }

    @Test
    fun `st10 compaction events are read as probed and pi's own error words survive`() {
        assertEquals("manual", SessionStatsParser.parseCompactionReason(probeCompactionStart()))
        assertNull(SessionStatsParser.parseCompactionEnd(probeCompactionEnd()))
        assertEquals(
            "失败原因原样上屏，不翻译成「请稍后再试」",
            "Compaction failed: Nothing to compact (session too small)",
            SessionStatsParser.parseCompactionEnd(
                probeCompactionEnd("Compaction failed: Nothing to compact (session too small)"),
            ),
        )
        assertNull(
            "空串不算一句错误",
            SessionStatsParser.parseCompactionEnd(probeCompactionEnd(errorMessage = "")),
        )
        assertNull(SessionStatsParser.parseCompactionReason(null))
    }

    @Test
    fun `st11 the one-line bar is stats plus live and says unloaded before the first reply`() {
        assertEquals("统计未加载", StatsUiState().barText())
        val withStats = StatsUiState(stats = SessionStatsParser.parse(probeStatsData()))
        assertEquals("240 tok｜${money(0.0)}｜120/128000 (0.09%)", withStats.barText())
        val live = withStats.copy(live = TurnUsage(100L, 20L, 120L, 0.0))
        assertEquals(
            "本轮那格只在真有用量时占位，否则白占一行",
            "240 tok｜${money(0.0)}｜120/128000 (0.09%)｜本轮 120 tok｜${money(0.0)}",
            live.barText(),
        )
        assertEquals("本轮 120 tok｜${money(0.0)}", live.liveText())
    }

    @Test
    fun `st12 money and percent keep enough digits that zero never looks like missing`() {
        assertEquals("\$0.0000", money(0.0))
        assertEquals("\$0.0031", money(0.0031))
        assertEquals("\$1.23", money(1.2345))
        assertEquals("0.09%", formatPercent(0.09375))
        assertEquals("100%", formatPercent(100.0))
        val stats = SessionStats(totalMessages = 5, cost = 0.0)
        assertEquals("user 0｜assistant 0｜toolCalls 0｜toolResults 0｜total 5", stats.detailsText())
        assertEquals("in 0｜out 0｜cacheR 0｜cacheW 0｜合计 0", stats.tokensText())
    }
}
