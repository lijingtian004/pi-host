package works.earendil.pihost.core.rpc

import java.nio.charset.StandardCharsets
import java.util.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * LineFramer 属性测试（方案 §11）：任意切包不丢字节、U+2028/2029 不误切、
 * 多字节字符跨缓冲、空行、>64KB 超长行。
 */
class LineFramerTest {

    private fun frame(vararg inputs: String): List<String> {
        val out = ArrayList<String>()
        val framer = LineFramer { out.add(it) }
        for (s in inputs) {
            val bytes = s.toByteArray(StandardCharsets.UTF_8)
            framer.feed(bytes, bytes.size)
        }
        framer.close()
        return out
    }

    /** 收集回调并返回一个「喂一个字节退一格」的驱动器，用于随机切包。 */
    private class Collector {
        val lines = ArrayList<String>()
        val framer = LineFramer { lines.add(it) }
    }

    @Test
    fun `basic multi line`() {
        assertEquals(
            listOf("""{"type":"a"}""", """{"type":"b"}"""),
            frame("""{"type":"a"}""" + "\n" + """{"type":"b"}""" + "\n"),
        )
    }

    @Test
    fun `empty lines are preserved as empty frames`() {
        // 空行是合法帧（连续 \n\n），不能丢弃：上层 JSON 解析会报错，
        // 但分帧层必须如实交付，否则字节流静默错位
        assertEquals(listOf("", "", "x"), frame("\n\nx\n"))
    }

    @Test
    fun `trailing CR is stripped`() {
        // pi emitLine 对行尾 \r 剥离，宿主对齐
        assertEquals(listOf("a", "b"), frame("a\r\nb\r\n"))
    }

    @Test
    fun `lone CR inside line is kept`() {
        // 行中间的 \r 不是帧边界
        assertEquals(listOf("a\rb"), frame("a\rb\n"))
    }

    @Test
    fun `U+2028 U+2029 are not line breaks`() {
        // JS readline 的陷阱字符：JSON 字符串内合法
        val s = "a\u2028b\u2029c"
        assertEquals(listOf(s), frame(s + "\n"))
    }

    @Test
    fun `multibyte char split across feeds`() {
        // "你" = E4 BD A0，拆成 3 个单字节包喂入
        val s = "你"
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        assertEquals(3, bytes.size)
        val out = ArrayList<String>()
        val framer = LineFramer { out.add(it) }
        framer.feed(byteArrayOf(bytes[0]), 1)
        framer.feed(byteArrayOf(bytes[1]), 1)
        framer.feed(byteArrayOf(bytes[2]), 1)
        framer.feed("\n".toByteArray(), 1)
        assertEquals(listOf(s), out)
    }

    @Test
    fun `no trailing LF flushed on close`() {
        // pi 退出时最后一帧可能无 LF（jsonl.ts onEnd）
        val out = ArrayList<String>()
        val framer = LineFramer { out.add(it) }
        framer.feed("""{"last":1}""".toByteArray())
        framer.close()
        assertEquals(listOf("""{"last":1}"""), out)
    }

    @Test
    fun `oversized line over 64KB`() {
        // 工具大输出真实存在：单行 JSONL 可超过 64KB
        val payload = "x".repeat(300_000)
        val out = ArrayList<String>()
        val framer = LineFramer { out.add(it) }
        // 分两半喂入，第二半带结尾 LF
        val full = (payload + "\n").toByteArray()
        val mid = full.size / 2
        framer.feed(full, mid)
        framer.feed(full.copyOfRange(mid, full.size))
        assertEquals(listOf(payload), out)
    }

    @Test
    fun `random packet splits never lose bytes`() {
        // 属性核心：任意行序列 + 任意 1 字节粒度切包 == 整体喂入
        val rng = Random(20241208L)
        repeat(200) { round ->
            val lines = (0 until rng.nextInt(1, 12)).map {
                val len = rng.nextInt(0, 60)
                val sb = StringBuilder(len)
                repeat(len) {
                    // 混入 ASCII、多字节 CJK、U+2028/2029、引号、反斜杠、CR
                    when (val p = rng.nextInt(10)) {
                        in 0..3 -> sb.append(('a' + rng.nextInt(26)))
                        4 -> sb.append('你')
                        5 -> sb.append('\u2028')
                        6 -> sb.append('\u2029')
                        7 -> sb.append('"')
                        8 -> sb.append('\\')
                        else -> sb.append('\r')
                    }
                }
                sb.toString()
            }
            val joined = lines.joinToString("\n") + "\n"
            val bytes = joined.toByteArray(StandardCharsets.UTF_8)

            val expected = ArrayList<String>()
            val expectFramer = LineFramer { expected.add(it) }
            expectFramer.feed(bytes)
            expectFramer.close()

            val actual = ArrayList<String>()
            val actualFramer = LineFramer { actual.add(it) }
            var i = 0
            while (i < bytes.size) {
                // 随机步长切包，含单字节粒度
                val step = 1 + rng.nextInt(7)
                val end = minOf(i + step, bytes.size)
                val chunk = bytes.copyOfRange(i, end)
                actualFramer.feed(chunk)
                i = end
            }
            actualFramer.close()

            assertEquals(expected, actual, "round $round")
        }
    }
}
