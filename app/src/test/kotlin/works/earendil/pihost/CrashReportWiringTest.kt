package works.earendil.pihost

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import works.earendil.pihost.core.engine.crash.CrashPaths
import works.earendil.pihost.core.engine.crash.CrashReporter

/**
 * 崩溃收集的 app 侧接线（规格书 P3A §3.3 点名的两条 `:app` Robolectric 用例 + 两条加测）：
 * ①「Application onCreate 后 defaultHandler 非系统原样（已包装）」→ 11；
 * ②「pendingReports 反映文件」→ 13（14 顺带钉住「真目录 = `filesDir/crash`」）；
 * 12 走「真崩一次」的通路：装在图里的**同一个单例**上，直接调 handler，断言落盘 + 委托。
 *
 * ## 为什么这里**不用** Hilt 的测试设施（三条实测路线，前两条都撞墙）
 * 1. `@HiltAndroidTest` + `@Config(application = PiHostApp::class)` → `HiltAndroidRule` 构造期直接抛：
 *    `Hilt test ... cannot use a @HiltAndroidApp application but found works.earendil.pihost.PiHostApp`
 *    （`MarkThatRulesRanRule` 的硬校验：只接受 `HiltTestApplication` 或 `@CustomTestApplication` 生成的壳）；
 * 2. `@CustomTestApplication(PiHostApp::class)` → **KSP 期就拒绝**：
 *    `[Hilt] @CustomTestApplication value cannot be annotated with @HiltAndroidApp. Found: works.earendil.pihost.PiHostApp`
 *    （报告 §8 记这两条：Hilt 官方也不给「真 app + 假模块」的口子）；
 * 3. 于是取第三条：**纯 Robolectric**，`@Config(application = PiHostApp::class)`，
 *    让 `@HiltAndroidApp` 自己生成的 `PiHostApp_ComponentTree` 建**生产图**。
 *    这条路能走通的关键：`PiHostApp` 只注入 [CrashReporter] 一个字段，而它由
 *    [works.earendil.pihost.di.CrashReportModule] 提供（只要 `@ApplicationContext`）；
 *    `EngineModule` 那批要 `nativeLibraryDir` 的提供者全是 `@Provides` **惰性**工厂，
 *    没有注入点请求就一次都不执行（`MainActivityTest` 之所以要 `@UninstallModules`，
 *    是因为它起 Activity、经 `MainActivity` → VM 链把引擎面全要了一遍）。
 *
 * **为什么不测「真抛未捕获异常」**：Robolectric 自己的 handler 会把它记成测试失败，
 * 而「JVM 会不会调到我们」是 `Thread` 的既有语义，不是本仓代码。所以委托目标先换成
 * 本用例的记录器，再直接调 `uncaughtException`——测的是我们的包装层与 onCreate 的接线。
 *
 * **沙箱纪律**：`filesDir` 是 Robolectric 每沙箱唯一的临时目录，写进去不碰设备路径（P2F §7.1 的教训）；
 * `Thread` 的默认 handler 是进程级状态，[restoreHandler] 在 `@After` 还回去，
 * 免得同 JVM 的后续测试类拿到一个指向已销毁沙箱的 reporter。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PiHostApp::class)
class CrashReportWiringTest {

    private val app: PiHostApp
        get() = RuntimeEnvironment.getApplication() as PiHostApp

    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun captureHandler() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun restoreHandler() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
    }

    // ── 11 onCreate 之后默认 handler 已被包装 ─────────────────────────────────

    @Test
    fun `11 application onCreate wraps the default uncaught exception handler`() {
        val current = Thread.getDefaultUncaughtExceptionHandler()

        assertTrue(
            "onCreate 后默认 handler 必须是 CrashReporter（而不是系统原样），实际=$current",
            current is CrashReporter,
        )
        assertSame("装的必须是应用对象持有的那个字段（onCreate 用的就是它）", current, app.crashReporter)
        assertNotNull("字段注入必须已发生（lateinit 未填会抛）", app.crashReporter)
        assertTrue("installed 旗为真", app.crashReporter.installed)
    }

    // ── 12 走一次真 handler → 沙箱里落一份 + 委托回去 ─────────────────────────

    @Test
    fun `12 going through the installed handler writes into the sandbox crash dir`() {
        val reporter = app.crashReporter
        // 委托目标换成记录器：Robolectric 自己那层不该被我们用真异常喂（它会记成测试失败）
        val recorder = Recording()
        Thread.setDefaultUncaughtExceptionHandler(recorder)
        reporter.install() // 当时 handler 是 recorder（不是自己）→ 真重挂并留档 recorder
        assertSame(reporter, Thread.getDefaultUncaughtExceptionHandler())

        val thread = Thread(null, null, "ui-crash", 0L)
        val outcome = runCatching { reporter.uncaughtException(thread, IllegalStateException("wiring-boom")) }

        assertTrue("接线测试不该抛：$outcome", outcome.isSuccess)
        assertEquals("委托要交回留档的那层，恰好一次", 1, recorder.calls.size)
        assertEquals("ui-crash", recorder.calls.single().first.name)
        val reports = CrashPaths.reports(CrashPaths.crashDir(app))
        assertEquals("恰好一份：${reports.map { it.name }}", 1, reports.size)
        val text = reports.single().readText()
        assertTrue("线程名要上正文：$text", text.contains("thread=ui-crash"))
        assertTrue("正文要带上抛的那条异常：$text", text.contains("wiring-boom"))
        // §3.3 原文「DI 提供 BuildConfig.VERSION_NAME」：版本行只能来自 BuildConfig
        assertTrue("版本行必须是 BuildConfig.VERSION_NAME：$text", text.contains("version=${BuildConfig.VERSION_NAME}"))
    }

    // ── 13 pendingReports 反映磁盘上的文件 ────────────────────────────────────

    @Test
    fun `13 pendingReports reflects the files on disk newest first`() {
        val reporter = app.crashReporter
        val dir = CrashPaths.crashDir(app)
        assertTrue(reporter.writeSync(threadNamed("first"), IllegalStateException("body-one")))
        assertTrue(reporter.writeSync(threadNamed("second"), IllegalStateException("body-two")))
        // 混一个布局外的文件：列表不许把它算进去
        File(dir, "keepme.txt").writeText("不是崩溃报告")

        val listed = reporter.pendingReports()
        assertEquals("与磁盘布局内的报告一一对应", CrashPaths.reports(dir).size, listed.size)
        assertEquals(2, listed.size)
        assertTrue("都在 filesDir/crash 下：${listed.map { it.path }}", listed.all { it.parentFile == dir })
        assertTrue("只认崩溃报告布局", listed.all { CrashPaths.isReport(it.name) })
        val bodies = listed.map { it.readText() }
        assertTrue(
            "两份正文都在：$bodies",
            bodies.any { it.contains("body-one") } && bodies.any { it.contains("body-two") },
        )
        assertTrue("布局外的文件不许被列进来", File(dir, "keepme.txt").exists())
        assertTrue("读侧拿到的最新那份就是刚写的第二份", listed.first().readText().contains("body-two"))

        assertEquals(2, reporter.clearReports())
        assertEquals("清空后列表为空", emptyList<File>(), reporter.pendingReports())
        assertTrue("布局外的文件一个不动", File(dir, "keepme.txt").exists())
    }

    // ── 14 崩溃目录布局 = filesDir/crash（不进冻结的 PathLayout） ─────────────

    @Test
    fun `14 crash dir is filesDir crash and reads never create it`() {
        assertEquals(File(app.filesDir, "crash"), CrashPaths.crashDir(app))
        assertFalse(
            "崩溃目录不该落 cacheDir（会被系统空间回收）：${CrashPaths.crashDir(app).path}",
            CrashPaths.crashDir(app).path.contains("cache"),
        )
        // 读侧不许建目录（§3.5 进屏就要读一次，不能因为「看一眼」造出一个空目录）
        val fresh = File(app.filesDir, "crash-untouched")
        assertEquals(emptyList<File>(), CrashPaths.reports(fresh))
        assertFalse(fresh.exists())
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private fun threadNamed(name: String): Thread = Thread(null, null, name, 0L)

    /** 假装「上一个 handler」：只记收到的 thread/throwable。 */
    private class Recording : Thread.UncaughtExceptionHandler {
        val calls = mutableListOf<Pair<Thread, Throwable>>()
        override fun uncaughtException(t: Thread, e: Throwable) {
            calls += t to e
        }
    }
}
