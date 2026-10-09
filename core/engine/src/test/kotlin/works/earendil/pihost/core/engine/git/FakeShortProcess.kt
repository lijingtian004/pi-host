package works.earendil.pihost.core.engine.git

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import works.earendil.pihost.core.process.ProcessHandle
import works.earendil.pihost.core.process.ProcessStarter

/**
 * 短命令假进程（P3C §3.5）。
 *
 * 与 `core/engine` 里的 [works.earendil.pihost.core.engine.FakePiProcess] **无关系**：那条假的是
 * 「长命的 node」——管道、握手应答、stdin EOF 语义；这里要的是「一跑就完」——
 * 内容固定、退出码固定、可选永不退出。两条形态共用一个假体只会让各自的断言互相牵制，
 * 所以分开写（同一模块的测试源集内，不是跨模块借）。
 *
 * `stdout`/`stderr` 用 `ByteArrayInputStream`：`available()` 天然返回剩余字节数，
 * 正好是 [ShortProcessRunner] 轮询循环依赖的那个语义（真实管道的 available 也是这个约定）。
 */
internal class FakeShortProcess(
    private val stdoutText: String,
    private val stderrText: String = "",
    private val exitCode: Int = 0,
    /** true → [isAlive] 永真、[waitFor] 永不被合法调用：用来测超时分支。 */
    private val neverExit: Boolean = false,
    private val stdoutStream: InputStream = ByteArrayInputStream(stdoutText.toByteArray(Charsets.UTF_8)),
    private val stderrStream: InputStream = ByteArrayInputStream(stderrText.toByteArray(Charsets.UTF_8)),
) : ProcessHandle {

    val destroyCount = AtomicInteger(0)
    private var exited = !neverExit

    override val stdin: OutputStream = nullOutputStream()
    override val stdout: InputStream get() = stdoutStream
    override val stderr: InputStream get() = stderrStream

    override fun isAlive(): Boolean = !exited

    override fun waitFor(): Int {
        exited = true
        return exitCode
    }

    override fun destroy() {
        destroyCount.incrementAndGet()
        exited = true
    }

    private companion object {
        fun nullOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) = Unit
        }
    }
}

/**
 * 记录调用、返回预设假进程的 starter。
 *
 * [failWith] 非空 → `start()` 抛它（测 `error=2` / `error=13` 两条 spawn 失败形状）；
 * [results] 按调用顺序给出假进程，用完之后重复最后一个（与 `FakeStarter.modes` 同一条约定）。
 */
internal class FakeCommandStarter(
    private val results: List<FakeShortProcess>,
    private val failWith: (() -> Throwable)? = null,
) : ProcessStarter {

    data class Call(
        val command: List<String>,
        val env: Map<String, String>,
        val workingDir: String?,
    )

    val calls = CopyOnWriteArrayList<Call>()
    val processes = CopyOnWriteArrayList<FakeShortProcess>()

    override fun start(command: List<String>, env: Map<String, String>, workingDir: String?): ProcessHandle {
        calls.add(Call(command, env, workingDir))
        failWith?.let { throw it() }
        val fake = results[minOf(calls.size - 1, results.lastIndex)]
        processes.add(fake)
        return fake
    }

    /** 断言用：第 n 次调用的 argv。 */
    fun commandAt(index: Int): List<String> = calls[index].command

    fun destroyTotal(): Int = processes.sumOf { it.destroyCount.get() }

    companion object {
        fun ioFailure(message: String): () -> Throwable = { IOException(message) }
    }
}
