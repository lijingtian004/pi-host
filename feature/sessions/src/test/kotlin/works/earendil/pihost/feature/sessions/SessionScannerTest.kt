package works.earendil.pihost.feature.sessions

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `PiSessionScanner` 的头读语义（规格书 §4.5 的 ≥6 条，实交 10 条）。
 *
 * 全部用 `TemporaryFolder` 造**真实 jsonl 文件**，走 `scanBlocking()`（非 suspend 实体）
 * 避开调度器；这样断言的是磁盘形状本身，而不是协程时序。
 *
 * 行的拼法逐条对照 `/root/pi/.tools/pi-1.0.4/packages/coding-agent/src/core/session-manager.ts`
 * 的实读结论（RECON-P2E §4.1 给了行号），不是照规格书措辞臆造的形状：
 * 首行 `{type:"session",version,id,timestamp,cwd}`（`:1064-1071`）、
 * 条目 `{type:"message",message:{role,content}}`（`:1206`）、`{type:"session_info",name}`（`:829`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** scanner 的根：`sessions/`，下面直接挂 `--…--` 编码目录。 */
    private val root: File get() = tmp.root

    private fun dir(name: String = "--data-proj-default--"): File =
        File(root, name).apply { assertTrue("编码目录必须建出来", mkdirs()) }

    private fun scan(): List<ScannedSession> = PiSessionScanner(root).scanBlocking()

    private fun List<ScannedSession>.only(): ScannedSession {
        assertEquals("期望恰好一条，实际 $this", 1, size)
        return single()
    }

    private fun header(
        id: String,
        cwd: String = "/data/proj/default",
        ts: String = "2026-10-06T11:22:33.444Z",
    ) = """{"type":"session","version":3,"id":"$id","timestamp":"$ts","cwd":"$cwd"}"""

    private fun userMessage(text: String) =
        """{"type":"message","timestamp":"2026-10-06T11:22:34.000Z",""" +
            """"message":{"role":"user","content":[{"type":"text","text":"$text"}]}}"""

    private fun assistantMessage(text: String) =
        """{"type":"message","timestamp":"2026-10-06T11:22:35.000Z",""" +
            """"message":{"role":"assistant","content":[{"type":"text","text":"$text"}]}}"""

    private fun toolResult(text: String) =
        """{"type":"message","timestamp":"2026-10-06T11:22:36.000Z",""" +
            """"message":{"role":"toolResult","content":[{"type":"text","text":"$text"}]}}"""

    private fun sessionInfo(name: String?) =
        if (name == null) """{"type":"session_info"}""" else """{"type":"session_info","name":"$name"}"""

    private fun File.writeLines(vararg lines: String) {
        writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    }

    // ── s01 正常扫描：header + session_info + user message ────────────────────────

    @Test
    fun `s01 scans a well formed session file and lifts every field`() {
        val d = dir()
        File(d, "2026-10-06T11-22-33-444Z_11111111-2222-4444-8888-555555555555.jsonl").writeLines(
            header("11111111-2222-4444-8888-555555555555"),
            sessionInfo("分支命令"),
            userMessage("跑一下 git branch"),
            assistantMessage("main* master"),
        )

        val row = scan().only()
        assertEquals(
            "2026-10-06T11-22-33-444Z_11111111-2222-4444-8888-555555555555.jsonl",
            File(row.sessionPath).name,
        )
        assertTrue("sessionPath 必须是绝对路径（SwitchSession 的入参）", File(row.sessionPath).isAbsolute)
        assertEquals("11111111-2222-4444-8888-555555555555", row.sessionId)
        assertEquals("分支命令", row.name)
        assertEquals("/data/proj/default", row.cwd)
        assertEquals("firstMessage 只认 user", "跑一下 git branch", row.firstMessage)
        assertEquals("messageCount 数所有 role", 2, row.messageCount)
        assertEquals("sizeBytes 就是磁盘字节数", File(row.sessionPath).length(), row.sizeBytes)
    }

    // ── s02 缺 header → 整个文件跳过；坏行在前仍认后面的 header ───────────────────

    @Test
    fun `s02 skips a file whose first parsable line is not a session header`() {
        val d = dir()
        File(d, "no-header.jsonl").writeLines(userMessage("没有首行头的坏文件"), sessionInfo("坏名字"))
        File(d, "good.jsonl").writeLines(header("good-id"), userMessage("正文"))

        assertEquals(
            "坏文件必须整个丢弃，只剩 good.jsonl",
            listOf("good-id"),
            scan().map { it.sessionId },
        )

        // pi 的规则是「第一条**可解析**行」：前面的空行/坏行被 continue 跳过（:816-826）
        File(d, "junk-then-header.jsonl").writeText(
            "\n{ 这不是 JSON }\n" + header("later-id") + "\n" + userMessage("迟到的正文") + "\n",
        )
        assertEquals("迟到的正文", scan().first { it.sessionId == "later-id" }.firstMessage)
    }

    // ── s03 name 取最新（含「空串=清除」这条 pi 规则）─────────────────────────────

    @Test
    fun `s03 the latest session_info wins and an empty name clears it`() {
        val d = dir()
        val file = File(d, "naming.jsonl")
        file.writeLines(header("n1"), sessionInfo("第一个名字"), sessionInfo("第二个名字"), userMessage("正文"))
        assertEquals("第二个名字", scan().only().name)

        // pi：`name = entry.name?.trim() || undefined` —— 后来的空串把名字清掉，不是保留旧的
        file.writeLines(header("n2"), sessionInfo("第一个名字"), sessionInfo(""), userMessage("正文"))
        assertNull("空串 name 必须清除显示名（session-manager.ts:829）", scan().first { it.sessionId == "n2" }.name)

        // 完全没有 session_info → null（UI 回落「未命名会话」）
        File(d, "bare.jsonl").writeLines(header("n3"), userMessage("正文"))
        assertNull(scan().first { it.sessionId == "n3" }.name)
    }

    // ── s04 目录不存在 / 非编码目录 → 空表，且绝不 mkdir ──────────────────────────

    @Test
    fun `s04 a missing root gives an empty list and never creates directories`() {
        val missing = File(tmp.root, "does-not-exist")
        assertTrue("前置：目录确实不存在", !missing.exists())
        assertEquals(emptyList<ScannedSession>(), PiSessionScanner(missing).scanBlocking())
        assertFalse("扫描绝不能 mkdir（App 只读 pi 的存储）", missing.exists())

        // 存在但不是 `--…--` 编码形状的子目录：不认
        val odd = File(root, "random-sub").apply { mkdirs() }
        File(odd, "x.jsonl").writeLines(header("ignored"), userMessage("不该出现"))
        assertEquals(emptyList<ScannedSession>(), scan())
    }

    // ── s05 排序：mtime 降序 → 同 mtime 时文件名字典序 ───────────────────────────

    @Test
    fun `s05 sorts by mtime descending then file name ascending`() {
        val d = dir()
        val base = 1_700_000_000_000L
        val specs = listOf(
            "2026-01-01T00-00-00-000Z_old.jsonl" to base,
            "2026-01-02T00-00-00-000Z_new.jsonl" to base + 10_000,
            // 两条同 mtime → 落到文件名比较：`a` 在 `b` 前
            "2026-01-03T00-00-00-000Z_a.jsonl" to base + 20_000,
            "2026-01-03T00-00-00-000Z_b.jsonl" to base + 20_000,
        )
        for ((name, mtime) in specs) {
            val file = File(d, name)
            file.writeLines(header(name.substringAfterLast('_').removeSuffix(".jsonl")), userMessage("正文"))
            file.setLastModified(mtime)
        }

        assertEquals(
            "mtime 降序，同值按文件名字典序（§3.1，pi 的 :756/:1983）",
            listOf("a", "b", "new", "old"),
            scan().map { File(it.sessionPath).name.substringAfterLast('_').removeSuffix(".jsonl") },
        )
    }

    // ── s06 只读头：大于头窗的文件不抛，窗口外内容不计入 ──────────────────────────

    @Test
    fun `s06 a file bigger than the head window is read only at its head and still parses`() {
        val d = dir()
        val filler = "x".repeat(400)
        val lines = ArrayList<String>()
        lines.add(header("big-id"))
        lines.add(userMessage("窗口内的第一条"))
        // 60 条约 410 字节的胖行（compaction）撑开头窗，尾部再挂 30 条消息
        repeat(60) { lines.add("""{"type":"compaction","id":"c$it","text":"$filler"}""") }
        repeat(30) { lines.add(assistantMessage("尾部消息 $it")) }
        val file = File(d, "big.jsonl")
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        assertTrue("前置：文件确实超过 2KB 头窗", file.length() > 2_048L)

        val row = PiSessionScanner(root, headBytes = 2_048, maxLines = 200).scanBlocking().only()
        assertEquals("big-id", row.sessionId)
        assertEquals("头窗内的 firstMessage 照常可得", "窗口内的第一条", row.firstMessage)
        assertEquals("窗口外的消息不得计入（§13 偏差 2 的截断语义）", 1, row.messageCount)
        assertEquals("sizeBytes 仍是整文件大小", file.length(), row.sizeBytes)

        // 第二道上限同样生效：headBytes 给足、maxLines=3 → 只数到 header + user 这一条消息
        assertEquals(1, PiSessionScanner(root, headBytes = 1 shl 20, maxLines = 3).scanBlocking().only().messageCount)
    }

    // ── s07 头窗截断在行中间：半行必须丢弃而不是崩 ────────────────────────────────

    @Test
    fun `s07 a line cut in the middle of the head window is dropped, not parsed`() {
        val d = dir()
        val headerLine = header("cut-id")
        File(d, "cut.jsonl").writeText(
            headerLine + "\n" + userMessage("这是一条被头窗切掉的长消息，切点落在 JSON 中间"),
        )
        val row = PiSessionScanner(root, headBytes = headerLine.length + 1 + 30).scanBlocking().only()
        assertEquals("cut-id", row.sessionId)
        assertNull("半行被丢弃 → firstMessage 取不到，UI 显示占位符", row.firstMessage)
        assertEquals("半行不产生计数", 0, row.messageCount)
    }

    // ── s08 删除：只删地盘内的 jsonl ─────────────────────────────────────────────

    @Test
    fun `s08 delete removes an in-root jsonl and refuses everything else`() {
        val sandbox = File(tmp.root, "sandbox").apply { mkdirs() }
        val d = File(sandbox, "--p--").apply { mkdirs() }
        val victim = File(d, "del.jsonl")
        victim.writeLines(header("del-id"), userMessage("要删的会话"))
        val outside = File(tmp.root, "outside.jsonl")
        outside.writeLines(header("out-id"))
        val nonJsonl = File(d, "keep.txt")
        nonJsonl.writeLines(header("keep-id"))
        val sibling = File(tmp.root, "sandbox-but-not.jsonl")

        val scanner = PiSessionScanner(sandbox)
        assertFalse("拒绝删根外的文件", scanner.deleteBlocking(outside.absolutePath))
        assertTrue("根外文件必须还在", outside.exists())
        assertFalse("拒绝删非 .jsonl", scanner.deleteBlocking(nonJsonl.absolutePath))
        assertTrue("非 jsonl 必须还在", nonJsonl.exists())
        assertFalse("拒绝删根目录本身", scanner.deleteBlocking(sandbox.absolutePath))
        assertFalse("拒绝空路径", scanner.deleteBlocking(" "))
        assertFalse("拒绝不存在的文件", scanner.deleteBlocking(sibling.absolutePath))

        assertTrue(scanner.deleteBlocking(victim.absolutePath))
        assertFalse("删成功后文件不存在", victim.exists())
        assertFalse("再删一次（已不存在）返回 false", scanner.deleteBlocking(victim.absolutePath))
        assertEquals(emptyList<ScannedSession>(), scanner.scanBlocking())
    }

    // ── s09 编码规则 + 脏字段宽松处理 ────────────────────────────────────────────

    @Test
    fun `s09 encodes the session dir exactly like pi and tolerates junk fields`() {
        // 编码规则本身（RECON-P2E §4.1 的 `:592`）只替 `/ \ :` 三个字符：**点号与大小写都原样保留**。
        // 编译机实测把这两条钉死：`works.earendil.pihost` 的三个点不进替换集，
        // Windows 盘符 `C:` 变成 `C-` 而不是 `c-`（pi 侧没有 toLowerCase 这一环）。
        assertEquals(
            "--data-user-0-works.earendil.pihost-files-projects-default--",
            encodeSessionDirName("/data/user/0/works.earendil.pihost/files/projects/default"),
        )
        assertEquals("--C--Programs-git--", encodeSessionDirName("C:\\Programs\\git"))
        assertEquals("--p--", encodeSessionDirName("/p"))

        val d = dir("--p--")
        File(d, "junk.jsonl").writeText(
            """{"type":"session","id":"j1","cwd":123,"timestamp":null}""" + "\n" +
                """{"type":"message","message":{"role":"user","content":[{"type":"text"}]}}""" + "\n" +
                """{"type":"message","message":{"role":"user","content":"字符串正文"}}""" + "\n" +
                """{"type":"message","message":{"role":"assistant","content":[{"type":"text","text":"回复"}]}}""" + "\n" +
                """{"type":"session_info","name":true}""" + "\n",
        )
        val row = scan().only()
        assertEquals("j1", row.sessionId)
        assertEquals("cwd 不是字符串 → 空串（对齐 pi 的 typeof 检查）", "", row.cwd)
        assertEquals("非字符串 name → 视为清除", null, row.name)
        assertEquals("字符串 content 也算正文", "字符串正文", row.firstMessage)
        assertEquals("缺 text 的字块贡献空串，不改变计数", 3, row.messageCount)
    }

    // ── s10 toolResult 计数但不抢 firstMessage ───────────────────────────────────

    @Test
    fun `s10 tool result messages count but never become the first message`() {
        val d = dir()
        File(d, "roles.jsonl").writeLines(
            header("r1"),
            toolResult("exit 0"),
            assistantMessage("我先看看"),
            userMessage("真正的提问"),
        )
        val row = scan().only()
        assertEquals("所有 role 都计入 messageCount（pi 同规则）", 3, row.messageCount)
        assertEquals("firstMessage 只取 user", "真正的提问", row.firstMessage)
    }
}
