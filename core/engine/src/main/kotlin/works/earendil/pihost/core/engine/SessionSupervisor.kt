package works.earendil.pihost.core.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import works.earendil.pihost.core.process.ProcessStarter
import works.earendil.pihost.core.rpc.ExtensionUiResponse
import works.earendil.pihost.core.rpc.PiEvent
import works.earendil.pihost.core.rpc.PiSession
import works.earendil.pihost.core.rpc.RpcCommand
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 会话状态机取值（P2C 规格书 §5.1，对方案 §3.2 的定案修正见 §12.2）。
 *
 * `Ready` = **握手成功且进程存活**：方案里的 `Running(pid)` 已并入此处——
 * P1 冻结的 [works.earendil.pihost.core.process.ProcessHandle] 不暴露 pid，
 * 也没有其它取 pid 的通路，故不带 pid 字段。
 */
sealed interface SessionState {

    /** 已请求 spawn，握手未定。 */
    data object Spawning : SessionState

    /** `get_state` 拿到成功 response，进程仍活着。 */
    data object Ready : SessionState

    /**
     * 第 [attempt] 次重启前的退避等待（1 起）；[backoffMs] 是本次要等待的时长。
     * 退避表用完仍失败 → [Failed]，不再 spawn。
     */
    data class Restarting(val attempt: Int, val backoffMs: Long) : SessionState

    /** 重启额度耗尽。[cause] 必含 stderr 尾部（node 崩溃时唯一的诊断线索）。 */
    data class Failed(val cause: String) : SessionState

    /** 用户主动停：**绝不**触发重启。 */
    data object Stopped : SessionState
}

/**
 * pi 子进程会话的监督者（方案 §3.2 / P2C 规格书 §5）。
 *
 * 每条会话一个监视协程：spawn → 先订阅 events 再 `PiSession.start()` →
 * 发 `get_state` 握手 → Ready → 阻塞到进程退出或用户停 → 崩溃则按退避表重启。
 *
 * 关键约定（都来自冻结区的契约，不是本地发明）：
 * - **重启上限**：初始 1 次 spawn + [backoffMs] 长度次重启 = 最多
 *   `backoffMs.size + 1` 次 [ProcessStarter.start]，用完 → [SessionState.Failed]（§12.5）；
 * - **握手超时与 error response 都是失败**，走同一退避，且失败后
 *   **先优雅 close 残留进程**再退避，不留活进程（§12.6）；
 * - **订阅先于 start**：`PiSession.events` 无 replay（KDoc 明示），
 *   所以 events 收集器先起、`eventsSubscriptionCount > 0` 确认后才 start()；
 * - **用户主动停 → [SessionState.Stopped]，绝不重启**；[stop] 幂等，
 *   对已 [SessionState.Failed]/[SessionState.Stopped] 的会话无副作用。
 *
 * 每会话恢复用 host 生成的 UUID 作 `--session-id`（见 [PiSpawn]），
 * 因此崩溃重启后 argv 里的 session id 不变，会话文件续用。
 *
 * **P2D 起实现 [ChatEngine]**（规格书 §4.4 / §13 偏差 4）：语义一条没改，
 * 只是把对外面收成接口；同时**移除类与全部方法的 `open` 修饰**——
 * P2C 标 `open` 的唯一理由是这个工程不引 Mockito、`PiEngineService`/MainActivity 的测试
 * 只能靠继承塞 stub 监督者（规格书 §6.1 的原话）。现在测试改绑 [ChatEngine] 的假体
 * （`FakeChatEngine`），`open` 就成了纯粹的暴露面，按 P2C 报告预告清掉。
 */
class SessionSupervisor(
    private val scope: CoroutineScope,
    private val starter: ProcessStarter,
    private val commandFor: (sessionId: String) -> List<String>,
    private val envFor: () -> Map<String, String>,
    private val workingDir: String?,
    private val backoffMs: List<Long> = DEFAULT_BACKOFF_MS,
    private val handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
    private val gracefulCloseTimeoutMs: Long = PiSession.DEFAULT_GRACEFUL_TIMEOUT_MS,
) : ChatEngine {

    init {
        require(backoffMs.isNotEmpty()) { "backoffMs 不能为空：重启上限 = backoffMs.size + 1 次 spawn" }
    }

    private val _sessionStates = MutableStateFlow<Map<String, SessionState>>(emptyMap())

    /** key = sessionId（host 生成的 UUID）。每次转移替换整张 map 快照，保证引用透明。 */
    override val sessionStates: StateFlow<Map<String, SessionState>> = _sessionStates.asStateFlow()

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * 生成 UUID → spawn → `get_state` 握手。
     *
     * 挂起直到首个 [SessionState.Ready] 或终态（[SessionState.Failed]/[SessionState.Stopped]），
     * 返回 sessionId（= argv 里的 `--session-id`，重启后不变）。
     */
    override suspend fun start(): String {
        val sessionId = UUID.randomUUID().toString()
        val entry = Entry(sessionId)
        entries[sessionId] = entry
        setState(sessionId, SessionState.Spawning)
        val watcher = scope.launch { watchSession(entry) }
        entry.watcher = watcher
        awaitTerminalOrReady(sessionId)
        return sessionId
    }

    /**
     * 用户主动停：置停止标志 → 会话活着则优雅 close → [SessionState.Stopped]。
     * 未知 id / 已终态的 id 直接返回（幂等，无副作用）。
     */
    override suspend fun stop(sessionId: String) {
        val entry = entries[sessionId] ?: return
        entry.stopRequested.set(true)
        entry.stopSignal.complete(Unit)
        val current = _sessionStates.value[sessionId]
        if (current is SessionState.Failed || current is SessionState.Stopped) return
        awaitTerminal(sessionId)
        entry.watcher?.cancel()
    }

    /** 全部 [stop]，并发推进，等全部进终态。 */
    override suspend fun shutdownAll() {
        val ids = entries.keys.toList()
        coroutineScope { ids.map { id -> launch { stop(id) } } }
        ids.forEach { entries[it]?.watcher?.cancel() }
    }

    /**
     * 代理该会话的 [PiSession.events]（转发后的稳定 flow：进程重启后订阅者不用重订阅）。
     * 无 replay ⇒ 与 PiSession 同一条纪律：**先订阅再发命令**。会话未知 → null。
     */
    override fun events(sessionId: String): SharedFlow<PiEvent>? = entries[sessionId]?.bus

    /**
     * 该会话事件总线的**订阅计数**代理。
     *
     * 存在的理由：`subscriptionCount` 只声明在 `MutableSharedFlow` 上（1.10.2 的
     * `SharedFlow` 接口里**没有**这个成员，编译机 javap 实测只有 `getReplayCache` + `collect`），
     * 而 [events] 对外只给 `SharedFlow`（不给 emit 口）——调用方要「先订阅再发命令」
     * 就只能从这里取证据。与 [PiSession.eventsSubscriptionCount] 同一用途。会话未知 → null。
     *
     * **不在 [ChatEngine] 接口里**（规格书 §4.4 的接口就是那 8 个成员）：
     * UI 侧的订阅时序改用「在 Unconfined 上起收集协程」保证（见 ChatViewModel）。
     */
    fun eventSubscriptions(sessionId: String): StateFlow<Int>? =
        entries[sessionId]?.bus?.subscriptionCount

    /** 代理 [PiSession.stderrRing] 快照（8KB 环）。会话未知或还没有进程 → null。 */
    override fun stderrTail(sessionId: String): String? = entries[sessionId]?.session?.stderrRing?.snapshot()

    /**
     * 向该会话的活动进程发命令（`测试 RPC` 按钮的发送通路，规格书 §7）。
     * 返回用于关联 response 的 id；无活动进程 → [IllegalStateException]。
     */
    override suspend fun send(sessionId: String, command: RpcCommand, id: String?): String {
        val session = entries[sessionId]?.session
            ?: throw IllegalStateException("会话 $sessionId 没有活动 pi 进程")
        val requestId = id ?: session.nextId()
        session.send(command, requestId)
        return requestId
    }

    /**
     * 权限应答写回（`extension_ui_response`，protocol.md §3.7：**无回执**）。
     *
     * [PiSession.sendUiResponse] 是 suspend（进单 writer 的 outbox），而 [ChatEngine] 这条
     * 签名按规格书原文是非 suspend —— 故在这里 `scope.launch` 投递。
     * 会话已无进程（崩溃窗口 / 已 stop）时 `send` 会抛 [IllegalStateException]，
     * 这里吞掉并记日志：那条请求在 pi 侧已经按超时（confirm=false）结算了，
     * 补发一条应答没有意义，抛给 UI 只会把「拒绝按钮」变成崩溃。
     */
    override fun sendUiResponse(sessionId: String, response: ExtensionUiResponse) {
        val session = entries[sessionId]?.session ?: return
        scope.launch {
            runCatching { session.sendUiResponse(response) }
                .onFailure { throwable ->
                    android.util.Log.w(TAG, "extension_ui_response 投递失败 id=${response.id}", throwable)
                }
        }
    }

    // ── 核心循环（规格书 §5.3） ────────────────────────────────────────────────

    @Suppress("CyclomaticComplexMethod")
    private suspend fun watchSession(entry: Entry) {
        val id = entry.sessionId
        var restartsDone = 0
        var lastCause = "未尝试握手"

        while (true) {
            if (entry.stopRequested.get()) {
                setState(id, SessionState.Stopped)
                return
            }
            setState(id, SessionState.Spawning)

            var session: PiSession? = null
            var collector: Job? = null
            val slot = CompletableDeferred<Handshake>()
            val helpers = ArrayList<Job>(2)

            val handshake = try {
                // P2F §4.1(a)：ProcessBuilder 的子进程 **chdir 先于 execve**，cwd 不存在时抛的
                // IOException 与「exe 不存在」共用同一条 errno 管道（都是 `error=2`，消息里只写 exe 路径），
                // 现场根本无法区分（P2F §3.2 首要嫌疑：全仓没有任何代码建 `filesDir/projects/`，
                // 见 RECON-P2F §2.1）。spawn 前 mkdirs 把这条歧义从根上消掉。
                // runCatching = **失败不抛**：权限问题让后续 exec 的真实 errno 说话；
                // mkdirs 幂等，所以重启循环（每次 setState(Spawning) 之后）天然重复覆盖，无需额外状态。
                workingDir?.let { w -> runCatching { java.io.File(w).mkdirs() } }
                val process = starter.start(commandFor(id), envFor(), workingDir)
                val s = PiSession(process, scope)
                session = s
                entry.session = s
                val requestId = s.nextId()
                val seenResponse = AtomicReference<PiEvent.Response?>(null)

                // ① 订阅先于 start：events 无 replay（PiSession KDoc）
                collector = scope.launch(Dispatchers.Unconfined) {
                    s.events.collect { event ->
                        entry.bus.emit(event)
                        if (event is PiEvent.Response && event.id == requestId) {
                            seenResponse.set(event)
                            if (event.success) slot.complete(Handshake.Ok(event))
                            else slot.complete(Handshake.Error(event))
                        }
                    }
                }
                s.eventsSubscriptionCount.first { it > 0 }
                s.start()
                s.send(RpcCommand.GetState, requestId)

                // ② 握手期进程死亡 → 立刻判失败，不白等 15s。
                //    但应答优先：pi 可以「答完 get_state 就退」，此时握手算成功，
                //    随后的退出走 Ready→崩溃 的重启定性（否则已到达的应答会被 Died 吞掉）。
                helpers += scope.launch(Dispatchers.Unconfined) {
                    val code = s.exit.await()
                    val answered = seenResponse.get()
                    when {
                        answered == null -> slot.complete(Handshake.Died(code))
                        answered.success -> slot.complete(Handshake.Ok(answered))
                        else -> slot.complete(Handshake.Error(answered))
                    }
                }
                // ③ stop() 也要能打断握手等待
                helpers += scope.launch {
                    entry.stopSignal.await()
                    slot.complete(Handshake.StoppedByUser)
                }
                withTimeoutOrNull(handshakeTimeoutMs) { slot.await() } ?: Handshake.Timeout
            } catch (t: Throwable) {
                Handshake.SpawnFailure(t.message ?: t.javaClass.name)
            } finally {
                helpers.forEach { it.cancel() }
            }

            if (handshake is Handshake.Ok) {
                setState(id, SessionState.Ready)
                val wait = awaitExitOrStop(session!!, entry)
                collector?.cancel()
                if (wait is ReadyWait.Stop || entry.stopRequested.get()) {
                    closeGracefully(session)
                    setState(id, SessionState.Stopped)
                    return
                }
                // 非用户停的自然退出 = 崩溃（规格书 §5.3）
                lastCause = "进程在 Ready 后退出 code=${(wait as ReadyWait.Exited).code}"
                closeGracefully(session)
            } else {
                collector?.cancel()
                lastCause = describeFailure(handshake)
                // §12.6：先优雅关掉残留进程，再退避——不能丢活进程
                closeGracefully(session)
            }

            if (entry.stopRequested.get()) {
                setState(id, SessionState.Stopped)
                return
            }
            if (restartsDone >= backoffMs.size) {
                setState(id, SessionState.Failed(cause = "$lastCause；stderr: ${stderrTail(id).orEmpty()}"))
                return
            }
            restartsDone += 1
            val backoff = backoffMs[restartsDone - 1]
            setState(id, SessionState.Restarting(restartsDone, backoff))
            if (awaitBackoffOrStop(entry, backoff)) {
                setState(id, SessionState.Stopped)
                return
            }
        }
    }

    /** Ready 之后等「进程退出」或「用户停」，取先到的那个。 */
    private suspend fun awaitExitOrStop(session: PiSession, entry: Entry): ReadyWait {
        val slot = CompletableDeferred<ReadyWait>()
        val exitJob = scope.launch(Dispatchers.Unconfined) {
            slot.complete(ReadyWait.Exited(session.exit.await()))
        }
        val stopJob = scope.launch {
            entry.stopSignal.await()
            slot.complete(ReadyWait.Stop)
        }
        return try {
            slot.await()
        } finally {
            exitJob.cancel()
            stopJob.cancel()
        }
    }

    /** 退避等待；返回 true 表示期间用户请求了停止。 */
    private suspend fun awaitBackoffOrStop(entry: Entry, backoff: Long): Boolean {
        if (entry.stopRequested.get()) return true
        val slot = CompletableDeferred<Boolean>()
        val delayJob = scope.launch {
            delay(backoff)
            slot.complete(false)
        }
        val stopJob = scope.launch {
            entry.stopSignal.await()
            slot.complete(true)
        }
        return try {
            slot.await()
        } finally {
            delayJob.cancel()
            stopJob.cancel()
        }
    }

    private suspend fun closeGracefully(session: PiSession?) {
        if (session == null) return
        runCatching { session.close(gracefulCloseTimeoutMs) }
    }

    private fun describeFailure(handshake: Handshake?): String = when (handshake) {
        is Handshake.Error -> "握手 response 失败：${handshake.response.error ?: "success=false"}"
        is Handshake.Died -> "进程在握手中退出 code=${handshake.exitCode}"
        is Handshake.Timeout -> "握手超时：${handshakeTimeoutMs}ms 内没有成功 response"
        is Handshake.SpawnFailure -> "spawn 失败：${handshake.message}"
        Handshake.StoppedByUser -> "握手期被用户停止"
        else -> "未知失败原因（handshake=$handshake）"
    }

    private suspend fun awaitTerminalOrReady(sessionId: String) {
        _sessionStates.first { state ->
            when (state[sessionId]) {
                is SessionState.Ready, is SessionState.Failed, is SessionState.Stopped -> true
                else -> false
            }
        }
    }

    private suspend fun awaitTerminal(sessionId: String) {
        _sessionStates.first { state ->
            when (state[sessionId]) {
                is SessionState.Failed, is SessionState.Stopped -> true
                else -> false
            }
        }
    }

    private fun setState(sessionId: String, state: SessionState) {
        _sessionStates.update { current -> current + (sessionId to state) }
    }

    /** 一条会话的运行期材料。 */
    private class Entry(val sessionId: String) {
        /** 转发 [PiSession.events] 的稳定总线：进程重启后订阅者无需重订阅。 */
        val bus = MutableSharedFlow<PiEvent>(
            extraBufferCapacity = BUS_BUFFER,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )

        val stopRequested = AtomicBoolean(false)
        val stopSignal = CompletableDeferred<Unit>()

        @Volatile
        var session: PiSession? = null

        @Volatile
        var watcher: Job? = null

        companion object {
            /** 与 PiSession.events 同量级缓冲（背压不丢事件）。 */
            const val BUS_BUFFER = 256
        }
    }

    private companion object {
        /** §12.5：初始 1 次 + 3 次重启 = 最多 4 次 spawn。 */
        val DEFAULT_BACKOFF_MS: List<Long> = listOf(1_000L, 4_000L, 16_000L)

        const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 15_000L

        /** 权限应答投递失败的留痕 tag（不是崩溃路径，只进 logcat）。 */
        const val TAG = "PiHostSupervisor"
    }
}

/** 握手结果（内部）。 */
private sealed interface Handshake {
    data class Ok(val response: PiEvent.Response) : Handshake
    data class Error(val response: PiEvent.Response) : Handshake
    data class Died(val exitCode: Int) : Handshake
    data class SpawnFailure(val message: String) : Handshake
    data object Timeout : Handshake
    data object StoppedByUser : Handshake
}

/** Ready 期间的结束原因（内部）。 */
private sealed interface ReadyWait {
    data class Exited(val code: Int) : ReadyWait
    data object Stop : ReadyWait
}
