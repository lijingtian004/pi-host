package works.earendil.pihost.feature.sessions

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import works.earendil.pihost.core.environment.PathLayout

/**
 * 会话列表的一行（规格书 §4.1 的字段表，一个不多一个不少）。
 *
 * `pi` 自己不给 App 提供查询接口，这些字段全部从 jsonl **头部窗口**里抠出来
 * （§13 偏差 2：只读头 64KB，读不到的一律给占位值，见 [PiSessionScanner] 的对齐表）。
 */
data class ScannedSession(
    /** jsonl 的绝对路径 —— `RpcCommand.SwitchSession` 的参数就是它（§3.2：不是 sessionId）。 */
    val sessionPath: String,
    /** 首行 header 的 `id`。缺 id 的文件直接跳过（无法定位，也不该出现在列表里）。 */
    val sessionId: String,
    /** 最新一条 `session_info.name`；空串等于「清除名字」→ null（照 pi 的规则）。 */
    val name: String?,
    /** 首行 header 的 `cwd`；缺失给空串（pi 同规则：`typeof cwd === "string" ? cwd : ""`）。 */
    val cwd: String,
    /** 恒取文件 mtime（有意偏差：头窗看不到尾部活动时间；排序规则仍与 §3.1 一致）。 */
    val modified: Instant,
    /** 头窗内的 `type=="message"` 条数。大文件是「窗口内条数」，不是总数（§13 偏差 2 的截断语义）。 */
    val messageCount: Int,
    /** 头窗内首条 `role=="user"` 且正文非空的文本；取不到 → null，UI 显示「（无消息）」。 */
    val firstMessage: String?,
    /** 文件字节数（`File.length()`，不需要读内容）。 */
    val sizeBytes: Long,
)

/**
 * 会话扫描面（RECON-P2E §7.5：接口化是为了 `SessionsViewModelTest` 能塞 FakeScanner）。
 *
 * 两条操作都是 suspend + IO 线程：列表页可能有好几十条 jsonl，绝不能占主线程。
 */
interface SessionScanner {

    /** 目录不存在 / 读不到 → 空表（**绝不 mkdir**：App 只读 pi 的存储，§4.1「App 不复制」）。 */
    suspend fun scan(): List<ScannedSession>

    /**
     * 删除一条会话 = 删那个 jsonl（§13 偏差 3：pi 没有删除命令，session-manager 无此 API）。
     * 路径必须落在 [sessionsRoot] 之内且后缀是 `.jsonl`，否则不执行、返回 false。
     */
    suspend fun delete(sessionPath: String): Boolean

    companion object {
        const val TAG: String = "PiHostSessions"

        /** 头窗上限（§13 偏差 2 的「64KB」）。 */
        const val HEAD_BYTES: Int = 64 * 1024

        /** 头窗行数上限（§3.1 原文「N=200 行或 64KB」里的另一个上限，两者取先到）。 */
        const val MAX_LINES: Int = 200

        /** pi 的 sessions 根目录：`$HOME/.pi/agent/sessions`，`HOME = filesDir/home`（EnvFactory 冻结区）。 */
        fun sessionsRoot(ctx: Context): File =
            File(File(File(PathLayout.homeDir(ctx), ".pi"), "agent"), "sessions")
    }
}

/**
 * pi 目录编码规则（session-manager.ts:592 的 1:1 移植）：
 * `--` + (cwd 去掉开头一个 `/` 或 `\`，再把所有 `/ \ :` 换成 `-`) + `--`。
 *
 * 注意 [PiSessionScanner] **不靠这个函数找目录**（它扫 `sessions/` 下所有 `--*--`，
 * 见 RECON-P2E §5 第 5 条：真机 filesDir 有 `/data/user/0/…` 与 `/data/data/…` 两种形状，
 * 猜目录名会踩 symlink 的坑）。本函数存在的意义是把规则钉死在可测的地方
 * （`SessionScannerTest` 用它断言 §5 推导出的那串真实路径形状）。
 */
fun encodeSessionDirName(cwd: String): String {
    val stripped = cwd.replace(LEADING_SEP, "")
    return "--" + stripped.replace(SEP_OR_COLON, "-") + "--"
}

private val LEADING_SEP: Regex = Regex("^[/\\\\]")
private val SEP_OR_COLON: Regex = Regex("[/\\\\:]")

/**
 * [SessionScanner] 的磁盘实现：`RandomAccessFile` 一次读满 `min(size, 64KB)`，按 `\n` 切行。
 *
 * **与 pi 侧 `buildSessionInfo`（session-manager.ts:806-880）的逐条对齐**（完整版在 RECON-P2E §4.2）：
 *
 * | 项 | pi | 本实现 |
 * |---|---|---|
 * | 扫描范围 | 全文件逐行（createReadStream + readline） | 头窗 ≤64KB 且 ≤200 行 |
 * | 坏行 | `parseSessionEntryLine` 返回 null → `continue` | 同（`parseLine` 失败即跳过） |
 * | 缺 header | 第一条**可解析**行 `type!=="session"` → 整文件 null | 同 |
 * | name | 每条 `session_info` 都覆盖：`entry.name?.trim() \|\| undefined` | 同（空串=清除，不是「取最新非空」） |
 * | messageCount | 全文件 `type==="message"` 计数（含所有 role） | 头窗内同规则 |
 * | firstMessage | 首条 `role==="user"` 且 `extractTextContent` 非空 | 同规则；取不到 → null（占位符在 UI 层） |
 * | modified | 最后一条 message 时间戳 → header timestamp → mtime | 恒 mtime（**有意偏差**） |
 * | 解析失败 | try/catch → null | 单文件 runCatching → 跳过 + `Log.w` |
 * | 目录不存在 | `getDefaultSessionDir` 会 mkdir | 返回空表，**绝不 mkdir** |
 *
 * 两处刻意的宽松差（脏数据不牵连整条会话）：pi 的 `extractTextContent` 遇到
 * 「content 既不是字符串也不是数组」会在 `content.filter` 处抛异常，被外层 catch 吞成
 * **整文件 null**；本实现只把这一条消息判为「无正文」后继续扫。
 * 另外 pi 的空串判定是 JS  falsy（`" "` 算有内容），本实现用 `isEmpty()` 对齐的是
 * 「有非空白字符」这条更常见的读感——差异只影响纯空白首条消息的显示，不影响排序与计数。
 *
 * @param sessionsRoot 通常 [SessionScanner.sessionsRoot]
 * @param headBytes    头窗字节上限，默认 64KB（测试压成小值来验证「只读头」这条纪律）
 * @param maxLines     头窗行上限，默认 200
 */
class PiSessionScanner(
    private val sessionsRoot: File,
    private val headBytes: Int = SessionScanner.HEAD_BYTES,
    private val maxLines: Int = SessionScanner.MAX_LINES,
) : SessionScanner {

    /**
     * `ignoreUnknownKeys` 对本实现没意义（手工按 key 取值，不反序列化 data class）；
     * `isLenient=false` 保持和 `JSON.parse` 一致的严格度，`explicitNulls` 不动默认值。
     */
    private val json = Json

    override suspend fun scan(): List<ScannedSession> = withContext(Dispatchers.IO) { scanBlocking() }

    override suspend fun delete(sessionPath: String): Boolean = withContext(Dispatchers.IO) {
        deleteBlocking(sessionPath)
    }

    /** 非 suspend 的实体：单测直接调它，省掉一层 runBlocking（§4.5 的六条用例都这么走）。 */
    fun scanBlocking(): List<ScannedSession> {
        val dirs = sessionsRoot
            .listFiles { f -> f.isDirectory && isEncodedSessionDir(f.name) }
            ?.toList()
            .orEmpty()
        if (dirs.isEmpty()) return emptyList()

        val out = ArrayList<ScannedSession>()
        for (dir in dirs) {
            val files = dir
                .listFiles { f -> f.isFile && f.name.endsWith(JSONL_SUFFIX) }
                ?.toList()
                .orEmpty()
            for (file in files) {
                val session = parseSession(file)
                if (session == null) Log.w(SessionScanner.TAG, "跳过无法解析的会话文件: ${file.absolutePath}")
                else out.add(session)
            }
        }
        // §3.1：mtime 降序 → 文件名字典序（pi 侧 :756/:1983 的同一条规则）
        return out.sortedWith(
            compareByDescending<ScannedSession> { it.modified.toEpochMilli() }
                .thenBy { it.sessionPath.substringAfterLast(File.separatorChar) },
        )
    }

    fun deleteBlocking(sessionPath: String): Boolean {
        if (sessionPath.isBlank()) return false
        val target = runCatching { File(sessionPath).canonicalFile }.getOrNull() ?: return false
        val root = runCatching { sessionsRoot.canonicalFile }.getOrNull() ?: return false
        // 只许删自己地盘上的 jsonl：越界路径（../、符号链接逃逸、绝对路径指向别处）一律拒
        if (!target.path.startsWith(root.path + File.separator)) return false
        if (!target.name.endsWith(JSONL_SUFFIX)) return false
        val deleted = target.delete()
        if (!deleted) Log.w(SessionScanner.TAG, "删除会话文件失败: ${target.path}")
        return deleted
    }

    /** 单条文件的完整解析；任何异常/形状不符 → null（调用方记日志并跳过该文件）。 */
    internal fun parseSession(file: File): ScannedSession? = try {
        parseSessionUnsafe(file)
    } catch (t: Throwable) {
        Log.w(SessionScanner.TAG, "解析会话文件异常: ${file.absolutePath}", t)
        null
    }

    private fun parseSessionUnsafe(file: File): ScannedSession? {
        val size = file.length()
        val head = readHead(file, size) ?: return null
        val lines = headLines(head, complete = size <= headBytes)

        var header: JsonObject? = null
        var name: String? = null
        var firstMessage: String? = null
        var messageCount = 0

        for (line in lines) {
            val entry = parseLine(line) ?: continue

            if (header == null) {
                // 第一条可解析行必须是 type:"session"，否则整个文件不算会话（pi 同规则）
                if (entry.stringOrNull("type") != "session") return null
                header = entry
                continue
            }

            when (entry.stringOrNull("type")) {
                "session_info" -> name = entry.stringOrNull("name")?.trim()?.ifEmpty { null }
                "message" -> {
                    messageCount++
                    if (firstMessage == null) {
                        val message = entry["message"] as? JsonObject
                        if (message != null && message.stringOrNull("role") == "user") {
                            firstMessage = message.textContent()?.ifEmpty { null }
                        }
                    }
                }
                // usage / compaction / 其它类型：P2E 不展示（§13 偏差 8：usage 从简）
            }
        }

        val h = header ?: return null
        val id = h.stringOrNull("id") ?: return null
        return ScannedSession(
            sessionPath = file.absolutePath,
            sessionId = id,
            name = name,
            cwd = h.stringOrNull("cwd").orEmpty(),
            modified = Instant.ofEpochMilli(file.lastModified()),
            messageCount = messageCount,
            firstMessage = firstMessage,
            sizeBytes = size,
        )
    }

    /**
     * 头读：一次 `readFully(min(size, headBytes))`。
     * 不整文件读入、不 mmap、不用 BufferedReader（RECON-P2E §4.2：后者要么全读，要么靠 `ready()` 猜边界）。
     */
    private fun readHead(file: File, size: Long): String? = try {
        val bytes = if (size > headBytes) headBytes.toLong() else size
        if (bytes <= 0L) "" else {
            RandomAccessFile(file, "r").use { raf ->
                val buffer = ByteArray(bytes.toInt())
                raf.seek(0L)
                raf.readFully(buffer)
                String(buffer, Charsets.UTF_8)
            }
        }
    } catch (t: Throwable) {
        Log.w(SessionScanner.TAG, "头读失败: ${file.absolutePath}", t)
        null
    }

    private fun parseLine(line: String): JsonObject? = try {
        json.parseToJsonElement(line) as? JsonObject
    } catch (_: Exception) {
        null // 坏行：pi 也是 continue 跳过
    }

    /**
     * 切行 + 「半行丢弃」：头窗截断在行中间时，最后一段必然不是完整 JSON（RECON-P2E §4.2）。
     * 同时吃 `\r\n`（pi 的 `crlfDelay: Infinity` 是同一效果）。
     */
    internal fun headLines(head: String, complete: Boolean): List<String> {
        if (head.isEmpty()) return emptyList()
        val segments = head.split('\n')
        val out = ArrayList<String>(minOf(segments.size, maxLines))
        for (i in segments.indices) {
            if (out.size >= maxLines) break
            val isLast = i == segments.lastIndex
            if (isLast && !complete && segments[i].isNotEmpty()) break // 截断的半行，丢
            val trimmed = if (segments[i].endsWith('\r')) segments[i].dropLast(1) else segments[i]
            if (trimmed.isNotBlank()) out.add(trimmed)
        }
        return out
    }

    private companion object {
        const val JSONL_SUFFIX = ".jsonl"

        /** 编码目录的形状：`--…--`（至少 5 个字符才可能是「两个连字符 + 一点内容 + 两个连字符」）。 */
        fun isEncodedSessionDir(name: String): Boolean =
            name.length >= 5 && name.startsWith("--") && name.endsWith("--")
    }
}

/** `message.content` → 文本（pi `extractTextContent`：字符串原样，块数组取 `type:"text"` 的 `text` 拼空格）。 */
private fun JsonObject.textContent(): String? {
    return when (val content = this["content"]) {
        is JsonPrimitive -> content.stringValueOrNull()
        is JsonArray -> content.filterIsInstance<JsonObject>()
            .filter { it.stringOrNull("type") == "text" }
            .joinToString(" ") { it.stringOrNull("text").orEmpty() }

        else -> null
    }
}

private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.stringValueOrNull()

/** 只认真字符串字面量：`123` / `true` / `null` 都不算（对齐 pi 的 `typeof x === "string"`）。 */
private fun JsonPrimitive.stringValueOrNull(): String? = if (isString) content else null
