package works.earendil.pihost.feature.chat

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
import works.earendil.pihost.core.engine.InstallProgress
import works.earendil.pihost.core.engine.RuntimeInstall
import works.earendil.pihost.core.engine.SessionState
import works.earendil.pihost.core.rpc.ExtensionUiResponse
import works.earendil.pihost.core.rpc.PiEvent
import works.earendil.pihost.core.rpc.RpcCommand

/**
 * 脚本化事件回放的引擎假体（规格书 §7 交付物 D）。
 *
 * 三条设计约束：
 * 1. **事件只走 [emit]**：VM 拿到的是 [SharedFlow]（无 replay，冻结区纪律），
 *    所以「先订阅再发事件」由测试侧保证——VM 进 Ready 时收集协程已经注册（Unconfined 起协程），
 *    测试紧接着 emit 即可，不需要 buffer 重放。`extraBufferCapacity` 给 512 只为
 *    让 `tryEmit` 永不挂起（无订阅者时按丢弃处理，不阻塞测试线程）。
 * 2. **捕获而非断言中间态**：出站命令进 [sent]、权限应答进 [uiResponses]，
 *    用例直接对这两张表下断言（比"轮询状态"稳）。
 * 3. **失败可脚本**：[startError]/[sendError]/[startState] 让「引擎起不来」「没有活动进程」
 *    这两条真实故障路径在不碰进程的前提下可复现。
 */
class FakeChatEngine(val sessionId: String = SESSION) : ChatEngine {

    /** 一条出站的 `send`（VM 用它发 prompt/abort/get_state）。 */
    data class Sent(val sessionId: String, val command: RpcCommand, val id: String?)

    private val _states = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    override val sessionStates: StateFlow<Map<String, SessionState>> = _states.asStateFlow()

    private val bus = MutableSharedFlow<PiEvent>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val startCalls = AtomicInteger(0)
    val shutdownCalls = AtomicInteger(0)
    val sent = CopyOnWriteArrayList<Sent>()
    val uiResponses = CopyOnWriteArrayList<ExtensionUiResponse>()
    val stoppedIds = CopyOnWriteArrayList<String>()

    /** 置非 null → [start] 抛它（验 ChatUiState.Failed 分支）。 */
    var startError: Throwable? = null

    /** [start] 之后会话落到的状态：默认 Ready，可脚本成 Failed 验「起不来」。 */
    var startState: SessionState = SessionState.Ready

    /** 置非 null → [send] 抛它（验「没有活动 pi 进程」的本地失败通道）。 */
    var sendError: Throwable? = null

    /** [stderrTail] 的返回值（VM 拼进 Failed.cause）。 */
    var stderr: String? = null

    private val replySeq = AtomicInteger(0)

    /** 事件注入：`tryEmit` 的返回值一并断言，别让「没订阅者」变成静默丢事件。 */
    fun emit(event: PiEvent) {
        check(bus.tryEmit(event)) { "事件未能进入总线：$event" }
    }

    /** 直接改会话表（验顶栏短码、Restarting→Ready 的运行期变化）。 */
    fun setState(state: SessionState) {
        setStateFor(sessionId, state)
    }

    /**
     * 给**任意** id 改会话表（§4.3 的 maxParallelSessions 门要演「别的会话已经在跑」：
     * 门读的是 `sessionStates` 整张表，不是本会话那一条）。
     */
    fun setStateFor(id: String, state: SessionState) {
        _states.update { it + (id to state) }
    }

    override suspend fun start(): String {
        startCalls.incrementAndGet()
        startError?.let { throw it }
        setState(startState)
        return sessionId
    }

    override suspend fun stop(sessionId: String) {
        stoppedIds.add(sessionId)
        _states.update { it - sessionId }
    }

    override suspend fun shutdownAll() {
        shutdownCalls.incrementAndGet()
        stoppedIds.addAll(_states.value.keys)
        _states.value = emptyMap()
    }

    override fun events(sessionId: String): SharedFlow<PiEvent>? =
        if (sessionId == this.sessionId) bus else null

    override suspend fun send(sessionId: String, command: RpcCommand, id: String?): String {
        val requestId = id ?: "fake-${replySeq.incrementAndGet()}"
        sendError?.let { throw it }
        sent.add(Sent(sessionId, command, requestId))
        return requestId
    }

    override fun sendUiResponse(sessionId: String, response: ExtensionUiResponse) {
        uiResponses.add(response)
    }

    override fun stderrTail(sessionId: String): String? =
        if (sessionId == this.sessionId) stderr else null

    companion object {
        /** 固定 UUID：argv/断言都好看，且与 P2C 的 session-id 形状一致。 */
        const val SESSION: String = "11111111-2222-4444-8888-555555555555"
    }
}

/**
 * 安装面假体（§7 交付物 D）：脚本化 progress + 结果。
 *
 * [ensureInstalled] **按脚本逐步改写 progress**，这正是 ChatViewModel 起镜像收集器
 * 要捕捉的东西——一次性跳到 Done 就验不出「Installing(step) 透传」这条语义了。
 */
class FakeRuntimeInstall(
    var steps: List<InstallProgress> = DEFAULT_STEPS,
    var outcome: Outcome = Outcome.Success,
    var failureMessage: String = "sha256 mismatch on bin/node",
    var boom: Throwable? = null,
    /**
     * 步进之间的挂起时长（虚拟时钟）。默认 0 = 一路狂写；
     * 需要「每一档都被观察者看见」的用例把它设成正数——StateFlow 只保最新值，
     * 不挂起就会把中间档 conflate 掉（实测就是这么挂的，见 ChatViewModelTest case01）。
     */
    var stepPauseMs: Long = 0L,
) : RuntimeInstall {

    enum class Outcome { Success, Failure }

    private val _progress = MutableStateFlow<InstallProgress>(InstallProgress.Idle)
    override val progress: StateFlow<InstallProgress> = _progress.asStateFlow()

    val calls = AtomicInteger(0)

    fun emit(step: InstallProgress) {
        _progress.value = step
    }

    override suspend fun ensureInstalled(): Boolean {
        calls.incrementAndGet()
        boom?.let { throw it }
        steps.forEachIndexed { index, step ->
            if (index > 0 && stepPauseMs > 0L) delay(stepPauseMs)
            _progress.value = step
        }
        val ok = when (outcome) {
            Outcome.Success -> true
            Outcome.Failure -> {
                _progress.value = InstallProgress.Failed(failureMessage)
                false
            }
        }
        // 收尾再挂一拍：调用方（ChatViewModel）在 ensureInstalled 返回后**立刻** cancel 镜像收集器，
        // 最后一档（Done/Failed）的观察者恢复可能还排在 trampoline 里——不给这一拍就会丢尾档。
        if (stepPauseMs > 0L) delay(stepPauseMs)
        return ok
    }

    companion object {
        /** AssetInstaller 的真实步进形状（Extracting→Verifying→Finalizing→Done）。 */
        val DEFAULT_STEPS: List<InstallProgress> = listOf(
            InstallProgress.Extracting(1, 2),
            InstallProgress.Extracting(2, 2),
            InstallProgress.Verifying(2, 2),
            InstallProgress.Finalizing,
            InstallProgress.Done,
        )
    }
}
