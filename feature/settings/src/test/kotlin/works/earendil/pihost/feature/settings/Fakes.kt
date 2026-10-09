package works.earendil.pihost.feature.settings

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import works.earendil.pihost.core.engine.ChatEngine
import works.earendil.pihost.core.engine.SessionState
import works.earendil.pihost.core.engine.projects.ProjectNames
import works.earendil.pihost.core.engine.settings.AppSettings
import works.earendil.pihost.core.engine.settings.ModelCredentialStore.ProviderEntry
import works.earendil.pihost.core.engine.settings.RelayEndpointStore.RelayEntry
import works.earendil.pihost.core.engine.settings.RelayModel
import works.earendil.pihost.core.engine.settings.SettingsRepository
import works.earendil.pihost.core.environment.MemTier
import works.earendil.pihost.core.rpc.ExtensionUiResponse
import works.earendil.pihost.core.rpc.PiEvent
import works.earendil.pihost.core.rpc.RpcCommand

/**
 * 设置页用的三个假体（规格书 §4.5「SettingsViewModelTest：探针（GetState 命令捕获 + Response 显示）、
 * memTier 写回 ≥2 条」）。
 *
 * 与 `:feature:sessions` 的 `Fakes.kt` 同一套路：**捕获而非轮询** ——
 * 出站命令进 [FakeEngine.sent]、设置写入进 [FakeSettingsRepository.writes]，
 * 用例直接对这两张表下断言。测试源集不跨模块可见，所以引擎假体在本模块重写一份最小面。
 */
class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {

    /** 一次写入的记录（setter + 参数），顺序即调用顺序。 */
    data class Write(val key: String, val value: Any?)

    private val _settings = MutableStateFlow(initial)
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val writes = CopyOnWriteArrayList<Write>()
    val writeCalls = AtomicInteger(0)

    /** 置非 null → 四个 setter 全部抛它（验「写盘失败时提示说没改」这条分支）。 */
    var writeError: Throwable? = null

    /** 置 false → setter **不**改快照（模拟 edit 失败后仓库保持原值）。 */
    var applyToSnapshot: Boolean = true

    override val memTier: MemTier
        get() = _settings.value.memTier

    override val maxParallelSessions: Int
        get() = _settings.value.maxParallelSessions

    override suspend fun setMemTier(tier: MemTier) {
        writeCalls.incrementAndGet()
        writes.add(Write("memTier", tier))
        writeError?.let { throw it }
        if (applyToSnapshot) _settings.update { it.copy(memTier = tier) }
    }

    override suspend fun setMaxParallelSessions(count: Int) {
        writeCalls.incrementAndGet()
        writes.add(Write("maxParallelSessions", count))
        writeError?.let { throw it }
        if (applyToSnapshot) _settings.update { it.copy(maxParallelSessions = count) }
    }

    override suspend fun setDefaultModel(model: String?) {
        writeCalls.incrementAndGet()
        writes.add(Write("defaultModel", model))
        writeError?.let { throw it }
        if (applyToSnapshot) _settings.update { it.copy(defaultModel = model) }
    }

    /**
     * P3C §3.2 接口扩员补的一条 override。
     *
     * 三个旧 setter 的契约（`writeCalls` 计数 + `writeError` 先抛 + `applyToSnapshot` 决定动不动快照）
     * 原样继承，否则「置非 null → setter 抛它」这句 KDoc 就当场失效。
     * 快照里落 `sanitizeOrFallback` 后的形状，记进 [Write] 的是调用方原样给的值。
     */
    override suspend fun setActiveProject(name: String?) {
        writeCalls.incrementAndGet()
        writes.add(Write("activeProject", name))
        writeError?.let { throw it }
        if (applyToSnapshot) _settings.update { it.copy(activeProject = ProjectNames.sanitizeOrFallback(name)) }
    }
}

/** 引擎假体：只给探针要用的那三面（sessionStates / events / send）。 */
class FakeEngine(val sessionId: String = SESSION) : ChatEngine {

    data class Sent(val sessionId: String, val command: RpcCommand, val id: String?)

    private val _states = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    override val sessionStates: StateFlow<Map<String, SessionState>> = _states.asStateFlow()

    /** 与真总线同构：**无 replay**（这正是「先订阅再发」为什么必须成立的原因）。 */
    private val bus = MutableSharedFlow<PiEvent>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val sent = CopyOnWriteArrayList<Sent>()
    val startCalls = AtomicInteger(0)

    var sendError: Throwable? = null
    var state: SessionState = SessionState.Ready

    fun setState(sessionId: String, state: SessionState) {
        _states.update { it + (sessionId to state) }
        this.state = state
    }

    /** 事件注入：`tryEmit` 的返回值一并检查，别让「没订阅者」变成静默丢事件。 */
    fun emit(event: PiEvent) {
        check(bus.tryEmit(event)) { "事件未能进入总线：$event" }
    }

    override suspend fun start(): String {
        startCalls.incrementAndGet()
        setState(sessionId, state)
        return sessionId
    }

    override suspend fun stop(sessionId: String) {
        _states.update { it - sessionId }
    }

    override suspend fun shutdownAll() {
        _states.value = emptyMap()
    }

    override fun events(sessionId: String): SharedFlow<PiEvent>? =
        if (sessionId == this.sessionId) bus else null

    override suspend fun send(sessionId: String, command: RpcCommand, id: String?): String {
        sendError?.let { throw it }
        val requestId = id ?: "fake-${sent.size + 1}"
        sent.add(Sent(sessionId, command, requestId))
        return requestId
    }

    override fun sendUiResponse(sessionId: String, response: ExtensionUiResponse) = Unit

    override fun stderrTail(sessionId: String): String? = null

    companion object {
        const val SESSION: String = "11111111-2222-4444-8888-555555555555"
    }
}

/**
 * 版本读取假体：两档发布（marker → manifest），[readPauseMs] > 0 时中间真的挂起一次，
 * 于是「读取中 → 具体版本号」两档都能被收集器看见（StateFlow 会把不挂起的中间档 conflate 掉）。
 */
class FakeRuntimeInfo(
    var marker: RuntimeInfo = RuntimeInfo(
        appVersion = "2.5.0-test",
        installedRuntimeVersion = "1.0.4-r7",
        bundledRuntimeVersion = null,
        manifestPending = true,
    ),
    var manifest: ManifestRead = ManifestRead.Present("1.0.4-r7"),
    var readPauseMs: Long = 0L,
) : RuntimeInfoSource {

    val readCalls = AtomicInteger(0)
    val manifestCalls = AtomicInteger(0)

    /** 置非 null → [read] 抛它（验「marker 读不到时整张卡片不崩」）。 */
    var boom: Throwable? = null

    override suspend fun read(): RuntimeInfo {
        readCalls.incrementAndGet()
        boom?.let { throw it }
        if (readPauseMs > 0L) delay(readPauseMs)
        return marker
    }

    override suspend fun readManifest(): ManifestRead {
        manifestCalls.incrementAndGet()
        if (readPauseMs > 0L) delay(readPauseMs)
        return manifest
    }
}

/**
 * 凭据面假体（P2G §4.2「注入 fake store」）。
 *
 * 三条纪律：
 * 1. **不挂起、不碰线程**——真实现自己 hop `Dispatchers.IO`（见 [ModelCredentialSource] 的 KDoc），
 *    假体留在这里，`runTest` 与 Robolectric 的 `pump()` 才是纯虚拟时钟的；
 * 2. **不持 key 明文**——[rows] 里只有 `ProviderEntry`，与生产面同形；[saves] 记的是**收到的入参**，
 *    用来断言「key 确实送进了写入调用」，而不是「UI 能读回 key」；
 * 3. [boom] 一置非 null，三个方法全抛 → 演 §4.2「失败进 notice」那条分支。
 *    为什么用旗而不是真文件权限：编译机是 root，`chmod`/只读目录都拦不住它（P2F §7.1 同一课）。
 */
class FakeModelCredentialSource(initial: List<ProviderEntry> = emptyList()) : ModelCredentialSource {

    /** 一次写入调用（providerId + 送进来的 key）。 */
    data class Save(val providerId: String, val apiKey: String)

    val saves = CopyOnWriteArrayList<Save>()
    val clears = CopyOnWriteArrayList<String>()
    val snapshotCalls = AtomicInteger(0)

    var boom: Throwable? = null

    private val rows = initial.toMutableList()

    override suspend fun snapshot(): List<ProviderEntry> {
        snapshotCalls.incrementAndGet()
        boom?.let { throw it }
        return rows.toList()
    }

    override suspend fun save(providerId: String, apiKey: String) {
        saves.add(Save(providerId, apiKey))
        boom?.let { throw it }
        rows.removeAll { it.providerId == providerId }
        rows.add(ProviderEntry(providerId, keyConfigured = true))
    }

    override suspend fun clear(providerId: String) {
        clears.add(providerId)
        boom?.let { throw it }
        rows.removeAll { it.providerId == providerId }
    }
}

/**
 * 中转面假体（P2H §3.2「UI 测试（Robolectric，fake source）」的注入点）。
 *
 * 四条纪律，前三条与 [FakeModelCredentialSource] 逐字同源：
 * 1. **不挂起、不碰线程**——真实现自己 hop `Dispatchers.IO`，假体留在这里才能让 `runTest`
 *    与 `pump()` 都跑在虚拟时钟上；
 * 2. **不持 key 明文**——[rows] 里只有 `RelayEntry`（带 `keyConfigured` 旗），
 *    [saves] 记的是**收到的入参**：§3.2 要断言的是「留空 key 时送进去的是 null」，
 *    不是「能把 key 读回来」；
 * 3. [boom] 非 null → 四个写方法全抛，演「写盘失败进 notice、草稿还在」那条分支
 *    （为什么不用真文件权限：编译机是 root，`chmod` 拦不住它，P2F §7.1 同一课）；
 * 4. **默认指针与行联动**：[clear] 把默认也一起清掉——这是 core 侧 `RelayEndpointStore.clear`
 *    的第 ⑩ 条语义（不留悬空指针），假体照抄，UI 才可能演「删掉默认那一行 → 徽标一起没了」。
 *    而 `rows` 里删不掉、默认却指着不存在的 id 这种脏状态**故意不做**：VM 侧还有一层
 *    `takeIf { rows.any { … } }` 兜底，两条路都通到同一个显示结果。
 */
class FakeRelayEndpointSource(
    initial: List<RelayEntry> = emptyList(),
    initialDefault: String? = null,
) : RelayEndpointSource {

    /** 一次写入调用（五个入参原样记下来，apiKey 可能是 null；[models] 带思考旗，P3B-FIX1 §3.2 第 5 条）。 */
    data class Save(
        val providerId: String,
        val baseUrl: String,
        val api: String,
        val models: List<RelayModel>,
        val apiKey: String?,
    )

    /** 一次钉选默认（provider + model）。 */
    data class Pin(val providerId: String, val modelId: String)

    val saves = CopyOnWriteArrayList<Save>()
    val clears = CopyOnWriteArrayList<String>()
    val pins = CopyOnWriteArrayList<Pin>()
    val snapshotCalls = AtomicInteger(0)
    val defaultCalls = AtomicInteger(0)

    var boom: Throwable? = null

    private val rows = initial.toMutableList()
    private var default: String? = initialDefault

    override suspend fun snapshot(): List<RelayEntry> {
        snapshotCalls.incrementAndGet()
        boom?.let { throw it }
        return rows.toList()
    }

    override suspend fun defaultProvider(): String? {
        defaultCalls.incrementAndGet()
        boom?.let { throw it }
        return default
    }

    override suspend fun save(providerId: String, baseUrl: String, api: String, models: List<RelayModel>, apiKey: String?) {
        saves.add(Save(providerId, baseUrl, api, models, apiKey))
        boom?.let { throw it }
        // 空白 key = 沿用已存（core 侧语义），假体同样按这个规则落 `keyConfigured`
        val keepsExistingKey = apiKey.isNullOrBlank() &&
            rows.firstOrNull { it.providerId == providerId }?.keyConfigured == true
        rows.removeAll { it.providerId == providerId }
        rows.add(
            RelayEntry(
                providerId = providerId,
                baseUrl = baseUrl,
                api = api,
                models = models,
                keyConfigured = keepsExistingKey || !apiKey.isNullOrBlank(),
            ),
        )
    }

    override suspend fun clear(providerId: String) {
        clears.add(providerId)
        boom?.let { throw it }
        rows.removeAll { it.providerId == providerId }
        if (default == providerId) default = null
    }

    override suspend fun setDefault(providerId: String, modelId: String) {
        pins.add(Pin(providerId, modelId))
        boom?.let { throw it }
        default = providerId
    }
}

/** 一条 `get_state` 的成功应答（data 里塞 [n] 个键，用来把「前 500 字截断」这条语义撑出来）。 */
fun probeResponse(id: String?, success: Boolean = true, keys: Int = 1, error: String? = null): PiEvent.Response {
    val body = buildString {
        append('{')
        for (i in 1..keys) {
            if (i > 1) append(',')
            append("\"k").append(i).append("\":\"").append("x".repeat(60)).append('"')
        }
        append('}')
    }
    return PiEvent.Response(
        id = id,
        command = "get_state",
        success = success,
        data = kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject,
        error = error,
    )
}

/**
 * 诊断面假体（P3A §3.5）：三态全靠三个可写字段演——
 * `rows` 空 = 无崩溃记录，非空 = 有报告；`body` = 可读正文（null = 读不出来）；
 * `boom` 一置，三个口全抛（验「读失败 → 空列表而不是半成品」「清失败 → notice 说原因」）。
 *
 * 与另两个假体同一决定：**不做线程切换**（生产实现切 IO，假体不切），
 * 于是 `runTest` + `UnconfinedTestDispatcher` 下「调一次 → 立刻断言」成立。
 * 真盘面（`pendingReports`/`readLatest`/`clearReports` 的语义）在 `:core:engine` 的
 * `CrashReporterTest`（11 条）里，这里只演形状。
 */
class FakeDiagnosticsSource(
    initial: List<CrashReportSummary> = emptyList(),
    var body: String? = null,
    var clearCount: Int = initial.size,
) : DiagnosticsSource {

    val listCalls = AtomicInteger(0)
    val latestCalls = AtomicInteger(0)
    val clearCalls = AtomicInteger(0)

    /** 一置非 null，三个口都抛它。 */
    var boom: Throwable? = null

    private val rows = initial.toMutableList()

    /** 最近一次 `clear()` 之后剩下的行数（VM 清完会立刻重读一次列表，断言点在这）。 */
    fun currentRows(): List<CrashReportSummary> = rows.toList()

    fun seed(report: CrashReportSummary) {
        rows.add(report)
        clearCount = rows.size
    }

    override suspend fun list(): List<CrashReportSummary> {
        listCalls.incrementAndGet()
        boom?.let { throw it }
        return rows.toList()
    }

    override suspend fun latestText(): String? {
        latestCalls.incrementAndGet()
        boom?.let { throw it }
        return body
    }

    override suspend fun clear(): Int {
        clearCalls.incrementAndGet()
        boom?.let { throw it }
        val deleted = rows.size
        rows.clear()
        clearCount = deleted
        return deleted
    }
}
