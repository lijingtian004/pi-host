package works.earendil.pihost.feature.sessions

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
import works.earendil.pihost.core.rpc.ExtensionUiResponse
import works.earendil.pihost.core.rpc.PiEvent
import works.earendil.pihost.core.rpc.RpcCommand

/**
 * 会话屏用的引擎假体（`:feature:chat` 的同名假体在测试源集里不跨模块可见，
 * 这里按 [ChatEngine] 接口重写一份**最小可用面**：sessions 屏只用到
 * `sessionStates` + `send`，其余成员给空实现）。
 *
 * 与 chat 侧一致的关键纪律：出站命令进 [sent] 供用例直接断言（§4.5「switch/new 发命令捕获」）。
 */
class FakeEngine(val sessionId: String = SESSION) : ChatEngine {

    data class Sent(val sessionId: String, val command: RpcCommand, val id: String?)

    private val _states = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    override val sessionStates: StateFlow<Map<String, SessionState>> = _states.asStateFlow()

    private val bus = MutableSharedFlow<PiEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val sent = CopyOnWriteArrayList<Sent>()
    val startCalls = AtomicInteger(0)

    /** 置非 null → [send] 抛它（验「没有活动 pi 进程」的本地失败通道）。 */
    var sendError: Throwable? = null

    /** [start] 后落的状态；`Stopped`/`Failed` 用来验「没有活会话」那条分支。 */
    var state: SessionState = SessionState.Ready

    fun setState(sessionId: String, state: SessionState) {
        _states.update { it + (sessionId to state) }
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
 * 扫描器假体：内存表 + 调用计数，让 ViewModel 的用例不碰磁盘
 * （§4.5「SessionsViewModelTest（FakeChatEngine + FakeScanner）」）。
 */
class FakeScanner(
    sessions: List<ScannedSession> = emptyList(),
    /**
     * [scan] 前的虚拟挂起时长。默认 0 = 立即返回。
     * 需要断言「刷新中」这一中间档的用例（v09）必须给它一个正数：StateFlow 只保最新值，
     * 同一次不挂起的 launch 里先写 `refreshing=true` 再写 `refreshing=false`，
     * 中间档会被 conflate 掉——与 `:feature:chat` 的 FakeRuntimeInstall.stepPauseMs 同一课。
     */
    var scanPauseMs: Long = 0L,
) : SessionScanner {

    private val source = sessions.toMutableList()

    val scanCalls = AtomicInteger(0)
    val deleted = CopyOnWriteArrayList<String>()

    /** 置非 null → [scan] 抛它（验 [SessionsUiState.Failure]）。 */
    var scanError: Throwable? = null

    /** 置 false → [delete] 报告失败（验删除失败提示）。 */
    var deleteResult: Boolean = true

    fun setSessions(vararg items: ScannedSession) {
        source.clear()
        source.addAll(items)
    }

    override suspend fun scan(): List<ScannedSession> {
        scanCalls.incrementAndGet()
        scanError?.let { throw it }
        if (scanPauseMs > 0L) delay(scanPauseMs)
        return source.toList()
    }

    override suspend fun delete(sessionPath: String): Boolean {
        deleted.add(sessionPath)
        if (deleteResult) source.removeAll { it.sessionPath == sessionPath }
        return deleteResult
    }
}

/** 造一行数据，省掉每条用例都手拼 8 个字段。 */
fun session(
    id: String,
    path: String = "/files/home/.pi/agent/sessions/--p--/$id.jsonl",
    name: String? = null,
    cwd: String = "/p",
    modifiedMs: Long = 1_700_000_000_000L,
    messageCount: Int = 1,
    firstMessage: String? = "hi",
    sizeBytes: Long = 1024L,
): ScannedSession = ScannedSession(
    sessionPath = path,
    sessionId = id,
    name = name,
    cwd = cwd,
    modified = java.time.Instant.ofEpochMilli(modifiedMs),
    messageCount = messageCount,
    firstMessage = firstMessage,
    sizeBytes = sizeBytes,
)
