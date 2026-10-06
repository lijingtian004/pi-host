package works.earendil.pihost.core.engine

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * spawn 诊断落盘（P2F §4.1(c)）：把 [ExecFallbackStarter] 上报的行**追加**进
 * `File(homeDir, "spawn-diagnostics.log")`。
 *
 * 为什么落 homeDir 而不是 runtimeDir（P2F §9 方向层定案）：`runtimeDir` 会被安装器
 * 原子切换（`runtime.tmp` → `runtime`，旧目录挪成 `runtime.old`）清掉，`homeDir` 稳定
 * （`PathLayout.kt:34`），日志要能活过重装/升级。
 *
 * 三条硬规定，全部在 [invoke] 里：
 * 1. **runCatching 全包**：诊断是旁路，任何 IO 失败都不能影响 spawn 主路径
 *    （磁盘满、目录被清、并发写锁都只丢日志，不丢会话）；
 * 2. **首行头部**：文件不存在（或被截断重写）时先写头部 —— 一行 `=== spawn-diagnostics <时间戳> ===`
 *    外加 [banner] 提供的设备信息（生产接线给的是 `Build.SUPPORTED_ABIS` / nativeLibraryDir / filesDir，
 *    见 `EngineModule.processStarter`；argv/workingDir/exe 三态由 starter 的下一行给出，
 *    于是文件顶部天然凑齐「时间戳 / argv / workingDir / exe 三态 / ABI」）；
 * 3. **>256KB 截断重写**：诊断文件不能无界长（每条会话每次 spawn 都写行），
 *    超限就整文件重写为新头部，只保留最近一轮。
 *
 * 本类同样守「纯 JVM」约束：只碰 `java.io` / `java.time`，Android 侧的东西一律从构造参数喂进来，
 * 因此测试直接指向 [TemporaryFolder] 就能验，不需要 Robolectric。
 */
class SpawnDiagnosticsSink(
    private val file: File,
    private val banner: () -> List<String> = { emptyList() },
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : (String) -> Unit {

    override fun invoke(line: String) {
        runCatching {
            if (!file.exists() || file.length() > maxBytes) {
                file.parentFile?.mkdirs()
                file.writeText(
                    buildString {
                        append("=== spawn-diagnostics ").append(LocalDateTime.now().format(STAMP)).append(" ===")
                        banner().forEach { append("\n").append(it) }
                        append("\n")
                    },
                )
            }
            file.appendText(line + "\n")
        }
    }

    /** 当前落盘内容（测试断言用；读失败给空串，与「无诊断」同义）。 */
    fun readAll(): String = runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")

    companion object {
        /** 诊断文件名（P2F §4.1(c) 定名）。 */
        const val FILE_NAME: String = "spawn-diagnostics.log"

        /** 截断阈值（P2F §4.1(c)：>256KB 重写）。 */
        const val DEFAULT_MAX_BYTES: Long = 256L * 1024L

        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
