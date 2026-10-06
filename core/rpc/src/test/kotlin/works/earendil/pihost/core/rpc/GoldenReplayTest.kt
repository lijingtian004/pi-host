package works.earendil.pihost.core.rpc

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * golden 回放冒烟（protocol.md §8 的 8 份实录样本）：
 * LineFramer 逐字节喂入 → 每行必须是合法 JSON → 无丢帧。
 *
 * 这是「pi RPC 协议变更在 CI 中显式暴露」的第一道门：
 * - 行数 == 物理行数（分帧不丢帧）；
 * - 每帧能被 kotlinx.serialization 解析为 JsonObject；
 * - 每帧有 type 字段（response 或事件）。
 */
class GoldenReplayTest {

    companion object {
        @JvmStatic
        fun goldenFiles(): List<File> {
            val dir = File("src/test/resources/golden")
            check(dir.isDirectory) { "golden dir missing: ${dir.absolutePath} (cwd=${File(".").absolutePath})" }
            return dir.listFiles { f -> f.extension == "jsonl" }!!
                .sortedBy { it.name }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    @ParameterizedTest
    @MethodSource("goldenFiles")
    fun `replay golden file byte-exact`(file: File) {
        val bytes = file.readBytes()

        // 先按物理 LF 行数得出期望帧数（字节级统计 0x0A，末尾无 LF 的尾巴算一帧）
        var expectedLines = 0
        var lastByteWasLF = false
        for (b in bytes) {
            if (b == 0x0A.toByte()) {
                expectedLines++
                lastByteWasLF = true
            } else {
                lastByteWasLF = false
            }
        }
        if (!lastByteWasLF && bytes.isNotEmpty()) expectedLines++

        val lines = ArrayList<String>(expectedLines)
        val framer = LineFramer { lines.add(it) }
        // 以递增步长切包喂入，顺带验证跨包分帧
        var i = 0
        var step = 1
        while (i < bytes.size) {
            val end = minOf(i + step, bytes.size)
            framer.feed(bytes.copyOfRange(i, end))
            i = end
            step = (step % 13) + 1 // 1..13 循环步长
        }
        framer.close()

        assertTrue(lines.size == expectedLines, "${file.name}: frames=${lines.size} expected=$expectedLines")
        lines.forEachIndexed { idx, line ->
            if (line.isEmpty()) return@forEachIndexed
            val obj = json.parseToJsonElement(line).let {
                assertTrue(it is kotlinx.serialization.json.JsonObject, "${file.name}:$idx not an object")
                it as kotlinx.serialization.json.JsonObject
            }
            assertTrue(
                obj.containsKey("type"),
                "${file.name}:$idx missing type field: ${line.take(120)}",
            )
        }
    }

    @Test
    fun `all eight goldens present`() {
        // 001-008 全量入库校验（防误删）
        val names = goldenFiles().map { it.name }
        (1..8).forEach { n ->
            val prefix = "%03d".format(n)
            assertTrue(names.any { it.startsWith(prefix) }, "missing golden $prefix*: $names")
        }
    }
}
