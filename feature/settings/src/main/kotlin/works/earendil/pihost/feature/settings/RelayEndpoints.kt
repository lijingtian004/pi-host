package works.earendil.pihost.feature.settings

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import works.earendil.pihost.core.engine.settings.RelayEndpointStore
import works.earendil.pihost.core.engine.settings.RelayEndpointStore.RelayEntry

/**
 * 设置页消费的中转面（规格书 P2H §3.2「接口 `RelayEndpointSource` + `FileRelayEndpointSource`
 * （Dispatchers.IO hop，照抄 [ModelCredentialSource] 模式）挂 SettingsModule」）。
 *
 * 为什么要有这一层，理由与凭据面**逐字相同**（见 [ModelCredentialSource] 的 KDoc）：
 * `RelayEndpointStore` 那六个方法是阻塞文件 IO，而 `SettingsViewModel` 跑在主线程的 `viewModelScope` 上；
 * 调度决定由实现侧负责，假体在这里不挂起，于是 `runTest` 与 Robolectric 的 `pump()` 都是纯虚拟时钟。
 * 多出来的一条：**[defaultProvider] 也在接口上**，因为 §3.2 要求「默认中转行高亮（数据从 settings.json 读）」，
 * 而那张表与 models.json 不是同一个文件，VM 不该知道这个区别——它只问 source 要两样东西。
 *
 * §2 红线在这一层的体现：[save] 的 `apiKey` 是**入参方向**（用户刚敲进去的），
 * 出参方向只有 [RelayEntry]（带 `keyConfigured` 旗，无明文）。假体记录入参是为了断言「留空 = 传 null」，
 * 不是为了把 key 读回来。
 */
interface RelayEndpointSource {

    /** 已配置的中转行（**不含 apiKey 明文**，§2 红线）。 */
    suspend fun snapshot(): List<RelayEntry>

    /** `settings.json` 里钉着的默认中转 providerId（没钉 → null）。 */
    suspend fun defaultProvider(): String?

    /**
     * 写入/更新一条中转。[apiKey] 为 null 或空白 = **沿用磁盘上已有的 key**（§3.1 语义，
     * 由 core 侧实现，这一层不改写它）。失败上抛，由调用方 `runCatching` 转成 notice。
     */
    suspend fun save(providerId: String, baseUrl: String, api: String, modelIds: List<String>, apiKey: String?)

    /** 删掉一条中转（幂等；若默认正指着它，core 侧同时清掉悬空默认）。 */
    suspend fun clear(providerId: String)

    /** 钉选默认模型（写 `settings.json` 两字段）。 */
    suspend fun setDefault(providerId: String, modelId: String)
}

/**
 * production 实现：包 [RelayEndpointStore]（`filesDir/home/.pi/agent/models.json` + `settings.json`），
 * 五个方法各自 hop 到 [Dispatchers.IO]。
 *
 * 原子替换、读-改-写、不回显 key 这三条纪律全在 core 侧那个类里，这里**只做调度**，
 * 不建第二份真相、不打任何日志（§2「凭据不进日志」——baseUrl 不是凭据，但也没必要打）。
 */
@Singleton
class FileRelayEndpointSource @Inject constructor(
    private val store: RelayEndpointStore,
) : RelayEndpointSource {

    override suspend fun snapshot(): List<RelayEntry> = withContext(Dispatchers.IO) { store.readRelays() }

    override suspend fun defaultProvider(): String? = withContext(Dispatchers.IO) { store.readDefaultProvider() }

    override suspend fun save(providerId: String, baseUrl: String, api: String, modelIds: List<String>, apiKey: String?) {
        withContext(Dispatchers.IO) { store.save(providerId, baseUrl, api, modelIds, apiKey) }
    }

    override suspend fun clear(providerId: String) {
        withContext(Dispatchers.IO) { store.clear(providerId) }
    }

    override suspend fun setDefault(providerId: String, modelId: String) {
        withContext(Dispatchers.IO) { store.setDefault(providerId, modelId) }
    }
}

/**
 * 中转表单的草稿（五个字段 = 四个输入框 + 选中的 api）。
 *
 * 放在 VM 而不是 `remember` 里，沿用 P2G 对 key 草稿的那条理由（§4.2）：
 * §3.2 要求「保存成功后清空输入框」，而只有知道写盘结果的一方才该发起清空。
 * [api] 不在「清空」之列——它是选择器不是输入框，保存后停在原地才能连着调同一家中转改地址。
 */
data class RelayDraft(
    val providerId: String = "",
    val baseUrl: String = "",
    val models: String = "",
    val apiKey: String = "",
    val api: String = RelayEndpointStore.DEFAULT_API,
)

/**
 * 「模型 ID 列表」输入框的解析（§3.2 第 7 条要求的逗号分隔语义，实现只有一份）。
 *
 * 两件事值得说清：
 * - **同时收半角 `,` 与全角 `，`**：中文输入法下打出的逗号是全角的，只认半角会让人以为 app 坏了；
 *   解析结果与 pi 无关（落盘是 `["id",…]` 数组），所以放宽这一条不破任何下游契约；
 * - **trim + 去空 + 去重**：`"a, ,a"` → `["a"]`。空白项是用户多点了一下逗号造成的，
 *   把它写进 models.json 就是让 pi 去猜一个空 id 的模型；重复 id 同理（pi 会显示两行同名模型）。
 *   core 侧 `RelayEndpointStore.save` 里还有同一套清洗，那是给脚本/手改通路的第三道门，两份一致。
 */
fun parseModelIds(text: String): List<String> =
    text.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
