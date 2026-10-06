package works.earendil.pihost.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import works.earendil.pihost.core.process.ProcessHandle
import works.earendil.pihost.core.process.ProcessStarter
import java.io.ByteArrayInputStream
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * ExecFallbackStarter（P2F §4.4 的 ①–⑤ 五条 + 三条边界）。
 *
 * 纯 JVM：不挂 Robolectric，假体只实现 `ProcessHandle` 的六个成员。
 * 每条用例都同时断言**降级决策**（delegate 收到什么 argv、被调几次）与**诊断行**（上报了什么），
 * 因为这个类的全部价值就是「失败时既选对路、又留下可复制的现场」。
 */
class ExecFallbackStarterTest {

    /**
     * 「不存在的 cwd」必须**每条用例独享且确定不存在**：`SessionSupervisorTest` 现在会在 spawn 前
     * mkdirs，构建机以 root 跑测试时真能把 `/data/user/0/...` 建出来（RECON-P2F §2.1 的 WORKING_DIR 常量），
     * 复用那个路径就会让本文件的 `(不在)` 断言随用例执行顺序翻脸。
     */
    @get:Rule
    val tmp = TemporaryFolder()

    // ── 假体 ────────────────────────────────────────────────────────────────────

    private class StubHandle(private val code: Int = 0) : ProcessHandle {
        override val stdin: OutputStream = ByteArrayOutputStream()
        override val stdout: InputStream = ByteArrayInputStream(EMPTY)
        override val stderr: InputStream = ByteArrayInputStream(EMPTY)
        override fun isAlive(): Boolean = false
        override fun waitFor(): Int = code
        override fun destroy() = Unit

        private companion object {
            val EMPTY: ByteArray = ByteArray(0)
        }
    }

    /** 按脚本第 n 次调用决定抛什么（超脚本 = 成功）；记录每次收到的 argv 与返回的句柄。 */
    private class ScriptedStarter(private val failures: List<Throwable?>) : ProcessStarter {
        val commands = CopyOnWriteArrayList<List<String>>()
        val workingDirs = CopyOnWriteArrayList<String?>()
        val handles = CopyOnWriteArrayList<ProcessHandle>()
        private val counter = AtomicInteger(0)

        override fun start(command: List<String>, env: Map<String, String>, workingDir: String?): ProcessHandle {
            val index = counter.getAndIncrement()
            commands.add(command)
            workingDirs.add(workingDir)
            failures.getOrNull(index)?.let { throw it }
            return StubHandle().also { handles.add(it) }
        }

        fun invocations(): Int = counter.get()
    }

    private fun eacces() = IOException(
        "Cannot run program \"/data/user/0/works.earendil.pihost/lib/libpi_node.so\": " +
            "error=13, Permission denied",
    )

    private fun enoent() = IOException(
        "Cannot run program \"/data/user/0/works.earendil.pihost/lib/libpi_node.so\": " +
            "error=2, No such file or directory",
    )

    private val argv = listOf("/data/user/0/works.earendil.pihost/lib/libpi_node.so", "/tmp/cli.js", "--mode", "rpc")

    /** 确定不存在的两层 cwd（父目录由 TemporaryFolder 建，子目录绝不建）。 */
    private val missingDir: File
        get() = File(tmp.root, "projects/default")

    // ── ① error=13 → 一次 linker64 降级，并返回 delegate 的成功产物 ────────────────

    @Test
    fun `01 errno 13 retries once through linker64 and returns the delegate handle`() {
        val delegate = ScriptedStarter(listOf(eacces(), null))
        val lines = CopyOnWriteArrayList<String>()
        val handle = ExecFallbackStarter(delegate) { lines.add(it) }
            .start(argv, emptyMap(), missingDir.path)

        assertNotNull("降级成功时必须拿到进程句柄", handle)
        assertEquals(2, delegate.invocations())
        assertEquals(listOf("/system/bin/linker64") + argv, delegate.commands[1])
        assertSame("必须把**重试那一次** delegate 的产物原样交上去", delegate.handles[0], handle)
        assertEquals(missingDir.path, delegate.workingDirs[1])
        assertEquals(ExecFallbackStarter.LINKER64, delegate.commands[1][0])
    }

    // ── ② error=2 → 立即重抛，delegate 只被调一次 ─────────────────────────────────

    @Test
    fun `02 errno 2 is rethrown immediately with a single delegate call`() {
        val original = enoent()
        val delegate = ScriptedStarter(listOf(original))
        val lines = CopyOnWriteArrayList<String>()

        val caught = runCatching {
            ExecFallbackStarter(delegate) { lines.add(it) }.start(argv, emptyMap(), null)
        }.exceptionOrNull() as IOException

        assertSame("errno=2 必须是原异常，不包装、不降级", original, caught)
        assertEquals(1, delegate.invocations())
        assertEquals(argv, delegate.commands[0])
        assertTrue(lines.any { it.contains("errno=2") })
        assertFalse("绝不能出现 linker64 降级", lines.any { it.contains("linker64") })
        assertFalse(delegate.commands.any { it.firstOrNull() == "/system/bin/linker64" })
    }

    // ── ③ 无 errno → 原样重抛 ────────────────────────────────────────────────────

    @Test
    fun `03 message without errno is rethrown untouched`() {
        val original = IOException("error launching process")
        val delegate = ScriptedStarter(listOf(original))
        val lines = CopyOnWriteArrayList<String>()

        val caught = runCatching {
            ExecFallbackStarter(delegate) { lines.add(it) }.start(argv, emptyMap(), null)
        }.exceptionOrNull() as IOException

        assertSame(original, caught)
        assertEquals(1, delegate.invocations())
        assertTrue(lines.any { it.contains("errno=无") })
    }

    // ── ④ 降级也失败 → 抛第二个异常，原始异常在 suppressed 里 ───────────────────────

    @Test
    fun `04 retry failure is thrown with the original exception suppressed`() {
        val first = eacces()
        val second = IOException("Cannot run program \"/system/bin/linker64\": error=2, No such file or directory")
        val delegate = ScriptedStarter(listOf(first, second))
        val lines = CopyOnWriteArrayList<String>()

        val caught = runCatching {
            ExecFallbackStarter(delegate) { lines.add(it) }.start(argv, emptyMap(), null)
        }.exceptionOrNull() as IOException

        assertSame("抛出的是**重试**的异常", second, caught)
        assertTrue("原始 EACCES 必须挂在 suppressed 里", caught.suppressed.any { it === first })
        assertEquals(2, delegate.invocations())
        assertTrue(lines.any { it.contains("降级也失败") })
    }

    // ── ⑤ 诊断行：尝试 + 失败 + 重试 全都上报 ──────────────────────────────────────

    @Test
    fun `05 diagnostics receive attempt failure and retry lines with full argv and exe states`() {
        val delegate = ScriptedStarter(listOf(eacces(), null))
        val lines = CopyOnWriteArrayList<String>()
        ExecFallbackStarter(delegate) { lines.add(it) }
            .start(argv, emptyMap(), missingDir.path)

        assertTrue("至少 4 行（尝试/失败/降级说明/降级尝试），实测 ${lines.size}", lines.size >= 4)
        val first = lines[0]
        assertTrue(first.contains("[spawn 尝试]"))
        assertTrue("完整 argv 一字不漏：$first", first.contains(argv.joinToString(" ")))
        assertTrue("workingDir 存在性：$first", first.contains("(不在)"))
        assertTrue("exe 四态齐全：$first", first.contains("exists=") && first.contains("length=") &&
            first.contains("canRead=") && first.contains("canExecute="))
        assertTrue("行首要时间戳：$first", first.matches(Regex("""\d{4}-\d\d-\d\d \d\d:\d\d:\d\d .*""")))
        assertTrue("失败行带 errno 与原文：${lines[1]}", lines[1].contains("errno=13") && lines[1].contains("Permission denied"))
        assertTrue("降级说明行：${lines[2]}", lines[2].contains("降级重试"))
        assertTrue("降级尝试行的 argv[0] 是 linker64：${lines[3]}",
            lines[3].contains("/system/bin/linker64 /data/user/0/works.earendil.pihost/lib/libpi_node.so"))
    }

    // ── ⑥ 成功路径：只上报尝试行，绝不出现失败/降级 ─────────────────────────────────

    @Test
    fun `06 successful spawn reports exactly one attempt line`() {
        val delegate = ScriptedStarter(emptyList())
        val lines = CopyOnWriteArrayList<String>()

        ExecFallbackStarter(delegate) { lines.add(it) }.start(argv, emptyMap(), null)

        assertEquals(1, delegate.invocations())
        assertEquals(listOf(argv), delegate.commands)
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("[spawn 尝试]"))
        assertFalse(lines[0].contains("errno"))
    }

    // ── ⑦ 非 IOException 不吞、不降级（保持真实报错上抛） ───────────────────────────

    @Test
    fun `07 non-io exception propagates without any retry`() {
        val boom = IllegalStateException("boom")
        val delegate = ScriptedStarter(listOf(boom))
        val lines = CopyOnWriteArrayList<String>()

        val caught = runCatching {
            ExecFallbackStarter(delegate) { lines.add(it) }.start(argv, emptyMap(), null)
        }.exceptionOrNull()

        assertSame(boom, caught)
        assertEquals(1, delegate.invocations())
        assertEquals(1, lines.size)
    }

    // ── ⑧ errnoOf 的提取形状（§3.3 正则） ────────────────────────────────────────

    @Test
    fun `08 errnoOf extracts the numeric errno from libcore messages`() {
        assertEquals(13, ExecFallbackStarter.errnoOf("Cannot run program \"x\": error=13, Permission denied"))
        assertEquals(2, ExecFallbackStarter.errnoOf("Cannot run program \"x\": error=2, No such file or directory"))
        assertEquals(13, ExecFallbackStarter.errnoOf("prefix error=13 suffix and error=99 more"))
        assertNull(ExecFallbackStarter.errnoOf("no digits here: error=X"))
        assertNull(ExecFallbackStarter.errnoOf(null))
        assertNull(ExecFallbackStarter.errnoOf(""))
        assertEquals(13, ExecFallbackStarter.ERRNO_EACCES)
    }

    // ── ⑨ workingDir 为 null 时诊断行不炸，且 null 原样透传 ─────────────────────────

    @Test
    fun `09 null workingDir is forwarded as null and rendered without crashing`() {
        val delegate = ScriptedStarter(emptyList())
        val lines = CopyOnWriteArrayList<String>()

        ExecFallbackStarter(delegate) { lines.add(it) }.start(argv, emptyMap(), null)

        assertNull(delegate.workingDirs[0])
        assertTrue("null cwd 要显式写出来，别让人误读成空字符串：${lines[0]}", lines[0].contains("workingDir=(null)"))
    }
}
