package works.earendil.pihost.feature.settings

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import works.earendil.pihost.core.engine.settings.RelayEndpointStore
import works.earendil.pihost.core.engine.settings.RelayEndpointStore.RelayEntry
import works.earendil.pihost.core.engine.settings.RelayModel

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
     * 由 core 侧实现，这一层不改写它）。[models] 是带思考旗的模型项（P3B-FIX1 §3.2 第 1 条：
     * `modelIds: List<String>` 表达不了「哪个模型能调思考档」，而这件事在 pi 侧是**模型级**字段）。
     * 失败上抛，由调用方 `runCatching` 转成 notice。
     */
    suspend fun save(providerId: String, baseUrl: String, api: String, models: List<RelayModel>, apiKey: String?)

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

    override suspend fun save(providerId: String, baseUrl: String, api: String, models: List<RelayModel>, apiKey: String?) {
        withContext(Dispatchers.IO) { store.save(providerId, baseUrl, api, models, apiKey) }
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
 *
 * [models] 是输入框里的**原文**（逗号分隔、思考模型带尾缀 `*`），不是解析结果：
 * 编辑框必须存用户能打出来的东西，解析每次在渲染时做一次（[parseRelayModels]）。
 * 反方向由 [relayModelsToText] 负责——[works.earendil.pihost.feature.settings.SettingsViewModel.editRelay]
 * 播种草稿时用它，所以「带 `*` 存进去的行，点开后仍带 `*`」。
 */
data class RelayDraft(
    val providerId: String = "",
    val baseUrl: String = "",
    val models: String = "",
    val apiKey: String = "",
    val api: String = RelayEndpointStore.DEFAULT_API,
)

/**
 * 「模型 ID 列表」输入框的解析（P3B-FIX1 §3.2 第 1 条：`parseModelIds` 的升级版，实现仍只有一份）。
 *
 * 四件事值得说清：
 * - **同时收半角 `,` 与全角 `，`**：中文输入法下打出的逗号是全角的，只认半角会让人以为 app 坏了；
 *   解析结果与 pi 无关（落盘是 `{"id":…}` 数组），所以放宽这一条不破任何下游契约（P2H 语义原样保留）；
 * - **项尾缀半角 `*` = 思考模型**：`deepseek-reasoner*` → `RelayModel("deepseek-reasoner", true)`。
 *   这是本轮唯一的语法入口，因为 pi 的开关就是模型级一个布尔（§1 第 3 条），
 *   而「哪些模型有思考档」只有用户知道（中转站的模型目录 app 读不到，pi 也不替它猜）。
 *   剥缀用 `trimEnd('*')`，所以 `a**` 合法（= `a`, true）；**只认半角 `*`**——全角 `＊` 不当语法，
 *   不加 `+`/`?` 之类更多语法（每多一种语法，回填时就多一种可能被输入法改坏的东西）；
 * - **trim + 去空 + 按 id 去重**：`"a, ,a"` → `[a]`。空白项是用户多点了一下逗号造成的，
 *   把它写进 models.json 就是让 pi 去猜一个空 id 的模型；重复 id 同理（pi 会显示两行同名模型）；
 * - **同 id 混后缀 → reasoning 取或**：`"a,a*"` → `[a(true)]`。规则是 OR 而不是「后写的算」也不是 AND：
 *   AND 会让 `a,a*` 变回 false，等于**静默吃掉用户显式打上的那个星号**——而星号是本轮唯一的思考入口，
 *   吃掉它的表现和这次的 bug 一模一样（档位只剩 off，且保存回执仍是成功）。
 *
 * core 侧 `RelayEndpointStore.save` 里还有**第二道门**（§3.1 第 5 条，给脚本与手改通路）：
 * trim / 去空 id / 按 id 去重 / 同 id 取或这四条与这里逐字同语义，改一处必须改另一处。
 * 唯一多出来的是上面那条「剥完只剩星号也算无效项」——它守的是 `*` 这层**表单语法**，
 * 而 core 收到的已经是解析好的 [RelayModel]，不该再认星号：手改 models.json 写一个名叫 `*` 的模型
 * 是用户的自由，core 不替他改（§2 第 7 条「非托管字段原样保留」同一课）。
 */
fun parseRelayModels(text: String): List<RelayModel> {
    val merged = LinkedHashMap<String, Boolean>()
    text.split(',', '，').forEach { token ->
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return@forEach
        val starless = trimmed.trimEnd(REASONING_MARK)
        val id = starless.trim()
        // 剥掉尾缀星号之后还要再过一次空白与星号：`"* *"` 剥成 `"* "`，留着它等于往 models.json
        // 里写一个「只有语法字符、没有 id」的项——pi 侧会渲染出一行点不动的模型。
        if (id.isEmpty() || id.all { it == REASONING_MARK }) return@forEach
        val reasoning = starless.length != trimmed.length
        merged[id] = merged.getOrDefault(id, false) || reasoning
    }
    return merged.map { (id, reasoning) -> RelayModel(id, reasoning) }
}

/**
 * [parseRelayModels] 的**逆**：把一条已存清单渲染回输入框文本（§3.2 第 2 条「编辑回填」）。
 *
 * 这一对必须互为逆函数，否则「写 `a*` 存进去、再打开变成 `a`」——用户在屏幕上看到的
 * 就是他刚存的东西，思考旗却已经在草稿里丢了，下一次保存会把它写没。
 * 分隔符固定半角 `,`、不带空格：这样 [parseRelayModels] 逐字还回同一份清单，
 * 回填 → 保存 的往返是**无损**的（用例 `h14` 钉这一条）。
 */
fun relayModelsToText(models: List<RelayModel>): String =
    models.joinToString(separator = ",") { it.id + if (it.reasoning) REASONING_MARK.toString() else "" }

/** 思考模型的语法字符：项尾缀一个半角星号。 */
const val REASONING_MARK: Char = '*'

