package works.earendil.pihost.feature.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import works.earendil.pihost.core.engine.ChatEngine
import works.earendil.pihost.core.engine.InstallProgress
import works.earendil.pihost.core.engine.InstalledRuntime
import works.earendil.pihost.core.engine.RuntimeInstall
import works.earendil.pihost.core.engine.SessionState
import works.earendil.pihost.core.engine.engineStartIntent
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
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.NotInstalled)

    /** 首启/运行期总状态。 */
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _turnState = MutableStateFlow(TurnState.Idle)
    val turnState: StateFlow<TurnState> = _turnState.asStateFlow()

    private val _transcript = MutableStateFlow<List<TranscriptItem>>(emptyList())
    val transcript: StateFlow<List<TranscriptItem>> = _transcript.asStateFlow()

    private val _queueDepth = MutableStateFlow(0)

    /** `queue_update` 的 steering+followUp 合计（§6 队列徽标）。 */
    val queueDepth: StateFlow<Int> = _queueDepth.asStateFlow()

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

    private var sessionId: String? = null
    private var collectorJob: Job? = null
    private var statusJob: Job? = null
    private var permissionTicker: Job? = null

    /** 幂等闩：init 起一次，失败才放开（重试按钮就靠它）。 */
    private var starting = false

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
                _uiState.value = ChatUiState.Failed(
                    cause = (exception?.message ?: failedStep?.message ?: "运行时安装失败").truncate(),
                )
                starting = false
                return@launch
            }

            startEngine()
        }
    }

    private suspend fun startEngine() {
        val ctx = getApplication<Application>()
        ctx.startForegroundService(engineStartIntent(ctx))

        val started = runCatching { engine.start() }
        val id = started.getOrNull()
        val state = id?.let { engine.sessionStates.value[it] }
        if (started.isFailure || state !is SessionState.Ready) {
            val reason = started.exceptionOrNull()?.message
                ?: (state as? SessionState.Failed)?.cause
                ?: "引擎未就绪：$state"
            _uiState.value = ChatUiState.Failed(
                cause = "$reason；stderr: ${id?.let { engine.stderrTail(it) }.orEmpty()}".truncate(),
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
        writePermissionAnswer(pending.id, confirmed = allowOnce || allowSession)
        if (_turnState.value == TurnState.PermissionPending) _turnState.value = TurnState.Streaming
    }

    private fun writePermissionAnswer(requestId: String, confirmed: Boolean) {
        val id = sessionId ?: return
        if (!settledPermissions.add(requestId)) return
        val response = if (confirmed) {
            ExtensionUiResponse(requestId, confirmed = true)
        } else {
            ExtensionUiResponse(requestId, cancelled = true)
        }
        engine.sendUiResponse(id, response)
    }

    /** 超时/TurnEnd 抢跑的兜底：补 cancelled 恰好一次，再清空弹框态。 */
    private fun cancelPermissionOnce() {
        val pending = _permissionRequest.value ?: return
        permissionTicker?.cancel()
        permissionTicker = null
        _permissionRequest.value = null
        writePermissionAnswer(pending.id, confirmed = false)
        if (_turnState.value == TurnState.PermissionPending) _turnState.value = TurnState.Streaming
    }

    // ── 入站装配（§5.1 事件映射）─────────────────────────────────────────────────

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun onEvent(event: PiEvent) {
        when (event) {
            is PiEvent.MessageStart -> onMessageStart(event.message)
            is PiEvent.MessageEnd -> onMessageEnd(event.message)
            is PiEvent.MessageUpdate -> event.assistantMessageEvent?.let { onAssistantEvent(it) }

            is PiEvent.ToolExecutionStart -> _transcript.update { list ->
                list + TranscriptItem.ToolItem(
                    id = nextItemId(),
                    callId = event.toolCallId,
                    toolName = event.toolName,
                    status = ToolStatus.Running,
                )
            }

            is PiEvent.ToolExecutionUpdate -> appendToolOutput(event.toolCallId, resultToText(event.partialResult))

            is PiEvent.BashExecutionUpdate -> appendBashOutput(event.id, event.delta)

            is PiEvent.ToolExecutionEnd -> _transcript.update { list ->
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

            is PiEvent.TurnStart -> _turnState.value = TurnState.Streaming

            is PiEvent.TurnEnd, is PiEvent.AgentEnd -> endTurn()

            is PiEvent.AgentSettled -> {
                settleLastAssistant()
                if (_turnState.value != TurnState.PermissionPending) _turnState.value = TurnState.Idle
            }

            is PiEvent.QueueUpdate -> _queueDepth.value = event.steering.size + event.followUp.size

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
            ROLE_ASSISTANT -> _transcript.update { list -> list + TranscriptItem.AssistantItem(nextItemId()) }
            else -> Unit // system / toolResult 不开新气泡（工具结果落进 ToolItem 输出）
        }
    }

    private fun onMessageEnd(message: PiMessage) {
        when (message.role) {
            ROLE_ASSISTANT -> _transcript.update { list ->
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
            is AssistantMessageEvent.TextDelta -> updateLastAssistant { it.copy(text = it.text + event.delta) }
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
            writePermissionAnswer(event.id, confirmed = true)
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
            cancelPermissionOnce()
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
        _commandResult.value = event
    }

    // ── transcript 写入 helper（全部「整表替换」）───────────────────────────────

    private fun nextItemId(): Int = ++itemSeq

    private fun nextCommandId(): String = "ui-${++commandSeq}"

    /** 本地即时入列 + 去重：pi 的 `message_start(user)` 回声不能把气泡复制一遍。 */
    private fun addUserItem(text: String) {
        if (text.isEmpty()) return
        _transcript.update { list ->
            val echoed = list.lastOrNull() is TranscriptItem.UserItem &&
                (list.last() as TranscriptItem.UserItem).text == text
            if (echoed) list else list + TranscriptItem.UserItem(nextItemId(), text)
        }
    }

    /** delta 到达但没收到 message_start 时，也要有一条可写的 assistant。 */
    private fun ensureAssistant() {
        _transcript.update { list ->
            if (list.lastOrNull() is TranscriptItem.AssistantItem) list
            else list + TranscriptItem.AssistantItem(nextItemId())
        }
    }

    private inline fun updateLastAssistant(transform: (TranscriptItem.AssistantItem) -> TranscriptItem.AssistantItem) {
        _transcript.update { list ->
            val index = list.indexOfLast { it is TranscriptItem.AssistantItem }
            if (index < 0) list + TranscriptItem.AssistantItem(nextItemId())
            else list.toMutableList().also { it[index] = transform(it[index] as TranscriptItem.AssistantItem) }
        }
    }

    private fun settleLastAssistant() {
        _transcript.update { list ->
            val index = list.indexOfLast { it is TranscriptItem.AssistantItem }
            if (index < 0) list
            else list.toMutableList().also {
                it[index] = (it[index] as TranscriptItem.AssistantItem).copy(settled = true)
            }
        }
    }

    private fun appendToolOutput(callId: String, text: String) {
        if (text.isEmpty()) return
        _transcript.update { list ->
            list.map { item ->
                if (item is TranscriptItem.ToolItem && item.callId == callId) item.copy(output = item.output + text)
                else item
            }
        }
    }

    /** `bash_execution_update`：id 为 null 时落到最后一条仍在 Running 的工具卡。 */
    private fun appendBashOutput(id: String?, delta: String) {
        if (delta.isEmpty()) return
        _transcript.update { list ->
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
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        /** protocol.md §3.7：pi 侧 confirm 的缺省超时（实测 15000ms）。 */
        const val DEFAULT_UI_TIMEOUT_MS = 15_000L

        /** 倒计时改写 remainingMs 的粒度（虚拟时间测试里 = 15 格）。 */
        const val TICK_MS = 1_000L

        /** 失败原因上屏的截断长度（§6「cause（截断）」）。 */
        const val CAUSE_MAX_LEN = 240
    }
}

/** 失败文案截断：stderr 尾部可能很长，安装门/顶栏只放前若干字符。 */
internal fun String.truncate(limit: Int = CAUSE_MAX_LEN): String =
    if (length <= limit) this else take(limit) + "…"

/** 上屏失败原因的最大长度（§6「cause（截断）」）。 */
private const val CAUSE_MAX_LEN = 240
