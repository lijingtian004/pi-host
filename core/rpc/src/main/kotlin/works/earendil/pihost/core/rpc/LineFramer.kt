package works.earendil.pihost.core.rpc

/**
 * pi RPC 传输的分帧器（protocol.md §1）。
 *
 * 传输语义：帧 = 字节级 LF（0x0A）分隔的单条 JSON。pi 的 stdout 由
 * jsonl.ts（attachJsonlLineReader）写出，明确警告“客户端必须在 `\n` 上切分，
 * 不得用 readline 语义”——readline 会把 U+2028/U+2029（UTF-8: E2 80 A8/A9）
 * 当作行终止符，而它们是 JSON 字符串内的合法字符。
 *
 * 实现：字节累积，遇到 0x0A 才成帧；整行完成后统一 UTF-8 解码。多字节
 * UTF-8 序列跨 read 缓冲区边界天然安全（解码发生在收帧时，而非收包时）。
 *
 * pi 侧行尾 `\r` 会被剥离（jsonl.ts emitLine），发送端只发 LF；
 * 本分帧器不剥离 `\r`（见 [feed] 中注释），由上层按需处理。
 */
class LineFramer(
    private val onLine: (String) -> Unit,
) {
    private val line = ArrayList<Byte>(512)

    /**
     * 喂入一段原始字节。分包位置任意：单个 read 可能含 0..N 条完整帧，
     * 也可能是某帧的前缀或后缀，还可能把多字节 UTF-8 字符从中劈开。
     */
    fun feed(buf: ByteArray, len: Int = buf.size) {
        var i = 0
        while (i < len) {
            val b = buf[i]
            if (b == 0x0A.toByte()) {
                flushLine()
            } else {
                line.add(b)
            }
            i++
        }
    }

    /**
     * 流结束冲刷：pi 正常退出时最后一行可能没有尾随 LF（jsonl.ts 的
     * onEnd 分支——buffer 非空则 emitLine）。不冲刷会丢最后一帧。
     */
    fun close() {
        if (line.isNotEmpty()) {
            flushLine()
        }
    }

    private fun flushLine() {
        // pi 侧 emitLine 会剥行尾 \r（Windows 残留），但发送端只发 LF，
        // 稳妥起见同样剥离单个尾随 \r
        var end = line.size
        if (end > 0 && line[end - 1] == 0x0D.toByte()) end--
        val bytes = ByteArray(end) { line[it] }
        onLine(String(bytes, Charsets.UTF_8))
        line.clear()
    }
}
