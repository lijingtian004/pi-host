package works.earendil.pihost.core.rpc

/**
 * stderr 环形缓冲（方案 §3.4：8KB，进程死亡诊断用）。
 *
 * 线程安全：append（drain 协程）与 snapshot（诊断读取）可并发。
 * 容量按字节计；snapshot 的 UTF-8 解码宽松（截断多字节 → 替换字符）——
 * 诊断文本容忍一个坏字符，不抛异常。
 */
class StderrRing(private val capacityBytes: Int = 8 * 1024) {

    private val buffer = ByteArray(capacityBytes)
    private var writePos = 0
    private var size = 0
    private val lock = Any()

    /** 追加 [length] 字节（默认整段）。超过容量的写入只保留尾部。 */
    fun append(src: ByteArray, length: Int = src.size) {
        if (length <= 0 || capacityBytes == 0) return
        synchronized(lock) {
            var offset: Int
            var remaining: Int
            if (length >= capacityBytes) {
                // 只留最后 capacityBytes 字节
                offset = length - capacityBytes
                remaining = capacityBytes
            } else {
                offset = 0
                remaining = length
            }
            while (remaining > 0) {
                val chunk = minOf(remaining, capacityBytes - writePos)
                System.arraycopy(src, offset, buffer, writePos, chunk)
                writePos = (writePos + chunk) % capacityBytes
                size = minOf(capacityBytes, size + chunk)
                offset += chunk
                remaining -= chunk
            }
        }
    }

    /** 当前保留的字节数（0..capacityBytes）。 */
    fun byteCount(): Int = synchronized(lock) { size }

    /** 按写入顺序返回尾部内容（宽松 UTF-8 解码）。 */
    fun snapshot(): String = synchronized(lock) {
        val out = ByteArray(size)
        if (size == capacityBytes) {
            // 满：最老字节在 writePos，绕回
            val tail = capacityBytes - writePos
            System.arraycopy(buffer, writePos, out, 0, tail)
            System.arraycopy(buffer, 0, out, tail, writePos)
        } else {
            // 未满：顺序写入，头部即最老
            System.arraycopy(buffer, 0, out, 0, size)
        }
        String(out, Charsets.UTF_8)
    }

    fun clear() = synchronized(lock) {
        writePos = 0
        size = 0
    }
}
