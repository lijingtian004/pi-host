package works.earendil.pihost.feature.chat

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 运行时模型切换的解析面与状态面（P3B §3.3）。
 *
 * **住在 `feature/chat` 的理由与 §3.1 的 [ChatHistory] 完全同一条**（§2 红线 2）：
 * `core/rpc` 冻结区只到「信封层 + `data: JsonObject?`」为止，`get_available_models`
 * 里那些 `reasoning` / `contextWindow` / `cost` 字段属于**渲染语义**（哪些进选择器、
 * 缺字段怎么兜底），不是协议语义，写进冻结区就是让 v1.0.4 的冻结承诺替 UI 的演进背债。
 *
 * 形状全部来自**实跑探测**（REPORT-P3B §2 探测实录：桌面基准 pi 1.0.4 `dist/bundle/cli.js --mode rpc`），
 * 不是照抄规格书的假设——规格书 §3.3 自己就写了「默认 `{models:[{provider,id,name,...}]}` 待探测确认」：
 *
 * ```
 * get_available_models           → {"models":[{id,name,api,provider,baseUrl,reasoning,input[],cost{},contextWindow,maxTokens}]}
 * set_model（成功）              → data **就是那一条 model 对象本身**（没有外层包装键）
 * cycle_model                    → {"model":{…},"thinkingLevel":"off","isScoped":false}
 * get_available_thinking_levels  → {"levels":["off","minimal","low","medium","high"]}
 * cycle_thinking_level           → {"level":"high"}
 * get_state                      → {"model":{…},"thinkingLevel":"off", …}
 * thinking_level_changed（事件） → {"level":"medium"}
 * ```
 *
 * 四条「不猜」纪律（§2 红线 10）：
 * 1. **列表里没有 `current`/`selected` 标记**（探测里两条模型平铺）——「当前是哪条」
 *    只能从 `get_state.model` 或 `set_model`/`cycle_model` 的回执拿，绝不按「第一条 = 当前」猜；
 * 2. `set_thinking_level` **服务端不校验**（protocol.md §3.1 与探测 p09 双重证据：对
 *    reasoning:false 的模型设 "high" 回 success:true 且**不发事件**），所以选择器只暴露
 *    [ModelPickerState.thinkingLevels] 白名单里的档，UI 不给用户按出非法值的机会；
 * 3. 缺字段一律兜底（`name` 缺 → 用 `id`，`reasoning` 缺 → false，`contextWindow` 缺 → 0），
 *    但 `id`/`provider` 缺 → 这条**不算一条模型**（没有它就发不出 `set_model`），丢掉并留 logcat；
 * 4. `isScoped`（`cycle_model` 带）与 `api`/`baseUrl`/`cost`/`input`/`maxTokens` **一律不建模**：
 *    探测显示它们对 chip 与选择器都没有可用面，多存一份就是多一份与 pi 漂移的机会。
 */
object ModelCatalog {

    private const val LOG_TAG = "PiHost/ModelCatalog"

    /**
     * `get_available_models` → 模型列表。
     *
     * 形状不对（`data` 为 null / 不是对象 / 没有 `models` 数组）→ **null**，
     * 与 [ChatHistory.parseMessages] 同一条判据：「这个 provider 真的一条都没配」
     * 与「回的东西不是我们认识的样子」是两件事，chip 的措辞要能分开它们。
     */
    fun parseAvailableModels(data: JsonElement?): List<ProviderModel>? {
        val models = (data as? JsonObject)?.get("models") as? JsonArray ?: return null
        return models.mapNotNull { element ->
            parseModel(element).also {
                if (it == null) Log.w(LOG_TAG, "models[] 里混进不可用的条目（缺 id/provider），已跳过那一条")
            }
        }
    }

    /**
     * 单条 model 对象 → [ProviderModel]。
     *
     * 同一个函数吃四种来源（`models[]` 的元素、`set_model` 的整个 data、`cycle_model.model`、
     * `get_state.model`）——探测证明它们是**同一个形状**，所以这里是一份实现而不是四份近似。
     */
    fun parseModel(element: JsonElement?): ProviderModel? {
        val obj = element as? JsonObject ?: return null
        val id = stringOrNull(obj, FIELD_ID) ?: return null
        val provider = stringOrNull(obj, FIELD_PROVIDER) ?: return null
        return ProviderModel(
            provider = provider,
            id = id,
            name = stringOrNull(obj, "name") ?: id,
            reasoning = (obj["reasoning"] as? JsonPrimitive)?.booleanOrNull ?: false,
            contextWindow = (obj["contextWindow"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }

    /** `get_available_thinking_levels` → `{levels:string[]}`；形状不对 → null，非字符串项跳过。 */
    fun parseThinkingLevels(data: JsonElement?): List<String>? {
        val levels = (data as? JsonObject)?.get("levels") as? JsonArray ?: return null
        return levels.mapNotNull { stringOrNullIn(it) }.filter { it.isNotEmpty() }
    }

    /** `cycle_model` / `get_state` 里的外层键 `model`；缺键或形状不对 → null。 */
    fun parseModelField(data: JsonElement?): ProviderModel? =
        parseModel((data as? JsonObject)?.get(FIELD_MODEL))

    /** `{level:string}` 家族（`cycle_thinking_level` 的回执、`thinking_level_changed` 的事件）。 */
    fun parseLevel(data: JsonElement?): String? =
        (data as? JsonObject)?.let { stringOrNull(it, FIELD_LEVEL) }

    /**
     * `get_state.thinkingLevel` / `cycle_model.thinkingLevel`。
     *
     * 与 [parseLevel] 是**两个键名**（探测：单发 `cycle_thinking_level` 回 `{level}`，
     * 而带模型的那两条回 `{…, "thinkingLevel":"off"}`）。合并成一个函数只会让调用点
     * 猜「这条回执是哪种形状」，两个函数各认各的线格式才是照实建模。
     */
    fun parseThinkingLevelField(data: JsonElement?): String? =
        (data as? JsonObject)?.let { stringOrNull(it, FIELD_THINKING_LEVEL) }

    /**
     * 数组元素 → 字符串；非字符串、形状不对一律 null。
     *
     * `isString` 这道闸不能省：`contentOrNull` 会把 `JsonPrimitive(3)` 读成 `"3"`，
     * 于是白名单里凭空多出一个模型根本没给过的档位（ModelCatalogTest p10 盯的就是它）。
     */
    private fun stringOrNullIn(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

    private fun stringOrNull(obj: JsonObject, key: String): String? = stringOrNullIn(obj[key])

    private const val FIELD_ID = "id"
    private const val FIELD_PROVIDER = "provider"
    private const val FIELD_MODEL = "model"
    private const val FIELD_LEVEL = "level"
    private const val FIELD_THINKING_LEVEL = "thinkingLevel"
}

/**
 * 一条可选用的模型（选择器里的一行、chip 上的一个名字）。
 *
 * [provider] 与 [id] 是 `set_model` 的两个必填线字段，缺一不进列表；
 * [reasoning] / [contextWindow] 只做展示与「要不要给思考档入口」的判据（探测：
 * reasoning:false 的模型 `get_available_thinking_levels` 只回 `["off"]`）。
 */
data class ProviderModel(
    val provider: String,
    val id: String,
    val name: String,
    val reasoning: Boolean = false,
    val contextWindow: Int = 0,
) {
    /** 选择器/审计里区分同名模型的稳定串（`provider/modelId`，与 pi 的错误措辞 `mock/no-such-model` 同形）。 */
    val wireKey: String get() = "$provider/$id"
}

/**
 * 模型 chip + 选择器对话框的可视面（§3.3）。
 *
 * 五个字段的**唯一写入口**是 [ChatViewModel] 里对应命令的回执（[ModelPickerState.fetched] 之外
 * 没有任何本地乐观更新），所以 chip 说的永远是「pi 此刻的状态」，不是「用户刚才按过什么」。
 * 这与 §3.2 的 [QueueState] 是同一条纪律的两个实例：把「已发出」当成「已生效」，
 * 在 set_model 被 provider 拒掉（探测 p08：`Model not found`）那一刻就会显示出一个假的高亮。
 */
data class ModelPickerState(
    /** `get_available_models` 的快照；只由那条回执填充。 */
    val models: List<ProviderModel> = emptyList(),
    /** 当前模型：来自 `get_state` / `set_model` / `cycle_model` 回执。 */
    val current: ProviderModel? = null,
    /** 当前模型的思考档白名单（`get_available_thinking_levels`）。 */
    val thinkingLevels: List<String> = emptyList(),
    /** 当前思考档（`get_state.thinkingLevel` / `cycle_model.thinkingLevel` / `thinking_level_changed` 事件）。 */
    val thinkingLevel: String? = null,
    /** 有没有拉过列表：「还没拉」与「拉了但空」是两件事，chip 措辞必须分得开。 */
    val fetched: Boolean = false,
) {
    /** 已经拉过且列表为空 → §3.3 原文点名的「无可用模型」。 */
    val isEmpty: Boolean get() = fetched && models.isEmpty()

    /** 顶栏 chip 文案：已知当前模型 → 名字；否则拉过且空 → 「无可用模型」；再否则 → 「选择模型」。 */
    fun chipText(): String {
        val head = current?.name ?: if (isEmpty) CHIP_NO_MODELS else CHIP_PICK
        return if (thinkingLevel == null) head else "$head · 思考 $thinkingLevel"
    }

    /** 当前模型在 [models] 里的下标（高亮用）；未知/未拉 → -1，UI 就不高亮任何一行。 */
    fun indexOfCurrent(): Int =
        current?.let { c -> models.indexOfFirst { it.wireKey == c.wireKey } } ?: -1
}

/** chip 在「还没拉到任何模型」时的文案。 */
internal const val CHIP_PICK: String = "选择模型"
/** chip 在「拉过了、列表为空」时的文案（§3.3 原文）。 */
internal const val CHIP_NO_MODELS: String = "无可用模型"

/** 对话框在「刚点开、回执还没到」时的措辞——与 [CHIP_NO_MODELS] 分开，两种空不是一回事。 */
internal const val CHIP_PICK_LOADING: String = "模型列表加载中…"
