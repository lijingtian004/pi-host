package works.earendil.pihost.core.process

import java.io.InputStream
import java.io.OutputStream

/**
 * 子进程抽象（方案 §2：进程操作全部经此接口，测试注入 [FakeProcess]）。
 *
 * P1 桌面 JVM 实现见 [JvmProcess]；P2 Android 侧由 Runtime.exec 包装同一接口。
 * 设计基准 = java.lang.Process 的能力子集：写字节流 stdin、读字节流 stdout、
 * 独立读 stderr、查活/等待/强杀。
 *
 * 语义约定（与方案 §3.3/§3.4 对齐）：
 * - [destroy] 在 Android/Linux 上即 SIGKILL——没有 SIGTERM 通路，所以
 *   「优雅退出」必须走 RPC 层 quit 并等自然退出，超时才 destroy；
 * - [stderr] 由调用方单独 drain（PiSession 用 8KB 环形缓冲），本接口不合并流。
 */
interface ProcessHandle {

    /** 进程标准输入（写侧）。调用方保证单 writer（PiSession 的 outbox 协程）。 */
    val stdin: OutputStream

    /** 进程标准输出（读侧）。 */
    val stdout: InputStream

    /** 进程标准错误（读侧）。独立 drain，不并入事件流（方案 §3.4）。 */
    val stderr: InputStream

    /** 进程是否仍在运行。 */
    fun isAlive(): Boolean

    /** 阻塞等待退出并返回退出码；已退出时立即返回。 */
    fun waitFor(): Int

    /**
     * 强制终止。跨平台信号语义**不承诺一致**：Android 上即 SIGKILL（无 SIGTERM
     * 通路）；桌面 JVM 上为 SIGTERM。无论哪个平台，优雅退出都走 RPC quit 等待
     * 自然退出，destroy 只是超时后的最后手段（方案 §3.3）。
     * 幂等：对已退出进程调用无副作用。
     */
    fun destroy()
}

/** 进程启动器抽象：测试注入 Fake，生产注入 [JvmProcessFactory]。 */
fun interface ProcessStarter {
    fun start(command: List<String>, env: Map<String, String>, workingDir: String?): ProcessHandle
}

/** 桌面 JVM 默认实现：ProcessBuilder 包装。 */
object JvmProcessFactory : ProcessStarter {
    override fun start(command: List<String>, env: Map<String, String>, workingDir: String?): ProcessHandle {
        val pb = ProcessBuilder(command)
        pb.environment().putAll(env)
        if (workingDir != null) pb.directory(java.io.File(workingDir))
        // stderr 不重定向到 stdout（保持独立 drain 通路）；仅当调用方需要合并时才用 redirectErrorStream
        return JvmProcess(pb.start())
    }
}

/** java.lang.Process 的 ProcessHandle 包装。 */
class JvmProcess(private val process: Process) : ProcessHandle {
    override val stdin: OutputStream get() = process.outputStream
    override val stdout: InputStream get() = process.inputStream
    override val stderr: InputStream get() = process.errorStream
    override fun isAlive(): Boolean = process.isAlive
    override fun waitFor(): Int = process.waitFor()
    override fun destroy() = process.destroy()
}
