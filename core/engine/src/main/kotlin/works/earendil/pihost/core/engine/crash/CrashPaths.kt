package works.earendil.pihost.core.engine.crash

import android.content.Context
import java.io.File

/**
 * 崩溃报告的目录布局（规格书 P3A §3.3）。
 *
 * **为什么不进 `PathLayout`**：与 [works.earendil.pihost.core.engine.audit.AuditPaths] 完全同一条理由——
 * `core/environment` 是冻结区（`git diff 1f77127..HEAD` 在 `core/environment` 必须为空），
 * 而崩溃目录是 P3A 新增的产品化面。所以路径在本文件里以**与 `PathLayout` 同风格**
 * （object + const + fun）集中定义；全仓除本文件外不许再出现 `filesDir/crash` 字面量。
 *
 * 目录形状：
 * ```
 * filesDir/
 * ├── home/                 # PathLayout.HOME_DIR_NAME（冻结区，已存在）
 * ├── runtime/              # PathLayout.RUNTIME_DIR_NAME（冻结区，已存在）
 * ├── audit/                # AuditPaths.AUDIT_DIR_NAME（P3A §3.1）
 * └── crash/                # ← 本 object 的唯一新增面
 *     ├── crash-1746057600000.txt
 *     └── crash-1746057611000.txt  # 一次崩溃一个文件，最多保留 [DEFAULT_KEEP] 份
 * ```
 *
 * **文件名用 epoch millis 而不是日期**（与审计的 `audit-<yyyy-MM-dd>.jsonl` 刻意不同）：
 * 崩溃是「一次一份」而非「一天一份」，毫秒时间戳天然单调、可字典序排序，
 * 而同一天的两次崩溃必须落在两个文件里。时间戳同时写进文件正文第一行，
 * 读的人不依赖 mtime（拷备份、`adb pull` 都会改 mtime）。
 *
 * 落 `filesDir` 而不是 `cacheDir`：崩溃报告是**唯一**能解释「上次为什么没了」的证据，
 * 放 `cacheDir` 等于交给系统的空间回收策略决定它活不活；代价由 [DEFAULT_KEEP] 的份数上限兜住。
 */
object CrashPaths {

    /** 崩溃根目录名（`filesDir/crash`）。 */
    const val CRASH_DIR_NAME: String = "crash"

    /** 文件名前后缀：`crash-<epochMillis>.txt`（撞名时补 `-<序号>`，见 [crashFile]）。 */
    const val FILE_PREFIX: String = "crash-"
    const val FILE_SUFFIX: String = ".txt"

    /** 默认保留份数（规格书 §3.3 原文「保留 10 份」）。 */
    const val DEFAULT_KEEP: Int = 10

    /** 崩溃目录（不存在也返回路径，创建由写入方负责——与 `PathLayout` 的「只构造不建目录」同语义）。 */
    fun crashDir(ctx: Context): File = File(ctx.filesDir, CRASH_DIR_NAME)

    /**
     * 某一次崩溃的目标文件：`File(dir, "crash-<millis>.txt")`。
     *
     * **撞名让路**：同一毫秒内崩两次（或 `install()` 之外的手写调用）是真实的——
     * 让路形态是 `crash-<millis>-1.txt`、`crash-<millis>-2.txt`……
     * 规格书给的命名是「一份崩溃一个文件」，覆盖写等于**丢掉前一份证据**，
     * 比多一个后缀更糟；序号仍在同一前后缀里，[isReport] / 清理 / 列表都照常识别。
     */
    fun crashFile(dir: File, millis: Long): File {
        val first = File(dir, fileName(millis))
        if (!first.exists()) return first
        var index = 1
        while (index <= MAX_COLLISION_SUFFIX) {
            val candidate = File(dir, "$FILE_PREFIX$millis-$index$FILE_SUFFIX")
            if (!candidate.exists()) return candidate
            index++
        }
        // 让路用尽（3600 次同毫秒写还不满）→ 回到覆盖第 0 份：崩溃写盘绝不该因为命名而抛。
        return first
    }

    /** 基准文件名（不含目录）。序号形态由 [crashFile] 现场拼，这里只给最干净的一档。 */
    fun fileName(millis: Long): String = FILE_PREFIX + millis + FILE_SUFFIX

    /**
     * 这个文件名是不是崩溃报告（前缀 + 后缀 + 中间是「毫秒」或「毫秒-序号」）。
     *
     * 认不出来的文件**不参与**列表与清理：跟审计目录同一课（[works.earendil.pihost.core.engine.audit.AuditPaths.dayFromFileName]）——
     * 用户手放进来的东西不能当垃圾扫掉。
     */
    fun isReport(name: String): Boolean {
        if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) return false
        val raw = name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX)
        val millisPart = raw.substringBefore('-')
        val indexPart = raw.substringAfter('-', "")
        if (millisPart.isEmpty() || millisPart.any { it !in '0'..'9' }) return false
        if (indexPart.isNotEmpty() && indexPart.any { it !in '0'..'9' }) return false
        return true
    }

    /**
     * 目录下的崩溃报告，**新的在前**（[NEWEST_FIRST]）。
     *
     * 排序键 = 文件名里的毫秒（不是 mtime，理由见文件头）；同毫秒撞名时**序号大的更新**
     * （`crash-<ts>-1.txt` 比基准名 `crash-<ts>.txt` 晚写一步），基准名排最后。
     */
    fun reports(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && isReport(it.name) }
            ?.sortedWith(NEWEST_FIRST)
            .orEmpty()

    /**
     * 「新 → 旧」比较器：先比文件名毫秒，再比撞名序号。
     *
     * 公开是给 [CrashReporter.cleanup] 当第二排序键用——保留集的定义是「最近的若干份」，
     * 同毫秒的两份里删错哪一份都是丢证据。
     */
    val NEWEST_FIRST: Comparator<File> = compareByDescending<File> { millisOf(it.name) }
        .thenByDescending { indexOf(it.name) }

    /** 文件名里的毫秒段（认不出来 → 0，只影响排序，不影响是否列出）。 */
    fun millisOf(name: String): Long {
        val raw = name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX).substringBefore('-')
        return raw.toLongOrNull() ?: 0L
    }

    /** 同毫秒撞名的序号；基准名 = `-1`，于是任何带序号的都排在它**前面**（它更旧）。 */
    fun indexOf(name: String): Long =
        name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX).substringAfter('-', "").toLongOrNull() ?: -1L

    /** 撞名让路的最大序号：给一天同毫秒写满 3600 次留位，再多就退回覆盖，绝不为命名抛。 */
    private const val MAX_COLLISION_SUFFIX: Int = 3_600
}
