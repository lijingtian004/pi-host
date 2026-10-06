package works.earendil.pihost

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
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
import works.earendil.pihost.core.engine.di.EngineModule
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

    fun reset() {
        engine = RecordingChatEngine()
        installer = ScriptedRuntimeInstall()
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
