package works.earendil.pihost.core.engine.settings

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * 第三方中转站的端点读写（P2H §3.1）：落点是 pi 的 `<agent-dir>/models.json` 与 `<agent-dir>/settings.json`。
 *
 * ## 为什么是这两个文件（§1 方向层已实证，本轮不重做）
 * pi 1.0.4 **官方**支持中转站：`ModelRuntime.create({modelsPath: join(agentDir,"models.json")})`
 * （`agent-session-services.ts:144`），与 `--no-extensions` **解耦**（`noExtensions` 只进
 * `resourceLoaderOptions`）。本机 node v24 + mock 中转服务器已把 RPC 全链路跑通
 * （`get_available_models` 含 relay 模型 → `set_model{provider, modelId}` success=true →
 * prompt 收到 assistant message_end，provider/api/model 三处回填正确）。
 * 认证走 `models.json` 里的 `apiKey`（`AuthStatus.source` 枚举含 `models_json_key`/`models_json_command`）：
 * `checkAuth` 返回非 undefined → provider 进 `configuredProviders` → `hasConfiguredAuth` 为 true
 * （`model-runtime.ts:350-357`）。
 *
 * **为什么要第二个文件**：`findInitialModel` 五级回退（`model-resolver.ts:622-707`）里
 * `settings.json` 的 `defaultProvider`/`defaultModel` 排第三级、且要求 `hasConfiguredAuth`（中转配了 apiKey 即满足），
 * 而第四级 `defaultModelPerProvider` **只认内置 provider**，中转不在内。
 * 于是「配了中转就默认用中转」必须落到 [setDefault] —— 否则同时有内置凭据时 pi 会选回内置默认。
 *
 * ## 形状（§1 关键机制逐条落到字段）
 * - `models.json`：`{"providers":{"<id>":{"baseUrl":…,"api":…,"apiKey":…?,"models":[{"id":…,"reasoning":true?}]}}}`；
 * - **`reasoning` 是思考档的唯一开关**（P3B-FIX1 §1 第 2/3 条）：pi 读盘是
 *   `reasoning: definition.reasoning ?? false`（`provider-composer.ts:231`），缺省即 false，
 *   于是 `getSupportedThinkingLevels` 只回 `["off"]`，`setThinkingLevel` 把请求档钳回 off 且**仍回 success:true**
 *   ——「表面成功实际钳死」就是用户报的「模型思考无法调整」。写了 `"reasoning":true` 才有五档，
 *   且请求侧才真的把 `reasoning` 参数发给中转（`model.reasoning && thinkingLevel !== "off"`）。
 *   因此 false 的项**省略字段**（文件保持精瘦，`?? false` 与写 false 等价），true 的项**必须显式写**——
 *   `applyModelsJson`（`:327-332`）对同名 id 是**整体替换**、不继承内置目录的 reasoning，改名也拿不到；
 * - **`baseUrl` 必填**（provider 级或 model 级）：`provider-composer.ts:289` 的链是
 *   `definition.baseUrl ?? config.baseUrl ?? 内置默认`，自定义 provider 没有内置默认，缺它 pi 直接报
 *   `"baseUrl" is required` —— 所以 [save] 把它做成 `require`，并且 UI 的提示文案要求带 `/v1` 这类路径前缀；
 * - `api` 合法值来自 `BUILTIN_APIS`（`ai/src/compat.ts:181-190`）十项，**store 不做白名单校验**：
 *   用户手改文件写第六项照样读得出来，app 不该比 pi 更严（UI 下拉只放五项的理由见报告 §8）；
 * - `settings.json`：`{"defaultProvider":"<id>","defaultModel":"<modelId>", …其余字段原样}`
 *   （路径 `settings-manager.ts:294-301` = `join(agentDir,"settings.json")`，与 auth.json/models.json 同目录）。
 *
 * ## 纪律（§2 红线，与 [ModelCredentialStore] 同源，工具共用 [AgentConfigFiles]）
 * 1. **读-改-写**：其他 provider 条目、`providers` 之外的顶层未知字段、provider 条目里 app 不管的字段
 *    全部原样保留（靠 `JsonObject` 不做类型化 decode）；
 * 2. **原子替换**：同目录 tmp + `renameTo`，异常路径清 tmp；
 * 3. **apiKey 是凭据**：[RelayEntry] 只带 `keyConfigured` 旗，[readRelays] 不返回 key，本类不打日志。
 *    `baseUrl` **不是**凭据，可以回显（用户要能看/改自己填的地址）。
 *
 * 生效时机与 auth.json 同节奏：`settings.json` 经 `createAgentSessionServices` 注入、非每请求重读，
 * 所以**写后新会话生效**（UI 的 notice 文案由此而来）。
 *
 * @param homeDir `PathLayout.homeDir(ctx)` 那个目录（与 [ModelCredentialStore] 同一条通路，不写硬路径）
 */
class RelayEndpointStore(private val homeDir: File) {

    /** `<homeDir>/.pi/agent/models.json`（pi 的自定义 provider 落点）。 */
    val modelsFile: File
        get() = AgentConfigFiles.agentFile(homeDir, MODELS_FILE_NAME)

    /** `<homeDir>/.pi/agent/settings.json`（默认模型钉选的落点，与 models.json 同目录）。 */
    val settingsFile: File
        get() = AgentConfigFiles.agentFile(homeDir, SETTINGS_FILE_NAME)

    /**
     * 一条已配置中转的**无密钥**视图（§3.1 原文形状：baseUrl 可回显，key 只报状态）。
     *
     * [baseUrl] 允许上屏（不是凭据）；[keyConfigured] 只说「有没有可用 apiKey」。
     * 真值一律留在磁盘里 —— 连 `$ENV_NAME` 形态的引用串也不回显，app 不展开、也不显示它。
     *
     * [models] 是带思考旗的模型项（P3B-FIX1 §3.1 第 4 条）：原来的 `modelIds: List<String>`
     * 表达不了「这个模型能调思考档」这件事，而那件事正是 pi 侧唯一的开关（见类头「形状」）。
     */
    data class RelayEntry(
        val providerId: String,
        val baseUrl: String,
        val api: String,
        val models: List<RelayModel>,
        val keyConfigured: Boolean,
    )

    /**
     * 已配置的中转行（`models.json` 缺失/损坏/没有 `providers` → 空表，不抛）。
     *
     * `providers` 里值不是 JSON 对象的垃圾条目（如 `"note":"x"`）不进列表，但写回时原样保留（见 [save]）。
     */
    fun readRelays(): List<RelayEntry> =
        providersOf(AgentConfigFiles.readObject(modelsFile)).mapNotNull { (providerId, value) ->
            (value as? JsonObject)?.let {
                RelayEntry(providerId, it.baseUrlText(), it.apiText(), it.modelEntries(), it.hasApiKey())
            }
        }

    /**
     * 此刻钉着的默认中转 providerId（§3.2 的行首「默认」徽标就读它）。
     *
     * 没钉 / `settings.json` 损坏 / 值不是字符串或空白 → null。规格书 §3.1 的 API 列表里没写这个方法，
     * 但 §3.2 要求「默认中转行高亮（数据从 settings.json 读）」，读口必须有——报告 §8 记为补口。
     */
    fun readDefaultProvider(): String? =
        AgentConfigFiles.readObject(settingsFile).textOrNull(FIELD_DEFAULT_PROVIDER)?.takeIf { it.isNotBlank() }

    /**
     * 写入/更新一条中转（§3.1）。
     *
     * [apiKey] 传 null 或空白 → **保留该 provider 条目里已有的 apiKey**（用户只想改 baseUrl/模型清单时不丢 key）；
     * 传非空 → 覆盖。原来没有 key 且这次也没给 → **不写** `apiKey` 字段（pi 侧该 provider 判为未配置，
     * 比写一条空串诚实）。值**原样落盘**：`$ENV_NAME` 形态由 pi 自己解析，app 不展开。
     *
     * [models] 里 `reasoning=true` 的项多写一个 `"reasoning":true` 字段，false 的项**省略字段**
     * （pi 的 `?? false` 与写 false 等价，而文件要保持精瘦——用户会直接看这份文件）。
     * 同一 id 出现多次 → 按 id 折叠，reasoning **取或**（任一条 true → true）。
     *
     * @throws IllegalArgumentException providerId/baseUrl/api 空白，或 models 清洗后为空
     * @throws java.io.IOException 目录建不成（例如 `.pi` 位置被一个普通文件挡着）或原子替换失败
     */
    fun save(providerId: String, baseUrl: String, api: String, models: List<RelayModel>, apiKey: String?) {
        require(providerId.isNotBlank()) { "providerId 不能为空" }
        require(baseUrl.isNotBlank()) { "baseUrl 不能为空：自定义 provider 没有内置默认地址，缺它 pi 报 \"baseUrl\" is required" }
        require(api.isNotBlank()) { "api 不能为空" }
        require(models.isNotEmpty()) { "models 不能为空：中转至少要有一个模型 id" }
        val cleanModels = cleanModels(models)
        require(cleanModels.isNotEmpty()) { "models 清洗后为空：每一项的模型 id 都是空白" }
        val target = modelsFile
        val dir = requireNotNull(target.parentFile) { "modelsFile 没有父目录：${target.path}" }
        AgentConfigFiles.ensureDirectory(dir)

        val root = AgentConfigFiles.readObject(target)
        val providers = providersOf(root)
        val existing = providers[providerId] as? JsonObject
        val resolvedKey = apiKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: existing?.get(FIELD_API_KEY).textOrNull()?.takeIf { it.isNotBlank() }
        val entry = buildJsonObject {
            // app 不管理的 provider 级字段（headers、compat 之类）原样留着，再写我们管的四项
            existing?.forEach { (key, value) -> if (key !in MANAGED_PROVIDER_FIELDS) put(key, value) }
            put(FIELD_BASE_URL, JsonPrimitive(baseUrl.trim()))
            put(FIELD_API, JsonPrimitive(api.trim()))
            resolvedKey?.let { put(FIELD_API_KEY, JsonPrimitive(it)) }
            put(FIELD_MODELS, modelsArray(cleanModels))
        }
        AgentConfigFiles.writeAtomic(dir, target, root.with(FIELD_PROVIDERS, providers.with(providerId, entry)))
    }

    /**
     * 删掉一条中转（§3.1）：从 `models.json` 的 `providers` 里摘掉该条目，其余原样保留；
     * 并且**若 `settings.json` 的 `defaultProvider` 正指着它，同时清掉默认那两个字段** ——
     * 否则 pi 找不到模型会回退内置默认，磁盘上留下一条悬空指针（§3.1 第 ⑩ 条）。
     *
     * 幂等：文件不存在、条目不存在、默认没指着它 → 一个字节都不写（不凭空建 `{}`）。
     *
     * @throws IllegalArgumentException [providerId] 空白
     */
    fun clear(providerId: String) {
        require(providerId.isNotBlank()) { "providerId 不能为空" }
        val target = modelsFile
        if (target.isFile) {
            val root = AgentConfigFiles.readObject(target)
            val providers = providersOf(root)
            if (providers.containsKey(providerId)) {
                AgentConfigFiles.writeAtomic(
                    requireNotNull(target.parentFile),
                    target,
                    root.with(FIELD_PROVIDERS, providers.without(providerId)),
                )
            }
        }
        // 悬空指针清理与 models.json 是否真的动过无关：条目本来不存在、但默认指着它，同样要清
        if (readDefaultProvider() == providerId) clearDefault()
    }

    /**
     * 钉选默认模型（§3.1）：`settings.json` 写 `defaultProvider`/`defaultModel` 两字段，**其余字段全保留**。
     *
     * 不校验 providerId/modelId 是否真的在 `models.json` 里：pi 的 `findInitialModel` 自己会回退，
     * 而「先设默认、下一次 save 才把中转写进去」这种顺序是合法的（UI 走不到，手改文件走得到）。
     *
     * @throws IllegalArgumentException 任一入参空白
     * @throws java.io.IOException 目录建不成或原子替换失败
     */
    fun setDefault(providerId: String, modelId: String) {
        require(providerId.isNotBlank()) { "providerId 不能为空" }
        require(modelId.isNotBlank()) { "modelId 不能为空" }
        val target = settingsFile
        val dir = requireNotNull(target.parentFile) { "settingsFile 没有父目录：${target.path}" }
        AgentConfigFiles.ensureDirectory(dir)
        val written = AgentConfigFiles.readObject(target)
            .with(FIELD_DEFAULT_PROVIDER, JsonPrimitive(providerId.trim()))
            .with(FIELD_DEFAULT_MODEL, JsonPrimitive(modelId.trim()))
        AgentConfigFiles.writeAtomic(dir, target, written)
    }

    /**
     * 删掉 `defaultProvider`/`defaultModel` 两个字段，`settings.json` 其余字段原样保留（§3.1）。
     *
     * 幂等：文件不存在、两个字段都不在 → 不动文件。
     */
    fun clearDefault() {
        val target = settingsFile
        if (!target.isFile) return
        val root = AgentConfigFiles.readObject(target)
        if (!root.containsKey(FIELD_DEFAULT_PROVIDER) && !root.containsKey(FIELD_DEFAULT_MODEL)) return
        val remaining = root.without(FIELD_DEFAULT_PROVIDER).without(FIELD_DEFAULT_MODEL)
        AgentConfigFiles.writeAtomic(requireNotNull(target.parentFile), target, remaining)
    }

    // ── 内部：形状搬运（读 / 原子写 / 建目录三条工具在 [AgentConfigFiles]）────────────────

    /** `providers` 段：缺失或不是对象 → 空对象（顶层不是对象同样算损坏，见 [AgentConfigFiles.readObject]）。 */
    private fun providersOf(root: JsonObject): JsonObject = root[FIELD_PROVIDERS] as? JsonObject ?: AgentConfigFiles.EMPTY_OBJECT

    /** 改一个键（已存在就地替换、不存在追加到末尾），其余键与顺序原样。 */
    private fun JsonObject.with(key: String, value: JsonElement): JsonObject = buildJsonObject {
        var hit = false
        this@with.forEach { (existing, existingValue) ->
            if (existing == key) {
                put(key, value)
                hit = true
            } else {
                put(existing, existingValue)
            }
        }
        if (!hit) put(key, value)
    }

    /** 摘一个键，其余键与顺序原样。 */
    private fun JsonObject.without(key: String): JsonObject = buildJsonObject {
        this@without.forEach { (existing, existingValue) -> if (existing != key) put(existing, existingValue) }
    }

    /**
     * 清洗一份模型清单：trim id、去空 id、按 id 去重——**同 id 混后缀时 reasoning 取或**
     * （任一条 true → true）。顺序 = 首次出现的顺序，重复项不再往后插。
     *
     * 这一层与 `feature/settings` 的 `parseRelayModels` 是**两道同语义的门**（那份门防手滑、
     * 这道门防脚本与手调）；两份都保留，因为绕开 UI 直调 store 是合法通路（P2H §3.1 同一决定）。
     */
    private fun cleanModels(models: List<RelayModel>): List<RelayModel> {
        val merged = LinkedHashMap<String, Boolean>()
        models.forEach { item ->
            val id = item.id.trim()
            if (id.isNotEmpty()) merged[id] = merged.getOrDefault(id, false) || item.reasoning
        }
        return merged.map { (id, reasoning) -> RelayModel(id, reasoning) }
    }

    /**
     * `models` 数组：每项一个 `{"id":"…"}`，`reasoning=true` 的项多一个 `"reasoning":true`；
     * false 的项**省略字段**（`provider-composer.ts:231` 的 `?? false` 让「省略」与「写 false」在 pi 侧
     * 完全等价，而文件是用户会直接打开看的，少一个字少一份误读）。顺序按传入顺序（[cleanModels] 已在 [save] 里清洗过）。
     */
    private fun modelsArray(models: List<RelayModel>): JsonArray = buildJsonArray {
        models.forEach { model ->
            add(
                buildJsonObject {
                    put(FIELD_ID, JsonPrimitive(model.id))
                    if (model.reasoning) put(FIELD_REASONING, JsonPrimitive(true))
                },
            )
        }
    }

    private fun JsonObject.baseUrlText(): String = textOrNull(FIELD_BASE_URL).orEmpty()

    /** `api` 缺省给 [DEFAULT_API]：手写的 models.json 不填 api 时，UI 也按 OpenAI 兼容面展示。 */
    private fun JsonObject.apiText(): String = textOrNull(FIELD_API)?.takeIf { it.isNotBlank() } ?: DEFAULT_API

    private fun JsonObject.hasApiKey(): Boolean = !textOrNull(FIELD_API_KEY).isNullOrBlank()

    /**
     * `models` 里的模型项：只认 `{"id":"…","reasoning":…}` 这种对象项（pi 的形状）。
     * 顶层不是数组、项不是对象、或 id 是空白 → 跳过该项；
     * 某项直接是字符串（`["gpt-4o"]`，pi 不收，但读的时候没必要比 pi 更严）也当 id 收下来，
     * 下一次 [save] 会把它规范成对象形态。
     *
     * **reasoning 只认真 `true`**（§3.1 第 4 条）：缺省、写 false、写 `"true"` 字符串、写数字 1、
     * 写对象/数组 —— 一律读成 false。这一侧从严是有原因的：true 是「往请求里加参数」的那一侧，
     * 把非布尔的值猜成 true 会让 app 发出 pi 自己都不会发的请求。
     */
    private fun JsonObject.modelEntries(): List<RelayModel> = when (val models = this[FIELD_MODELS]) {
        is JsonArray -> models.mapNotNull { element ->
            when (element) {
                is JsonObject -> element.textOrNull(FIELD_ID)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { RelayModel(it, element.reasoningFlag()) }

                is JsonPrimitive -> element.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { RelayModel(it, false) }

                else -> null
            }
        }

        else -> emptyList()
    }

    /** JSON 布尔真值 `true` 才返回 true；其余（缺字段/字符串/数字/null/对象）全算 false。 */
    private fun JsonObject.reasoningFlag(): Boolean =
        (this[FIELD_REASONING] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } ?: false

    private fun JsonObject.textOrNull(field: String): String? = (this[field] as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.textOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

    companion object {
        /** 目录两段名（真相在 [AgentConfigFiles]，这里只是给单测一个就近的引用点）。 */
        const val PI_DIR_NAME: String = AgentConfigFiles.PI_DIR_NAME
        const val AGENT_DIR_NAME: String = AgentConfigFiles.AGENT_DIR_NAME

        /** 两张表的文件名同样引用共享常量（单一真相）。 */
        const val MODELS_FILE_NAME: String = AgentConfigFiles.MODELS_FILE_NAME
        const val SETTINGS_FILE_NAME: String = AgentConfigFiles.SETTINGS_FILE_NAME

        /** tmp 名前缀（与目标文件同目录）；单测按它断言「无残留」，值与 [AgentConfigFiles.writeAtomic] 派生的同一个。 */
        const val MODELS_TMP_PREFIX: String = MODELS_FILE_NAME + AgentConfigFiles.TMP_MARKER
        const val SETTINGS_TMP_PREFIX: String = SETTINGS_FILE_NAME + AgentConfigFiles.TMP_MARKER

        const val FIELD_PROVIDERS: String = "providers"
        const val FIELD_BASE_URL: String = "baseUrl"
        const val FIELD_API: String = "api"
        const val FIELD_API_KEY: String = "apiKey"
        const val FIELD_MODELS: String = "models"
        const val FIELD_ID: String = "id"

        /**
         * 模型级的思考旗（P3B-FIX1 §1 第 3/8 条）：pi 的 `ModelDefinitionSchema` 里
         * `reasoning` 是 `Type.Optional(Type.Boolean())`（`model-config.ts:199-221`），
         * 读盘缺省补 false（`provider-composer.ts:231`）—— 所以「不写」与「写 false」等价，
         * 而 app 只在 true 时写出来。
         */
        const val FIELD_REASONING: String = "reasoning"
        const val FIELD_DEFAULT_PROVIDER: String = "defaultProvider"
        const val FIELD_DEFAULT_MODEL: String = "defaultModel"

        /** §3.1 的默认值：中转绝大多数是 OpenAI 兼容面。 */
        const val DEFAULT_API: String = "openai-completions"

        /** [save] 会重写的四个 provider 级字段；条目里**其余字段原样保留**（§2 红线「保留未知字段」）。 */
        val MANAGED_PROVIDER_FIELDS: Set<String> = setOf(FIELD_BASE_URL, FIELD_API, FIELD_API_KEY, FIELD_MODELS)

        /**
         * 设置页 api 下拉的固定五项（§3.2，顺序照规格书原文）。
         *
         * 与 [ModelCredentialStore.KNOWN_PROVIDERS] 同一先例：**清单住在 core 侧**，
         * 因为「pi 认哪些 api」是引擎事实而不是 UI 选择；UI 只负责把它排成 RadioButton。
         * 十项全集里的其余五项（`openai-codex-responses`/`azure-openai-responses`/`google-vertex`/
         * `mistral-conversations`/`bedrock-converse-stream`）需要各自的基础设施凭据（Azure 资源名、
         * Vertex 的 GCP ADC、Bedrock 的 SigV4 身份、Mistral 的独立密钥体系），中转场景给不了，
         * 所以不进下拉 —— store 侧不做白名单校验，手改文件写它们照样读得出来。
         */
        val RELAY_APIS: List<ApiOption> = listOf(
            ApiOption("openai-completions", "OpenAI 兼容（Chat Completions）", "适用绝大多数中转/one-api/new-api"),
            ApiOption("anthropic-messages", "Anthropic Messages", null),
            ApiOption("openai-responses", "OpenAI Responses", null),
            ApiOption("google-generative-ai", "Google Gemini", null),
            ApiOption("pi-messages", "Pi Messages", null),
        )

        /** 下拉一项：pi 认的 api 值 + 展示名 + 可选的一行说明。 */
        data class ApiOption(val api: String, val displayName: String, val note: String?)

        /** 展示名：清单里没有的 api 值（手改文件写进来的第六项）原样回显，不编造名字。 */
        fun apiDisplayName(api: String): String = RELAY_APIS.firstOrNull { it.api == api }?.displayName ?: api
    }
}

/**
 * 中转条目里的一个模型（P3B-FIX1 §3.1 第 1 条）。
 *
 * 为什么不是 `List<String>` 加一个并行布尔表：这两样必须**绑在一起走**，
 * 因为 `reasoning` 是模型级的字段（`models.json` 的 `models` 数组里每项一个），
 * 一旦拆成「id 表 + 一份思考 id 表」，中间任何一步的排序/去重都会让两份错位——
 * 错位的结果不是崩，而是「思考档悄悄没了」，正是本轮要修的那类 bug。
 *
 * [reasoning] 的语义与 pi 侧一字不差：true = 这个模型有 off/minimal/low/medium/high 五档
 * （`getSupportedThinkingLevels`），且设档后请求里真的带 `reasoning` 参数；
 * false = 只有 off 一档。默认 false，所以老代码里「只有 id」的构造点行为不变。
 */
data class RelayModel(val id: String, val reasoning: Boolean = false)
