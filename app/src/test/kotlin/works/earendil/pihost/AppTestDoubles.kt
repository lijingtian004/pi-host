package works.earendil.pihost

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.junit.runner.Description
import org.junit.runners.model.Statement
import works.earendil.pihost.core.engine.ChatEngine
import works.earendil.pihost.core.engine.InstallProgress
import works.earendil.pihost.core.engine.RuntimeInstall
import works.earendil.pihost.core.engine.SessionState
import works.earendil.pihost.core.engine.audit.AuditEntry
import works.earendil.pihost.core.engine.audit.AuditSink
import works.earendil.pihost.core.engine.di.EngineModule
import works.earendil.pihost.core.engine.settings.AppSettings
import works.earendil.pihost.core.engine.settings.ModelCredentialStore
import works.earendil.pihost.core.engine.settings.RelayEndpointStore
import works.earendil.pihost.core.engine.settings.SettingsRepository
import works.earendil.pihost.core.environment.MemTier
import works.earendil.pihost.core.environment.PathLayout
import works.earendil.pihost.feature.settings.FileRelayEndpointSource
import works.earendil.pihost.feature.settings.RelayEndpointSource
import works.earendil.pihost.core.rpc.ExtensionUiResponse
import works.earendil.pihost.core.rpc.PiEvent
import works.earendil.pihost.core.rpc.RpcCommand

/**
 * app 侧测试假体与 Hilt 注入口（规格书 §7「app 测试：用 Hilt test module 绑 FakeChatEngine」）。
 *
 * **为什么要 holder + 无 scope 的 provider**（与 `core/engine` 的 `EngineTestHolder` 同一套理由）：
 * Hilt 的 `@Singleton` 组件在 Robolectric 沙箱里跨用例复用，而每条用例都要一对**全新**假体
 * （计数器、捕获列表不能串味）。provider 不带 scope ⇒ 每次注入点构造都重新调用，
 * 拿到的就是 [AppTestHolder.reset] 刚塞进去的那一对。
 *
 * **为什么假体重复定义**（而不是复用 `:feature:chat` 测试源集里的 `FakeChatEngine`）：
 * 复用要引 `testFixtures` 或额外的测试固件模块，两者都超出规格书 §2 的依赖白名单；
 * 这里只记「调用面」，比 feature 侧的脚本化总线更薄。
 */
object AppTestHolder {
    var engine: RecordingChatEngine = RecordingChatEngine()
        private set
    var installer: ScriptedRuntimeInstall = ScriptedRuntimeInstall()
        private set

    /**
     * 设置面（P2E §4.3）：`ChatViewModel` 现在要 [SettingsRepository] 才能建图
     * （maxParallelSessions 门读它）。默认快照 = 上限 2，`sessionStates` 初值为空 ⇒
     * app 侧主路径（安装门 → Ready）不会被门拦住，用例想演拦截就改 [ScriptedSettingsRepository]。
     */
    var settings: ScriptedSettingsRepository = ScriptedSettingsRepository()
        private set

    /**
     * 审计面（P3A §3.2）：`ChatViewModel` 多了第六个注入参数（`auditSink`），
     * 卸载了 `EngineModule` 的测试图必须补这条绑定，否则 Hilt 建图期就缺 binding
     * （P2G §8 第 4 条 / P2H §3.3 的同一课，一次不落）。
     *
     * 给的是**内存假体**而不是「真类 + 假目录」：审计的写盘面在 `:core:engine` 的
     * `FileAuditSinkTest`（14 条）里已经测透，`:app` 只需要「图建得起来 + 条目收得到」。
     */
    var audit: RecordingAuditSink = RecordingAuditSink()
        private set

    fun reset() {
        engine = RecordingChatEngine()
        installer = ScriptedRuntimeInstall()
        settings = ScriptedSettingsRepository()
        audit = RecordingAuditSink()
    }
}

/**
 * 最外层规则：在 **Hilt 建图与 Activity 启动之前**换掉假体。
 *
 * JUnit4 的 `order` 越小越外层，而 `@Before` 在所有规则**之内**——
 * `createAndroidComposeRule` 在规则阶段就把 Activity 起起来了，
 * 放 @Before 里 reset 会晚一步（VM 已握住上一条用例的假体）。
 */
class OutermostRule(private val action: () -> Unit) : org.junit.rules.TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                action()
                base.evaluate()
            }
        }
}

@Module
@InstallIn(SingletonComponent::class)
object AppTestEngineModule {
    /** 不给 scope：见 [AppTestHolder] 的 provider 时机说明。 */
    @Provides
    fun chatEngine(): ChatEngine = AppTestHolder.engine

    @Provides
    fun runtimeInstall(): RuntimeInstall = AppTestHolder.installer

    @Provides
    fun settingsRepository(): SettingsRepository = AppTestHolder.settings

    /**
     * 凭据面（P2G §4.1）：`SettingsViewModel` 多了一条注入边，卸载了 `EngineModule` 的测试图必须补上，
     * 否则 Hilt 在建图阶段就缺绑定（`:app` 的设置路由会真造这个 VM，见 `MainActivityTest:241-253`）。
     *
     * 这里给的是**真类 + 假目录**：`ModelCredentialStore(PathLayout.homeDir(ctx))` 的 `ctx` 是
     * Robolectric 沙箱 Application，`filesDir` 指向每沙箱唯一的临时目录，所以：
     * - 写的是临时盘上的 `home/.pi/agent/auth.json`，不碰任何设备路径（P2F §7.1 的越界教训）；
     * - 语义与生产一致（原子读-改-写真的跑），而凭据写入的完整覆盖在 `:core:engine` 的
     *   `ModelCredentialStoreTest`（17 条），`:app` 只需要这条边能让图建起来 + 列表是空的。
     */
    @Provides
    @Singleton
    fun modelCredentialStore(@ApplicationContext ctx: Context): ModelCredentialStore =
        ModelCredentialStore(PathLayout.homeDir(ctx))

    /**
     * 中转面（P2H §3.3）：`SettingsViewModel` 的第五个依赖是 [RelayEndpointSource]，
     * 而它的实现 [FileRelayEndpointSource] 要 [RelayEndpointStore] —— 后者由 `EngineModule` 提供，
     * 本文件一条 `@UninstallModules(EngineModule::class)` 把它连同凭据面一起卸载掉了，
     * 于是**这里必须补的就是这条 store 边**（与上面 [modelCredentialStore] 完全同一课，P2G §8 第 4 条）。
     *
     * 规格书 §3.3 原文写的是「补 RelayEndpointStore/RelayEndpointSource 注入边」，实测只需前者：
     * `RelayEndpointSource` 的接口→实现边住在**没有被卸载**的 `SettingsModule` 里，
     * 这里再给一条就是 `DuplicateBindings`（编译期直接失败，不是运行期才炸）。
     * 报告 §8 记这条偏差。
     *
     * 同样是**真类 + 假目录**：Robolectric 沙箱 Application 的 `filesDir` 每沙箱唯一，
     * 落盘的是临时 `home/.pi/agent/models.json` + `settings.json`，不碰设备路径；
     * 中转读写的完整覆盖在 `:core:engine` 的 `RelayEndpointStoreTest`（P3B-FIX1 后 37 条）与
     * `:feature:settings` 的两张测试表（57 条），`:app` 只要这条边能让图建起来 + 列表是空的。
     */
    @Provides
    @Singleton
    fun relayEndpointStore(@ApplicationContext ctx: Context): RelayEndpointStore =
        RelayEndpointStore(PathLayout.homeDir(ctx))

    /**
     * 审计面（P3A §3.2）：接口 = [AuditSink]，实现 = 本文件的 [RecordingAuditSink]。
     *
     * 与 [chatEngine] 一样**不给 scope**：每条用例要一对全新条目表（见 [AppTestHolder] 顶部说明）。
     */
    @Provides
    fun auditSink(): AuditSink = AppTestHolder.audit

    /**
     * §3.6 注入边清点（P3A 新增的两条依赖，**这里一条都不补**，写下来是为了让下一个人不必再试）：
     *
     * - [CrashReporter]：绑定住在 `:app` **主源集**的 `CrashReportModule`，本文件只卸载
     *   `EngineModule`，所以它没被卸掉；再给一条就是 `DuplicateBindings`（KSP 期直接失败）。
     *   真图里它由 Robolectric 沙箱 Application 的 `filesDir/crash` 落地，`appVersion` 取
     *   `BuildConfig.VERSION_NAME`——版本一升（P3B-FIX1 §3.3 的 7 / 0.1.6）崩溃报告首行跟着变，
     *   不需要在这里再写一份真相。
     * - [works.earendil.pihost.feature.settings.DiagnosticsSource]：接口→实现那条转发边住在
     *   **没有被卸载**的 `SettingsModule` 里，与 P2H 的 `RelayEndpointSource` 完全同一课
     *   （见 [relayEndpointStore] 的说明）：`:app` 缺的从来只是 store 本体，而 `CrashReporter`
     *   本体在上面那条里已经有人给了。
     *
     * 实证就是 `MainActivityTest:241-253` 那条设置路由：它真造 `SettingsViewModel`
     * （六个依赖全图解析），P3A §3.5 之后仍然是绿的——图建得起来，就不该再加边。
     *
     * **P3B §3.5 复查（结论仍是一条都不补）**：本轮把 `ChatViewModel` 从「发消息/插话」扩到
     * 「模型白名单 + 统计条 + 压缩开关」，新增的全是**方法**（`refreshModels/refreshModelState/
     * setModel/refreshStats/compactContext/setAutoCompaction`）与**UI 合成品**（`ModelChip/ModelPickerDialog/StatsBar`），
     * `@Inject constructor` 的六个参数一字未动——统计与模型都经既有 [ChatEngine.send] 走 RPC，
     * 解析住在 `:feature:chat` 的 `ChatSessionStats/ChatModelPicker`，没有任何新的可注入协作者。
     * 所以规格书 §3.5 那句「AppTestDoubles 补新注入边（若有）」实测为**无**，
     * 报告 §8 记这条「若有 → 判定为无」的偏差。
     */
}

/**
 * [AuditSink] 假体（P3A §3.2）：只把条目收进内存表，不落盘、不抛。
 *
 * 「不抛」是接口契约本身要求的（`AuditSink` KDoc：实现必须不抛），
 * 所以这里连 `runCatching` 都不需要；违约形态的覆盖在 `:feature:chat` 的
 * `ChatViewModelTest` 用例 30（那条用的 chat 侧 `FakeAuditSink.boom`）。
 */
class RecordingAuditSink : AuditSink {
    val entries = CopyOnWriteArrayList<AuditEntry>()

    override fun record(entry: AuditEntry) {
        entries.add(entry)
    }
}

/**
 * [SettingsRepository] 假体：内存快照 + 调用记录，不碰磁盘（app 测试不引 DataStore 规则）。
 *
 * 存在的唯一理由是把 P2E 新增的注入依赖补全，让 `@UninstallModules(EngineModule::class)`
 * 之后 Hilt 仍能建图；设置语义本身的覆盖在 `:core:engine` 的 SettingsRepositoryTest。
 */
class ScriptedSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val _settings = MutableStateFlow(initial)
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val writes = CopyOnWriteArrayList<String>()

    override suspend fun setMemTier(tier: MemTier) {
        writes.add("mem_tier=${tier.name}")
        _settings.update { it.copy(memTier = tier) }
    }

    override suspend fun setMaxParallelSessions(count: Int) {
        writes.add("max_parallel_sessions=$count")
        _settings.update { it.copy(maxParallelSessions = count) }
    }

    override suspend fun setDefaultModel(model: String?) {
        writes.add("default_model=$model")
        _settings.update { it.copy(defaultModel = model) }
    }
}

/**
 * [ChatEngine] 假体：只记调用面，事件总线由测试手动 [emit]。
 *
 * `@UninstallModules(EngineModule::class)` 把真装配（PiSpawn/JvmProcessFactory/AssetInstaller）
 * 整块换掉，所以 Robolectric 里不需要 `nativeLibraryDir` 也能建图。
 */
class RecordingChatEngine : ChatEngine {
    private val states = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    override val sessionStates: StateFlow<Map<String, SessionState>> = states.asStateFlow()

    private val bus = MutableSharedFlow<PiEvent>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val startCalls = AtomicInteger(0)
    val shutdownCalls = AtomicInteger(0)
    val sent = CopyOnWriteArrayList<Sent>()
    val uiResponses = CopyOnWriteArrayList<ExtensionUiResponse>()
    val stoppedIds = CopyOnWriteArrayList<String>()

    var startState: SessionState = SessionState.Ready
    var startError: Throwable? = null

    /** 一条出站命令（id = VM 生成的 `ui-n`，用于关联 response）。 */
    data class Sent(val sessionId: String, val command: RpcCommand, val id: String)

    fun emit(event: PiEvent) {
        check(bus.tryEmit(event)) { "事件没进总线：$event" }
    }

    fun setState(state: SessionState) {
        states.update { it + (SESSION to state) }
    }

    override suspend fun start(): String {
        startCalls.incrementAndGet()
        startError?.let { throw it }
        states.update { it + (SESSION to startState) }
        return SESSION
    }

    override suspend fun stop(sessionId: String) {
        stoppedIds.add(sessionId)
        states.update { it - sessionId }
    }

    override suspend fun shutdownAll() {
        shutdownCalls.incrementAndGet()
        stoppedIds.addAll(states.value.keys)
        states.value = emptyMap()
    }

    override fun events(sessionId: String): SharedFlow<PiEvent>? = if (sessionId == SESSION) bus else null

    override suspend fun send(sessionId: String, command: RpcCommand, id: String?): String {
        val requestId = id ?: "auto-${sent.size + 1}"
        sent.add(Sent(sessionId, command, requestId))
        return requestId
    }

    override fun sendUiResponse(sessionId: String, response: ExtensionUiResponse) {
        uiResponses.add(response)
    }

    override fun stderrTail(sessionId: String): String? =
        if (sessionId == SESSION) stderr else null

    var stderr: String? = null

    companion object {
        const val SESSION: String = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    }
}

/** [RuntimeInstall] 假体：脚本化进度流 + 可翻转的成功/失败结果。 */
class ScriptedRuntimeInstall(
    var outcome: Outcome = Outcome.Success,
) : RuntimeInstall {

    enum class Outcome { Success, Failure }

    private val _progress = MutableStateFlow<InstallProgress>(InstallProgress.Idle)
    override val progress: StateFlow<InstallProgress> = _progress.asStateFlow()

    val calls = AtomicInteger(0)
    var failureMessage: String = "sha256 mismatch on bin/node"

    fun emit(step: InstallProgress) {
        _progress.value = step
    }

    override suspend fun ensureInstalled(): Boolean {
        calls.incrementAndGet()
        _progress.value = InstallProgress.Extracting(1, 2)
        _progress.value = InstallProgress.Verifying(2, 2)
        _progress.value = InstallProgress.Finalizing
        return when (outcome) {
            Outcome.Success -> {
                _progress.value = InstallProgress.Done
                true
            }

            Outcome.Failure -> {
                _progress.value = InstallProgress.Failed(failureMessage)
                false
            }
        }
    }
}
