package works.earendil.pihost.feature.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.noties.markwon.Markwon
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import works.earendil.pihost.core.engine.ChatEngine
import works.earendil.pihost.core.engine.InstallProgress
import works.earendil.pihost.core.engine.InstalledRuntime
import works.earendil.pihost.core.engine.RuntimeInstall
import works.earendil.pihost.core.engine.SessionState
import works.earendil.pihost.core.engine.engineStartIntent
import works.earendil.pihost.core.engine.audit.AuditEntry
import works.earendil.pihost.core.engine.audit.AuditKind
import works.earendil.pihost.core.engine.audit.AuditSink
import works.earendil.pihost.core.engine.audit.FileAuditSink
import works.earendil.pihost.core.engine.audit.NoopAuditSink
import works.earendil.pihost.core.engine.di.EngineModule
import works.earendil.pihost.core.engine.settings.SettingsRepository
import works.earendil.pihost.core.environment.PathLayout
import works.earendil.pihost.core.rpc.AssistantMessageEvent
import works.earendil.pihost.core.rpc.ExtensionUiResponse
import works.earendil.pihost.core.rpc.PiEvent
import works.earendil.pihost.core.rpc.PiMessage
import works.earendil.pihost.core.rpc.RpcCommand

/**
 * 聊天核心链唯一状态源（规格书 §5）。
 *
 * 三条纪律来自冻结区的契约，不是本地发明：
 * 1. **先订阅再发命令**：[ChatEngine.events] 无 replay（`PiSession.events` KDoc 同一条），
 *    所以收集协程一律用 `Dispatchers.Unconfined` 起——`launch(Unconfined)` 在**调用点就地**
 *    开始执行到第一个挂起点，`SharedFlow.collect` 的注册发生在挂起之前，
 *    于是「命令发出时订阅者已就位」是机制保证，而不是时序巧合。
 *    （`SessionSupervisor.eventSubscriptions` 能直接给这个证据，但它**不在** §4.4 的
 *    ChatEngine 接口里，故 VM 侧靠 Unconfined 纪律自证；见 RECON-P2D §5 末。）
 * 2. **transcript 只换快照**：流式 delta 只替换末条 [TranscriptItem.AssistantItem]
 *    （§5.1 末条），Compose 侧才能收敛；
 * 3. **权限应答无回执**（protocol.md §3.7）：`sendUiResponse` 是非 suspend 的投递，
 *    UI 不等它，超时/被 TurnEnd 抢跑时由 [cancelPermissionOnce] 补发**恰好一次** cancelled。
 *
 * 状态机：`Idle → Streaming`（发 prompt / TurnStart）`→ Idle`（TurnEnd/AgentEnd/AgentSettled）；
 * `Streaming → PermissionPending`（extension_ui_request）`→ Streaming`（三选一或超时）。
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    app: Application,
    private val engine: ChatEngine,
    private val installer: RuntimeInstall,
    /** 应用设置快照源（§13 偏差 6：maxParallelSessions 门在 VM 层，读的是内存快照）。 */
    private val settings: SettingsRepository,
    /**
     * @Singleton Markwon（§9.5），由 `di/MarkwonModule` 提供。
     *
     * `internal` 而非 public：markwon 是 chat 的 `implementation` 依赖（不进消费者编译类路径），
     * 公开签名一旦暴露它的类型就是在给 `:app` 挖「unresolved reference」坑；
     * 同模块的 UI 与单测都能取到，够用。
     */
    private val markwonInstance: Markwon,
    /**
     * 审计出口（P3A §3.2）。**默认值 = [NoopAuditSink]**，且必须放在参数表**末尾**：
     * `ChatViewModelTest` 与 `:app` 的构造点此前都是五个实参，加默认值的尾位让既有调用一字不改就能编译
     * （规格书 §3.2 原文「保持既有测试编译兼容」）。
     *
     * Hilt 侧仍然要求一条 `AuditSink` binding——**Kotlin 默认值对 Dagger 不可见**，
     * 它照样按签名要绑定，所以 `EngineModule.auditSink`（生产）与
     * `AppTestEngineModule`（测试假体）两边都得有（§3.6 收尾）。
     */
    private val auditSink: AuditSink = NoopAuditSink,
) : AndroidViewModel(app) {

    /**
     * VM **只转交、不使用**：唯一的消费者是
     * [works.earendil.pihost.feature.chat.ui.TranscriptList]（经 ChatScreen 传入），
     * 渲染发生在 `AndroidView` 的 factory/update（主线程）。
     * 挂在 VM 上是为了让 UI 与单测拿到**同一个**实例，而不是在组合里自建第二份语法表。
     */
    internal val markwon: Markwon get() = markwonInstance

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.NotInstalled)

    /** 首启/运行期总状态。 */
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _turnState = MutableStateFlow(TurnState.Idle)
    val turnState: StateFlow<TurnState> = _turnState.asStateFlow()

    private val _transcript = MutableStateFlow<List<TranscriptItem>>(emptyList())
    val transcript: StateFlow<List<TranscriptItem>> = _transcript.asStateFlow()

    private val _queueState = MutableStateFlow(QueueState.EMPTY)

    /**
     * 两列队列快照（§3.2）。取代 P2C 起的 `queueDepth: Int`：
     * 一个数字说不出「哪条插到当前轮、哪条要等整轮结束」，而 InputBar 在流式期间
     * 必须让用户看清自己排了什么才敢按「清空队列」。深度现在是它的派生值
     * （[QueueState.depth]），顶栏与列表页因此不可能显示两个不同的数。
     */
    val queueState: StateFlow<QueueState> = _queueState.asStateFlow()

    private val _permissionRequest = MutableStateFlow<PendingPermission?>(null)
    val permissionRequest: StateFlow<PendingPermission?> = _permissionRequest.asStateFlow()

    private val _engineStatus = MutableStateFlow<SessionState?>(null)

    /** 顶栏短码数据源：本会话在 [ChatEngine.sessionStates] 里的取值（Ready/Restarting/Failed…）。 */
    val engineStatus: StateFlow<SessionState?> = _engineStatus.asStateFlow()

    private val _commandResult = MutableStateFlow<PiEvent.Response?>(null)

    /** VM 自己发的命令的应答（探针/错误显示用，**不混入 transcript**，§5.1）。 */
    val commandResult: StateFlow<PiEvent.Response?> = _commandResult.asStateFlow()

    private val _retryNote = MutableStateFlow<String?>(null)

    /** auto_retry_* 的一句话提示（不新开转录条目）。 */
    val retryNote: StateFlow<String?> = _retryNote.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)

    /**
     * 一次性提示（§4.3：并行上限拦截 = snackbar 文案）。
     *
     * 与 [retryNote] 的分工：retryNote 是**引擎侧持续状态**（auto_retry 期间一直挂着），
     * notice 是**本地动作的即时反馈**（发完即走，UI 消费后 [consumeNotice] 清空）。
     */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var sessionId: String? = null
    private var collectorJob: Job? = null
    private var statusJob: Job? = null
    private var permissionTicker: Job? = null

    /** 正文 delta 的待提交草稿 + 它的 debounce 协程（§3.3：80ms 窗口）。 */
    private val draftText = StringBuilder()
    private var draftFlushJob: Job? = null

    /** 「加载中」占位条的自动撤除协程（§4.3）。 */
    private var noteExpiryJob: Job? = null

    /** 幂等闩：init 起一次，失败才放开（重试按钮就靠它）。 */
    private var starting = false

    /** 切换/新建会话的进行闩（回执未定之前不接受第二次滚，见 [rollSession]）。 */
    private var switching = false

    /**
     * 在途回填的 requestId（P3B §3.1）。
     *
     * 只留**最新那一次**：回填是「清场之后补历史」，一旦又滚了一次会话，
     * 上一条 `get_messages` 的回执就属于旧会话了——它落进 [onCommandResponse] 时
     * 认不出这个 id，于是只进命令通道、绝不把旧历史 append 到新转录上。
     */
    private var backfillRequestId: String? = null

    private var itemSeq = 0
    private var commandSeq = 0
    private val pendingCommandIds = mutableSetOf<String>()

    /** 「本会话」权限记忆（§13 偏差 3：key=method，随 VM 销毁即失效）。 */
    private val rememberedMethods = mutableSetOf<String>()

    /** 已结算的权限请求 id：保证 confirmed/cancelled 每个 id 只回写一次。 */
    private val settledPermissions = mutableSetOf<String>()

    val remembered: Set<String> get() = rememberedMethods.toSet()

    init {
        // §5.1：没装运行时 → NotInstalled（安装门）；装了 → 直接走启动流程
        if (InstalledRuntime.version(getApplication()) == null) {
            _uiState.value = ChatUiState.NotInstalled
        } else {
            installAndStart()
        }
    }

    // ── 安装 + 起引擎（§5.1 installAndStart）────────────────────────────────────

    /**
     * 订阅安装进度 → [ChatUiState.Installing] → 装完发 FGS 启动意图 → `engine.start()`
     * → 订阅事件 → [ChatUiState.Ready]；任一环节失败 → [ChatUiState.Failed] 且放开重试。
     *
     * FGS 意图在 `engine.start()` **之前**发（§13 偏差 6：通知先于 spawn，
     * 才符合 targetSdk 35 的「后台起 FGS 需要豁免」约束路径）。
     */
    fun installAndStart() {
        if (starting) return
        starting = true
        // 每次重新发起都先擦掉上一条提示：上一次的「并行上限已满」不能糊在下一次的结果上
        _notice.value = null
        viewModelScope.launch {
            _uiState.value = ChatUiState.Installing(installer.progress.value)
            val mirror = launch(Dispatchers.Unconfined) {
                installer.progress.collect { step ->
                    val current = _uiState.value
                    if (current is ChatUiState.NotInstalled || current is ChatUiState.Installing) {
                        _uiState.value = ChatUiState.Installing(step)
                    }
                }
            }
            val outcome = runCatching { installer.ensureInstalled() }
            mirror.cancel()

            val exception = outcome.exceptionOrNull()
            val failedStep = installer.progress.value as? InstallProgress.Failed
            if (exception != null || outcome.getOrDefault(false) != true) {
                // P2F §4.2(c)/§1.1：过一层 ErrnoHint，并**撤掉 .truncate()**——
                // 240 字符的上限会把「；stderr: 」尾巴整段吃掉，而 Failed 屏与复制键要的正是全文。
                _uiState.value = ChatUiState.Failed(
                    cause = ErrnoHint.humanize(
                        exception?.message ?: failedStep?.message ?: "运行时安装失败",
                    ),
                )
                starting = false
                return@launch
            }

            startEngine()
        }
    }

    /**
     * 起引擎（§5.1）+ **并行上限门**（§4.3 / §13 偏差 6：门在 VM 层，SessionSupervisor 不加新语义）。
     *
     * 判据是「占用配额的会话」= 状态为 Spawning/Ready/Restarting 的条目：
     * 冻结区的 `sessionStates` 在会话进终态后**仍保留**那条记录（Stopped 是「用户主动停」、
     * Failed 是「退避额度耗尽」，两者都不再持有 pi 进程），把它们算进占用会让上限
     * 变成「历史会话总数」，与 §3.4 的「并行」语义不符。
     *
     * 拦截后**连 FGS 意图都不发**（通知先于 spawn 是 P2D 偏差 6 的次序，反过来做就是
     * 「前台服务起来了却不干活」），放开 [starting] 闩让用户改完设置再试。
     */
    private suspend fun startEngine() {
        val cap = settings.maxParallelSessions
        val occupied = engine.sessionStates.value.count { (_, state) ->
            state is SessionState.Spawning || state is SessionState.Ready || state is SessionState.Restarting
        }
        if (occupied >= cap) {
            val cause = "已达并行上限 $cap（当前 $occupied 条会话在跑），先在设置页调低再新建"
            _notice.value = cause
            // §5.1 的 Failed 定义就是「引擎起不来 + 可读原因」，这里正是它，不新增第五态。
            // P2F §4.2(c)：三处 Failed 统一过 ErrnoHint；本条是中文文案、不含 `error=N`，humanize 直通。
            _uiState.value = ChatUiState.Failed(cause = ErrnoHint.humanize(cause))
            starting = false
            return
        }

        val ctx = getApplication<Application>()
        ctx.startForegroundService(engineStartIntent(ctx))

        val started = runCatching { engine.start() }
        val id = started.getOrNull()
        val state = id?.let { engine.sessionStates.value[it] }
        if (started.isFailure || state !is SessionState.Ready) {
            val reason = started.exceptionOrNull()?.message
                ?: (state as? SessionState.Failed)?.cause
                ?: "引擎未就绪：$state"
            // P2F §4.2(c)：spawn 失败在这里第一次变成「人话」——errno 提示追加、全文不截断，
            // Failed 屏（§4.2(a)(b)）与「复制详情」拿到的就是 libcore 原始消息 + stderr 尾巴。
            _uiState.value = ChatUiState.Failed(
                cause = ErrnoHint.humanize("$reason；stderr: ${id?.let { engine.stderrTail(it) }.orEmpty()}"),
            )
            starting = false
            return
        }

        subscribe(id!!)
        _uiState.value = ChatUiState.Ready(id)
    }

    /** 订阅先于任何 send（纪律 1）：两条收集协程都用 Unconfined 就地注册。 */
    private fun subscribe(id: String) {
        sessionId = id
        val bus = engine.events(id)
        collectorJob = viewModelScope.launch(Dispatchers.Unconfined) {
            bus?.collect { onEvent(it) }
        }
        statusJob = viewModelScope.launch(Dispatchers.Unconfined) {
            engine.sessionStates.map { states -> states[id] }.collect { _engineStatus.value = it }
        }
    }

    /** 收尾：取消事件收集，别让 ViewModel 泄漏在总线上。 */
    override fun onCleared() {
        collectorJob?.cancel()
        statusJob?.cancel()
        permissionTicker?.cancel()
        // 草稿与占位行的协程都属于「本页已经不在了」的范畴：丢弃而不是补写，
        // 否则 viewModelScope 会在销毁后被一次迟到的 flush 唤起一次无意义的状态提交。
        draftFlushJob?.cancel()
        noteExpiryJob?.cancel()
        super.onCleared()
    }

    // ── 出站（§5.1 sendPrompt / abort，§5.2 answerPermission）───────────────────

    /** Idle 且已 Ready 才发；用户气泡即时入列（不等 pi 的 message_start 回声）。 */
    fun sendPrompt(text: String) {
        val id = sessionId ?: return
        if (text.isBlank() || _turnState.value != TurnState.Idle) return
        addUserItem(text)
        viewModelScope.launch(Dispatchers.Unconfined) {
            val requestId = nextCommandId()
            pendingCommandIds.add(requestId)
            val sent = runCatching { engine.send(id, RpcCommand.Prompt(message = text), requestId) }
            if (sent.isSuccess) _turnState.value = TurnState.Streaming
            else _commandResult.value = failureAsResponse("prompt", sent.exceptionOrNull()?.message)
        }
    }

    // ── 流式插话（P3B §3.2）───────────────────────────────────────────────────────

    /**
     * 插话：把用户**原话**塞进 steering 队列（在当前这一轮的下一个工具边界生效）。
     *
     * 与 [sendPrompt] 的三点差别：
     * 1. **不改 turnState、不挂用户气泡**——这一轮还在流式，插话不是新问题；
     *    它的内容由 pi 在紧随其后的 `queue_update` 里回显，那时才上徽标（§3.2 的「不做乐观更新」）；
     * 2. **不占 [switching] 闩、也不受它限制**：插话是「这一轮内部」的动作，会话没滚；
     * 3. 空文本不吃（与 sendPrompt 同一条 `isBlank` 判据），否则队列里多一条用户看不见的空指令。
     *
     * **§2 红线 8**：本函数只做「把用户打好的字发出去」这一件事。全类没有任何
     * 「看到某事件就自动 steer」的路径——自动插话等于 App 替用户决定说什么。
     */
    fun steer(text: String) = enqueue(RpcCommand.Steer(text), text, "插话", "steer")

    /** 追问：把用户原话塞进 followUp 队列（整轮结束之后才消费）。其余语义同 [steer]。 */
    fun followUp(text: String) = enqueue(RpcCommand.FollowUp(text), text, "追问", "follow_up")

    /**
     * 清空队列（`clear_queue`）。回执 `{steering:[],followUp:[]}` 是**清空后**的剩余
     * （protocol.md §3.2：可能已被消费），所以这里不拿回执做任何「撤销/退回」——
     * 队列的最终面貌只由 [PiEvent.QueueUpdate] 决定，与 [steer] 同一条判据。
     */
    fun clearQueue() {
        val id = sessionId ?: return
        viewModelScope.launch(Dispatchers.Unconfined) {
            val requestId = nextCommandId()
            pendingCommandIds.add(requestId)
            val sent = runCatching { engine.send(id, RpcCommand.ClearQueue, requestId) }
            if (sent.isFailure) {
                pendingCommandIds -= requestId
                _commandResult.value = failureAsResponse("clear_queue", sent.exceptionOrNull()?.message)
            }
        }
    }

    /**
     * steering 队列的消费模式（`set_steering_mode`）。取值只露 [MODE_ALL] / [MODE_ONE_AT_A_TIME]
     * 两个常量给 UI；传别的不在这里拦（pi 会回 success=false，交给 commandResult 显示）。
     */
    fun setSteeringMode(mode: String) = sendMode(RpcCommand.SetSteeringMode(mode), "steering 模式", "set_steering_mode")

    /** 见 [setSteeringMode]，作用于 follow-up 队列（`set_follow_up_mode`）。 */
    fun setFollowUpMode(mode: String) = sendMode(RpcCommand.SetFollowUpMode(mode), "follow-up 模式", "set_follow_up_mode")

    /** steer / follow_up 的共用出站：不碰 turnState、不碰 transcript，只发一条命令。 */
    private fun enqueue(command: RpcCommand, text: String, label: String, wireName: String) {
        val id = sessionId
        if (id == null) {
            _notice.value = "${label}失败：引擎尚未就绪"
            return
        }
        if (text.isBlank()) return
        viewModelScope.launch(Dispatchers.Unconfined) {
            val requestId = nextCommandId()
            pendingCommandIds.add(requestId)
            val sent = runCatching { engine.send(id, command, requestId) }
            if (sent.isFailure) {
                pendingCommandIds -= requestId
                _commandResult.value = failureAsResponse(wireName, sent.exceptionOrNull()?.message)
            }
        }
    }

    private fun sendMode(command: RpcCommand, label: String, wireName: String) {
        val id = sessionId ?: return
        viewModelScope.launch(Dispatchers.Unconfined) {
            val requestId = nextCommandId()
            pendingCommandIds.add(requestId)
            val sent = runCatching { engine.send(id, command, requestId) }
            if (sent.isFailure) {
                pendingCommandIds -= requestId
                _commandResult.value = failureAsResponse(wireName, sent.exceptionOrNull()?.message)
            }
        }
    }

    /** 中止当前轮（Idle 时按钮本就禁用，这里再兜一层）。 */
    fun abort() {
        val id = sessionId ?: return
        if (_turnState.value == TurnState.Idle) return
        viewModelScope.launch(Dispatchers.Unconfined) {
            val requestId = nextCommandId()
            pendingCommandIds.add(requestId)
            val sent = runCatching { engine.send(id, RpcCommand.Abort, requestId) }
            if (!sent.isSuccess) _commandResult.value = failureAsResponse("abort", sent.exceptionOrNull()?.message)
        }
    }

    /** P2C「测试 RPC」按钮的等价面：发一条 `get_state`，应答进 [commandResult]。 */
    fun probeState() {
        val id = sessionId ?: return
        viewModelScope.launch(Dispatchers.Unconfined) {
            val requestId = nextCommandId()
            pendingCommandIds.add(requestId)
            val sent = runCatching { engine.send(id, RpcCommand.GetState, requestId) }
            if (!sent.isSuccess) _commandResult.value = failureAsResponse("get_state", sent.exceptionOrNull()?.message)
        }
    }

    // ── 多会话（§4.3：切换 / 新建 = 命令 + 等回执 + 清场）─────────────────────────

    /**
     * 切到磁盘上的既有会话（`switch_session`，protocol.md §3.4 的 `switchSession`）。
     *
     * 与 `:feature:sessions` 的 `openSession` 是**同一个命令的两个消费者**，但职责不同：
     * 那边只管「发命令 + 导航」（列表页不持有转录），这边必须等成功回执才敢清场——
     * transcript 是本页的真相源，切失败却把内容清了就是丢数据。
     */
    fun switchSession(sessionPath: String) = rollSession(RpcCommand.SwitchSession(sessionPath), "切换会话")

    /**
     * 新开一个会话（`new_session`）。pi 侧在**同一进程**里换 session 文件，
     * 因此不涉及 spawn、也不受 maxParallelSessions 门约束（那条门只管 [ChatEngine.start]）。
     */
    fun newSession() = rollSession(RpcCommand.NewSession(), "新建会话")

    /**
     * 「别的页已经替我切过了」的落地钩子（§4.4：`:feature:sessions` 点行 → 发 `switch_session`
     * → 导航到 `chat/{sessionId}`）。
     *
     * 只做 [onSessionRolled] 的清场，**绝不发命令**：命令是 sessions 屏发的，这里再发一次
     * 就是同一目标切两遍；而 `[rollSession]` 的「等成功回执才清场」在这里也用不上——
     * 本页没有那条 requestId，等下去只会超时并糊一条假提示。
     *
     * 为什么必须有这个方法：`ChatViewModel` 在 app 侧是 **Activity 作用域**（见 `AppNavHost` 的
     * `ChatScreenRoute`），从 sessions 回进聊天页**不会重建 VM**——不重建就没有 `init`，
     * 也就没人把上一条会话的转录换成新会话。清场只能显式调用。
     *
     * 未 Ready（安装门/失败态）时直接返回：那时根本没有会话可清，
     * 硬清会把「运行时未安装」的界面洗成一条占位行。
     */
    fun onExternalSessionRoll() {
        if (_uiState.value !is ChatUiState.Ready) return
        onSessionRolled()
    }

    /** 消费一次性提示（snackbar/banner 自动收起时也调它，与 `:feature:settings` 同形）。 */
    fun consumeNotice() {
        _notice.value = null
    }

    /**
     * 「发命令 → 等这条命令自己的 Response → 成功才清场」的公共流程（§4.3）。
     *
     * 三条实现依据：
     * 1. **先订阅再发命令**（纪律 1）：collector 起在 `Dispatchers.Unconfined`，
     *    `SharedFlow.collect` 的注册发生在挂起之前，于是 send 时订阅者已就位；
     * 2. **超时跑在 viewModelScope 上**（RECON-P2E §5 的教训，与 SettingsViewModel 同源）：
     *    `Dispatchers.Unconfined` 不实现 `Delay`，`withTimeoutOrNull` 在那里会落到**真实时钟**上，
     *    虚拟时间测试就永远推不到超时；所以外层协程用 viewModelScope（测试 = UnconfinedTestDispatcher
     *    的虚拟时钟），只有 collector 那一层用 Unconfined。
     * 3. **失败不动转录**：notice 报原因，原会话内容一条不少——这是与「成功才清场」一体的设计，
     *    不是补救。
     */
    private fun rollSession(command: RpcCommand, label: String) {
        val id = sessionId
        if (id == null) {
            _notice.value = "${label}失败：引擎尚未就绪"
            return
        }
        if (switching) return // 一次只滚一条：回执未定之前不接受第二次切换
        switching = true
        flushDraft() // 草稿属于上一条会话，先落到上一条的转录里
        viewModelScope.launch {
            val requestId = nextCommandId()
            pendingCommandIds.add(requestId)
            val reply = CompletableDeferred<PiEvent.Response>()
            val collector = launch(Dispatchers.Unconfined) {
                engine.events(id)?.collect { event ->
                    if (event is PiEvent.Response && event.id == requestId) reply.complete(event)
                }
            }
            val sent = runCatching { engine.send(id, command, requestId) }
            if (sent.isFailure) {
                collector.cancel()
                pendingCommandIds -= requestId
                switching = false
                _notice.value = "${label}失败：${sent.exceptionOrNull()?.message ?: "未知原因"}"
                return@launch
            }
            val event = withTimeoutOrNull(SESSION_ROLL_TIMEOUT_MS) { reply.await() }
            collector.cancel()
            switching = false
            if (event == null) {
                pendingCommandIds -= requestId
                _notice.value = "${label}超时（${SESSION_ROLL_TIMEOUT_MS}ms），已保留原会话"
                return@launch
            }
            pendingCommandIds -= requestId
            if (!event.success) {
                _notice.value = "${label}失败：${event.error ?: "pi 侧回了 success=false"}"
                return@launch
            }
            _notice.value = null
            onSessionRolled()
        }
    }

    /**
     * 切换成功的清场（§4.3 原文：turnState=Idle + transcript 清空 + 「加载中」条目）。
     *
     * **P3B §3.1 补的最后一步**：清完场必须有人把历史补回来。
     * pi 的 `switchSession` 只换 SessionManager 并重发 `session_start{reason:"resume"}`，
     * **不重放任何历史事件**（`agent-session-runtime.ts:196`），所以「清场」与「有内容」
     * 之间隔着一条必须由宿主填上的缝——这就是那条 P1 的全部成因。
     * 挂在这里而不是只挂 [rollSession]：从 sessions 屏点行进来走的是 [onExternalSessionRoll]，
     * 命令由列表页发、本页只清场，两条路最终都汇到这一个清场点，回填自然也只有一处。
     */
    private fun onSessionRolled() {
        cancelPermissionOnce() // 挂着的权限弹框随旧会话一起作废（补 cancelled 恰好一次）
        flushDraft()
        _turnState.value = TurnState.Idle
        _queueState.value = QueueState.EMPTY
        _commandResult.value = null
        _retryNote.value = null
        showLoadingNote()
        backfillTranscript()
    }

    /** 挂「加载中」占位，并给它一个自动撤身时限（§4.3）。 */
    private fun showLoadingNote() {
        noteExpiryJob?.cancel()
        _transcript.value = listOf(TranscriptItem.NoteItem(nextItemId(), SWITCHING_NOTE_TEXT))
        noteExpiryJob = viewModelScope.launch {
            delay(SWITCH_NOTE_TIMEOUT_MS)
            hideLoadingNote()
        }
    }

    /** 撤掉占位行（真内容已经到了就是空操作）。 */
    private fun hideLoadingNote() {
        noteExpiryJob?.cancel()
        noteExpiryJob = null
        if (_transcript.value.none { it is TranscriptItem.NoteItem }) return
        _transcript.update { list -> list.filterNot { it is TranscriptItem.NoteItem } }
    }

    // ── 转录回填（P3B §3.1）──────────────────────────────────────────────────────

    /**
     * 拉当前会话的历史并补进转录（`get_messages`，protocol.md §3.6 → `{messages: AgentMessage[]}`）。
     *
     * 三条取舍：
     * 1. **纯读**（§2 红线 6）：这条路径只有 `get_messages` 一问一答，
     *    不发 `switch_session`/`new_session`、不写任何 session 文件——回填失败也只是「看不到」，
     *    绝不会让用户的历史在磁盘上少一行；
     * 2. **不等超时**：与 [rollSession] 不同，这里不 `withTimeoutOrNull`。
     *    rollSession 等的是「能不能清场」，超时不清场是对的；回填等的是「补不补得上」，
     *    真机上的 pi 无响应最终会由 `get_messages` 自己的回执或会话失败面收场，
     *    多挂一个计时器只会让「清完场到回执之间」那段本来就在滚的占位行提前变成假故障。
     *    回执永不到 = 占位行按 [SWITCH_NOTE_TIMEOUT_MS] 自己退场，界面留空但不撒谎；
     * 3. **append 不清场**（§3.1 的竞态约束）：结果用 `list + items` 追加，
     *    期间用户已经发出去的 prompt、正在流式的 assistant 一条都不动。
     *    代价是竞态时历史会落在新内容**下面**（顺序不如真机 pi 的时序），
     *    这比「把用户刚打的字抹掉」好得多，规格书点名的就是这个取舍。
     */
    fun backfillTranscript() {
        val id = sessionId ?: return
        viewModelScope.launch(Dispatchers.Unconfined) {
            val requestId = nextCommandId()
            pendingCommandIds.add(requestId)
            backfillRequestId = requestId
            val sent = runCatching { engine.send(id, RpcCommand.GetMessages, requestId) }
            if (sent.isFailure) {
                pendingCommandIds -= requestId
                backfillRequestId = null
                onBackfillFailure(sent.exceptionOrNull()?.message)
            }
        }
    }

    /**
     * 回填回执的唯一落地口（由 [onCommandResponse] 认出 [backfillRequestId] 后调用）。
     *
     * 三种结局：成功且有条目 → 追加；成功但空会话 → 只撤占位行；失败/形状不对 → 占位行换成
     * 「历史加载失败」。三条都先停掉 [noteExpiryJob]：回填一结束，那条「会话切换中…」
     * 的使命就到头了，再让计时器迟到一次去 `filterNot { NoteItem }` 会把刚挂上的故障提示也抹掉。
     */
    private fun applyBackfill(event: PiEvent.Response) {
        noteExpiryJob?.cancel()
        noteExpiryJob = null
        val messages = if (event.success) ChatHistory.parseMessages(event.data) else null
        if (messages == null) {
            onBackfillFailure(event.error ?: "get_messages 回执形状不对")
            return
        }
        val items = ChatHistory.toTranscriptItems(messages) { nextItemId() }
        mutateTranscript { list -> list + items }
    }

    /** 回填失败的可见化：一行说明 + 一条 logcat（红线 6 要求「保留空 transcript + 提示」）。 */
    private fun onBackfillFailure(cause: String?) {
        Log.w(BACKFILL_LOG_TAG, "get_messages 回填失败：${cause ?: "无原因"}")
        mutateTranscript { list -> list + TranscriptItem.NoteItem(nextItemId(), BACKFILL_FAILED_TEXT) }
    }

    /**
     * 权限三选一（§5.2）。全 false = 非法调用，直接忽略。
     *
     * 「本会话」只往 [rememberedMethods] 里加 method，线协议仍只有 confirmed
     * （§13 偏差 3——线协议不承载记忆语义）。
     */
    fun answerPermission(allowOnce: Boolean = false, allowSession: Boolean = false, deny: Boolean = false) {
        val pending = _permissionRequest.value ?: return
        if (!allowOnce && !allowSession && !deny) return
        permissionTicker?.cancel()
        permissionTicker = null
        _permissionRequest.value = null
        if (allowSession) rememberedMethods.add(pending.method)
        // §3.2 挂点三(a)：用户手上一锤子 = confirmed / cancelled
        writePermissionAnswer(
            pending.id,
            confirmed = allowOnce || allowSession,
            decision = if (allowOnce || allowSession) AUDIT_CONFIRMED else AUDIT_CANCELLED,
        )
        if (_turnState.value == TurnState.PermissionPending) _turnState.value = TurnState.Streaming
    }

    /**
     * 权限应答的唯一出口（§5.2 的「恰好一次」闩 + P3A §3.2 的权限审计挂点）。
     *
     * [decision] 由调用方给三态原文（`confirmed` / `cancelled` / `cancelled-timeout`），
     * 而不是在这里从 `confirmed` 反推——「cancelled」有两种来历（用户拒绝、TurnEnd 抢跑兜底），
     * 只有调用点知道自己属于哪一种；审计要留的正是这个区别。
     *
     * 记条目的位置在 `settledPermissions` 闩**之后**：同一条 request 被兜底路径重入时
     * 既不多发一次线协议应答，也不多写一行审计（否则「拒绝」会被记成两次）。
     */
    private fun writePermissionAnswer(requestId: String, confirmed: Boolean, decision: String) {
        val id = sessionId ?: return
        if (!settledPermissions.add(requestId)) return
        recordAudit(
            AuditKind.PERMISSION,
            toolCallId = requestId,
            toolName = AUDIT_PERMISSION_TOOL,
            argsJson = decision,
        )
        val response = if (confirmed) {
            ExtensionUiResponse(requestId, confirmed = true)
        } else {
            ExtensionUiResponse(requestId, cancelled = true)
        }
        engine.sendUiResponse(id, response)
    }

    /**
     * 超时/TurnEnd 抢跑的兜底：补 cancelled 恰好一次，再清空弹框态。
     *
     * [timedOut] 只影响审计措辞（§3.2 原文的 `"cancelled-timeout"`），线协议一字不变：
     * 倒计时到点 = true，[onSessionRolled]/[endTurn] 的抢跑兜底 = false。
     */
    private fun cancelPermissionOnce(timedOut: Boolean = false) {
        val pending = _permissionRequest.value ?: return
        permissionTicker?.cancel()
        permissionTicker = null
        _permissionRequest.value = null
        writePermissionAnswer(
            pending.id,
            confirmed = false,
            decision = if (timedOut) AUDIT_CANCELLED_TIMEOUT else AUDIT_CANCELLED,
        )
        if (_turnState.value == TurnState.PermissionPending) _turnState.value = TurnState.Streaming
    }

    // ── 审计挂点（P3A §3.2）─────────────────────────────────────────────────────

    /**
     * 审计条目的**唯一**构造口：三个挂点（工具 start / 工具 end / 权限决策）都走这里。
     *
     * `runCatching` 只在本函数出现一次，这是 §2 红线与 §3.2 测试第 ⑤ 条唯一能并存的形状：
     * - 红线说「ChatViewModel 直接调用不额外包 try」，意思是**挂点处**不各写一层防御
     *   （三个 `recordAudit(...)` 调用行确实一字没有 try）；
     * - §3.2 又要求「sink 抛 RuntimeException 时 ChatViewModel 不崩」，而 `AuditSink` 是接口——
     *   违约实现（或未来接进来的第三方 sink）抛出来会落在**事件收集协程**里
     *   （`viewModelScope` 的 `SupervisorJob` 只挡兄弟，不挡这条主收集协程自己死），
     *   后果正是红线要禁止的「审计把对话带下水」。
     * 生产实现 [FileAuditSink] 本来就全量吞异常（§3.1 用例 05/06/12 是它的证明），
     * 所以这一层在真机上是**永远不该被触发**的第二道闸；触发即说明有实现违约，直接打日志。
     */
    private fun recordAudit(
        kind: AuditKind,
        toolCallId: String,
        toolName: String,
        argsJson: String,
        isError: Boolean? = null,
        resultPreview: String? = null,
    ) {
        val entry = AuditEntry(
            ts = System.currentTimeMillis(),
            kind = kind,
            toolCallId = toolCallId,
            toolName = toolName,
            argsJson = argsJson,
            isError = isError,
            resultPreview = resultPreview,
            cwd = auditCwd(),
            sessionId = sessionId,
        )
        runCatching { auditSink.record(entry) }
            .onFailure { Log.w(AUDIT_LOG_TAG, "AuditSink 违反『必须不抛』契约：${entry.kind}", it) }
    }

    /**
     * 审计面里 `cwd` 的取值来源（§3.2 留给 REPORT §8 的那条）。
     *
     * 不是新造的跨模块查询：这两者都是**现成公开 API**，且与生产装配喂给
     * `SessionSupervisor.workingDir` 的那一条是同一个表达式（`EngineModule.kt:216`），
     * 所以审计记下的目录 = 子进程真实 chdir 的目录，不存在第二份真相。
     * `PathLayout.projectDir` 只构造路径不建目录，因此这里既不碰磁盘也不挂起。
     */
    private fun auditCwd(): String = runCatching {
        PathLayout.projectDir(getApplication(), EngineModule.DEFAULT_PROJECT).absolutePath
    }.getOrDefault("")

    // ── 入站装配（§5.1 事件映射）─────────────────────────────────────────────────

    /**
     * 入站装配（§5.1 事件映射）。
     *
     * 开头那行 `flushDraft()` 是 §3.3 debounce 的**另一半语义**：只有「正文 delta」
     * 允许延迟提交，其它任何事件先到都先把草稿落地——否则 `text_end`/`message_end`
     * 的定稿、工具卡的插入会跑到未提交的文字前面，转录顺序就错了。
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun onEvent(event: PiEvent) {
        if (!isTextDelta(event)) flushDraft()
        when (event) {
            is PiEvent.MessageStart -> onMessageStart(event.message)
            is PiEvent.MessageEnd -> onMessageEnd(event.message)
            is PiEvent.MessageUpdate -> event.assistantMessageEvent?.let { onAssistantEvent(it) }

            is PiEvent.ToolExecutionStart -> {
                // §3.2 挂点一：args **原样**入审计（不解析、不美化、不截断——§2 红线）
                recordAudit(
                    AuditKind.TOOL_START,
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                    argsJson = event.args?.toString().orEmpty(),
                )
                mutateTranscript { list ->
                    list + TranscriptItem.ToolItem(
                        id = nextItemId(),
                        callId = event.toolCallId,
                        toolName = event.toolName,
                        status = ToolStatus.Running,
                    )
                }
            }

            is PiEvent.ToolExecutionUpdate -> appendToolOutput(event.toolCallId, resultToText(event.partialResult))

            is PiEvent.BashExecutionUpdate -> appendBashOutput(event.id, event.delta)

            is PiEvent.ToolExecutionEnd -> {
                // §3.2 挂点二：结果只进 preview，截 [FileAuditSink.RESULT_PREVIEW_MAX] 字符
                recordAudit(
                    AuditKind.TOOL_END,
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                    argsJson = "",
                    isError = event.isError,
                    resultPreview = resultToText(event.result).toAuditPreview(),
                )
                mutateTranscript { list ->
                list.map { item ->
                    if (item is TranscriptItem.ToolItem && item.callId == event.toolCallId) {
                        item.copy(
                            toolName = item.toolName.ifEmpty { event.toolName },
                            status = if (event.isError) ToolStatus.Error else ToolStatus.Ok,
                            // result 是整块快照（§4.1）：非空则以它为准，空则保留已追加的流式输出
                            output = resultToText(event.result).ifEmpty { item.output },
                        )
                    } else {
                        item
                    }
                }
                }
            }

            is PiEvent.TurnStart -> _turnState.value = TurnState.Streaming

            is PiEvent.TurnEnd, is PiEvent.AgentEnd -> endTurn()

            is PiEvent.AgentSettled -> {
                settleLastAssistant()
                if (_turnState.value != TurnState.PermissionPending) _turnState.value = TurnState.Idle
            }

            is PiEvent.QueueUpdate -> {
                // §3.2：整表替换，不做增量合并——pi 说「内容=当前队列」，
                // 本地任何「+1」式的猜测都会在下一条事件上错得更难查。
                _queueState.value = QueueState.of(event)
            }

            is PiEvent.ExtensionUiRequest -> onPermissionRequest(event)

            is PiEvent.Response -> onCommandResponse(event)

            is PiEvent.AutoRetryStart ->
                _retryNote.value = "重试 ${event.attempt}/${event.maxAttempts}（${event.delayMs}ms 后）"

            is PiEvent.AutoRetryEnd -> _retryNote.value = null

            is PiEvent.SessionInfoChanged -> _retryNote.value = event.name?.let { "会话名：$it" }

            is PiEvent.AgentStart, is PiEvent.EntryAppended, is PiEvent.Unknown -> Unit
        }
    }

    private fun onMessageStart(message: PiMessage) {
        when (message.role) {
            ROLE_USER -> addUserItem(contentToText(message.content))
            ROLE_ASSISTANT -> mutateTranscript { list -> list + TranscriptItem.AssistantItem(nextItemId()) }
            else -> Unit // system / toolResult 不开新气泡（工具结果落进 ToolItem 输出）
        }
    }

    private fun onMessageEnd(message: PiMessage) {
        when (message.role) {
            ROLE_ASSISTANT -> mutateTranscript { list ->
                list.indexOfLast { it is TranscriptItem.AssistantItem }.takeIf { it >= 0 }?.let { index ->
                    val item = list[index] as TranscriptItem.AssistantItem
                    list.subList(0, index) + item.copy(
                        // 定稿以 message_end 的投影兜底：只在**没吃到 delta** 时回填，
                        // 否则会把已经流式拼好的正文截断（delta 丢包时才是它的用武之地）
                        text = item.text.ifEmpty { contentToText(message.content) },
                        thinking = item.thinking.ifEmpty { contentToThinking(message.content) },
                        settled = true,
                    )
                } ?: list
            }

            ROLE_USER -> addUserItem(contentToText(message.content))
            else -> Unit
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun onAssistantEvent(event: AssistantMessageEvent) {
        when (event) {
            is AssistantMessageEvent.TextStart -> ensureAssistant()
            // 正文 delta 是**唯一**允许延迟提交的事件（§3.3：80ms debounce 后一次性进末条）
            is AssistantMessageEvent.TextDelta -> appendTextDelta(event.delta)
            is AssistantMessageEvent.TextEnd -> updateLastAssistant {
                it.copy(text = it.text.ifEmpty { event.content }, settled = true)
            }

            is AssistantMessageEvent.ThinkingStart -> ensureAssistant()
            is AssistantMessageEvent.ThinkingDelta -> updateLastAssistant { it.copy(thinking = it.thinking + event.delta) }
            is AssistantMessageEvent.ThinkingEnd -> updateLastAssistant {
                it.copy(thinking = it.thinking.ifEmpty { event.content })
            }

            is AssistantMessageEvent.ToolcallStart -> updateLastAssistant {
                it.copy(toolCalls = it.toolCalls + ToolCallRecord(event.id, event.toolName, rawArgs = ""))
            }

            // Delta 是**原始 JSON 片段**：只拼接，End 才解析（§5.1 明写）
            is AssistantMessageEvent.ToolcallDelta -> updateLastAssistant { item ->
                if (item.toolCalls.isEmpty()) {
                    item.copy(toolCalls = listOf(ToolCallRecord(callId = "", toolName = "", rawArgs = event.delta)))
                } else {
                    val last = item.toolCalls.last()
                    item.copy(toolCalls = item.toolCalls.dropLast(1) + last.copy(rawArgs = last.rawArgs + event.delta))
                }
            }

            is AssistantMessageEvent.ToolcallEnd -> updateLastAssistant { item ->
                if (item.toolCalls.isEmpty()) {
                    item.copy(toolCalls = listOf(ToolCallRecord(callId = "", toolName = "", rawArgs = "", parsedName = event.toolCall["name"]?.toString())))
                } else {
                    val last = item.toolCalls.last()
                    item.copy(
                        toolCalls = item.toolCalls.dropLast(1) + last.copy(parsedName = event.toolCall["name"]?.toString()),
                    )
                }
            }

            is AssistantMessageEvent.Done -> settleLastAssistant()

            is AssistantMessageEvent.Error -> {
                settleLastAssistant()
                _retryNote.value = "本轮异常：${event.reason ?: event.error?.toString().orEmpty()}".truncate()
            }

            is AssistantMessageEvent.UnknownAm -> Unit
        }
    }

    private fun onPermissionRequest(event: PiEvent.ExtensionUiRequest) {
        // 本会话已记下的 method：自动 confirmed，**不弹框**（§5.2）
        if (event.method in rememberedMethods) {
            // 「本会话记住」的自动放行也是决策：记 confirmed（规格书 §3.2 只列了三态，
            // 自动放行与用户点「允许一次」在线协议上完全同形，审计面不额外造第四个词）
            writePermissionAnswer(event.id, confirmed = true, decision = AUDIT_CONFIRMED)
            return
        }
        val total = event.timeout ?: DEFAULT_UI_TIMEOUT_MS
        _permissionRequest.value = PendingPermission(
            id = event.id,
            method = event.method,
            title = event.title,
            message = event.message,
            timeoutMs = total,
            remainingMs = total,
        )
        _turnState.value = TurnState.PermissionPending
        startPermissionTicker()
    }

    /** 倒计时由 VM 协程驱动（§5.2）：逐秒改写 remainingMs，到点补 cancelled。 */
    private fun startPermissionTicker() {
        permissionTicker?.cancel()
        permissionTicker = viewModelScope.launch {
            var left = _permissionRequest.value?.remainingMs ?: return@launch
            while (left > 0L) {
                val step = minOf(TICK_MS, left)
                delay(step)
                left -= step
                val current = _permissionRequest.value ?: return@launch // 已应答 → 停表
                _permissionRequest.value = current.copy(remainingMs = left)
            }
            cancelPermissionOnce(timedOut = true) // §3.2 挂点三(c)：唯一一条「超时」措辞的来源
        }
    }

    private fun endTurn() {
        // §5.2：弹框还挂着而 TurnEnd/AgentEnd 先到 → 补 cancelled 一次
        cancelPermissionOnce()
        settleLastAssistant()
        _turnState.value = TurnState.Idle
    }

    /** 只认 VM 自己发出去的 id（其余 response 一律不进 transcript，§5.1）。 */
    private fun onCommandResponse(event: PiEvent.Response) {
        val id = event.id ?: return
        if (id !in pendingCommandIds) return
        pendingCommandIds -= id
        // §3.2：steer/follow_up 的回执是 `{disposition:"queued"}`，协议不要求任何 UI 动作
        // （队列的真相只由 queue_update 说），但必须留一条 logcat——用户按了「插入」而徽标
        // 半天不动时，这是唯一能分清「pi 没收到」还是「pi 收了没回队列」的地方。
        if (event.command == "steer" || event.command == "follow_up") {
            Log.i(QUEUE_LOG_TAG, "${event.command} 已入队：disposition=" +
                "${(event.data?.get("disposition") as? JsonPrimitive)?.contentOrNull ?: "无"} " +
                "success=${event.success}")
        }
        // §3.1：回填那条走专属通道（历史落进 transcript），但它仍然是 VM 自己发的命令，
        // 所以 commandResult 也照记——探针面板看到的形状对所有命令保持一致。
        if (id == backfillRequestId) {
            backfillRequestId = null
            applyBackfill(event)
        }
        _commandResult.value = event
    }

    // ── transcript 写入 helper（全部「整表替换」）───────────────────────────────

    /**
     * 转录写入的**唯一**入口：先撤「加载中」占位行，再整表替换（§4.3）。
     *
     * 单点清场的理由：占位行的语义是「pi 还没吐出这一页的真内容」，所以判据不能散在
     * 八个分支里（漏一个分支就会留一行永远挂着的「加载中」）；写在这里，
     * 任何一条真事件落地都必然带它走。`transform` 必须**纯**——
     * `StateFlow.update` 是 CAS 重试循环，带副作用的 lambda 会被执行多次。
     */
    private inline fun mutateTranscript(transform: (List<TranscriptItem>) -> List<TranscriptItem>) {
        _transcript.update { list ->
            val cleaned = if (list.none { it is TranscriptItem.NoteItem }) list else list.filterNot { it is TranscriptItem.NoteItem }
            transform(cleaned)
        }
    }

    /** 正文 delta 的合并窗口（§3.3 原文 80ms）：到点才提交一次，期间只攒草稿。 */
    private fun appendTextDelta(delta: String) {
        if (delta.isEmpty()) return
        draftText.append(delta)
        draftFlushJob?.cancel()
        draftFlushJob = viewModelScope.launch {
            delay(DELTA_DEBOUNCE_MS)
            flushDraft()
        }
    }

    /**
     * 把草稿一次性提交进末条 assistant。
     *
     * 挂 [appendTextDelta] 的挂起协程与 [onEvent] 的「非 delta 事件先到」两条路；
     * 空草稿时是纯空操作，所以可以无脑在每条路径上调用。
     */
    private fun flushDraft() {
        draftFlushJob?.cancel()
        draftFlushJob = null
        if (draftText.isEmpty()) return
        val chunk = draftText.toString()
        draftText.setLength(0)
        mutateTranscript { list ->
            val index = list.indexOfLast { it is TranscriptItem.AssistantItem }
            if (index < 0) list + TranscriptItem.AssistantItem(id = nextItemId(), text = chunk)
            else list.toMutableList().also {
                val item = it[index] as TranscriptItem.AssistantItem
                it[index] = item.copy(text = item.text + chunk)
            }
        }
    }

    /** 「只有正文 delta 允许延迟」这条规则的判据（§3.3）。 */
    private fun isTextDelta(event: PiEvent): Boolean =
        event is PiEvent.MessageUpdate && event.assistantMessageEvent is AssistantMessageEvent.TextDelta

    private fun nextItemId(): Int = ++itemSeq

    private fun nextCommandId(): String = "ui-${++commandSeq}"

    /** 本地即时入列 + 去重：pi 的 `message_start(user)` 回声不能把气泡复制一遍。 */
    private fun addUserItem(text: String) {
        if (text.isEmpty()) return
        mutateTranscript { list ->
            val echoed = list.lastOrNull() is TranscriptItem.UserItem &&
                (list.last() as TranscriptItem.UserItem).text == text
            if (echoed) list else list + TranscriptItem.UserItem(nextItemId(), text)
        }
    }

    /** delta 到达但没收到 message_start 时，也要有一条可写的 assistant。 */
    private fun ensureAssistant() {
        mutateTranscript { list ->
            if (list.lastOrNull() is TranscriptItem.AssistantItem) list
            else list + TranscriptItem.AssistantItem(nextItemId())
        }
    }

    private inline fun updateLastAssistant(transform: (TranscriptItem.AssistantItem) -> TranscriptItem.AssistantItem) {
        mutateTranscript { list ->
            val index = list.indexOfLast { it is TranscriptItem.AssistantItem }
            if (index < 0) list + TranscriptItem.AssistantItem(nextItemId())
            else list.toMutableList().also { it[index] = transform(it[index] as TranscriptItem.AssistantItem) }
        }
    }

    private fun settleLastAssistant() {
        mutateTranscript { list ->
            val index = list.indexOfLast { it is TranscriptItem.AssistantItem }
            if (index < 0) list
            else list.toMutableList().also {
                it[index] = (it[index] as TranscriptItem.AssistantItem).copy(settled = true)
            }
        }
    }

    private fun appendToolOutput(callId: String, text: String) {
        if (text.isEmpty()) return
        mutateTranscript { list ->
            list.map { item ->
                if (item is TranscriptItem.ToolItem && item.callId == callId) item.copy(output = item.output + text)
                else item
            }
        }
    }

    /** `bash_execution_update`：id 为 null 时落到最后一条仍在 Running 的工具卡。 */
    private fun appendBashOutput(id: String?, delta: String) {
        if (delta.isEmpty()) return
        mutateTranscript { list ->
            val index = list.indexOfLast { item ->
                item is TranscriptItem.ToolItem && (id != null && item.callId == id ||
                    id == null && item.status == ToolStatus.Running)
            }
            if (index < 0) list
            else list.toMutableList().also {
                val item = it[index] as TranscriptItem.ToolItem
                it[index] = item.copy(output = item.output + delta)
            }
        }
    }

    /** 本地失败（没有活动进程等）也要能被 commandResult 通道看见，但**不进 transcript**。 */
    private fun failureAsResponse(command: String, message: String?): PiEvent.Response =
        PiEvent.Response(id = null, command = command, success = false, error = message)

    private companion object {
        /** 权限决策的三种措辞（§3.2 原文），落 [AuditEntry.argsJson]。 */
        const val AUDIT_CONFIRMED = "confirmed"
        const val AUDIT_CANCELLED = "cancelled"
        const val AUDIT_CANCELLED_TIMEOUT = "cancelled-timeout"

        /** [AuditKind.PERMISSION] 条目的固定 toolName（§3.2 原文）。 */
        const val AUDIT_PERMISSION_TOOL = "permission"

        /** 审计旁路自己的日志 tag（与 `FileAuditSink.TAG` 同一串，日志侧好聚合）。 */
        const val AUDIT_LOG_TAG = "PiHost/Audit"

        /** protocol.md §3.7：pi 侧 confirm 的缺省超时（实测 15000ms）。 */
        const val DEFAULT_UI_TIMEOUT_MS = 15_000L

        /** 倒计时改写 remainingMs 的粒度（虚拟时间测试里 = 15 格）。 */
        const val TICK_MS = 1_000L

        /** 回填失败的日志 tag（§3.1：提示上屏之外还要留一条可查的现场）。 */
        const val BACKFILL_LOG_TAG = "PiHost/ChatHistory"

        /** §3.2：steer/follow_up 回执的唯一可见面（无 UI 动作，但必须可查）。 */
        const val QUEUE_LOG_TAG = "PiHost/Queue"
    }
}

/**
 * 审计 preview 的截断（§3.2 挂点二）：**不带省略号**——审计行是 JSON，
 * 尾部多一个 `…` 就是给下游解析器添一个假字节；上限直接取 [FileAuditSink.RESULT_PREVIEW_MAX]，
 * 与 `:core:engine` 写侧的那道闸同一个常量，两边永远不会算出两个数。
 */
internal fun String.toAuditPreview(): String =
    if (length <= FileAuditSink.RESULT_PREVIEW_MAX) this else substring(0, FileAuditSink.RESULT_PREVIEW_MAX)

/** 失败文案截断：stderr 尾部可能很长，安装门/顶栏只放前若干字符。 */
internal fun String.truncate(limit: Int = CAUSE_MAX_LEN): String =
    if (length <= limit) this else take(limit) + "…"

/** 上屏失败原因的最大长度（§6「cause（截断）」）。 */
private const val CAUSE_MAX_LEN = 240

/**
 * 正文 delta 的合并窗口（§3.3 原文 80ms）。
 *
 * 之所以做成常量而不是 `flow { … }.debounce(80)`：VM 的转录是「整表替换的快照」，
 * 不是可串联的流；再套一层 Flow 只会让「谁拥有 transcript」这条纪律多一个所有者。
 * 虚拟时钟测试直接 `advanceTimeBy(DELTA_DEBOUNCE_MS)` 推它。
 */
internal const val DELTA_DEBOUNCE_MS: Long = 80L

/** 「加载中」占位行的自动撤身时限：pi 不吐内容时不留死行（§4.3）。 */
internal const val SWITCH_NOTE_TIMEOUT_MS: Long = 3_000L

/** `switch_session`/`new_session` 等回执的上限（与设置页探针同一档，RECON-P2E §5）。 */
internal const val SESSION_ROLL_TIMEOUT_MS: Long = 5_000L

/** 占位行文案（UI/VM 测试按同一常量断言，不重复字面量）。 */
internal const val SWITCHING_NOTE_TEXT: String = "会话切换中…"
