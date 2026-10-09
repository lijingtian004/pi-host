package works.earendil.pihost.core.engine.audit

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 审计日志的目录布局（规格书 P3A §3.1）。
 *
 * **为什么不进 `PathLayout`**（§1.3 方向层已决）：`core/environment` 是冻结区
 * （`git diff 1f77127..HEAD` 在 `core/environment` 必须为空），而审计目录是 P3A 新增的产品化面。
 * 所以路径在本包内以**与 `PathLayout` 完全同风格**（object + const + fun）集中定义，
 * 全仓除本文件外不许再出现 `filesDir/audit` 字面量——这条纪律与 §7「禁止散落硬编码」是同一件事，
 * 只是承载文件不同。崩溃目录同理由同仓 `crash/CrashPaths.kt`（§3.3）自己定义。
 *
 * 目录形状：
 * ```
 * filesDir/
 * ├── home/                 # PathLayout.HOME_DIR_NAME（冻结区，已存在）
 * ├── runtime/              # PathLayout.RUNTIME_DIR_NAME（冻结区，已存在）
 * └── audit/                # ← 本 object 的唯一新增面
 *     ├── audit-2026-05-01.jsonl
 *     └── audit-2026-05-02.jsonl   # 每天一个，最多 [RETENTION_DAYS] 份
 * ```
 *
 * 落 `filesDir` 而不是 `cacheDir`：`cacheDir` 会被系统在空间不足时清理，
 * 审计留痕被静默擦掉等于没有审计；代价由 [RETENTION_DAYS] 的懒清理兜住。
 */
object AuditPaths {

    /** 审计根目录名（`filesDir/audit`）。 */
    const val AUDIT_DIR_NAME: String = "audit"

    /** 单日文件名前后缀：`audit-<yyyy-MM-dd>.jsonl`。 */
    const val FILE_PREFIX: String = "audit-"
    const val FILE_SUFFIX: String = ".jsonl"

    /** 只保留最近 14 天（规格书 §3.1 原文「懒清理只保留最近 14 天文件」）。 */
    const val RETENTION_DAYS: Int = 14

    /** 文件名里的日期段（ISO 日序，字典序 = 时间序，所以排序不需要 parse）。 */
    val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** 审计根目录（不存在也返回路径，创建由写入方负责——与 `PathLayout` 的「只构造不建目录」同语义）。 */
    fun auditDir(ctx: Context): File = File(ctx.filesDir, AUDIT_DIR_NAME)

    /** `File(auditDir(ctx), "audit-<day>.jsonl")`。 */
    fun auditFile(ctx: Context, day: LocalDate): File = auditFile(auditDir(ctx), day)

    /**
     * 同一个命名的第二形态：[FileAuditSink] 构造收的是 `dir: File` 而不是 Context（可测性关键，
     * 单测直接把目录指向 [org.junit.rules.TemporaryFolder]），所以文件名拼装必须能从「目录」出发，
     * 不能让 Context 出现在写入路径上。
     */
    fun auditFile(dir: File, day: LocalDate): File = File(dir, auditFileName(day))

    /** 单日文件名（不含目录）：清理与「按天分文件」断言都用它，避免两边各写一遍格式。 */
    fun auditFileName(day: LocalDate): String = FILE_PREFIX + day.format(DAY_FORMAT) + FILE_SUFFIX

    /** epoch millis → 本地日期（文件名用的那一天）。时区取设备默认，与 `Instant.ofEpochMilli` 一致。 */
    fun dayOf(ts: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()

    /**
     * 从文件名解析日期；不是审计文件（前缀/后缀不符、日期段损坏）一律 null。
     *
     * 「损坏名 → null」在清理里意味着**不删**：认不出的文件不属于本布局，
     * 宁可多留一天磁盘，也不能把用户手放的东西当垃圾扫掉。
     */
    fun dayFromFileName(name: String): LocalDate? {
        if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) return null
        val raw = name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX)
        return runCatching { LocalDate.parse(raw, DAY_FORMAT) }.getOrNull()
    }

    /** 目录下的审计文件（按日期升序；非审计文件与读不动的条目都过滤掉）。 */
    fun auditFiles(dir: File): List<File> =
        dir.listFiles()
            // 只认**普通文件**：一个碰巧叫 `audit-2026-05-01.jsonl` 的目录不是日志，
            // 让清理去 delete 它只会拿到 false 然后白刷一条日志（单测 06 就故意留了一个）。
            ?.filter { it.isFile }
            ?.mapNotNull { f -> dayFromFileName(f.name)?.let { it to f } }
            ?.sortedBy { it.first }
            ?.map { it.second }
            .orEmpty()
}
