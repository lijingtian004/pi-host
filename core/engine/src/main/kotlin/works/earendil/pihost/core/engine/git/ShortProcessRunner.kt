package works.earendil.pihost.core.engine.git

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import works.earendil.pihost.core.process.ProcessHandle
import works.earendil.pihost.core.process.ProcessStarter

/**
 * 跑一条**短命**命令并把 stdout/stderr 读回来（P3C §3.5 的底座）。
 *
 * ## 为什么全仓没有先例，以及为什么 P3C 必须先有它
 * `main` 源码里唯一的 `ProcessBuilder` 出现在 [works.earendil.pihost.core.process.JvmProcessFactory]，
 * 服务对象是那条长命的 node 进程（`PiSession` 四协程读写它）。P3C 要的是相反形态：
 * 一条 `git status` / `git init`，拿退出码和文本，进程随即消失。
 * 全仓 0 处先例，所以这层必须新写；而它是 §3.5 探针与 §3.4 `git init` **共用**的底座——
 * 两处各拼一遍 `ProcessBuilder` 就会有两份「超时/上限/管道死锁」的实现，那正是 P2H §3.1
 * 把三个 private 工具抽成 [works.earendil.pihost.core.engine.settings.AgentConfigFiles] 时避开的同一个错误。
 *
 * ## 冻结接口给的三样东西不够用（`core/process` 一字未动）
 * [ProcessHandle] 只有 `stdout/stderr/stdin/isAlive/waitFor/destroy`，于是：
 *
 * 1. **超时**：`waitFor()` 无时限，而它一挂就是整条 UI 路径（探针在 `viewModelScope` 里）。
 *    做法是不等 `waitFor`，改用**轮询循环**：每拍读走 `available()` 的字节，
 *    「进程已退**且**管道已空」判完成，超线就 [ProcessHandle.destroy]。
 *    destroy 会关掉对端管道，正在读的一侧随之拿到 EOF/异常——这是唯一能把阻塞中的读叫醒的手段。
 * 2. **stdout 管道满死锁**：子进程写满 64KB 管道后会阻塞在 write 上，父进程若还在
 *    等它退出就互锁。所以读 stdout 必须**边读边腾管道**，绝不攒到退出后再读。
 * 3. **stderr 独立泵**：本接口不合并流（`ProcessHandle` KDoc 明示由调用方 drain）。
 *    若在 stdout 读完之后才去读 stderr，git 在这期间写满 stderr 管道 → git 阻塞、我们阻塞，
 *    双向死锁。所以 stderr 必须**另起一条线程**同时泵（`PiSession` 对 node 的
 *    stderr 用的是同一招，那边是 8KB 环，这里截断到 [maxBytes]）。
 *
 * ## 为什么不读超过 [maxBytes] 就停
 * 停读会让子进程阻塞在 write 上（正是第 2 条）。所以超限之后**继续读、只丢弃**：
 * 我们要的只是「别把内存和 UI 撑爆」，不是「少读点字节省时间」。
 * 被丢弃这件事记在 [ShortResult.stdoutTruncated] 上，让调用方知道「这份输出不完整」，
 * 而不是拿着半截文本当全文解析。
 *
 * ## 失败一律进结果，不抛
 * [ProcessStarter.start] 抛 `IOException`（ENOENT=exe 或 cwd 不存在、EACCES=权限）是这里的正常分支：
 * P2F §4.1(a) 已经证明这两种 errno 在消息里长得一样。所以本类把它们收成
 * [ShortResult.SpawnFailed] 并**原样带上异常文本**——`error=13` 之类的分类是
 * [works.earendil.pihost.core.engine.ExecFallbackStarter] 的职责（生产装配里就在本类外面那一层），
 * 这里只负责不把它吞掉、也不把它变成一次崩溃。
 */
internal class ShortProcessRunner(
    private val starter: ProcessStarter,
    private val envFor: () -> Map<String, String>,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) {

    /**
     * 跑一条命令。[command] 是完整 argv；[workingDir] 给子进程 chdir。
     *
     * 空 argv / 空白 workingDir 一律 [ShortResult.InvalidRequest] 且**一次都不碰 starter**：
     * 把 `null` 塞进 `ProcessStarter` 会走 chdir 分支，`emptyList()` 会让 argv[0] 缺失，
     * 两种都得等真 spawn 才知道错，而调用方（VM）传错参数不该换来一条退避重启。
     */
    fun run(command: List<String>, workingDir: String?): ShortResult {
        if (command.isEmpty()) return ShortResult.InvalidRequest("argv 为空")
        if (workingDir.isNullOrBlank()) return ShortResult.InvalidRequest("workingDir 为空")

        val handle = try {
            starter.start(command, envFor(), workingDir)
        } catch (io: IOException) {
            return ShortResult.SpawnFailed(io.message ?: io.javaClass.simpleName)
        } catch (t: Throwable) {
            // 非 IO 的实现级异常（假数据、越界 argv 之类）：同样不许崩调用方，
            // 但保留类名，logcat 里能认出是哪一类
            return ShortResult.SpawnFailed("${t.javaClass.simpleName}: ${t.message}")
        }

        val stderrBuf = BoundedCapture(maxBytes)
        val stderrDone = AtomicBoolean(false)
        // 第 3 条纪律：stderr 另起线程泵。daemon + 独立命名，崩了好认、也不拖进程退出。
        val stderrThread = Thread({
            try {
                capture(handle.stderr, stderrBuf)
            } catch (_: IOException) {
                // destroy 之后读已关：这是超时的正常残局，不是新事实
            } finally {
                stderrDone.set(true)
            }
        }, "pi-host-short-stderr")
        stderrThread.isDaemon = true
        stderrThread.start()

        val stdoutBuf = BoundedCapture(maxBytes)
        var timedOut = false
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        try {
            // 第 1 条纪律：轮询而非 waitFor。`available()` 只报「现在不阻塞就能读到的字节数」，
            // 所以必须配合「进程已退」一起判：进程死了而管道还有字节时不能提前收工。
            while (true) {
                captureInto(handle.stdout, stdoutBuf)
                if (!handle.isAlive() && handle.stdout.available() == 0) break
                if (System.nanoTime() >= deadline) {
                    timedOut = true
                    handle.destroy()
                    break
                }
                sleepPoll()
            }
        } catch (io: IOException) {
            // 管道被 destroy 关掉：按超时处理（我们主动关的，没有别的事实要记）
            timedOut = true
            handle.destroy()
        }

        val exitCode = if (timedOut) null else runCatching { handle.waitFor() }.getOrNull()
        awaitStderr(stderrThread, stderrDone)
        return when {
            timedOut -> ShortResult.TimedOut(stdoutBuf.text(), stderrBuf.text())
            exitCode == null -> ShortResult.SpawnFailed("进程已退但取不到退出码")
            else -> ShortResult.Exited(
                exitCode = exitCode,
                stdout = stdoutBuf.text(),
                stderr = stderrBuf.text(),
                stdoutTruncated = stdoutBuf.truncated,
            )
        }
    }

    /** 把当前可读的字节读进 [buf]；超限后继续读、只丢（见类头第 2 条）。 */
    private fun captureInto(stream: InputStream, buf: BoundedCapture) {
        val chunk = ByteArray(CHUNK)
        while (true) {
            val ready = stream.available()
            if (ready <= 0) return
            val n = stream.read(chunk, 0, minOf(ready, chunk.size))
            if (n < 0) return
            buf.append(chunk, n)
        }
    }

    /** 阻塞读到 EOF —— 只给 stderr 那条线程用（子进程退了管道必然 EOF）。 */
    private fun capture(stream: InputStream, buf: BoundedCapture) {
        val chunk = ByteArray(CHUNK)
        while (true) {
            val n = stream.read(chunk)
            if (n < 0) return
            buf.append(chunk, n)
        }
    }

    /**
     * 等 stderr 线程收尾，最多 [STDERR_JOIN_MS]。
     *
     * 不等不行（拿到半截 stderr 就返回是错的），死等更不行（那条线程可能卡在
     * 一个不肯 EOF 的管道上——destroy 对已经退出但被继承的 fd 无效）。
     * 等不到就带着已有内容返回：stderr 在本仓只用于**分类**（not a repo / index.lock），
     * 分类不出来时降级成 Unavailable，比整条 UI 卡住好。
     */
    private fun awaitStderr(thread: Thread, done: AtomicBoolean) {
        val until = System.nanoTime() + STDERR_JOIN_MS * 1_000_000L
        while (!done.get() && System.nanoTime() < until) {
            if (!runCatching { thread.join(JOIN_SLICE_MS) }.isSuccess) return
        }
        if (!done.get()) thread.interrupt()
    }

    private fun sleepPoll() {
        runCatching { Thread.sleep(POLL_MS) }
    }

    internal companion object {
        /** 3s：git 在真机上 status 一条小仓库是几十毫秒量级，超线就是出问题，不该再等。 */
        const val DEFAULT_TIMEOUT_MS: Long = 3_000L

        /**
         * 64K **字符**上限（`StringBuilder.length` 计数，不是字节）。porcelain 行以 ASCII 为主，
         * 与 64KB 同量级；够约 1000 个改动文件。超限后仍继续读、只丢弃，见类头第 2 条。
         */
        const val DEFAULT_MAX_BYTES: Int = 64 * 1024

        private const val CHUNK = 8 * 1024
        private const val POLL_MS = 5L
        private const val JOIN_SLICE_MS = 20L
        private const val STDERR_JOIN_MS = 200L
    }
}

/** 一次短命令的结果。四条分支都是正常业务形态，没有一条该以异常收场。 */
internal sealed interface ShortResult {

    /** 正常退出。[stdoutTruncated] 为 true 时 [stdout] 是被截断的，调用方不得当全文解析。 */
    data class Exited(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val stdoutTruncated: Boolean,
    ) : ShortResult

    /** 超线并已 destroy。[stdout]/[stderr] 是截止那一刻已读到的部分。 */
    data class TimedOut(val stdout: String, val stderr: String) : ShortResult

    /** 根本没跑起来。[cause] 原样带 `error=N` 之类的消息文本，供上层分类。 */
    data class SpawnFailed(val cause: String) : ShortResult

    /** 调用方参数就不对（空 argv / 空 cwd），一次 spawn 都不该尝试。 */
    data class InvalidRequest(val cause: String) : ShortResult
}

/** 带上限的字节收集器：多线程写（stdout 在调用线程、stderr 在泵线程），所以整体加锁。 */
private class BoundedCapture(private val maxBytes: Int) {
    private val buffer = StringBuilder()
    var truncated: Boolean = false
        private set

    fun append(chunk: ByteArray, length: Int) {
        synchronized(this) {
            if (buffer.length >= maxBytes) {
                truncated = true
                return
            }
            val text = String(chunk, 0, length, Charsets.UTF_8)
            val room = maxBytes - buffer.length
            if (text.length > room) {
                buffer.append(text, 0, room)
                truncated = true
            } else {
                buffer.append(text)
            }
        }
    }

    fun text(): String = synchronized(this) { buffer.toString() }
}
