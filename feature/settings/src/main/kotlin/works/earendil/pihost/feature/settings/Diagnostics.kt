package works.earendil.pihost.feature.settings

import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import works.earendil.pihost.core.engine.crash.CrashReporter

/**
 * 诊断面（规格书 P3A §3.5）：崩溃报告的「设置页那一层」读口。
 *
 * ## 为什么又包一层接口（照 [ModelCredentialSource] / [RelayEndpointSource] 的先例）
 * - **VM 只依赖抽象**：`SettingsViewModelTest` 用 [FakeDiagnosticsSource] 演三态（空/有/读不动），
 *   不需要真磁盘；而 `:core:engine` 那边已经把「同步落盘 + 委托 + 清理」测透了（`CrashReporterTest` 11 条）。
 * - **装配面只需要一条转发边**：`SettingsModule.diagnosticsSource(impl)`，与另两条同形状；
 *   被包的 [CrashReporter] 本体**不在这里提供**——它绑在 `:app` 的 `CrashReportModule`
 *   （只有 app 的 `BuildConfig` 才是版本号的真相，core 侧再写一份就是第二份真相）。
 *
 * ## 三条红线在这层的落点
 * 1. **不重复实现**：三个方法全部转发给 [CrashReporter]，本类**不碰 `File`、不拼路径、不排序**，
 *    目录布局只有一份（`CrashPaths`）；
 * 2. **调度在实现侧**：三个口都 `suspend`，[FileDiagnosticsSource] 统一 `withContext(Dispatchers.IO)`——
 *    与 [ModelCredentialSource] / [RelayEndpointSource] 同一条纪律（VM 不替实现做线程决定，
 *    也就不必有「先在 Main 上 listFiles 再切走」这种半吊子形态）；
 * 3. **正文只交给剪贴板，不上屏**：[latestText] 的返回值只被「复制最近一份」按钮取用，
 *    列表只显示 [CrashReportSummary]（文件名 + 字节数 + 时间），栈与绝对路径不进 LazyColumn。
 */
data class CrashReportSummary(
    val fileName: String,
    val sizeBytes: Long,
    val modified: Instant,
)

/** 设置页需要的三个读口（没有写口：崩溃报告的产生方式只有「崩一次」）。 */
interface DiagnosticsSource {

    /**
     * 磁盘上的崩溃报告，**新的在前**（顺序由 [CrashReporter] 决定，这层不重排）。
     *
     * `suspend` + 实现侧切 IO，与 [ModelCredentialSource.snapshot] 同一条纪律：
     * `listFiles` + `length` + `lastModified` 是三次真 syscall，不该在 Main 上跑
     * （StrictMode 会罚），而 VM 更不该替实现做调度决定。
     */
    suspend fun list(): List<CrashReportSummary>

    /** 最近一份**可读**的正文；一份都没有 / 全都读不动 → null。 */
    suspend fun latestText(): String?

    /** 删掉全部报告，返回实际删掉的份数。 */
    suspend fun clear(): Int
}

/** 生产实现：把 [DiagnosticsSource] 的三个口原样转发给 [CrashReporter]，线程切换在这里做完。 */
class FileDiagnosticsSource @Inject constructor(
    private val reporter: CrashReporter,
) : DiagnosticsSource {

    override suspend fun list(): List<CrashReportSummary> = withContext(Dispatchers.IO) {
        reporter.pendingReports().map { file ->
            CrashReportSummary(
                fileName = file.name,
                sizeBytes = file.length(),
                // mtime 为 0 = 文件系统没给时间（某些 tmpfs/拷贝形态）。
                // 这里不拿文件名里的毫秒兜底：那会让「时间」这一列在两种来源之间摇摆，
                // 而 EPOCH 至少是个**诚实的**「不知道」，UI 侧再决定怎么显示。
                modified = Instant.ofEpochMilli(file.lastModified()),
            )
        }
    }

    override suspend fun latestText(): String? = withContext(Dispatchers.IO) { reporter.readLatest() }

    override suspend fun clear(): Int = withContext(Dispatchers.IO) { reporter.clearReports() }
}
