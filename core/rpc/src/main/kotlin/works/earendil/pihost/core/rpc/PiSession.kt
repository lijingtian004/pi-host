package works.earendil.pihost.core.rpc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import works.earendil.pihost.core.process.ProcessHandle
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.SerializationException

/**
 * pi rpc 子进程的宿主侧会话（方案 §4.3）。
 *
 * 职责边界：本类只管「字节 ⇄ 类型化事件」的搬运与进程生命周期信号；
 * 会话状态机/重启/ready 判定属 P2 SessionSupervisor（方案 §3.2）。
 *
 * 并发模型（§4.3 原样落地）：
 * - **单 writer**：所有 stdin 写入只发生在 writeLoop 协程，来源经 [outbox]
 *   （UNLIMITED Channel）串行化，多来源（prompt/权限应答）不会交错；
 * - **读侧背压**：事件经 SharedFlow（extraBufferCapacity=256, SUSPEND）发射，
 *   消费慢时 emit 挂起 → readLoop 暂停读 → 管道反压 → pi 的 stdout 写阻塞，
 *   工具输出不丢；
 * - **stderr 独立 drain**：8KB 环形缓冲（[StderrRing]，方案 §3.4），不并入事件流；
 * - **退出信号**：watchExit 协程阻塞 waitFor → [exit]（CompletableDeferred），
 *   供 Supervisor await 触发状态机。
 *
 * 优雅退出（protocol.md §2 + 方案 §3.3 修正版）：stdin EOF → pi 退出码 0。
 * [close] 的序列：关 outbox → writeLoop 排干后关 stdin → 等自然退出
 * （默认 5s 上限）→ 超时 [ProcessHandle.destroy] 兜底。
 *
 * 订阅时序：SharedFlow 无 replay——**必须在 start() 前订阅 [events]**，
 * 否则先发的事件丢失（[eventsSubscriptionCount] 可用于确认订阅到位）。
 */
class PiSession(
    private val process: ProcessHandle,
    private val scope: CoroutineScope,
) {

    /** 已知事件类型的形状漂移/坏行诊断；循环不死，坏行跳过继续（§1 容错）。 */
    data class ProtocolError(val line: String, val message: String)

    private val _events = MutableSharedFlow<PiEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /** 解析成功的事件流（response + 全部 AME 事件）。 */
    val events: SharedFlow<PiEvent> = _events

    private val _protocolErrors = MutableSharedFlow<ProtocolError>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /** 坏行/形状漂移诊断流。 */
    val protocolErrors: SharedFlow<ProtocolError> = _protocolErrors

    /** events 的订阅计数（测试/Supervisor 确认订阅时序用）。 */
    val eventsSubscriptionCount: StateFlow<Int> get() = _events.subscriptionCount

    /** protocolErrors 的订阅计数。 */
    val protocolErrorsSubscriptionCount: StateFlow<Int> get() = _protocolErrors.subscriptionCount

    /** 进程退出码信号：watchExit 完成时补全。 */
    val exit: CompletableDeferred<Int> = CompletableDeferred()

    /** stderr 尾部快照（8KB 环，方案 §3.4——node 崩溃时唯一诊断线索）。 */
    val stderrRing = StderrRing(DEFAULT_STDERR_RING_BYTES)

    private val outbox = Channel<String>(Channel.UNLIMITED)
    private val idCounter = AtomicLong(0)
    private val started = AtomicBoolean(false)

    /** 启动四个协程（读/写/stderr drain/退出监视）。幂等：二次调用抛 IllegalStateException。 */
    fun start() {
        check(started.compareAndSet(false, true)) { "PiSession.start() already called" }
        scope.launch(Dispatchers.IO) { readLoop() }
        scope.launch(Dispatchers.IO) { writeLoop() }
        scope.launch(Dispatchers.IO) { drainStderrLoop() }
        scope.launch(Dispatchers.IO) { watchExit() }
    }

    /** 命令 id 生成（response 原样回显，protocol.md §3）。 */
    fun nextId(): String = "c-${idCounter.incrementAndGet()}"

    /** 发送命令（自动分配 id）。close 后调用抛 ChannelClosedException。 */
    suspend fun send(command: RpcCommand, id: String = nextId()) {
        outbox.send(encodeCommand(id, command))
    }

    /** 发送权限应答（extension_ui_response，无回执，protocol.md §3.7）。 */
    suspend fun sendUiResponse(response: ExtensionUiResponse) {
        outbox.send(encodeExtensionUiResponse(response))
    }

    /**
     * 优雅关停：排干 outbox → 关 stdin（EOF→pi 退 0，§2）→ 等退出；
     * [gracefulTimeoutMs] 超时则 destroy 兜底并返回其退出码。
     * 幂等：可重复调用，返回同一退出码。
     */
    suspend fun close(gracefulTimeoutMs: Long = DEFAULT_GRACEFUL_TIMEOUT_MS): Int {
        outbox.close()
        val graceful = withTimeoutOrNull(gracefulTimeoutMs) { exit.await() }
        return graceful ?: run {
            process.destroy()
            exit.await()
        }
    }

    /**
     * 读循环：阻塞读 stdout → LineFramer 分帧 → 挂起 emit。
     * framer 回调是同步的，本批完成的行先进 pending，feed 返回后统一 emit——
     * emit 的挂起（SUSPEND 背压）在两次 read 之间传播，这正是 §4.3 要的
     * 「消费慢 → 读循环降速」。
     * EOF 后 framer.close() 冲刷无尾 LF 的最后一帧（protocol.md §1）。
     */
    private suspend fun readLoop() {
        val pending = ArrayList<String>(16)
        val framer = LineFramer { line -> if (line.isNotEmpty()) pending.add(line) }
        val buf = ByteArray(64 * 1024)
        try {
            while (true) {
                val n = process.stdout.read(buf)
                if (n < 0) break
                if (n > 0) {
                    framer.feed(buf, n)
                    emitPending(pending)
                }
            }
        } finally {
            framer.close()
            emitPending(pending)
        }
    }

    private suspend fun emitPending(pending: MutableList<String>) {
        for (line in pending) {
            try {
                _events.emit(parsePiEvent(line))
            } catch (e: SerializationException) {
                _protocolErrors.emit(ProtocolError(line, e.message ?: "serialization failure"))
            } catch (e: Exception) {
                _protocolErrors.emit(ProtocolError(line, e.message ?: e.javaClass.simpleName))
            }
        }
        pending.clear()
    }

    /** 单 writer：outbox → stdin。outbox 关闭后排干剩余并关 stdin（EOF 信号）。 */
    private suspend fun writeLoop() {
        try {
            for (line in outbox) {
                process.stdin.write(line.toByteArray(Charsets.UTF_8))
                process.stdin.flush()
            }
        } finally {
            runCatching { process.stdin.close() }
        }
    }

    /** stderr drain：只进环形缓冲，不并入事件流（方案 §3.4）。 */
    private fun drainStderrLoop() {
        val buf = ByteArray(8 * 1024)
        try {
            while (true) {
                val n = process.stderr.read(buf)
                if (n < 0) break
                if (n > 0) stderrRing.append(buf, n)
            }
        } catch (_: Exception) {
            // 进程死亡时管道可能异常关闭；环内已有的尾部足够诊断
        }
    }

    /** 阻塞 waitFor → 补全 [exit]。Supervisor await 此信号驱动状态机（§3.2）。 */
    private suspend fun watchExit() {
        val code = process.waitFor()
        exit.complete(code)
    }

    companion object {
        const val DEFAULT_STDERR_RING_BYTES = 8 * 1024
        const val DEFAULT_GRACEFUL_TIMEOUT_MS = 5_000L
    }
}
