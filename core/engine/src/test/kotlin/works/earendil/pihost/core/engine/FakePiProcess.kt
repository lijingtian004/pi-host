package works.earendil.pihost.core.engine

import works.earendil.pihost.core.process.ProcessHandle
import works.earendil.pihost.core.process.ProcessStarter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 回放模板（规格书 §5.4：形状取自 golden，不复刻全集，只写 P2C 需要的形状）。
 *
 * 来源原文（`core/rpc/src/test/resources/golden/`，实测行号）：
 * - 成功：`001-basic.jsonl:1` 的 `get_state` response 行（整行照抄，只把 id 换成占位符）；
 * - 失败：`001-basic.jsonl:25`（`quit` 的 `Unknown command` 形状）+ `003-errors-bash.jsonl:2`
 *   的字段布局，命令名改成本处用到的 `get_state`。
 *
 * `PiJsonIn.ignoreUnknownKeys = true`，所以 data 里的 model/cost 等字段全部照抄不影响解码。
 * `data.sessionId` 是 **pi 自己回读的会话 id**，与 host 生成并写进 `--session-id` 的 UUID 是两回事，
 * 保留原值正是为了不让测试误把响应里的 id 当成恢复依据（§12.1）。
 */
internal object PiWire {
    const val ID_PLACEHOLDER: String = "@@ID@@"

    val GET_STATE_OK: String =
        """{"id":"@@ID@@","type":"response","command":"get_state","success":true,"data":{"model":{"id":"mock-1","name":"Mock Model 1","api":"openai-completions","provider":"mock","baseUrl":"http://127.0.0.1:8787/v1","reasoning":false,"input":["text"],"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0},"contextWindow":128000,"maxTokens":16384},"thinkingLevel":"off","isStreaming":false,"isCompacting":false,"steeringMode":"one-at-a-time","followUpMode":"one-at-a-time","sessionId":"01a10e68-5c3c-72b4-98ac-70e0ce9a55d2","autoCompactionEnabled":true,"messageCount":0,"pendingMessageCount":0}}"""

    val GET_STATE_ERROR: String =
        """{"id":"@@ID@@","type":"response","command":"get_state","success":false,"error":"Unknown command: get_state"}"""

    fun ok(id: String): String = GET_STATE_OK.replace(ID_PLACEHOLDER, id) + "\n"

    fun error(id: String): String = GET_STATE_ERROR.replace(ID_PLACEHOLDER, id) + "\n"
}

/**
 * 会话脚本模式（规格书 §5.4 的四条 + 一条纯定时器路径）。
 *
 * - [HandshakeThenHang]：应答握手后挂着，直到优雅 close（stdin EOF→退 0）或 destroy（→137）；
 * - [HandshakeThenExit]：应答握手后立刻关全部管道 = 进程自然退出（崩溃重启路径）；
 * - [SilentDie]：构造即关管道退出（码 1），永不回应答；
 * - [HandshakeFail]：回 `success:false` 的 response 后挂着；
 * - [SilentHang]：活着但永不回应答，且**无视 stdin EOF**——专用于打「15s 虚拟超时 + destroy 兜底」。
 */
enum class PiMode { HandshakeThenHang, HandshakeThenExit, SilentDie, HandshakeFail, SilentHang }

/**
 * 测试用假 pi 进程（[ProcessHandle] 的进程内实现）。
 *
 * 与 `core/rpc` 测试里的 FakeProcess **无继承关系、零复制**（规格书 §3：跨模块借不到，
 * 这里只写 P2C 需要的形状）：
 * - stdin 用自绘 [OutputStream]（不用 PipedStream 读侧）：写侧按 LF 切行 →
 *   含 `get_state` 的行解析出 `"id"` → **同线程**把真实形状的 response 写进 stdout 管道；
 *   `close()` 即 EOF，除 [PiMode.SilentHang] 外都按 pi 的实际行为退 0（protocol.md §2）；
 * - stdout/stderr 走 `PipedInputStream`/`PipedOutputStream`，读侧（PiSession 的 readLoop/
 *   stderr drain）是另一个线程，管道在退出时关闭 → EOF；
 * - [destroy] 关管道并让 [waitFor] 返回 137（SIGKILL 惯用码）。
 */
internal class FakePiProcess(
    private val mode: PiMode = PiMode.HandshakeThenHang,
    stderrSeed: String? = null,
) : ProcessHandle {

    private val stdoutSource = PipedInputStream(PIPE_SIZE)
    private val stdoutSink = PipedOutputStream(stdoutSource)
    private val stderrSource = PipedInputStream(PIPE_SIZE)
    private val stderrSink = PipedOutputStream(stderrSource)
    private val stdinTap = StdinTap()

    override val stdin: OutputStream get() = stdinTap
    override val stdout: InputStream get() = stdoutSource
    override val stderr: InputStream get() = stderrSource

    /** 子进程收到的 stdin 行（不含 LF）。 */
    val stdinLines = CopyOnWriteArrayList<String>()

    @Volatile
    var stdinClosed = false
        private set

    @Volatile
    var destroyed = false
        private set

    private val exitClaimed = AtomicBoolean(false)

    @Volatile
    private var exitCode: Int? = null
    private val exitLatch = CountDownLatch(1)
    private val repliesSent = AtomicInteger(0)

    /** 已写出的握手应答条数（断言「一次 spawn 只答一次」用）。 */
    fun replyCount(): Int = repliesSent.get()

    init {
        stderrSeed?.let { writeStderrQuietly(it) }
        if (mode == PiMode.SilentDie) exit(EXIT_SILENT_DIE)
    }

    private fun writeStderrQuietly(text: String) {
        runCatching {
            stderrSink.write(text.toByteArray(Charsets.UTF_8))
            stderrSink.flush()
        }
    }

    /** 让「进程」退出（幂等：首次生效）：关两条输出管道 = 读侧 EOF。 */
    fun exit(code: Int) {
        if (exitClaimed.compareAndSet(false, true)) {
            // volatile 写在 countDown 之前：waitFor 的读者经 latch 一定看到这次赋值
            exitCode = code
            runCatching { stdoutSink.close() }
            runCatching { stderrSink.close() }
            exitLatch.countDown()
        }
    }

    override fun isAlive(): Boolean = exitCode == null

    override fun waitFor(): Int {
        exitLatch.await()
        return exitCode!!
    }

    override fun destroy() {
        destroyed = true
        exit(EXIT_SIGKILL)
    }

    /** 供 @After 清理：走一遍 EOF，再兜底置退出码，解除挂在管道/latch 上的线程。 */
    fun closeQuietly() {
        runCatching { stdinTap.close() }
        exit(EXIT_SIGKILL)
    }

    private inner class StdinTap : OutputStream() {
        private val pending = ByteArrayOutputStream()

        override fun write(byte: Int) = write(byteArrayOf(byte.toByte()), 0, 1)

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            var index = offset
            val end = offset + length
            while (index < end) {
                val byte = bytes[index]
                index++
                if (byte == LF_BYTE) {
                    deliver(pending.toByteArray())
                    pending.reset()
                } else {
                    pending.write(byte.toInt() and 0xFF)
                }
            }
        }

        override fun flush() = Unit

        override fun close() {
            if (pending.size() > 0) {
                deliver(pending.toByteArray())
                pending.reset()
            }
            stdinClosed = true
            // pi 的真实行为：stdin EOF → 优雅退出 0（protocol.md §2）。SilentHang 装死。
            if (mode != PiMode.SilentHang) exit(EXIT_GRACEFUL)
        }

        private fun deliver(bytes: ByteArray) {
            val line = String(bytes, Charsets.UTF_8)
            if (line.isEmpty()) return
            stdinLines.add(line)
            if (line.contains(GET_STATE_MARKER)) reply(line)
        }
    }

    private fun reply(stdinLine: String) {
        if (mode == PiMode.SilentDie || mode == PiMode.SilentHang) return
        val id = ID_IN_LINE.find(stdinLine)?.groupValues?.get(1) ?: return
        val payload = if (mode == PiMode.HandshakeFail) PiWire.error(id) else PiWire.ok(id)
        runCatching {
            stdoutSink.write(payload.toByteArray(Charsets.UTF_8))
            stdoutSink.flush()
            repliesSent.incrementAndGet()
        }
        if (mode == PiMode.HandshakeThenExit) {
            // 真实进程：应答写进管道之后才关 fd、才退出。这里留一个短窗口让读循环
            // 有机会先把应答取走（否则 Ready→崩溃 的定性会退化成握手失败）。
            kotlin.concurrent.thread(isDaemon = true, name = "fake-exit-after-reply") {
                Thread.sleep(EXIT_AFTER_REPLY_MS)
                exit(EXIT_GRACEFUL)
            }
        }
    }

    internal companion object {
        const val PIPE_SIZE: Int = 1 shl 16
        const val GET_STATE_MARKER: String = "get_state"
        val LF_BYTE: Byte = 0x0A
        const val EXIT_AFTER_REPLY_MS: Long = 30L
        const val EXIT_GRACEFUL: Int = 0
        const val EXIT_SILENT_DIE: Int = 1
        const val EXIT_SIGKILL: Int = 137
        val ID_IN_LINE: Regex = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
    }
}

/** 记名字的 [ProcessStarter]：按脚本顺序发假进程，超出脚本长度则重复最后一个模式。 */
internal class FakeStarter(
    private val modes: List<PiMode>,
    private val stderrSeed: String? = null,
) : ProcessStarter {

    private val invocationCounter = AtomicInteger(0)
    val commands = CopyOnWriteArrayList<List<String>>()
    val envs = CopyOnWriteArrayList<Map<String, String>>()
    val workingDirs = CopyOnWriteArrayList<String?>()
    val spawned = CopyOnWriteArrayList<FakePiProcess>()

    /**
     * P2F §4.4：每次 `start()` **被调用的那一瞬间** workingDir 是否已是目录
     * （null → false）。`SessionSupervisor` 的 spawn 前 mkdirs 只能这样证明——
     * 事后 `isDirectory` 会在「starter 没建但别人建了」上假绿，且看不到调用次序。
     */
    val workingDirExistedAtStart = CopyOnWriteArrayList<Boolean>()

    fun invocationCount(): Int = invocationCounter.get()

    override fun start(
        command: List<String>,
        env: Map<String, String>,
        workingDir: String?,
    ): ProcessHandle {
        val index = invocationCounter.getAndIncrement()
        commands.add(command)
        envs.add(env)
        workingDirs.add(workingDir)
        workingDirExistedAtStart.add(workingDir?.let { File(it).isDirectory } ?: false)
        val fake = FakePiProcess(modes[minOf(index, modes.lastIndex)], stderrSeed)
        spawned.add(fake)
        return fake
    }

    fun closeAll() = spawned.forEach { it.closeQuietly() }
}

/** 断言用：从 argv 里取 `--session-id` 的值。 */
internal fun sessionIdArg(command: List<String>): String? {
    val index = command.indexOf("--session-id")
    return if (index >= 0 && index + 1 < command.size) command[index + 1] else null
}
