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
import works.earendil.pihost.core.engine.settings.AppSettings
import works.earendil.pihost.core.engine.settings.ModelCredentialStore.ProviderEntry
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

    /** 置非 null → 三个 setter 抛它（验「写盘失败时提示说没改」这条分支）。 */
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

/** 一条 `get_state` 的成功应答（data 里塞 [n] 个键，用来把「前 500 字截断」这条语义撑出来）。 */fun probeResponse(id: String?, success: Boolean = true, keys: Int = 1, error: String? = null): PiEvent.Response {
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
