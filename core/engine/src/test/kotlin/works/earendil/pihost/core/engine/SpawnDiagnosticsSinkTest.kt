package works.earendil.pihost.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * SpawnDiagnosticsSink（P2F §4.1(c) 的落盘那一半）。
 *
 * 三条硬规定各有独立用例：**必写头部**、**>阈值截断重写**、**任何 IO 失败都不许把 spawn 带下水**。
 * 纯 JVM + [TemporaryFolder]，不挂 Robolectric（类本身不引 Android API，见其 KDoc）。
 */
class SpawnDiagnosticsSinkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val banner = listOf("abis=x86_64,arm64-v8a", "nativeLibraryDir=/data/app/lib")

    private fun sink(maxBytes: Long = SpawnDiagnosticsSink.DEFAULT_MAX_BYTES, file: File? = null): SpawnDiagnosticsSink =
        SpawnDiagnosticsSink(file ?: File(tmp.root, SpawnDiagnosticsSink.FILE_NAME), banner = { banner }, maxBytes = maxBytes)

    // ── ① 首写：目录自动建 + 头部 + 正文 ─────────────────────────────────────────

    @Test
    fun `01 first write creates the parent dir banner and the line`() {
        val target = File(tmp.root, "home/spawn-diag/spawn-diagnostics.log")
        assertFalse(target.exists())

        sink(file = target)("argv=/x/y/libpi_node.so /x/cli.js | workingDir=/tmp/nope(不在)")

        val text = target.readText()
        assertTrue("首行必须是头部标记：$text", text.startsWith("=== spawn-diagnostics "))
        assertTrue(text.contains("abis=x86_64,arm64-v8a"))
        assertTrue(text.contains("nativeLibraryDir=/data/app/lib"))
        assertTrue("正文原样落盘", text.contains("workingDir=/tmp/nope(不在)"))
        assertTrue("按行分隔", text.endsWith("\n"))
    }

    // ── ② 追加不重写头部 ─────────────────────────────────────────────────────────

    @Test
    fun `02 subsequent writes append without re-writing the banner`() {
        val s = sink()
        s("line-one")
        s("line-two")
        s("line-three")

        val text = s.readAll()
        assertEquals("头部只应出现一次", 1, Regex("=== spawn-diagnostics").findAll(text).count())
        assertEquals(1, text.lines().count { it == "abis=x86_64,arm64-v8a" })
        assertTrue(text.contains("line-one"))
        assertTrue(text.contains("line-two"))
        assertTrue("顺序=写入顺序", text.indexOf("line-two") < text.indexOf("line-three"))
    }

    // ── ③ 超阈值截断重写（只留最近一轮） ─────────────────────────────────────────

    @Test
    fun `03 exceeding the byte cap rewrites the file from the banner`() {
        // 阈值故意压到比「头部 + 一行」还小：任何第二次写入都必然触发轮转，
        // 不靠数字节（头部长度含时间戳，跟着墙上时钟变，硬编码会漂）。
        val s = sink(maxBytes = 8L)
        s("line-one")
        assertTrue("首写正常落地", s.readAll().contains("line-one"))

        s("line-two")

        val text = s.readAll()
        assertEquals("轮转后头部重新出现且只有一份", 1, Regex("=== spawn-diagnostics").findAll(text).count())
        assertFalse("旧行随截断消失", text.contains("line-one"))
        assertTrue("新行必须留下", text.contains("line-two"))
    }

    // ── ④ IO 失败静默：目标是个目录，绝不把异常漏给 spawn 主路径 ────────────────────

    @Test
    fun `04 io failure is swallowed and never reaches the caller`() {
        val directory = tmp.newFolder("i-am-a-directory")
        val s = SpawnDiagnosticsSink(directory, banner = { banner })

        val outcome = runCatching { s("this must not throw") }
        assertTrue("runCatching 全包：$outcome", outcome.isSuccess)
        assertEquals("读不出内容就当没有", "", s.readAll())
    }

    // ── ⑤ banner 自身抛异常也不能传染 ────────────────────────────────────────────

    @Test
    fun `05 a throwing banner is contained too`() {
        val s = SpawnDiagnosticsSink(File(tmp.root, "banner-boom.log"), banner = { error("boom") })
        assertTrue(runCatching { s("line") }.isSuccess)
    }

    // ── ⑥ 文件名与阈值常量（§4.1(c) 的定名不能漂） ────────────────────────────────

    @Test
    fun `06 the file name and byte cap match the spec`() {
        assertEquals("spawn-diagnostics.log", SpawnDiagnosticsSink.FILE_NAME)
        assertEquals(256L * 1024L, SpawnDiagnosticsSink.DEFAULT_MAX_BYTES)
    }
}
