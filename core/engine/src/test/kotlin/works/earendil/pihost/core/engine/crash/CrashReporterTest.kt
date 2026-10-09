package works.earendil.pihost.core.engine.crash

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `CrashReporter`（规格书 P3A §3.3 点名的 ①–⑥，逐条对号）：
 * ①→01 install 包装 default handler 并留档、②→02 直接调 handler 落盘（版本/线程名/栈首帧/cause 链）、
 * ③→03 IO 失败不抛且后续调用仍可用、④→01+04 委托（prev 被调用且 `finally` 保证）、
 * ⑤→05 cleanup 保留 keep 份、⑥→06 prev 为 null 时 install 仍安全；
 * 07 幂等（自指委托 = 栈溢出）、08 读侧（列表新在前 + 坏文件容忍）、09 清空、10 撞名让路、11 路径布局。
 *
 * ## 三条纪律
 * 1. **全程纯 JVM**：这批用例不挂 Robolectric，`deviceLine` 一律注入定值（[CrashReporter] 的设计动机之一），
 *    `android.util.Log` 的桩由注入的 `logger` 绕开；
 * 2. **不污染 JVM 全局**：`Thread.setDefaultUncaughtExceptionHandler` 是进程级状态，
 *    [restoreHandler] 在 `@After` 里原样还回去——否则同 JVM 的后续用例拿到本用例的 reporter，
 *    真崩一次时写的是早已销毁的 TemporaryFolder；
 * 3. **目录全在 [TemporaryFolder] 里**，不碰任何设备路径（P2F §7.1 的越界教训）。
 */
class CrashReporterTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private lateinit var dir: File
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        dir = File(tmp.root, "crash")
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun restoreHandler() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
    }

    // ── ① install 包装 default handler + 委托发生、且证据先落 ──────────────────

    @Test
    fun `01 install replaces the default handler and remembers the previous one`() {
        val order = mutableListOf<String>()
        // 探针：委托那一刻文件是否已在盘上——这才证得出「try 写 / finally 委托」的顺序；
        // 只在 uncaughtException 返回后数文件是证不出顺序的。
        val probe = RecordingHandler(order = order, probe = {
            order += if (CrashPaths.reports(dir).isNotEmpty()) "file-exists-at-delegate" else "no-file-at-delegate"
        })
        Thread.setDefaultUncaughtExceptionHandler(probe)
        val reporter = reporter(dir)

        reporter.install()

        assertSameThreadHandler(reporter)
        assertTrue("installed 旗必须跟着真实全局状态走", reporter.installed)

        val boom = IllegalStateException("delegation-check")
        reporter.uncaughtException(worker("main-ish"), boom)

        assertEquals("原 handler 必须被叫到恰好一次", 1, probe.calls.size)
        val received = probe.calls.single()
        assertEquals("main-ish", received.first.name)
        assertSame("交回原 handler 的必须是同一个异常对象", boom, received.second)
        assertEquals("证据先落盘，再委托", listOf("file-exists-at-delegate", "delegated"), order)
        assertEquals(1, CrashPaths.reports(dir).size)
    }

    // ── ② handler 被调 → 文件内容要素齐 ───────────────────────────────────────

    @Test
    fun `02 direct handler call writes version thread name and the full stack`() {
        val reporter = reporter(dir, appVersion = "0.1.4-test", clock = 0L)
        val thread = worker("crash-worker")

        // 带 cause 的异常：`printStackTrace(PrintWriter)` 会把 `Caused by` 链整条吐出来
        val cause = IllegalArgumentException("root-cause-value")
        val boom = IllegalStateException("boom-from-test", cause)

        reporter.uncaughtException(thread, boom)

        val reports = CrashPaths.reports(dir)
        assertEquals("恰好一份：${reports.map { it.name }}", 1, reports.size)
        val text = reports.single().readText()
        val header = text.lineSequence().first()

        assertTrue("首行必须带版本：$header", header.contains("version=0.1.4-test"))
        assertTrue("首行必须带线程名：$header", header.contains("thread=crash-worker"))
        assertTrue("首行必须带设备行（装配面注入）：$header", header.contains("device=TestVendor TestModel (SDK 34)"))
        assertTrue("首行必须带时间：$header", header.contains("time=1970-01-01T00:00:00Z"))
        assertTrue("首行必须带 epoch：$header", header.contains("epoch=0"))
        assertTrue("正文必须有异常 toString", text.contains("java.lang.IllegalStateException: boom-from-test"))
        // 栈首帧：真正的 `\tat ` 行且落在本测试类里（不是 toString 里那串文本）
        assertTrue(
            "必须有真实栈帧：\n$text",
            text.lineSequence().any { it.startsWith("\tat ") && it.contains("CrashReporterTest") },
        )
        assertTrue(
            "cause 链必须整条留住：\n$text",
            text.contains("Caused by: java.lang.IllegalArgumentException: root-cause-value"),
        )
        assertFalse("正文不许出现凭据形态的字面量", text.contains("sk-"))
    }

    // ── ③ IO 失败不抛且后续调用仍可用 ─────────────────────────────────────────

    @Test
    fun `03 io failure does not throw and the reporter stays usable afterwards`() {
        // 把目标目录占成一个**普通文件**：mkdirs 必然失败（同路径已存在非目录）
        dir.writeText("我不是目录")
        val logs = mutableListOf<String>()
        val reporter = CrashReporter(
            dir = dir,
            appVersion = "0.1.4",
            deviceLine = { "TestVendor TestModel (SDK 34)" },
            clock = { 0L },
            logger = { message, throwable -> logs.add(message + (throwable?.let { " | ${it::class.java.simpleName}" } ?: "")) },
        )

        val first = runCatching { reporter.uncaughtException(worker("io-fail"), IllegalStateException("first")) }

        assertTrue("写盘失败绝不能把异常交回 JVM：$first", first.isSuccess)
        assertFalse("失败时不该冒出目录", dir.isDirectory)
        assertEquals("失败必须留下一条日志：$logs", 1, logs.size)
        assertTrue("日志要指名落盘失败：$logs", logs.single().contains("崩溃报告落盘失败"))
        assertTrue("日志要带上原始异常类型：$logs", logs.single().contains("IOException"))

        // 「后续调用仍可用」：障碍清掉后**同一个实例**（同一个 dir 路径）照常工作，
        // 不留「失败过一次就废掉」的内部状态。
        assertTrue(dir.delete())
        assertTrue(dir.mkdirs())
        val second = runCatching { reporter.uncaughtException(worker("io-recover"), IllegalStateException("second")) }

        assertTrue("恢复后不该再抛：$second", second.isSuccess)
        val reports = CrashPaths.reports(dir)
        assertEquals("恢复后必须真的写出文件", 1, reports.size)
        assertTrue(reports.single().readText().contains("second"))
    }

    // ── ④ finally 保证委托（连日志器都炸的极端形态） ──────────────────────────

    @Test
    fun `04 delegation still happens when the collection path itself blows up`() {
        dir.writeText("我不是目录")
        val probe = RecordingHandler(order = mutableListOf())
        Thread.setDefaultUncaughtExceptionHandler(probe)
        val reporter = CrashReporter(
            dir = dir,
            appVersion = "0.1.4",
            // 全线坏：目录建不出来 → 记日志 → 日志器自己也抛（真实形态 = Log 桩之外的 ROM 限制）
            deviceLine = { throw IllegalStateException("设备行读取也炸") },
            clock = { throw IllegalStateException("时间源也炸") },
            logger = { _, _ -> throw RuntimeException("日志器自己炸了") },
        )
        // 必须先 install：委托目标就是 install 那一刻的默认 handler，
        // 没装过就直接调 handler 只落盘不委托（`previous` 还是 null）。
        reporter.install()

        val outcome = runCatching { reporter.uncaughtException(worker("hard-case"), IllegalStateException("boom")) }

        assertTrue("收集面全线坏掉时也不许把异常交回 JVM：${outcome.exceptionOrNull()}", outcome.isSuccess)
        assertEquals("`finally` 里的委托必须照样发生（否则 App 卡在垂死状态）", 1, probe.calls.size)
        assertEquals("hard-case", probe.calls.single().first.name)
        assertTrue("写盘失败时不该有半截文件", !dir.isDirectory)
    }

    // ── ⑤ cleanup 保留最新 keep 份 ────────────────────────────────────────────

    @Test
    fun `05 cleanup keeps only the newest reports`() {
        val base = 1_700_000_000_000L
        // 六份，时间戳递增 1s；mtime 与文件名毫秒同步（生产里同源，见 cleanup 的 KDoc）
        val written = (0 until 6).map { index ->
            val millis = base + index * 1_000L
            assertTrue(reporter(dir, clock = millis).writeSync(worker("w$index"), IllegalStateException("crash-$index")))
            val file = CrashPaths.reports(dir).first()
            assertTrue("mtime 要设上：${file.name}", file.setLastModified(millis))
            file
        }
        assertEquals("六份都在：${written.map { it.name }}", 6, CrashPaths.reports(dir).size)

        assertEquals("删三留三", 3, reporter(dir).cleanup(keep = 3))
        assertEquals(
            "留下的必须是最新的三份（新的在前）",
            written.takeLast(3).map { it.name }.reversed(),
            CrashPaths.reports(dir).map { it.name },
        )
        written.take(3).forEach { assertFalse("旧文件必须真的没了：$it", it.exists()) }
        assertEquals("已在保留集内 → no-op", 0, reporter(dir).cleanup(keep = 3))
        assertEquals("负数按 0 处理 = 全删，但不抛", 3, reporter(dir).cleanup(keep = -1))
        assertTrue(CrashPaths.reports(dir).isEmpty())
    }

    // ── ⑥ prev 为 null 时 install 仍安全 ─────────────────────────────────────

    @Test
    fun `06 install is safe when there is no previous handler`() {
        Thread.setDefaultUncaughtExceptionHandler(null)
        assertNull("前置：默认 handler 必须是空的", Thread.getDefaultUncaughtExceptionHandler())
        val reporter = reporter(dir)

        reporter.install()

        assertSameThreadHandler(reporter)
        // 没有原 handler 也要把证据落盘，委托走安全调用而不是 NPE
        reporter.uncaughtException(worker("no-prev"), IllegalStateException("orphan"))
        assertEquals(1, CrashPaths.reports(dir).size)
        assertTrue(CrashPaths.reports(dir).single().readText().contains("thread=no-prev"))
    }

    // ── ⑦ 幂等：install 两次不能自指委托 ──────────────────────────────────────

    @Test
    fun `07 second install does not create a self-referencing delegate chain`() {
        val probe = RecordingHandler(order = mutableListOf())
        Thread.setDefaultUncaughtExceptionHandler(probe)
        val reporter = reporter(dir)

        reporter.install()
        reporter.install()

        assertSameThreadHandler(reporter)
        reporter.uncaughtException(worker("twice"), IllegalStateException("boom"))
        // 自指链的形态是 write → write → … 栈溢出；这里必须是「一份文件 + 原 handler 恰好一次」
        assertEquals("只能写一份：${CrashPaths.reports(dir).map { it.name }}", 1, CrashPaths.reports(dir).size)
        assertEquals(1, probe.calls.size)
    }

    // ── ⑧ 读侧：列表新在前 + 坏文件容忍 ──────────────────────────────────────

    @Test
    fun `08 pendingReports are newest first and a broken newest does not hide the rest`() {
        assertTrue(reporter(dir, clock = 1_000L).writeSync(worker("a"), IllegalStateException("first-report-body")))
        assertTrue(reporter(dir, clock = 2_000L).writeSync(worker("b"), IllegalStateException("second-report-body")))
        // 混进来的非报告文件不参与列表（也不参与清理/清空）
        File(dir, "notes.txt").writeText("用户手放的文件")
        File(dir, "crash-latest.txt").writeText("布局外的同名文件")

        assertEquals(
            "只认布局内的报告，且新的在前",
            listOf("crash-2000.txt", "crash-1000.txt"),
            reporter(dir).pendingReports().map { it.name },
        )

        // 最新那份被截成空文件（掉电形态）→ readLatest 顺延到上一份
        val listed = reporter(dir).pendingReports()
        listed.first().writeText("")
        val latest = reporter(dir).readLatest()
        assertNotNull("坏文件不能把整份历史遮住", latest)
        assertTrue("应回落到完好那份：$latest", latest!!.contains("first-report-body"))

        // 全坏 → null
        listed.forEach { it.writeText("") }
        assertNull(reporter(dir).readLatest())
    }

    // ── ⑨ 清空 ────────────────────────────────────────────────────────────────

    @Test
    fun `09 clearReports deletes every report and tolerates a missing directory`() {
        reporter(dir, clock = 1_000L).writeSync(worker("a"), IllegalStateException("one"))
        reporter(dir, clock = 2_000L).writeSync(worker("b"), IllegalStateException("two"))
        File(dir, "notes.txt").writeText("不该被动")

        assertEquals(2, reporter(dir).clearReports())
        assertTrue("布局内文件全清", CrashPaths.reports(dir).isEmpty())
        assertTrue("布局外文件一个不动", File(dir, "notes.txt").exists())

        // 目录不存在 = 空表 + 零删除，且不 mkdir（读侧不该有副作用）
        val missing = File(tmp.root, "never-created")
        assertEquals(emptyList<File>(), reporter(missing).pendingReports())
        assertEquals(0, reporter(missing).clearReports())
        assertFalse("读侧不许建目录", missing.exists())
    }

    // ── ⑩ 同毫秒撞名让路 ──────────────────────────────────────────────────────

    @Test
    fun `10 two crashes in the same millisecond get two files instead of overwriting`() {
        val reporter = reporter(dir, clock = 5_000L)

        reporter.writeSync(worker("a"), IllegalStateException("first-at-5000"))
        reporter.writeSync(worker("b"), IllegalStateException("second-at-5000"))

        assertEquals(
            "覆盖写等于丢掉前一份证据；同毫秒时序号大的更新（新的在前）",
            listOf("crash-5000-1.txt", "crash-5000.txt"),
            CrashPaths.reports(dir).map { it.name },
        )
        val bodies = CrashPaths.reports(dir).map { it.readText() }
        assertTrue("两份内容都在：$bodies", bodies.any { it.contains("first-at-5000") })
        assertTrue("两份内容都在：$bodies", bodies.any { it.contains("second-at-5000") })
        assertTrue(
            "readLatest 必须给同毫秒里更晚那一份",
            reporter.readLatest()!!.contains("second-at-5000"),
        )
    }

    // ── ⑪ 路径布局 ────────────────────────────────────────────────────────────

    @Test
    fun `11 crash path helpers recognise only their own file names`() {
        assertEquals("crash", CrashPaths.CRASH_DIR_NAME)
        assertEquals("crash-1700.txt", CrashPaths.fileName(1_700L))
        assertEquals(File(dir, "crash-1700.txt"), CrashPaths.crashFile(dir, 1_700L))

        assertTrue(CrashPaths.isReport("crash-1700.txt"))
        assertTrue(CrashPaths.isReport("crash-1700-2.txt"))
        assertFalse("缺前缀", CrashPaths.isReport("audit-1700.txt"))
        assertFalse("缺后缀", CrashPaths.isReport("crash-1700.jsonl"))
        assertFalse("中间不是毫秒", CrashPaths.isReport("crash-latest.txt"))
        assertFalse("序号不是数字", CrashPaths.isReport("crash-1700-x.txt"))
        assertFalse("只有前后缀", CrashPaths.isReport("crash-.txt"))
        assertEquals(1_700L, CrashPaths.millisOf("crash-1700-2.txt"))
        assertEquals(0L, CrashPaths.millisOf("crash-latest.txt"))
        // 撞名序号：基准名 = -1，于是同毫秒时任何带序号的都排在它前面（它更旧）
        assertEquals(-1L, CrashPaths.indexOf("crash-1700.txt"))
        assertEquals(2L, CrashPaths.indexOf("crash-1700-2.txt"))
        assertEquals(10, CrashPaths.DEFAULT_KEEP)
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private fun reporter(
        target: File,
        appVersion: String = "0.1.4",
        clock: Long = 1_700_000_000_000L,
    ): CrashReporter = CrashReporter(
        dir = target,
        appVersion = appVersion,
        deviceLine = { "TestVendor TestModel (SDK 34)" },
        clock = { clock },
        // 纯 JVM 里 android.util.Log 是抛「not mocked」的桩，测试一律换成收集器
        logger = { _, _ -> Unit },
    )

    /** 只要名字、不起线程：崩溃线程名要进正文首行，而真起一条会崩的线程会拖垮测试 JVM。 */
    private fun worker(name: String): Thread = Thread(null, null, name, 0L)

    private fun assertSameThreadHandler(expected: Thread.UncaughtExceptionHandler) {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue("默认 handler 必须是本 reporter，实际=$current", current === expected)
    }

    /**
     * 假装「上一个 handler」：记收到的 thread/throwable，并在被调的瞬间跑一次探针
     * （探针用来证明**顺序**：委托发生时证据文件已经在盘上）。
     */
    private class RecordingHandler(
        val order: MutableList<String> = mutableListOf(),
        private val probe: (() -> Unit)? = null,
    ) : Thread.UncaughtExceptionHandler {
        val calls = mutableListOf<Pair<Thread, Throwable>>()

        override fun uncaughtException(t: Thread, e: Throwable) {
            calls += t to e
            probe?.invoke()
            order += "delegated"
        }
    }
}
