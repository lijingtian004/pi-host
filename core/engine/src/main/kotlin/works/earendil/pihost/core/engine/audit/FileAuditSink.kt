package works.earendil.pihost.core.engine.audit

import android.util.Log
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import works.earendil.pihost.core.engine.settings.AgentConfigFiles

/**
 * 审计日志的落盘实现（规格书 P3A §3.1）：`audit-<yyyy-MM-dd>.jsonl`，**一行一条 [AuditEntry]**。
 *
 * ## 构造收 `dir: File` 而不是 Context（§3.1 原文「可测性关键」）
 * 于是 `FileAuditSinkTest` 整份跑在 [org.junit.rules.TemporaryFolder] 上，纯 JVM、不挂 Robolectric、
 * 不碰任何设备路径（P2F §7.1 的越界教训）。Context → dir 的那一跳住在 DI
 * （`EngineModule.fileAuditSink`），路径本身来自 [AuditPaths.auditDir]。
 *
 * ## 四条行为纪律
 * 1. **fire-and-forget**：[record] 同步返回，**不等磁盘**——它只往 [scope] 里塞一个协程。
 *    [scope] 由构造注入（生产 = `@AuditScope` 的 `Dispatchers.IO`），本类不自建 scope，
 *    否则「谁负责取消」这条线就断了；
 * 2. **Mutex 串行化**：一天一个文件、多协程并发追加。`File.appendBytes` 本身不保证行完整性
 *    （两次 append 之间没有原子性，短行会拼成一条畸形 JSON），[writeMutex] 才是「无交错」的根据。
 *    锁挂在实例上而不是文件上：跨天写入共用一条队列，代价可忽略，换来「同时只有一处打开审计文件」；
 * 3. **失败必吞**：任何 IO/序列化异常都只走 [logger]（默认 `Log.w`）。审计是旁路，
 *    磁盘满 / 目录被清 / 权限变了都只丢记录，**绝不影响对话主流程**（§2 红线）；
 * 4. **懒清理**：不装定时器、不开周期协程，每次追加后顺手扫一遍目录，删掉日期早于
 *    `本次写入那一天 - [retentionDays]` 的审计文件。认不出日期的文件名**不删**
 *    （见 [AuditPaths.dayFromFileName]）。
 *
 * ## 只截 preview，不截 args
 * §2 红线原文：「审计 args 原样记录（本地沙盒文件，审计目的即留痕）；result 只存 preview 截断 4096 字符」。
 * 所以 [clamp] 只管 [AuditEntry.resultPreview]。调用方（ChatViewModel）会先截一次，这里再截一次是
 * **纵深防御**：审计面不信任调用方守纪律。
 *
 * @param dir           审计根目录（= `AuditPaths.auditDir(ctx)`）
 * @param scope         写入协程的宿主 scope；已取消的 scope = 静默丢弃（不抛）
 * @param retentionDays 保留天数，默认 [AuditPaths.RETENTION_DAYS]（14）
 * @param logger        失败上报口；**默认实现内部还裹了一层 `runCatching`**——纯 JVM 单测里
 *                      `android.util.Log` 是「not mocked」的抛体，而本类的承诺是「绝不抛」，
 *                      上报这条最后一环同样不能成为抛出源。
 *
 * 构造参数（`File` / `CoroutineScope` / `Int` / 函数）没有一条是 Hilt 能自动给的，
 * 所以本类**不带** `@Inject constructor`：装配一律走 `EngineModule.fileAuditSink` 的 `@Provides`，
 * 单例作用域也由那条 `@Singleton` 承担（类上不另挂注解，免得看起来像两条独立绑定）。
 */
class FileAuditSink(
    private val dir: File,
    private val scope: CoroutineScope,
    private val retentionDays: Int = AuditPaths.RETENTION_DAYS,
    private val logger: (String, Throwable?) -> Unit = ::logWarn,
) : AuditSink {

    private val writeMutex = Mutex()

    /** 同步入口：只做「把这条塞进 [scope]」这一件事，立刻返回。 */
    override fun record(entry: AuditEntry) {
        // 外层 runCatching：scope 已取消时 launch 并不抛，但自定义 scope 实现万一抛，承诺仍然成立。
        runCatching {
            scope.launch {
                runCatching { writeLocked(entry) }
                    .onFailure { logger("审计落盘失败，本条丢弃：kind=${entry.kind} tool=${entry.toolName}", it) }
            }
        }.onFailure { logger("审计写入协程起不来，本条丢弃", it) }
    }

    /** 某天的那份文件（读侧与测试断言都用它，免得调用方自己拼文件名）。 */
    fun fileFor(day: LocalDate): File = AuditPaths.auditFile(dir, day)

    /** 今天那份文件。 */
    fun fileForToday(): File = fileFor(LocalDate.now())

    /**
     * 读回某天的记录：损坏行 / 空行**跳过**，好行原样返回（§3.1 测试第 ④ 条「坏行容忍」）。
     *
     * 读侧同样不抛：文件不存在 = 空表。审计文件是 append-only 的，
     * 「最后一行被写坏」是掉电时的常见形态，读的人不该因此整份日志不可用。
     */
    fun readEntries(day: LocalDate): List<AuditEntry> = readFile(fileFor(day))

    /** 全部审计文件按日期升序拼起来（诊断面用；损坏文件天然被 [readFile] 吃成空表）。 */
    fun readAll(): List<AuditEntry> = AuditPaths.auditFiles(dir).flatMap { readFile(it) }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private suspend fun writeLocked(entry: AuditEntry) = writeMutex.withLock {
        val clamped = clamp(entry)
        val day = AuditPaths.dayOf(clamped.ts)
        // 建目录失败就抛 IOException，由 record 的 runCatching 吞掉——「宁可不写这条，也不写去别处」
        AgentConfigFiles.ensureDirectory(dir)
        // 显式 serializer() + 手工换行（§3.1 原文的写入形态）：一行 = 一条 JSON，
        // 不靠便利 API，测试才能拿文件原文做断言。
        AuditPaths.auditFile(dir, day).appendLine(
            JSON.encodeToString(AuditEntry.serializer(), clamped) + "\n",
        )
        cleanup(day)
    }

    /**
     * 追加写一行（open+append+close，不做跨行缓冲复用：宁可慢，也不要留在半缓冲区里的尾行）。
     *
     * **必须是 `appendText`，不能是 `outputStream().use { write }`**：Kotlin stdlib 的
     * `File.outputStream(append: Boolean = false)` 默认**截断**，第一轮编译机实测就是这样
     * 把前一行洗掉的（`01`/`04`/`08`/`09`/`10` 五条同时红）。`appendText` 走的是
     * `FileOutputStream(this, true)`，与 `SpawnDiagnosticsSink` 的追加写法同一形态。
     */
    private fun File.appendLine(text: String) {
        appendText(text, Charsets.UTF_8)
    }

    /** 懒清理：只删「比这一天早 [retentionDays] 天以上」的审计文件。 */
    private fun cleanup(day: LocalDate) {
        val cutoff = day.minusDays(retentionDays.toLong())
        AuditPaths.auditFiles(dir).forEach { file ->
            val fileDay = AuditPaths.dayFromFileName(file.name) ?: return@forEach
            if (fileDay.isBefore(cutoff) && !file.delete()) {
                logger("审计旧文件删不掉，下次再试：${file.name}", null)
            }
        }
    }

    private fun clamp(entry: AuditEntry): AuditEntry {
        val preview = entry.resultPreview ?: return entry
        if (preview.length <= RESULT_PREVIEW_MAX) return entry
        return entry.copy(resultPreview = preview.substring(0, RESULT_PREVIEW_MAX))
    }

    companion object {
        /** `Log.w` 的 tag（与 `PiHost/` 前缀的其余日志同源）。 */
        const val TAG: String = "PiHost/Audit"

        /** [AuditEntry.resultPreview] 上限（§3.1 原文 4096 字符）。 */
        const val RESULT_PREVIEW_MAX: Int = 4_096

        /** 宽松读：未来给 [AuditEntry] 加字段不破旧文件的读侧。 */
        val JSON: Json = Json { ignoreUnknownKeys = true }

        /**
         * 逐行解析（[FileAuditSink.readEntries] 的静态形态：给定文件，不依赖实例）。
         *
         * 容忍规则：空行、纯空白行、JSON 不合法、缺必填字段 → **整行跳过**，不抛、不部分成功。
         */
        fun readFile(file: File): List<AuditEntry> {
            val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
            return text.lineSequence().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                runCatching { JSON.decodeFromString(AuditEntry.serializer(), line) }.getOrNull()
            }.toList()
        }
    }
}

/**
 * 默认失败上报：`Log.w` 裹一层 `runCatching`。
 *
 * 不是形式主义：`:core:engine` 的审计单测跑在**纯 JVM**（不挂 Robolectric），
 * 那种环境里 `android.util.Log` 的每个方法都是抛 `Method ... not mocked` 的桩。
 * 这里不兜住，「IO 失败不抛」那条用例就会在**上报失败的瞬间**破功——而它正是最该成立的场合。
 */
private const val LOG_TAG: String = FileAuditSink.TAG

private fun logWarn(message: String, throwable: Throwable?) {
    runCatching { if (throwable == null) Log.w(LOG_TAG, message) else Log.w(LOG_TAG, message, throwable) }
}
