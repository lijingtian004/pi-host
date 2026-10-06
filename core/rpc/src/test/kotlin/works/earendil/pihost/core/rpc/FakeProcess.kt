package works.earendil.pihost.core.rpc

import works.earendil.pihost.core.process.ProcessHandle
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * 测试用假进程：管道三通路 + 受控退出。
 *
 * - session 写 stdin → [receivedStdinLines] 记录（按 LF 分行）；
 *   EOF → [stdinClosed] = true；[autoExitOnStdinClose] 时模拟 pi 行为退 0
 *   （protocol.md §2：stdin EOF → exit 0）。
 * - 测试经 [stdoutWriter]/[stderrWriter] 注入子进程输出，close 即 EOF。
 * - [exit] 显式置退出码；[destroy] 记录并以 137（SIGKILL 惯用码）退出。
 */
class FakeProcess(
    autoExitOnStdinClose: Boolean = false,
) : ProcessHandle {

    private val stdinSink = PipedOutputStream()
    private val stdinSource = PipedInputStream(stdinSink, PIPE_SIZE)
    override val stdin: OutputStream get() = stdinSink

    private val stdoutSource = PipedInputStream(PIPE_SIZE)
    val stdoutWriter = PipedOutputStream(stdoutSource)
    override val stdout: InputStream get() = stdoutSource

    private val stderrSource = PipedInputStream(PIPE_SIZE)
    val stderrWriter = PipedOutputStream(stderrSource)
    override val stderr: InputStream get() = stderrSource

    val receivedStdinLines = CopyOnWriteArrayList<String>()

    @Volatile
    var stdinClosed = false
        private set

    @Volatile
    var destroyed = false
        private set

    @Volatile
    private var exitCode: Int? = null
    private val exitLatch = CountDownLatch(1)

    init {
        thread(isDaemon = true, name = "fake-stdin-watcher") {
            val reader = BufferedReader(InputStreamReader(stdinSource, Charsets.UTF_8))
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotEmpty()) receivedStdinLines.add(line)
                }
            } catch (_: IOException) {
                // 管道断裂视同 EOF
            }
            stdinClosed = true
            if (autoExitOnStdinClose) exit(0)
        }
    }

    /** 显式让「进程」退出（幂等：首次生效）。 */
    fun exit(code: Int) {
        if (exitCode == null) {
            exitCode = code
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
        exit(137)
    }

    companion object {
        private const val PIPE_SIZE = 1 shl 16
    }
}
