package works.earendil.pihost.core.engine.crash

import android.os.Build
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.io.Writer
import java.time.Instant
import java.time.format.DateTimeFormatter
import works.earendil.pihost.core.engine.settings.AgentConfigFiles

/**
 * 未捕获异常收集器（规格书 P3A §3.3）。
 *
 * 三条硬承诺（§2 红线，逐条有对应用例）：
 * 1. **同步落盘**：崩溃那一刻进程随时会被系统掐掉，任何「交给协程/后台线程去写」的写法
 *    都在赌调度——所以 [writeSync] 全程当前线程直写，不起协程、不进 DI、不碰 [kotlinx.coroutines]。
 * 2. **委托原 handler**：[install] 先把当时的
 *    [Thread.getDefaultUncaughtExceptionHandler] 存下来（Android 上是 `RuntimeInit` 的
 *    `KillApplicationHandler`，负责弹 ANR 对话框、写 logcat、把进程杀掉），
 *    [uncaughtException] 用 `try { write } finally { delegate }` 把它**原样叫回去**——
 *    我们只是插了一份证据到磁盘，绝不接管「杀死 App」这件事。
 *    不委托的后果：进程不再死（僵尸态）、logcat 里没有栈、系统 Crash 对话框不弹。
 * 3. **绝不因收集而二次崩溃**：写盘失败只 `Log.w`，异常不外溢；[uncaughtException] 的
 *    `finally` 保证委托一定发生。
 *
 * **不接 DI、不收 Context**：构造只吃 [dir] + [appVersion]（§3.3 原文），
 * 于是纯 JVM 单测可以把目录指向 [org.junit.rules.TemporaryFolder]（`:core:engine` 不挂 Robolectric），
 * 目录与版本号的来源留给装配面（`:app` 的 `CrashReportModule`）。
 *
 * @param dir 崩溃目录（`filesDir/crash`，见 [CrashPaths.crashDir]）；不存在时写入前 mkdirs。
 * @param appVersion 版本串，直接来自 `BuildConfig.VERSION_NAME`，只进正文首行。
 * @param deviceLine 设备行（厂商/型号/SDK）。可注入的原因与 `SpawnDiagnosticsSink` 的 `banner` 同一课：
 *   把 `android.os.Build` 的读取留在装配面，核心类保持纯 JVM 可测；默认实现 [defaultDeviceLine] 读 `Build`，
 *   在纯 JVM 环境里读到的只是桩值（字段访问，不是 `Stub!` 方法调用，所以不会抛）。
 * @param clock 时间源；单测用它造出确定的文件名与首行时间。
 */
class CrashReporter(
    private val dir: File,
    private val appVersion: String,
    private val deviceLine: () -> String = ::defaultDeviceLine,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: (String, Throwable?) -> Unit = ::logWarn,
) : Thread.UncaughtExceptionHandler {

    /**
     * install 之前那个 handler。
     *
     * `@Volatile`：`install()` 在主线程调，`uncaughtException` 可能在**任意**线程被执行
     * （崩溃线程恰恰是刚被创建的那条），那条线程必须看得见主线程刚写进去的引用。
     */
    @Volatile
    private var previous: Thread.UncaughtExceptionHandler? = null

    /** 是否已经装上（给装配面与测试判断「别装第二次」）。 */
    val installed: Boolean
        get() = Thread.getDefaultUncaughtExceptionHandler() === this

    /**
     * 接管默认 handler，并把原来那个留档。
     *
     * **幂等**：`install()` 被调两次时，第二次取到的 prev 就是 `this`——
     * 若照常赋值就成了 `this → this` 的自指委托，`uncaughtException` 里的
     * `write → write → write …` 直接栈溢出（`StackOverflowError` 本身又是一次崩溃）。
     * 所以碰到 `prev === this` 就原地返回，**保留第一次存下的真 prev**。
     *
     * prev 为 null 也照常安装（JVM 单测里默认 handler 常常就是 null）：
     * 「装上」与「委托」是两件事——委托走 `previous?.` 的安全调用，
     * 没有原 handler 就只落盘（§3.3 用例 ⑥：prev 为 null 时 install 仍然安全）。
     */
    fun install() {
        val prev = runCatching { Thread.getDefaultUncaughtExceptionHandler() }.getOrNull()
        if (prev === this) return
        previous = prev
        runCatching { Thread.setDefaultUncaughtExceptionHandler(this) }
            .onFailure { logger("崩溃 handler 装不上（安全策略禁改默认 handler？）", it) }
    }

    /**
     * 撤装：把默认 handler 还原成 [previous]（测试与「运行时切换」用；生产里一次进程只装一回）。
     *
     * 只在**当前默认 handler 仍是自己**时才还原——若期间别人又装了一层（LeakCanary 之类），
     * 还原会把别人那层抹掉。
     */
    fun uninstall() {
        if (Thread.getDefaultUncaughtExceptionHandler() !== this) return
        Thread.setDefaultUncaughtExceptionHandler(previous)
        previous = null
    }

    /**
     * 崩溃入口：`try { 同步写 } finally { 委托原 handler }`。
     *
     * `finally` 是这条设计的承重墙：写盘无论成功、失败、还是自己又抛一个异常，
     * 原 handler 都必须被叫到（否则 App 卡在垂死状态）。
     *
     * 两侧都套 `runCatching`：
     * - 写侧——[writeSync] 已经把 IO 失败吞了，这里兜的是「连上报失败的日志都抛」那种极端形态；
     * - 委托侧——原 handler 自己炸了不该由我们处理（JVM 有自己的兜底），
     *   但在 `finally` 里让它抛出去就等于把这次异常变成**新的**未捕获异常，
     *   在崩溃线程上会直接递归回本方法（栈溢出），所以必须吞。
     *
     * 委托对象只有 [install] 过才存在：没装就直接调本方法（单测形态）时 `previous` 为 null，
     * 于是「只落盘、不委托」。
     */
    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            runCatching { writeSync(t, e) }
        } finally {
            runCatching { previous?.uncaughtException(t, e) }
        }
    }

    /**
     * 同步写一份崩溃报告（当前线程，无协程）。
     *
     * 正文形态（一行头 + 栈）：
     * ```
     * time=2026-10-09T12:00:00Z epoch=1791… version=0.1.4 device=Google Pixel 9 (SDK 35) thread=main
     * java.lang.IllegalStateException: boom
     * 	at com.example.Foo.bar(Foo.kt:1)
     * 	... 12 more
     * ```
     * **失败不抛**（§2 红线）：目录建不出来、磁盘满、权限不对——都只 `Log.w` 后返回 false。
     * 这里抛出去等于「因为收集崩溃而再崩一次」，而 JVM 对 handler 内的异常不会再交给任何人。
     */
    fun writeSync(thread: Thread, throwable: Throwable): Boolean {
        val millis = runCatching { clock() }.getOrDefault(System.currentTimeMillis())
        val written = runCatching { writeBody(thread, throwable, millis) }
            .onFailure { logger("崩溃报告落盘失败（不影响进程退出）：dir=${dir.path}", it) }
            .isSuccess
        if (written) {
            // 份数上限当场生效：崩一次清一次，不留「等下次启动再删」的尾巴（那次启动可能根本来不了）。
            runCatching { cleanup() }
                .onFailure { logger("崩溃报告清理失败，下次再试", it) }
        }
        return written
    }

    /** 便捷形态：线程名取当前线程（装配面手动写报告时用，崩溃路径走 [writeSync] 的双参形态）。 */
    fun writeSync(throwable: Throwable): Boolean = writeSync(Thread.currentThread(), throwable)

    private fun writeBody(thread: Thread, throwable: Throwable, millis: Long) {
        AgentConfigFiles.ensureDirectory(dir)
        val file = CrashPaths.crashFile(dir, millis)
        // BufferedWriter + 显式 use：崩溃路径要「写完就 close」，不留半缓冲区里的尾行。
        // FileWriter 的默认构造即「截断新建」，正合「一次崩溃一个新文件」。
        val writer: Writer = BufferedWriter(FileWriter(file))
        try {
            writer.write(header(millis, thread))
            writer.write(LINE)
            writer.write(throwable.toString())
            writer.write(LINE)
            writer.write(traceText(throwable))
        } finally {
            runCatching { writer.close() }
                .onFailure { logger("崩溃报告 close 失败：${file.name}", it) }
        }
    }

    /** 正文首行（§3.3 原文：time / version / MANUFACTURER MODEL / SDK_INT / thread.name）。 */
    private fun header(millis: Long, thread: Thread): String {
        val iso = runCatching { DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis)) }
            .getOrDefault("unavailable")
        val device = runCatching { deviceLine() }.getOrDefault("unavailable")
        return "time=$iso epoch=$millis version=$appVersion device=$device thread=${thread.name}"
    }

    /**
     * 完整栈（含 `Caused by` 链）：`printStackTrace(PrintWriter)` 是唯一能把
     * 「递归 cause + 共享帧省略」原样吐出来的标准口，手写 cause 遍历会漏语义。
     *
     * `PrintWriter.use` 里 close 会 flush；`StringWriter` 不需要担心容量——
     * 崩溃栈的量级是 KB，而 [Log] 的单条上限（4KB）本来就不是这份文件的读者。
     */
    private fun traceText(throwable: Throwable): String {
        val buffer = StringWriter()
        PrintWriter(buffer).use { printable -> throwable.printStackTrace(printable) }
        return buffer.toString()
    }

    /**
     * 只保留最新 [keep] 份（§3.3 原文默认 10）。
     *
     * **排序键 = mtime**，同 mtime 再比文件名里的毫秒：规格书按「最近落盘的那些要留」定义保留集，
     * 而文件名毫秒与 mtime 在生产里必然同步（同一次 `clock()` 既进文件名又进正文）。
     * 分开的场景是 `adb pull` / 拷贝——那时 mtime 是「拷进来的时间」，
     * 按 mtime 保留反而符合「别删我刚放进来的」直觉。
     *
     * 认不出布局的文件不参与（[CrashPaths.isReport]），删不动的只记日志——清理永远不该抛。
     *
     * @return 实际删掉的份数。
     */
    fun cleanup(keep: Int = CrashPaths.DEFAULT_KEEP): Int {
        val target = keep.coerceAtLeast(0)
        val ordered = CrashPaths.reports(dir).sortedWith(
            compareByDescending<File> { it.lastModified() }.then(CrashPaths.NEWEST_FIRST),
        )
        if (ordered.size <= target) return 0
        var deleted = 0
        ordered.drop(target).forEach { file ->
            if (file.delete()) {
                deleted++
            } else {
                logger("崩溃旧文件删不掉，下次再试：${file.name}", null)
            }
        }
        return deleted
    }

    /**
     * 磁盘上的报告（新的在前）。§3.5 的「诊断」列表读它。
     *
     * 目录不存在 = 空表，不建目录：读侧不该有 mkdir 副作用。
     */
    fun pendingReports(): List<File> = CrashPaths.reports(dir)

    /** 删掉全部报告，返回删掉的份数（§3.5 的「清空」按钮）。失败只记日志，不抛。 */
    fun clearReports(): Int {
        var deleted = 0
        CrashPaths.reports(dir).forEach { file ->
            if (file.delete()) {
                deleted++
            } else {
                logger("崩溃报告删不掉：${file.name}", null)
            }
        }
        return deleted
    }

    /**
     * 最近一份**可读**的正文（§3.5 的「复制最近一份」）。
     *
     * 容忍形态：最新那份被掉电截成空文件 / 读不出来（`readText` 抛）→ 顺着列表往下一份试，
     * 全都不行才给 null。一次崩溃的正文不该被另一份坏文件遮住。
     */
    fun readLatest(): String? =
        CrashPaths.reports(dir)
            .asSequence()
            .mapNotNull { file -> runCatching { file.readText() }.getOrNull() }
            .firstOrNull { it.isNotBlank() }

    companion object {
        /** `Log.w` 的 tag（与 `PiHost/` 前缀的其余日志同源）。 */
        const val TAG: String = "PiHost/Crash"

        private const val LINE: String = "\n"
    }
}

/**
 * 设备行默认实现：厂商 + 型号 + SDK（§3.3 原文 `Build.MANUFACTURER Build.MODEL` / `SDK_INT`）。
 *
 * 抽成顶层函数是为了当构造默认参数用（companion 的限定名在默认参数里写起来更绕）。
 * 纯 JVM 环境（无 Robolectric）里 `Build.*` 是桩：字段是 null / 0，不抛——
 * 所以单测照样能跑，只是这行内容没有意义，测试自己注入 [deviceLine]。
 */
private fun defaultDeviceLine(): String =
    "${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})"

/**
 * 默认失败上报：`Log.w` 裹一层 `runCatching`。
 *
 * 与 `FileAuditSink` 的同名 helper 完全同一课（P3A §3.1 实测教训）：
 * 纯 JVM 单测里 `android.util.Log` 每个方法都抛 `Method ... not mocked`，
 * 「失败不抛」那条用例正是最该破功的场合——上报失败的瞬间不能再炸一次。
 */
private const val LOG_TAG: String = CrashReporter.TAG

private fun logWarn(message: String, throwable: Throwable?) {
    runCatching { if (throwable == null) Log.w(LOG_TAG, message) else Log.w(LOG_TAG, message, throwable) }
}
