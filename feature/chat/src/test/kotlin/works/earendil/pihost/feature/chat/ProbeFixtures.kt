package works.earendil.pihost.feature.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 探测形状的回执载荷（P3B §3.3 首步在桌面基准 `--mode rpc` 上手跑出来的真实 JSON）。
 *
 * 放在 test 源集的**顶层**而不是某个类的 companion 里，是因为两套用例都要同一张形状表：
 * VM 侧（ChatViewModelTest case49–case60）认解析与状态迁移，UI 侧（ChatScreenUiTest §3.3）
 * 认列表渲染与高亮。两处各抄一份 `buildJsonObject` 迟早漂——漂了之后 UI 绿、VM 红，
 * 而真正的线协议变更没人发现。
 *
 * 键的取舍照抄探测：`ProviderModel` 只留 `id/name/provider/reasoning/contextWindow`，
 * 而这里**故意**把 `api/baseUrl/maxTokens/input/cost` 一起摆出来，用来演
 * 「多出来的键一律忽略」这条解析纪律（ModelCatalogTest p03 直接盯着它）。
 */

/** 一条 model 对象：`models[]` 的元素、`set_model` 的整个 data、`cycle_model.model`、`get_state.model` 同形。 */
internal fun probeModelObject(
    id: String,
    name: String,
    reasoning: Boolean,
    contextWindow: Int = 128_000,
): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    put("api", "openai-completions")
    put("provider", "mock")
    put("baseUrl", "http://127.0.0.1:8787/v1")
    put("reasoning", reasoning)
    put("input", JsonArray(listOf(JsonPrimitive("text"))))
    put("contextWindow", contextWindow)
    put("maxTokens", JsonPrimitive(16384))
}

/** `get_available_models` 的 data：`{"models":[…]}`，两条 mock 模型（第二条 reasoning:true、窗口更大）。 */
internal fun probeModelsData(): JsonObject = buildJsonObject {
    put(
        "models",
        JsonArray(
            listOf(
                probeModelObject("mock-1", "Mock Model 1", reasoning = false),
                probeModelObject("mock-2", "Mock Model 2", reasoning = true, contextWindow = 200_000),
            ),
        ),
    )
}

/** 认证没配好时的真实回形：键在、数组空（与「没拉过」是两件事，chip 措辞据此分档）。 */
internal fun probeEmptyModelsData(): JsonObject =
    buildJsonObject { put("models", JsonArray(emptyList())) }

/**
 * `get_available_thinking_levels` 的 data。
 *
 * 探测实测两档：`reasoning:false` → `{"levels":["off"]}`；`reasoning:true` → 五档。
 * 白名单是唯一的档位出口（`set_thinking_level` 服务端不校验，探测 p09 对 reasoning:false
 * 设 "high" 也回 success），所以签名收 `vararg`，让用例能演出「列表里根本没有 high」的形状。
 */
internal fun probeLevelsData(vararg levels: String): JsonObject =
    buildJsonObject { put("levels", JsonArray(levels.map { JsonPrimitive(it) })) }

/** `get_state` 的 data：外层 `model` + `thinkingLevel`，其余键照探测留着（§3.4 也吃这条）。 */
internal fun probeStateData(id: String, name: String, thinkingLevel: String): JsonObject = buildJsonObject {
    put("model", probeModelObject(id, name, reasoning = false))
    put("thinkingLevel", thinkingLevel)
    put("isStreaming", JsonPrimitive(false))
    put("isCompacting", JsonPrimitive(false))
    put("steeringMode", "all")
    put("followUpMode", "all")
    put("autoCompactionEnabled", JsonPrimitive(true))
    put("messageCount", JsonPrimitive(5))
    put("pendingMessageCount", JsonPrimitive(0))
}

/** `cycle_model` 的 data：`{model, thinkingLevel, isScoped}`（探测 p07）。 */
internal fun probeCycleData(id: String, name: String, thinkingLevel: String): JsonObject = buildJsonObject {
    put("model", probeModelObject(id, name, reasoning = true, contextWindow = 200_000))
    put("thinkingLevel", thinkingLevel)
    put("isScoped", JsonPrimitive(false))
}

/** `set_model` 的 data **就是那条 model 对象本身**，没有外层键（探测 p03）。 */
internal fun probeSetModelData(id: String, name: String): JsonObject =
    probeModelObject(id, name, reasoning = true, contextWindow = 200_000)

/** `cycle_thinking_level` 的 data：`{"level":"high"}`——键名是 `level`，不是 `thinkingLevel`。 */
internal fun probeLevelData(level: String): JsonObject = buildJsonObject { put("level", level) }

/** `thinking_level_changed` 事件（协议未列，走 PiEvent.Unknown）：`{"type":…,"level":…}`。 */
internal fun probeThinkingLevelChanged(level: String): JsonObject = buildJsonObject {
    put("type", "thinking_level_changed")
    put("level", level)
}

// ── §3.4 会话统计：逐键照 rpc33.jsonl 的 p13/p19、message_update 与 compaction_* ──────

/**
 * `get_session_stats` 的 data（探测 p13 原文，`sessionFile`/`sessionId` 留着：
 * [SessionStatsParser] 必须能无视它俩，只数出来的那几个键才是统计条要的东西）。
 */
internal fun probeStatsData(
    totalTokens: Int = 240,
    contextTokens: Int = 120,
    contextWindow: Int = 128_000,
    percent: Double? = 0.09375,
): JsonObject = buildJsonObject {
    put("sessionFile", "/root/.pi/agent/sessions/2026-10-09T05-03-15-088Z_01a11f0b.jsonl")
    put("sessionId", "01a11f0b-468f-712a-90d4-aa4fa5772d31")
    put("userMessages", 1)
    put("assistantMessages", 2)
    put("toolCalls", 1)
    put("toolResults", 1)
    put("totalMessages", 5)
    put("tokens", buildJsonObject {
        put("input", 200)
        put("output", 40)
        put("cacheRead", 0)
        put("cacheWrite", 0)
        put("total", totalTokens)
    })
    put("cost", 0)
    put("contextUsage", buildJsonObject {
        put("tokens", contextTokens)
        put("contextWindow", contextWindow)
        percent?.let { put("percent", it) }
    })
}

/** `message_update.usage` / `message_end.message.usage`（探测：`cost` 是**对象**，`reasoning` 可缺）。 */
internal fun probeUsageData(
    input: Int = 100,
    output: Int = 20,
    totalTokens: Int = 120,
    costTotal: Double = 0.0,
    withReasoning: Boolean = true,
): JsonObject = buildJsonObject {
    put("input", input)
    put("output", output)
    put("cacheRead", 0)
    put("cacheWrite", 0)
    if (withReasoning) put("reasoning", 0)
    put("totalTokens", totalTokens)
    put("cost", buildJsonObject {
        put("input", 0)
        put("output", 0)
        put("cacheRead", 0)
        put("cacheWrite", 0)
        put("total", costTotal)
    })
}

/** `get_state` 里统计条要吃的两个键（探测 p11：`autoCompactionEnabled` / `isCompacting`）。 */
internal fun probeStateFlags(autoCompaction: Boolean? = null, compacting: Boolean? = null): JsonObject =
    buildJsonObject {
        autoCompaction?.let { put("autoCompactionEnabled", JsonPrimitive(it)) }
        compacting?.let { put("isCompacting", JsonPrimitive(it)) }
    }

/** `compaction_start`（协议未列，走 PiEvent.Unknown）：`{"type":…,"reason":"manual"}`。 */
internal fun probeCompactionStart(reason: String = "manual"): JsonObject =
    buildJsonObject {
        put("type", "compaction_start")
        put("reason", reason)
    }

/** `compaction_end`：`{reason,aborted,willRetry,errorMessage?}`（探测 p15 那句失败原文）。 */
internal fun probeCompactionEnd(errorMessage: String? = null): JsonObject = buildJsonObject {
    put("type", "compaction_end")
    put("reason", "manual")
    put("aborted", false)
    put("willRetry", false)
    errorMessage?.let { put("errorMessage", it) }
}
