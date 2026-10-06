package works.earendil.pihost.core.engine

import works.earendil.pihost.core.process.ProcessHandle
import works.earendil.pihost.core.process.ProcessStarter
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * spawn 降级链（P2F §4.1(b)）：真实 starter 外面套一层「诊断 + errno 分流」。
 *
 * 为什么需要它（P2F 规格书 §1/§3.4）：真机上 `libpi_node.so` 直接 `execve` 有两种失败形，
 * 而两种在 UI 上都长成一个「失败」——
 * - `error=2` ENOENT：exe 不存在 **或 cwd 不存在**（Android libcore 的 chdir 与 execve 共用同一条
 *   errno 管道，消息体里都只写 exe 路径）。cwd 那半已由 [SessionSupervisor] 的 mkdirs 修掉，
 *   这里剩下的 2 就是「exe 真不在」，重试毫无意义 → **原样上抛，换取真实报错**。
 * - `error=13` EACCES：SELinux 对 `apk_data_file` 标签的 execve 拒绝。bionic 的
 *   `/system/bin/linker64 <目标 ELF>` 是「链接器直接装载」调用形（Termux 同款兜底）：
 *   linker64 是系统文件、execve 必放行，它用 mmap 装载目标（apk_data_file 允许 execute-mmap），
 *   **跳过对目标文件的 execve 权限检查**。故只对 13 降级，且**只重试一次**。
 *
 * 诊断先行（P2F §1「让完整诊断一字不漏摆到用户眼前」的落盘那一半）：每次尝试前上报一行
 * 时间戳 + 完整 argv + workingDir 及其存在性 + `command[0]` 的 exists/length/canRead/canExecute；
 * 失败后再上报一行 errno 与原始消息。sink 由调用方给（生产接线见 `EngineModule.processStarter`）。
 *
 * **纯 JVM 约束**：只用 `java.io` / `java.time`，不得引任何 Android API —— 这样本类可以在
 * 普通 JVM 单元测试里被逐条驱动（`ExecFallbackStarterTest`），不必挂 Robolectric。
 *
 * 冻结区红线：[ProcessStarter] 是 `fun interface`（`core/process/.../ProcessHandle.kt:45`），
 * 本类只做实现，不改接口。
 */
class ExecFallbackStarter(
    private val delegate: ProcessStarter,
    private val diagnostics: (String) -> Unit,
) : ProcessStarter {

    /**
     * @throws IOException delegate 的原始异常（errno≠13 / 无 errno），或降级重试的异常
     * （errno=13 且重试也失败，此时原始异常挂在 `suppressed` 里）。
     */
    override fun start(
        command: List<String>,
        env: Map<String, String>,
        workingDir: String?,
    ): ProcessHandle {
        diagnostics(attemptLine("尝试", command, workingDir))

        var original: IOException? = null
        try {
            return delegate.start(command, env, workingDir)
        } catch (e: IOException) {
            val errno = errnoOf(e.message)
            diagnostics(
                "失败 | errno=${errno ?: "无"} | ${e.javaClass.simpleName}: ${e.message} | " +
                    "cause=${e.cause?.javaClass?.name ?: "-"}",
            )
            if (errno != ERRNO_EACCES) throw e
            original = e
        }

        // 只有 error=13 走到这里。linker64 装载 = 把原 argv 整体往后挪一位。
        val fallback = listOf(LINKER64) + command
        diagnostics("降级重试 | 改用 $LINKER64 装载（跳过对目标的 execve 权限检查）")
        diagnostics(attemptLine("尝试（降级）", fallback, workingDir))
        return try {
            delegate.start(fallback, env, workingDir)
        } catch (retry: IOException) {
            original?.let { retry.addSuppressed(it) }
            diagnostics("降级也失败 | ${retry.javaClass.simpleName}: ${retry.message}（原异常见 suppressed）")
            throw retry
        }
    }

    /** 一行尝试诊断：时间戳 + 标签 + 完整 argv + workingDir 存在性 + exe 四态。 */
    private fun attemptLine(tag: String, command: List<String>, workingDir: String?): String {
        val exe = command.firstOrNull()?.let(::File)
        return buildString {
            append(LocalDateTime.now().format(STAMP))
            append(" [spawn ").append(tag).append("] ")
            append("argv=").append(command.joinToString(" ")).append(" | ")
            append("workingDir=").append(workingDir ?: "(null)")
            append(if (workingDir == null) " " else if (File(workingDir).isDirectory) "(在) " else "(不在) ")
            append("exe=")
            if (exe == null) {
                append("(argv 为空)")
            } else {
                append(exe.absolutePath)
                append(" exists=").append(exe.exists())
                append(" length=").append(if (exe.exists()) exe.length() else -1L)
                append(" canRead=").append(exe.canRead())
                append(" canExecute=").append(exe.canExecute())
            }
        }
    }

    companion object {
        /** bionic 链接器的直接装载入口（系统文件，execve 必放行）。 */
        const val LINKER64: String = "/system/bin/linker64"

        /** `error=13` EACCES：唯一触发降级的 errno。 */
        const val ERRNO_EACCES: Int = 13

        /** libcore 的消息形：`Cannot run program "<exe>": error=N, <strerror>`。 */
        private val ERRNO_REGEX: Regex = Regex("""error=(\d+)""")

        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** 从异常消息里提 errno；没有就返回 null（P2F §3.3）。 */
        internal fun errnoOf(message: String?): Int? {
            val match = message?.let { ERRNO_REGEX.find(it) } ?: return null
            return match.groupValues.getOrNull(1)?.toIntOrNull()
        }
    }
}
