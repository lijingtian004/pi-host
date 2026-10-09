package works.earendil.pihost.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import works.earendil.pihost.core.engine.ChatEngine
import works.earendil.pihost.core.engine.SessionState
import works.earendil.pihost.core.engine.settings.AppSettings
import works.earendil.pihost.core.engine.settings.ModelCredentialStore.ProviderEntry
import works.earendil.pihost.core.engine.settings.RelayEndpointStore.RelayEntry
import works.earendil.pihost.core.engine.settings.SettingsRepository
import works.earendil.pihost.core.environment.MemTier
import works.earendil.pihost.core.rpc.PiEvent
import works.earendil.pihost.core.rpc.RpcCommand
import works.earendil.pihost.feature.settings.ui.SettingsStrings

/**
 * `get_state` 探针的结果（规格书 §3.4：探针入口在 settings，不在 chat）。
 *
 * [Done] 的 [Done.text] **已经是截断后的**（[PROBE_TEXT_LIMIT]），并带 [Done.truncated] 旗，
 * 这样 UI 只管显示，截断语义只有一份实现（在 VM 里，可单测）。
 */
sealed interface ProbeState {

    /** 还没探过（进屏初值）。 */
    data object Idle : ProbeState

    /** 命令已出、等应答中（顶栏按钮此时禁用）。 */
    data object Running : ProbeState

    /**
     * 拿到应答。[success] 直接取自 `Response.success`；
     * [sessionId] 是这条应答来自哪条会话（同 §13 偏差 6：会话 id 在顶栏显示短码）。
     */
    data class Done(
        val sessionId: String,
        val success: Boolean,
        val text: String,
        val truncated: Boolean,
        val atMillis: Long,
    ) : ProbeState

    /** 探不了 / 发不出去 / 超时 / 没有事件总线。[retryable] = 值不值得再点一次。 */
    data class Unavailable(val message: String, val retryable: Boolean) : ProbeState
}

/**
 * 设置页的 ViewModel（规格书 §4.2）：`SettingsRepository` + `ChatEngine`（探针）+ `RuntimeInfoSource`（版本卡片）。
 *
 * **四条纪律**：
 * 1. **写设置就是写 DataStore**：三个 setter 全部委托给 [SettingsRepository]（乐观写在仓库里，见它的 KDoc），
 *    VM 不缓存副本、不自建第二份真相；memTier 变更对**后续** spawn 才生效（§13 偏差 4），
 *    本页不试图通知活动会话。
 * 2. **探针先订阅、后发命令**（[works.earendil.pihost.core.engine.ChatEngine.events] 无 replay）：
 *    等待协程用 `async(start = CoroutineStart.UNDISPATCHED)` 起，于是 `SharedFlow.collect` 的注册
 *    一定发生在 `engine.send` 之前——「先订阅再发」由**机制**保证，不靠调度巧合（`:feature:chat`
 *    用长驻收集器达到同一效果，见 ChatViewModel 的 Unconfined 纪律；这里是一次性请求-应答，
 *    UNDISPATCHED 是最短路径）。
 * 3. **探针有超时**（[PROBE_TIMEOUT_MS]）：protocol.md §1 说每条命令都有 response，但进程刚死、
 *    管道阻塞时不会来；没有超时就是一张永久转圈的卡片。
 * 4. **版本卡片读磁盘**：[RuntimeInfoSource.read] 是阻塞 IO，放 `Dispatchers.IO`；
 *    假体那侧不挂起（见 [runtimeInfo] 的类型是接口），所以测试里不需要真线程。
 * 5. **凭据只走 auth.json、只报状态不报内容**（P2G §4.2 + §2 红线）：[credentialStore] 是
 *    [ModelCredentialSource] 接口，阻塞 IO 与线程切换都在实现侧（见它的 KDoc），VM 不做调度决定；
 *    页面能拿到的只有 [ProviderEntry]（providerId + 已配置旗），**没有任何一条路能把 key 回到 UI**；
 *    写入失败只报「原因」，而原因来自 [java.io.IOException] 的 message（内容只有路径，见
 *    [works.earendil.pihost.core.engine.settings.ModelCredentialStore]），不掺入凭据。
 * 6. **中转与凭据同一条红线，但落点不同**（P2H §3.2 + §2）：[relayStore] 写的是 pi 的
 *    `models.json`/`settings.json`，`apiKey` 字段是凭据 —— 不进 DataStore（前三键与它无关）、
 *    不进日志、不回显；出参只有 [RelayEntry]（`keyConfigured` 旗），而 [baseUrl] **不是**凭据，
 *    按 §3.1 的判断可以上屏（用户要能看见自己填的地址）。唯一比凭据通路多出来的语义：
 *    [saveRelay] 的空白 key 是「沿用」而不是「抹掉」，这条转换必须在 VM 里发生，不能让 UI 猜。
 * 7. **诊断面只报「有没有」，不报「内容」**（P3A §3.5 + §2 红线）：[crashReports] 的元素是
 *    [CrashReportSummary]（文件名 + 字节数 + mtime），崩溃正文唯一的出口是 [readLatestCrash]
 *    ——它把字符串交给调用方，VM 自己**不存副本、不上屏**（正文里有绝对路径与完整栈）。
 *    三个动作（刷新/清空/复制）全部经 [DiagnosticsSource] 转发给 `CrashReporter`，
 *    本类不碰 `File`、不拼路径、不排序：目录布局只有一份实现在 `core/engine`。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val engine: ChatEngine,
    private val runtimeInfo: RuntimeInfoSource,
    private val credentialStore: ModelCredentialSource,
    private val relayStore: RelayEndpointSource,
    private val diagnostics: DiagnosticsSource,
) : ViewModel() {

    /** 三键快照，直接转发仓库的流（UI `collectAsState`）。 */
    val settings: StateFlow<AppSettings> = repository.settings

    private val _probe = MutableStateFlow<ProbeState>(ProbeState.Idle)

    /** 探针卡片状态。 */
    val probe: StateFlow<ProbeState> = _probe.asStateFlow()

    private val _runtimeInfo = MutableStateFlow<RuntimeInfo?>(null)

    /** 版本卡片：null = 还在读。 */
    val runtimeInfoState: StateFlow<RuntimeInfo?> = _runtimeInfo.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)

    /** 一次性提示（memTier「下次启动会话生效」/ 写盘失败）；消费后清掉。 */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _credentials = MutableStateFlow<List<ProviderEntry>>(emptyList())

    /**
     * 已配置 provider 的行（§4.2）。**只有 providerId 与「已配置」旗**——这是本屏唯一允许出现的
     * 凭据相关信息（§2 红线「UI 永不回显已存 key」）。
     */
    val credentials: StateFlow<List<ProviderEntry>> = _credentials.asStateFlow()

    private val _credentialDraft = MutableStateFlow("")

    /**
     * key 输入框的草稿（放在 VM 而不是 `remember` 里，只为一条：§4.2 要求「保存成功后清空输入框」，
     * 而清空这个动作只有知道写盘结果的 VM 才该发起）。
     */
    val credentialDraft: StateFlow<String> = _credentialDraft.asStateFlow()

    private val _relays = MutableStateFlow<List<RelayEntry>>(emptyList())

    /** 已配置的中转行（P2H §3.2）。**没有 apiKey**——§2 红线在类型上的体现，同 [credentials]。 */
    val relays: StateFlow<List<RelayEntry>> = _relays.asStateFlow()

    private val _defaultRelay = MutableStateFlow<String?>(null)

    /** `settings.json` 里钉着的默认中转（驱动列表行首那枚「默认」徽标，§3.2）。 */
    val defaultRelay: StateFlow<String?> = _defaultRelay.asStateFlow()

    private val _relayDraft = MutableStateFlow(RelayDraft())

    /** 中转表单的五个字段（四个输入框 + 选中的 api）。 */
    val relayDraft: StateFlow<RelayDraft> = _relayDraft.asStateFlow()

    private val _crashReports = MutableStateFlow<List<CrashReportSummary>>(emptyList())

    /**
     * 崩溃报告列表（P3A §3.5），**新的在前**，顺序由 [DiagnosticsSource] 背后的 `CrashReporter` 定。
     *
     * 只有 [CrashReportSummary] 三样：文件名、字节数、mtime。**没有正文**——
     * 正文里是绝对路径与完整栈，上屏等于把内部结构贴进可截图的 UI；要读它只有一个出口，
     * 就是「复制最近一份」按钮（[readLatestCrash]）。
     */
    val crashReports: StateFlow<List<CrashReportSummary>> = _crashReports.asStateFlow()

    init {
        refreshRuntimeInfo()
        refreshCredentials()
        refreshRelays()
        refreshCrashReports()
    }

    // ── 诊断（P3A §3.5）───────────────────────────────────────────────────────

    /**
     * 重读崩溃报告列表（进屏一次 + 每次清空后 + UI 的手动刷新）。
     *
     * 读失败**不进 notice**，与 [refreshCredentials] / [refreshRelays] 同一决定：
     * 进屏首帧就该有这张列表，磁盘抖动时显示「无崩溃记录」比弹一条提示更诚实——
     * 而且这一页是**诊断**页，用户来看的就是「有没有」，一条红色 toast 反而是噪音。
     */
    fun refreshCrashReports() {
        viewModelScope.launch {
            _crashReports.value = runCatching { diagnostics.list() }.getOrDefault(emptyList())
        }
    }

    /**
     * 清空崩溃报告（§3.5 的「清空」按钮）：成功 → 刷列表 + notice 报**删了几份**；
     * 失败 → notice 说原因，列表原样留着让人原地重试。
     *
     * notice 用「删了几份」而不是「已清空」：`clear()` 返回的是**实际删掉的份数**，
     * 有一个文件被别的进程握着删不掉时，屏幕上说的必须是那个数字（§2「真实输出」的 UI 版）。
     */
    fun clearCrashReports() {
        viewModelScope.launch {
            val outcome = runCatching { diagnostics.clear() }
            outcome.fold(
                onSuccess = { deleted ->
                    _crashReports.value = runCatching { diagnostics.list() }.getOrDefault(emptyList())
                    _notice.value = SettingsStrings.crashCleared(deleted)
                },
                onFailure = { throwable -> _notice.value = SettingsStrings.crashClearFailed(reasonOf(throwable)) },
            )
        }
    }

    /**
     * 「复制最近一份」的读侧原语（§3.5）：交回正文，**没有可读的一份就回 null**。
     *
     * `suspend`：读的是磁盘（[CrashReporter.readLatest] 是同步 IO），调用方（UI 的按钮）在
     * `rememberCoroutineScope` 里 await，主线程只等一个文件读完。
     * 剪贴板写入留在 UI 侧——VM 不碰 `LocalClipboardManager`，那是 Compose 的东西；
     * 两条结果的**文案**则留在这里（[crashCopied] / [crashCopyMissed]），UI 不自己编句子。
     */
    suspend fun readLatestCrash(): String? = runCatching { diagnostics.latestText() }.getOrNull()

    /** 复制成功那条 notice（正文已经在剪贴板里了，这里只说结果，不念内容）。 */
    fun crashCopied() {
        _notice.value = SettingsStrings.DIAGNOSTICS_COPIED
    }

    /** 复制落空那条 notice（列表刚被清空、或全坏时点按钮）。 */
    fun crashCopyMissed() {
        _notice.value = SettingsStrings.DIAGNOSTICS_NONE_NOTICE
    }

    /**
     * 重读 auth.json（进屏一次 + 每次写后）。
     *
     * 读失败**不进 notice**：进屏首帧就该有这张列表，磁盘抖动时显示「还没配置」比弹一条提示更诚实
     * （§4.2 只要求保存/清除的失败进 notice）。
     */
    fun refreshCredentials() {
        viewModelScope.launch {
            _credentials.value = runCatching { credentialStore.snapshot() }.getOrDefault(emptyList())
        }
    }

    /** 输入框变化。 */
    fun onCredentialDraftChange(value: String) {
        _credentialDraft.value = value
    }

    /**
     * 写凭据（§4.2）：成功 → 刷列表 + 清输入框 + notice「已保存，新会话生效（对话进行中请停止后重开）」；
     * 失败 → notice 说原因，列表与输入框都不动（让人能原地重试）。
     *
     * 空 key 第一道门是 UI 的禁用按钮，这里是**第二道**：直接调 VM 也写不进一条空凭据
     * （core 侧 `require` 是第三道，§4.1 第 ⑦ 条）。
     */
    fun saveCredential(providerId: String, apiKey: String) {
        if (apiKey.isBlank()) {
            _notice.value = SettingsStrings.CREDENTIAL_BLANK_KEY
            return
        }
        viewModelScope.launch {
            val outcome = runCatching {
                credentialStore.save(providerId, apiKey)
                credentialStore.snapshot()
            }
            outcome.fold(
                onSuccess = { rows ->
                    _credentials.value = rows
                    _credentialDraft.value = ""
                    _notice.value = SettingsStrings.CREDENTIAL_SAVED
                },
                onFailure = { throwable -> _notice.value = SettingsStrings.credentialWriteFailed(reasonOf(throwable)) },
            )
        }
    }

    /** 撤销一个 provider 的凭据（§4.2）：成功后同样刷新列表 + notice，生效时机与保存一致。 */
    fun clearCredential(providerId: String) {
        viewModelScope.launch {
            val outcome = runCatching {
                credentialStore.clear(providerId)
                credentialStore.snapshot()
            }
            outcome.fold(
                onSuccess = { rows ->
                    _credentials.value = rows
                    _notice.value = SettingsStrings.credentialCleared(providerId)
                },
                onFailure = { throwable -> _notice.value = SettingsStrings.credentialWriteFailed(reasonOf(throwable)) },
            )
        }
    }

    /**
     * 重读两张表（进屏一次 + 每次写后，§3.2）。
     *
     * 两件事一起办：models.json 的行与 settings.json 的默认指针是**两个文件**，
     * 任何一次写入都可能动到其中之一（`clear` 就会连带清悬空默认），所以每次刷新都成对读回来，
     * 不留下「行没了、徽标还亮着」这种半新半旧的列表。
     *
     * 读失败不进 notice（与 [refreshCredentials] 同一决定）：进屏首帧就该有这张列表，
     * 磁盘抖动时显示「还没配置」比弹一条提示更诚实。
     */
    fun refreshRelays() {
        viewModelScope.launch {
            val rows = runCatching { relayStore.snapshot() }.getOrDefault(emptyList())
            _relays.value = rows
            _defaultRelay.value = runCatching { relayStore.defaultProvider() }.getOrNull()
                // 默认指针指着一条已经不存在的行（用户手删 models.json）：徽标不该亮着
                ?.takeIf { default -> rows.any { it.providerId == default } }
        }
    }

    /** 输入框/选择器变化：[transform] 自己说改哪个字段（五合一，免得 VM 里长出五个 `onXxxChange`）。 */
    fun onRelayDraftChange(transform: (RelayDraft) -> RelayDraft) {
        _relayDraft.value = transform(_relayDraft.value)
    }

    /**
     * 写中转（§3.2）：成功 → 刷列表 + 清三个文本框（[RelayDraft.api] 保留）+ notice「已保存，新会话生效」；
     * 失败 → notice 说原因，草稿原样留着让人原地重试。
     *
     * [apiKey] **空白一律当 null 传下去**：这正是 §3.2 第 2 条要的语义 ——
     * 「留空 = 沿用磁盘上已有的 key」，而不是「把 key 抹掉」。把这件事在 VM 里定死，
     * UI 就只需负责把输入框的原样交上来，假体侧则断言收到的确实是 null。
     *
     * 三道门（与凭据通路同构）：providerId 空白 → 拦；baseUrl 空白 → 拦；模型清单解析后为空 → 拦。
     * 三条都**不碰 source**（一次调用都不发），core 侧的 `require` 是第四道，防的是脚本直调。
     */
    fun saveRelay(providerId: String, baseUrl: String, api: String, modelIds: List<String>, apiKey: String?) {
        if (providerId.isBlank()) {
            _notice.value = SettingsStrings.RELAY_BLANK_ID
            return
        }
        if (baseUrl.isBlank()) {
            _notice.value = SettingsStrings.RELAY_BLANK_URL
            return
        }
        if (modelIds.isEmpty()) {
            _notice.value = SettingsStrings.RELAY_BLANK_MODELS
            return
        }
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            val outcome = runCatching {
                relayStore.save(providerId.trim(), baseUrl.trim(), api, modelIds, key)
                relayStore.snapshot() to relayStore.defaultProvider()
            }
            outcome.fold(
                onSuccess = { (rows, default) ->
                    _relays.value = rows
                    _defaultRelay.value = default?.takeIf { d -> rows.any { it.providerId == d } }
                    _relayDraft.value = _relayDraft.value.copy(providerId = "", baseUrl = "", models = "", apiKey = "")
                    _notice.value = SettingsStrings.RELAY_SAVED
                },
                onFailure = { throwable -> _notice.value = SettingsStrings.relayWriteFailed(reasonOf(throwable)) },
            )
        }
    }

    /**
     * 钉选默认（§3.2）：成功后 notice「默认模型已钉选，新会话直接用它」，并重新读一次默认指针
     * （指针是 settings.json 的内容，必须**读回来**才算数，不能拿参数直接写进 StateFlow）。
     *
     * 只有列表里真有这条中转、且它有至少一个模型时才允许钉 —— UI 侧用按钮禁用挡住第一层，
     * 这里挡第二层（`modelId` 空白在 core 侧是 `require`，但那是异常通路，不适合当提示来源）。
     */
    fun setRelayDefault(providerId: String, modelId: String) {
        if (providerId.isBlank() || modelId.isBlank()) {
            _notice.value = SettingsStrings.RELAY_NO_MODEL
            return
        }
        viewModelScope.launch {
            val outcome = runCatching {
                relayStore.setDefault(providerId, modelId)
                relayStore.defaultProvider()
            }
            outcome.fold(
                onSuccess = { default ->
                    _defaultRelay.value = default
                    _notice.value = SettingsStrings.RELAY_DEFAULT_SET
                },
                onFailure = { throwable -> _notice.value = SettingsStrings.relayWriteFailed(reasonOf(throwable)) },
            )
        }
    }

    /**
     * 删一条中转（§3.2）：成功后刷列表；core 侧若清掉了悬空默认，这里跟着刷新的默认指针一起归位。
     *
     * 与凭据通路同一个措辞：说清「新会话生效」，不暗示热加载。
     */
    fun clearRelay(providerId: String) {
        if (providerId.isBlank()) return
        viewModelScope.launch {
            val outcome = runCatching {
                relayStore.clear(providerId)
                relayStore.snapshot() to relayStore.defaultProvider()
            }
            outcome.fold(
                onSuccess = { (rows, default) ->
                    _relays.value = rows
                    _defaultRelay.value = default?.takeIf { d -> rows.any { it.providerId == d } }
                    _notice.value = SettingsStrings.relayCleared(providerId)
                },
                onFailure = { throwable -> _notice.value = SettingsStrings.relayWriteFailed(reasonOf(throwable)) },
            )
        }
    }

    /**
     * 重读版本三格（进屏一次 + UI 的手动刷新）。
     *
     * 两档发布：marker 先落（「未安装 / 1.0.4-…」立刻可见），随包清单再补写
     * （UI 从「读取中」变成具体号）。调度都在 [RuntimeInfoSource] 内部（IO），VM 不碰线程。
     */
    fun refreshRuntimeInfo() {
        viewModelScope.launch {
            _runtimeInfo.value = runCatching { runtimeInfo.read() }.getOrNull()
            val base = _runtimeInfo.value ?: RuntimeInfo(
                appVersion = RuntimeInfo.UNKNOWN,
                installedRuntimeVersion = null,
                bundledRuntimeVersion = null,
                manifestPending = true,
            )
            _runtimeInfo.value = base.withManifest(
                runCatching { runtimeInfo.readManifest() }.getOrElse { ManifestRead.Missing },
            )
        }
    }

    /**
     * memTier 写入（[SettingsRepository.setMemTier] 是乐观写：edit 成功后改内存快照），
     * 回执只根据**仓库回读到的值**给，不根据参数猜——写盘失败时快照不动，提示就得说没改。
     */
    fun setMemTier(tier: MemTier) {
        viewModelScope.launch {
            val before = settings.value.memTier
            val written = runCatching { repository.setMemTier(tier) }.isSuccess
            val now = settings.value.memTier
            _notice.value = when {
                !written -> SettingsStrings.SETTING_WRITE_FAILED
                now == tier -> SettingsStrings.memTierSaved(tier)
                else -> SettingsStrings.memTierUnchanged(before)
            }
        }
    }

    /** 并行上限步进（越界夹紧到 [SettingsRepository.PARALLEL_RANGE]，不抛）。 */
    fun setMaxParallelSessions(count: Int) {
        viewModelScope.launch {
            val clamped = count.coerceIn(SettingsRepository.PARALLEL_RANGE)
            runCatching { repository.setMaxParallelSessions(clamped) }
            if (clamped != count) _notice.value = SettingsStrings.parallelClamped(clamped)
        }
    }

    /** 默认模型只是显示（P2E 不做模型切换，§13 偏差 7 同源）；这里留写口给 P3，不在 UI 暴露输入框。 */
    suspend fun setDefaultModel(model: String?) {
        runCatching { repository.setDefaultModel(model) }
    }

    /**
     * 发一条 `get_state` 并把 `data` 显示出来（§3.4）。
     *
     * 活会话的判据与 `:feature:sessions` 一致：优先 [SessionState.Ready]，
     * 其次 [SessionState.Spawning]（进程已起、握手未定，命令仍能进 outbox）；
     * `Failed`/`Stopped` 不算活。多条时取插入序第一条（单引擎多会话场景里就是最早那条）。
     */
    fun runProbe() {
        val id = liveSessionId()
        if (id == null) {
            _probe.value = ProbeState.Unavailable(SettingsStrings.PROBE_NO_SESSION, retryable = false)
            return
        }
        val bus = engine.events(id)
        if (bus == null) {
            _probe.value = ProbeState.Unavailable(SettingsStrings.PROBE_NO_BUS, retryable = false)
            return
        }
        _probe.value = ProbeState.Running
        val requestId = "settings-${UUID.randomUUID()}"
        // 外层走 viewModelScope（生产 = Main.immediate，测试 = UnconfinedTestDispatcher），
        // 于是下面那条 `withTimeoutOrNull` 挂在**带虚拟时钟的调度器**上（t04 超时用例靠这一点才可测）。
        viewModelScope.launch {
            val reply = CompletableDeferred<PiEvent.Response>()
            // **先订阅、后发命令**（`ChatEngine.events` 无 replay，晚一拍就永远错过这条应答）。
            // 订阅的注册由机制保证，而不是时序巧合：内层 `launch(Dispatchers.Unconfined)` 在
            // 当前线程立刻执行到第一个挂起点，`SharedFlow.collect` 的注册发生在挂起之前——
            // 这正是 `ChatViewModel` 事件收集器用的同一套纪律（RECON-P2D §4），
            // 本仓 23 条聊天用例已经把这条路跑绿过，所以这里不另发明（换 `first{}`/`onSubscription`
            // 的两种写法在编译机实测收不到事件，别再走第三遍）。
            val collector = launch(Dispatchers.Unconfined) {
                bus.collect { event ->
                    if (event is PiEvent.Response && event.id == requestId) reply.complete(event)
                }
            }
            val sent = runCatching { engine.send(id, RpcCommand.GetState, requestId) }
            if (sent.isFailure) {
                collector.cancel()
                _probe.value = sent.exceptionOrNull().asProbeSendFailure()
                return@launch
            }
            val event = withTimeoutOrNull(PROBE_TIMEOUT_MS) { reply.await() }
            collector.cancel()
            _probe.value = if (event == null) {
                ProbeState.Unavailable(SettingsStrings.PROBE_TIMEOUT, retryable = true)
            } else {
                renderResponse(id, event)
            }
        }
    }

    /** 消费一次性提示（LaunchedEffect 自动收起时也调它）。 */
    fun consumeNotice() {
        _notice.value = null
    }

    /** 手动清场探针卡片（UI 的「清空」按钮）。 */
    fun clearProbe() {
        _probe.value = ProbeState.Idle
    }

    private fun liveSessionId(): String? {
        val states = engine.sessionStates.value
        return states.entries.firstOrNull { it.value is SessionState.Ready }?.key
            ?: states.entries.firstOrNull { it.value is SessionState.Spawning }?.key
    }

    /** 收集协程里的非 Response 事件：原样转一条 Done 文案（协议漂移时至少看得见）。 */
    private fun renderResponse(sessionId: String, event: PiEvent): ProbeState {
        if (event !is PiEvent.Response) {
            return ProbeState.Unavailable(SettingsStrings.PROBE_NOT_RESPONSE, retryable = true)
        }
        val raw = if (event.success) {
            event.data?.toString() ?: event.command
        } else {
            event.error ?: SettingsStrings.PROBE_FAILED
        }
        val text = raw.take(PROBE_TEXT_LIMIT)
        return ProbeState.Done(
            sessionId = sessionId,
            success = event.success,
            text = text,
            truncated = raw.length > text.length,
            atMillis = System.currentTimeMillis(),
        )
    }

    /** 命令根本没出去（无活动进程等）：连应答都不会有，直接落 Unavailable。 */
    private fun Throwable?.asProbeSendFailure(): ProbeState.Unavailable {
        val reason = this?.message ?: this?.javaClass?.simpleName ?: "未知"
        return ProbeState.Unavailable(SettingsStrings.PROBE_SEND_FAILED + reason, retryable = true)
    }

    /**
     * 写盘失败的人话原因（§4.2 notice）。
     *
     * message 优先：store 抛的都是 `java.io.IOException("无法创建目录：…")` 这种**只含路径**的句子；
     * 没有 message 才退回类名。两条都不会带出凭据内容。
     */
    private fun reasonOf(throwable: Throwable?): String =
        throwable?.message?.takeIf { it.isNotBlank() } ?: throwable?.javaClass?.simpleName ?: "未知"

    companion object {
        /** 探针应答的等待上限（比 `:feature:chat` 的握手超时短——这是 UI 上的一个按钮，不该让人等 10 秒）。 */
        const val PROBE_TIMEOUT_MS: Long = 5_000L

        /** `data` JSON 的显示上限（规格书 §3.4 原文「前 500 字」）。 */
        const val PROBE_TEXT_LIMIT: Int = 500
    }
}
