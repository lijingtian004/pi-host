package works.earendil.pihost.core.engine.settings

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * 模型 API 凭据的读写（规格书 §4.1）：pi 的 `auth.json` 是唯一落点，别的通路一律不用。
 *
 * ## 为什么是文件而不是 env（§9 定案）
 * [works.earendil.pihost.core.environment.EnvFactory] 的 11 个键是 P2F 定案冻结的，往 env 里塞 key
 * 既动冻结区、又让凭据躺在 spawn 的参数表里（`ps`/诊断日志都可能带出去）。`auth.json` 是 pi 的
 * 一等公民路径，桌面实验已实证闭环：文件里有非空 `key` → provider 进 `configuredProviders` →
 * `get_state` 自动给出该 provider 的默认模型（假 key 也进 available，`resolve()` 只验非空不验真伪）。
 *
 * ## 路径与形状（RECON-P2G §1 逐处实测）
 * - 目录：`EnvFactory` 已把 `HOME` 指到 `filesDir/home`（冻结区），pi 的 `getAgentDir()`
 *   （`packages/coding-agent/src/config.ts:605-611`）= `<HOME>/.pi/agent`，
 *   `authPath` = `join(getAgentDir(), "auth.json")`（同文件 `:623-625`）；
 * - 形状：`packages/ai/src/auth/types.ts:17-21` 的 `ApiKeyCredential` ——
 *   `{"<providerId>":{"type":"api_key","key":"<KEY>"}}`。注意 `key?: string` 是**可选**字段，
 *   所以「已配置」的判据是 `type == "api_key" && key 非空白`，不能只看 provider 键存在。
 *
 * ## 三条硬纪律（§2 红线）
 * 1. **读-改-写**：先读现有对象再改目标条目，其他 provider 与未知字段（`models.json` 之外的手写扩展、
 *    未来 pi 新增的字段）原样保留 —— 靠 [JsonObject] 本身是 `Map<String, JsonElement>`，
 *    不做类型化 decode 就不会丢字段；
 * 2. **原子替换**：写同目录的 `auth.json.tmp*` 再 `renameTo`（同目录 = 同一文件系统，`rename(2)` 覆盖是原子的），
 *    任何异常路径都清掉 tmp，绝不留半成品给 pi 读；
 * 3. **明文不出本类**：[ProviderEntry] 只带 `keyConfigured` 旗，[readConfigured] 不返回 key，
 *    日志/快照/提交里都不出现凭据（本类也不打日志）。
 *
 * P2H §3.1 起，这三条里「读 / 原子写 / 建目录」三个动作的**工具实现**住在同包 [AgentConfigFiles]
 * （与 [RelayEndpointStore] 共用一份，规格书明令不许复制粘贴两份）。本类的**公开 API 与行为一字未动**：
 * `ModelCredentialStoreTest` 17 条原样全绿就是这次内部重构的验收（tmp 前缀、错误消息、键序全部沿用原值）。
 *
 * 时机安全：pi 只在 spawn 时同步读一次（`readStoredCredential`），RPC 模式运行期不回写
 * （无 `/login` 通路 + `--no-extensions`），所以 app 任意时机写入都不会与子进程抢文件；
 * 代价是**已运行会话不吃新凭据，新会话生效**（§9），UI 侧的 notice 文案由此而来。
 *
 * @param homeDir `PathLayout.homeDir(ctx)` 那个目录（RECON-P2G §1：现成访问器，不写硬路径）
 */
class ModelCredentialStore(private val homeDir: File) {

    /** `<homeDir>/.pi/agent/auth.json`（规格书 §4.1 原文形状；三段拼法与 pi 的 `join()` 逐段对齐）。 */
    val authFile: File
        get() = AgentConfigFiles.agentFile(homeDir, AUTH_FILE_NAME)

    /**
     * 一个 provider 的**无密钥**视图（§4.1 原文：不持 key 明文）。
     *
     * [keyConfigured] = 该条目是 `api_key` 型且 `key` 非空白。为 false 的行有两种来历：
     * 手改过的 `{"deepseek":{}}`，或 pi 侧写进来的非 api_key 条目（如 oauth）——
     * UI 对两者都只说「没有可用密钥」，不显示内容，也不许把它当已配置。
     */
    data class ProviderEntry(val providerId: String, val keyConfigured: Boolean)

    /** 下拉清单的一项：pi 的 providerId + 给人看的展示名。 */
    data class ProviderOption(val providerId: String, val displayName: String)

    /**
     * 已存在的 provider 行（文件缺失/损坏 → 空表，不抛）。
     * 顶层值不是 JSON 对象的垃圾条目（如 `"note":"xxx"`）不进列表，但写回时原样保留（见 [save]）。
     */
    fun readConfigured(): List<ProviderEntry> =
        AgentConfigFiles.readObject(authFile).mapNotNull { (providerId, value) ->
            (value as? JsonObject)?.let { ProviderEntry(providerId, it.isConfiguredApiKey()) }
        }

    /**
     * 写入/更新一个 provider 的 api_key（§4.1）。
     *
     * @throws IllegalArgumentException 任一入参空白（空 key 该走 [clear]，不是写一条空凭据）
     * @throws java.io.IOException 目录建不成（例如 `.pi` 位置被一个普通文件挡着）或原子替换失败
     */
    fun save(providerId: String, apiKey: String) {
        require(providerId.isNotBlank()) { "providerId 不能为空" }
        require(apiKey.isNotBlank()) { "apiKey 不能为空：要撤销凭据请用 clear()" }
        val target = authFile
        val dir = requireNotNull(target.parentFile) { "authFile 没有父目录：${target.path}" }
        AgentConfigFiles.ensureDirectory(dir)
        val merged = buildJsonObject {
            AgentConfigFiles.readObject(target).forEach { (key, value) -> put(key, value) }
            put(providerId, apiKeyCredential(apiKey))
        }
        AgentConfigFiles.writeAtomic(dir, target, merged)
    }

    /**
     * 删掉一个 provider 条目，其余原样保留（§4.1 第 ④ 条）。
     *
     * 幂等：文件不存在、或本来就没有这个条目 → 什么都不做（不凭空建 `{}`，也不重写文件）。
     * 唯一的例外是「文件是损坏 JSON」——此时 `AgentConfigFiles.readObject` 给空对象、`containsKey` 为 false，同样不动文件：
     * 损坏内容由下一次 [save] 负责覆盖成合法对象（§4.1 第 ⑤ 条），clear 不该替它擦屁股。
     *
     * @throws IllegalArgumentException [providerId] 空白
     */
    fun clear(providerId: String) {
        require(providerId.isNotBlank()) { "providerId 不能为空" }
        val target = authFile
        if (!target.isFile) return
        val existing = AgentConfigFiles.readObject(target)
        if (!containsKey(existing, providerId)) return
        val remaining = buildJsonObject {
            existing.forEach { (key, value) -> if (key != providerId) put(key, value) }
        }
        AgentConfigFiles.writeAtomic(requireNotNull(target.parentFile), target, remaining)
    }

    // ── 内部：判定（读 / 原子写 / 建目录三条工具已挪进同包 [AgentConfigFiles]，P2H §3.1）──

    companion object {
        // 路径与 tmp 前缀的真相住在 [AgentConfigFiles]（P2H §3.1），这里保留同名公开常量：
        // `ModelCredentialStoreTest` 一直按它们下断言，公开 API 一字不动。
        const val PI_DIR_NAME: String = AgentConfigFiles.PI_DIR_NAME
        const val AGENT_DIR_NAME: String = AgentConfigFiles.AGENT_DIR_NAME
        const val AUTH_FILE_NAME: String = AgentConfigFiles.AUTH_FILE_NAME

        /** tmp 文件名前缀（与 auth.json 同目录）；单测按它断言「无残留」。 */
        const val TMP_PREFIX: String = AUTH_FILE_NAME + AgentConfigFiles.TMP_MARKER

        const val FIELD_TYPE: String = "type"
        const val FIELD_KEY: String = "key"
        const val TYPE_API_KEY: String = "api_key"

        /** 与 `AndroidRuntimeInfo.MANIFEST_JSON` 同一套宽松配置：未来 pi 加字段不破旧宿主。 */
        val JSON: Json = AgentConfigFiles.JSON

        /**
         * v1 下拉清单（规格书 §3 定案的 10 项，顺序照原文；全是 `api_key` 型）。
         *
         * 这 10 个 id 是 pi 的 `KnownProvider` 子集，改这里等于改「app 认为存在的 provider」——
         * 只在 pi 升版本且 §3 重新定案时动，别按灵感加。
         */
        val KNOWN_PROVIDERS: List<ProviderOption> = listOf(
            ProviderOption("deepseek", "DeepSeek"),
            ProviderOption("anthropic", "Anthropic"),
            ProviderOption("openai", "OpenAI"),
            ProviderOption("openrouter", "OpenRouter"),
            ProviderOption("zai", "Z.AI"),
            ProviderOption("groq", "Groq"),
            ProviderOption("xai", "xAI"),
            ProviderOption("moonshotai-cn", "Moonshot（国内）"),
            ProviderOption("minimax-cn", "MiniMax（国内）"),
            ProviderOption("kimi-coding", "Kimi"),
        )

        /** 展示名：清单里没有的 id（用户手改文件塞进来的）原样回显，不编造名字。 */
        fun displayNameOf(providerId: String): String =
            KNOWN_PROVIDERS.firstOrNull { it.providerId == providerId }?.displayName ?: providerId

        private fun apiKeyCredential(apiKey: String): JsonObject = buildJsonObject {
            // JsonPrimitive 显式包一层：1.9 的 JsonObjectBuilder 只有 put(String, JsonElement) 这条成员，
            // put(String, String) 那组便捷重载不在候选里（编译机实测 e:「actual type is 'String', but 'JsonElement' was expected」）
            put(FIELD_TYPE, JsonPrimitive(TYPE_API_KEY))
            put(FIELD_KEY, JsonPrimitive(apiKey))
        }

        private fun JsonObject.isConfiguredApiKey(): Boolean {
            val type = (this[FIELD_TYPE] as? JsonPrimitive)?.contentOrNull
            val key = (this[FIELD_KEY] as? JsonPrimitive)?.contentOrNull
            return type == TYPE_API_KEY && !key.isNullOrBlank()
        }

        /** `in` 对 JsonObject 走的是 `Map` 委托，这里只是把意图写清楚（保留键的判据 = 键存在，不看值形状）。 */
        private fun containsKey(obj: JsonObject, providerId: String): Boolean = obj.containsKey(providerId)
    }
}
