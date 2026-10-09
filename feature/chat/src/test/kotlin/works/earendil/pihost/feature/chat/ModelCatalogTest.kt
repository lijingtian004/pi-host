package works.earendil.pihost.feature.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `ModelCatalog` 的解析与 `ModelPickerState` 的措辞（P3B §3.3，编号 `pNN` 沿用「一个文件一条序列」）。
 *
 * **输入形状不是手写的**：每一条都照 REPORT-P3B §2 的探测实录（桌面基准 pi 1.0.4
 * `dist/bundle/cli.js --mode rpc`，命令 id p01…p19），包括那几个容易猜错的点：
 * - `get_available_models` 有外层 `models` 键，列表里**没有** `current`/`selected` 标记；
 * - `set_model` 的回执 data **就是那条 model 对象本身**（外层没有包装键）；
 * - `cycle_model`/`get_state` 包一层 `model`，且带的是 `thinkingLevel`；
 * - `cycle_thinking_level` 带 `level`，而 `set_thinking_level` **什么都不带**。
 * 按 §2 红线 10：文档没写的形状不猜，探测到了才建模。
 *
 * Robolectric 的理由与 [ChatHistoryTest] 同一条：跳过条目要写 `android.util.Log`，
 * 本模块没开 `returnDefaultValues`（开了等于允许「忘记 mock」的假绿）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModelCatalogTest {

    // ── get_available_models（探测 p01）──────────────────────────────────────────

    @Test
    fun `p01 the probed get_available_models shape parses into two usable models`() {
        val models = ModelCatalog.parseAvailableModels(availableModelsEnvelope())
        assertNotNull("探测实录的形状必须认得", models)
        assertEquals(2, models!!.size)
        assertEquals(listOf("mock/mock-1", "mock/mock-2"), models.map { it.wireKey })
        assertEquals(listOf(false, true), models.map { it.reasoning })
        assertEquals(listOf(128_000, 200_000), models.map { it.contextWindow })
        assertEquals("Mock Model 1", models[0].name)
        // api/baseUrl/cost/input/maxTokens **不建模**（探测里有、chip 用不上）：
        // 存一份副本就是多一处与 pi 漂移的机会，见 ChatModelPicker.kt 头注纪律 4。
    }

    @Test
    fun `p02 a missing or mistyped models key is null, never an empty list`() {
        assertNull(ModelCatalog.parseAvailableModels(null))
        assertNull(ModelCatalog.parseAvailableModels(buildJsonObject { put("model", "typo") }))
        assertNull(ModelCatalog.parseAvailableModels(buildJsonObject { put("models", "not-an-array") }))
    }

    @Test
    fun `p03 an empty models array is a real empty catalog, not a shape failure`() {
        val models = ModelCatalog.parseAvailableModels(buildJsonObject { putJsonArray("models") { } })
        assertNotNull("没配 provider 是合法状态，chip 要说「无可用模型」而不是「形状不对」", models)
        assertTrue(models!!.isEmpty())
    }

    @Test
    fun `p04 an entry without id or provider is dropped while its neighbours survive`() {
        val models = ModelCatalog.parseAvailableModels(
            buildJsonObject {
                putJsonArray("models") {
                    add(buildJsonObject { put("provider", "mock") })
                    add(buildJsonObject { put("id", "no-provider") })
                    add(modelObject("survivor", "Survivor", reasoning = false))
                }
            },
        )
        assertEquals("缺了 set_model 需要的线字段就留不进列表", listOf("mock/survivor"), models!!.map { it.wireKey })
    }

    @Test
    fun `p05 optional display fields fall back instead of failing the entry`() {
        val parsed = ModelCatalog.parseModel(
            buildJsonObject {
                put("id", "bare")
                put("provider", "mock")
            },
        )
        assertNotNull(parsed)
        assertEquals("name 缺 → 用 id", "bare", parsed!!.name)
        assertFalse("reasoning 缺 → false（保守：不给思考档入口）", parsed.reasoning)
        assertEquals(0, parsed.contextWindow)
        assertNull("非对象（数组/裸字符串）不是一条 model", ModelCatalog.parseModel(buildJsonArray { }))
    }

    // ── 四条回执的四种外层形状 ─────────────────────────────────────────────────────

    @Test
    fun `p06 set_model data is the bare model object with no wrapper key`() {
        val parsed = ModelCatalog.parseModel(setModelPayload())
        assertNotNull("探测 p03：data 就是 model 本身，按「外层键 model」去读就永远是 null", parsed)
        assertEquals("mock/mock-2", parsed!!.wireKey)
        assertTrue(parsed.reasoning)
        assertEquals(200_000, parsed.contextWindow)
        assertNull(ModelCatalog.parseModelField(setModelPayload()))
    }

    @Test
    fun `p07 cycle_model carries a wrapped model plus thinkingLevel and isScoped is ignored`() {
        val data = cycleModelPayload()
        assertEquals("mock/mock-1", ModelCatalog.parseModelField(data)?.wireKey)
        assertEquals("off", ModelCatalog.parseThinkingLevelField(data))
    }

    @Test
    fun `p08 get_state carries the same wrapped model and thinkingLevel keys`() {
        val data = getStatePayload()
        assertEquals("mock/mock-1", ModelCatalog.parseModelField(data)?.wireKey)
        assertEquals("off", ModelCatalog.parseThinkingLevelField(data))
        assertNull("外层没有 level 键（那是 cycle_thinking_level 的形状）", ModelCatalog.parseLevel(data))
    }

    // ── 思考档（探测 p02/p04/p06 与 thinking_level_changed 事件）─────────────────────

    @Test
    fun `p09 thinking levels parse the whitelisted strings in order`() {
        assertEquals(
            listOf("off", "minimal", "low", "medium", "high"),
            ModelCatalog.parseThinkingLevels(fiveLevelsPayload()),
        )
        assertEquals(
            "reasoning:false 的模型只回一档（探测 p02）",
            listOf("off"),
            ModelCatalog.parseThinkingLevels(offOnlyPayload()),
        )
    }

    @Test
    fun `p10 thinking levels are null on shape drift and non-strings are dropped`() {
        assertNull(ModelCatalog.parseThinkingLevels(null))
        assertNull(ModelCatalog.parseThinkingLevels(buildJsonObject { put("level", "off") }))
        assertEquals(
            "非字符串项丢掉而不是整页失败",
            listOf("off"),
            ModelCatalog.parseThinkingLevels(
                buildJsonObject {
                    putJsonArray("levels") {
                        add(JsonPrimitive("off"))
                        add(JsonPrimitive(3))
                    }
                },
            ),
        )
    }

    @Test
    fun `p11 level and thinkingLevel are two different wire keys on purpose`() {
        assertEquals("high", ModelCatalog.parseLevel(buildJsonObject { put("level", "high") }))
        assertEquals("off", ModelCatalog.parseThinkingLevelField(buildJsonObject { put("thinkingLevel", "off") }))
        assertNull(ModelCatalog.parseLevel(null))
        assertNull("空串不算一个档位", ModelCatalog.parseLevel(buildJsonObject { put("level", "") }))
    }

    // ── chip 的措辞与高亮 ──────────────────────────────────────────────────────────

    @Test
    fun `p12 chip text covers the three states the user can actually be in`() {
        assertEquals("没拉过 → 邀请点击，不能提前说「无可用模型」", CHIP_PICK, ModelPickerState().chipText())
        assertEquals(
            "拉过且空 → §3.3 点名的措辞",
            CHIP_NO_MODELS,
            ModelPickerState(models = emptyList(), fetched = true).chipText(),
        )
        assertEquals("Mock Model 2", ModelPickerState(current = mock2, fetched = true).chipText())
        assertEquals(
            "思考档已知 → 一并显示，chip 是这一页「pi 正在用什么」的唯一可见面",
            "Mock Model 2 · 思考 high",
            ModelPickerState(current = mock2, thinkingLevel = "high", fetched = true).chipText(),
        )
    }

    @Test
    fun `p13 index of current matches on provider plus id and stays -1 when unknown`() {
        assertEquals(1, ModelPickerState(models = listOf(mock1, mock2), current = mock2, fetched = true).indexOfCurrent())
        assertEquals(
            "当前模型不在这份列表里（scoped 模型/新加 provider）→ 不高亮任何一条，也不猜第一条",
            -1,
            ModelPickerState(models = listOf(mock1), current = mock2, fetched = true).indexOfCurrent(),
        )
        assertEquals(-1, ModelPickerState(models = listOf(mock1), fetched = true).indexOfCurrent())
    }

    @Test
    fun `p14 isEmpty separates never-fetched from fetched-but-empty`() {
        assertFalse("还没拉过不是「没有模型」", ModelPickerState().isEmpty)
        assertTrue(ModelPickerState(fetched = true).isEmpty)
        assertFalse(ModelPickerState(models = listOf(mock1), fetched = true).isEmpty)
    }

    // ── 探测实录的 JSON 构造（键名逐条照抄 rpc33.jsonl）────────────────────────────

    private companion object {
        val mock1 = ProviderModel("mock", "mock-1", "Mock Model 1", reasoning = false, contextWindow = 128_000)
        val mock2 = ProviderModel("mock", "mock-2", "Mock Model 2", reasoning = true, contextWindow = 200_000)

        /** 一条 model 对象的全部键（探测 p01 列表元素 / p03 回执 / p07 的 model 键，三者同形）。 */
        fun JsonObjectBuilder.modelFields(
            id: String,
            name: String,
            reasoning: Boolean,
            contextWindow: Int = 128_000,
        ) {
            put("id", id)
            put("name", name)
            put("api", "openai-completions")
            put("provider", "mock")
            put("baseUrl", "http://127.0.0.1:8787/v1")
            put("reasoning", reasoning)
            putJsonArray("input") { add(JsonPrimitive("text")) }
            putJsonObject("cost") {
                put("input", 0)
                put("output", 0)
                put("cacheRead", 0)
                put("cacheWrite", 0)
            }
            put("contextWindow", contextWindow)
            put("maxTokens", 16384)
        }

        fun modelObject(id: String, name: String, reasoning: Boolean, contextWindow: Int = 128_000): JsonObject =
            buildJsonObject { modelFields(id, name, reasoning, contextWindow) }

        /** 探测 p01 的 data。 */
        fun availableModelsEnvelope(): JsonObject = buildJsonObject {
            putJsonArray("models") {
                add(modelObject("mock-1", "Mock Model 1", reasoning = false))
                add(modelObject("mock-2", "Mock Model 2", reasoning = true, contextWindow = 200_000))
            }
        }

        /** 探测 p03 的 data（**裸 model 对象**，没有外层键）。 */
        fun setModelPayload(): JsonObject =
            modelObject("mock-2", "Mock Model 2", reasoning = true, contextWindow = 200_000)

        /** 探测 p07 的 data。 */
        fun cycleModelPayload(): JsonObject = buildJsonObject {
            putJsonObject("model") { modelFields("mock-1", "Mock Model 1", reasoning = false) }
            put("thinkingLevel", "off")
            put("isScoped", false)
        }

        /** 探测 p11 的 data（get_state；本页不建模的键留着，正好验「多键不影响解析」）。 */
        fun getStatePayload(): JsonObject = buildJsonObject {
            putJsonObject("model") { modelFields("mock-1", "Mock Model 1", reasoning = false) }
            put("thinkingLevel", "off")
            put("isStreaming", false)
            put("isCompacting", false)
            put("steeringMode", "all")
            put("followUpMode", "all")
            put("autoCompactionEnabled", true)
        }

        /** 探测 p04 的 data。 */
        fun fiveLevelsPayload(): JsonObject = buildJsonObject {
            putJsonArray("levels") {
                listOf("off", "minimal", "low", "medium", "high").forEach { add(JsonPrimitive(it)) }
            }
        }

        /** 探测 p02 的 data。 */
        fun offOnlyPayload(): JsonObject = buildJsonObject {
            putJsonArray("levels") { add(JsonPrimitive("off")) }
        }
    }
}
