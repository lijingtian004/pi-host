package works.earendil.pihost.core.engine.audit

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `FileAuditSink`（规格书 P3A §3.1 点名的 ①–⑩，逐条对号）：
 * ①→01 追加两行读回两条、②→02 按天分文件、③→03/12 十四天外懒清理、④→04 坏行容忍、
 * ⑤→05/06 IO 失败不抛且后续调用仍可用、⑥→07 args 原样、⑦→08 resultPreview 4096 截断、
 * ⑧→09 并发 record 无交错、⑨→10 isError null 透传、⑩→11 NoopAuditSink 静默；
 * 13/14 把「取消中的 scope 不抛」与「路径布局」钉住（门槛 ≥10）。
 *
 * ## 纯 JVM + 就地执行的写入协程
 * 全部用例把 [TemporaryFolder] 派生目录喂给构造，不碰任何设备路径（P2F §7.1 的同一课）。
 * scope 一律用 `Dispatchers.Unconfined`：`Mutex.lock()` 在无竞争时**不挂起**，于是 `record()`
 * 返回那一刻文件已经写完——断言不需要 sleep/轮询，测试里没有任何时序运气。
 * 唯一真并发的是 09，那条换 `Dispatchers.IO` 并用 `join()` 等齐（见用例内注释）。
 */
class FileAuditSinkTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private val logs = CopyOnWriteArrayList<String>()

    private val sinkLogger: (String, Throwable?) -> Unit = { message, throwable ->
        logs.add(message + (throwable?.let { " | ${it::class.java.simpleName}" } ?: ""))
    }

    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private lateinit var sink: FileAuditSink

    @Before
    fun setUp() {
        dir = File(tmp.root, "audit")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        sink = FileAuditSink(dir, scope, logger = sinkLogger)
    }

    // ── ① 追加两行读回两条 ────────────────────────────────────────────────────

    @Test
    fun `01 appends one json line per record and reads both back`() {
        val first = entry(toolCallId = "call-1", toolName = "bash", argsJson = """{"command":"ls"}""")
        val second = entry(toolCallId = "call-2", toolName = "write", argsJson = """{"path":"a.txt"}""")

        sink.record(first)
        sink.record(second)

        val file = sink.fileForToday()
        assertTrue("落盘文件必须存在：${file.path}", file.exists())
        assertEquals("一行一条，两调两行", 2, file.readLines().count { it.isNotBlank() })
        // 逐字形状：每行都是合法 JSON，且第 2 行是第二条（追加序，不是插入序）
        assertEquals(listOf(first, second), FileAuditSink.readFile(file))
        assertTrue("行尾自带换行", file.readText().endsWith("}\n"))
    }

    // ── ② 按天分文件 ──────────────────────────────────────────────────────────

    @Test
    fun `02 splits files by day using the entry timestamp`() {
        val yesterday = LocalDate.now().minusDays(1)
        val today = LocalDate.now()

        sink.record(entry(ts = tsOf(yesterday), toolCallId = "old"))
        sink.record(entry(ts = tsOf(today), toolCallId = "new"))

        val oldFile = sink.fileFor(yesterday)
        val newFile = sink.fileFor(today)
        assertEquals("audit-${yesterday}.jsonl", oldFile.name)
        assertEquals("audit-${today}.jsonl", newFile.name)
        assertEquals(listOf("old"), FileAuditSink.readFile(oldFile).map { it.toolCallId })
        assertEquals(listOf("new"), FileAuditSink.readFile(newFile).map { it.toolCallId })
        assertEquals(2, AuditPaths.auditFiles(dir).size)
    }

    // ── ③ 14 天外旧文件懒清理 ─────────────────────────────────────────────────

    @Test
    fun `03 lazily deletes audit files older than the retention window on write`() {
        val today = LocalDate.now()
        val outOfWindow = File(dir, AuditPaths.auditFileName(today.minusDays(15)))
        val inWindow = File(dir, AuditPaths.auditFileName(today.minusDays(13)))
        assertTrue(outOfWindow.parentFile.mkdirs())
        outOfWindow.writeText("x\n")
        inWindow.writeText("y\n")

        sink.record(entry())

        assertFalse("15 天前的审计文件必须被扫掉", outOfWindow.exists())
        assertTrue("13 天前的还在保留期内", inWindow.exists())
    }

    // ── ④ 坏行容忍 ────────────────────────────────────────────────────────────

    @Test
    fun `04 skips corrupt lines instead of failing the whole file`() {
        val file = sink.fileForToday()
        assertTrue(file.parentFile.mkdirs())
        file.writeText("this is not json\n")
        file.appendText("""{"ts":1,"kind":"NOPE","toolCallId":"x"}\n""")
        file.appendText("\n")

        sink.record(entry(toolCallId = "good-1"))
        sink.record(entry(toolCallId = "good-2"))

        val parsed = FileAuditSink.readFile(file)
        assertEquals("三行垃圾不算进结果，两条好行全在", listOf("good-1", "good-2"), parsed.map { it.toolCallId })
        assertTrue("损坏行原样留在磁盘上（读侧容忍，不做写侧清洗）", file.readText().startsWith("this is not json"))
    }

    // ── ⑤ IO 失败不抛（目录位置被一个普通文件占着）──────────────────────────────

    @Test
    fun `05 a failing mkdir does not throw out of record`() {
        val blocked = File(tmp.root, "audit-blocked")
        blocked.writeText("我不是目录")
        val failing = FileAuditSink(blocked, scope, logger = sinkLogger)

        failing.record(entry()) // 不抛 = 本条第一句胜利；抛了 JUnit 直接红

        assertTrue("失败要响一声：${logs}", logs.any { it.contains("审计落盘失败") })
        assertFalse("目录建不成时不该凭空写出文件", blocked.isDirectory)
    }

    // ── ⑤续 后续调用仍可用（同一实例、单日写入点被堵 ≠ 整个 sink 报废）──────────

    @Test
    fun `06 a failed write leaves the sink usable for later records`() {
        val blockedDay = LocalDate.now().minusDays(2)
        assertTrue(dir.mkdirs())
        // 把某一天的「文件」做成目录：那天的 append 必然 FileNotFoundException，其它天不受牵连
        assertTrue(File(dir, AuditPaths.auditFileName(blockedDay)).mkdirs())

        sink.record(entry(ts = tsOf(blockedDay), toolCallId = "blocked"))
        assertTrue("堵住的写入响了一声但没抛：${logs}", logs.any { it.contains("审计落盘失败") })

        sink.record(entry(toolCallId = "still-here"))

        assertEquals(listOf("still-here"), sink.readEntries(LocalDate.now()).map { it.toolCallId })
    }

    // ── ⑥ args 原样 ───────────────────────────────────────────────────────────

    @Test
    fun `07 stores args verbatim including newlines quotes and chinese`() {
        val raw = """{"command":"echo \"中文\" \n ls -la","cwd":"/data/user/0/files/projects/default"}"""
        sink.record(entry(argsJson = raw))

        val line = sink.fileForToday().readLines().single()
        assertTrue("行内是合法 JSON 转义而不是裸引号", line.startsWith("""{"ts":"""))
        assertEquals(raw, FileAuditSink.readFile(sink.fileForToday()).single().argsJson)
    }

    // ── ⑦ resultPreview 4096 截断 ─────────────────────────────────────────────

    @Test
    fun `08 clamps resultPreview to the documented limit`() {
        val long = "结".repeat(FileAuditSink.RESULT_PREVIEW_MAX + 500)
        val exact = "x".repeat(FileAuditSink.RESULT_PREVIEW_MAX)

        sink.record(entry(kind = AuditKind.TOOL_END, toolCallId = "long", resultPreview = long))
        sink.record(entry(kind = AuditKind.TOOL_END, toolCallId = "exact", resultPreview = exact))

        val readBack = FileAuditSink.readFile(sink.fileForToday())
        assertEquals(FileAuditSink.RESULT_PREVIEW_MAX, readBack.first { it.toolCallId == "long" }.resultPreview?.length)
        assertEquals(FileAuditSink.RESULT_PREVIEW_MAX, readBack.first { it.toolCallId == "exact" }.resultPreview?.length)
        assertEquals(4_096, FileAuditSink.RESULT_PREVIEW_MAX)
    }

    // ── ⑧ 并发 record 无交错 ──────────────────────────────────────────────────

    @Test
    fun `09 concurrent records produce one hundred intact lines`() = runBlocking {
        // 这一条要真并发，故换 Dispatchers.IO；Unconfined 会把两次写变成串行、测不出互斥锁的价值
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ioSink = FileAuditSink(dir, ioScope, logger = sinkLogger)
        val file = ioSink.fileForToday()

        List(2) { worker ->
            launch(Dispatchers.IO) {
                repeat(50) { i ->
                    ioSink.record(
                        entry(
                            kind = AuditKind.TOOL_END,
                            toolCallId = "w$worker-$i",
                            argsJson = """{"command":"echo w$worker-$i"}""",
                            resultPreview = "输出 w$worker-$i ".repeat(30),
                        ),
                    )
                }
            }
        }.joinAll()

        // record 起的是 ioScope 里的 fire-and-forget 协程，**join 不到它们**（那正是 fire-and-forget 的定义），
        // 所以断言分两步：先有界等齐 100 行（只等「都落地了」），再一次性验完整性。
        // 「无交错」的判据是每一行都能独立解成一条 AuditEntry，且 100 个 id 一个不丢。
        val lines = awaitLineCount(file, 100)
        ioScope.cancel()

        assertEquals(100, lines.size)
        val parsed = FileAuditSink.readFile(file)
        assertEquals("每行都必须是完整 JSON（有交错就说明互斥锁没起作用）", 100, parsed.size)
        assertEquals((0 until 100).map { "w${it / 50}-${it % 50}" }.toSet(), parsed.map { it.toolCallId }.toSet())
        assertTrue("并发写入不该产生任何失败上报：$logs", logs.isEmpty())
    }

    /**
     * 有界轮询到 [expected] 行（上限 [timeoutMs]），返回最后一次观察到的行。
     *
     * 不用固定 `sleep`：固定时长在编译机（4 核 / 内存吃紧）上是抖动源；轮询到够数就返回，
     * 不够也把「实际等到的行数」交回给断言，失败信息比超时异常有用。
     */
    private fun awaitLineCount(file: File, expected: Int, timeoutMs: Long = 10_000): List<String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lines = emptyList<String>()
        while (System.currentTimeMillis() < deadline) {
            lines = if (file.isFile) file.readLines().filter { it.isNotBlank() } else emptyList()
            if (lines.size >= expected) return lines
            Thread.sleep(10)
        }
        return lines
    }

    // ── ⑨ isError null 透传 ───────────────────────────────────────────────────

    @Test
    fun `10 keeps a null isError null instead of inventing false`() {
        sink.record(entry(kind = AuditKind.TOOL_START, toolCallId = "null-case", isError = null))
        sink.record(entry(kind = AuditKind.TOOL_END, toolCallId = "false-case", isError = false))
        sink.record(entry(kind = AuditKind.TOOL_END, toolCallId = "true-case", isError = true))

        val file = sink.fileForToday()
        assertTrue("null 也要显式落一行键，不能整字段消失", file.readLines()[0].contains(""""isError":null"""))
        val byId = FileAuditSink.readFile(file).associateBy { it.toolCallId }
        assertNull(byId.getValue("null-case").isError)
        assertEquals(false, byId.getValue("false-case").isError)
        assertEquals(true, byId.getValue("true-case").isError)
    }

    // ── ⑩ NoopAuditSink 静默 ──────────────────────────────────────────────────

    @Test
    fun `11 noop sink stays silent and creates nothing`() {
        val untouched = File(tmp.root, "never-created")
        assertFalse(untouched.exists())

        NoopAuditSink.record(entry())
        NoopAuditSink.record(entry(kind = AuditKind.PERMISSION, resultPreview = "x".repeat(9_000)))
        // fun interface 的函数式假体：空 lambda 也要能当 AuditSink 用（ChatViewModelTest 靠这条写 Fake）
        AuditSink { }.record(entry())

        assertFalse("Noop 连目录都不该碰", untouched.exists())
        assertFalse(NoopAuditSink.toString().isEmpty())
    }

    // ── 附加：取消中的 scope（fire-and-forget 的另一半承诺）────────────────────

    @Test
    fun `12 records into an already cancelled scope are dropped without throwing`() {
        val deadScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dead = FileAuditSink(dir, deadScope, logger = sinkLogger)
        deadScope.cancel()

        dead.record(entry())

        assertFalse("scope 已取消 → 本条丢弃，且不许留下半截文件", dir.walkTopDown().any { it.name.startsWith("audit-") })
    }

    // ── 附加：布局与保留边界（清理只认「比写入那天早 14 天以上」）──────────────────

    @Test
    fun `13 cleanup keeps the boundary day and ignores foreign files`() {
        val today = LocalDate.now()
        assertTrue(dir.mkdirs())
        val boundary = File(dir, AuditPaths.auditFileName(today.minusDays(14)))
        val foreign = File(dir, "notes.txt")
        val unreadableName = File(dir, "audit-notadate.jsonl")
        listOf(boundary, foreign, unreadableName).forEach { it.writeText("keep me\n") }

        sink.record(entry())

        assertTrue("第 14 天整仍在保留期内", boundary.exists())
        assertTrue("非审计命名一律不动", foreign.exists() && unreadableName.exists())
    }

    @Test
    fun `14 audit file naming round trips through the day parser`() {
        val day = LocalDate.of(2026, 5, 1)
        assertEquals("audit-2026-05-01.jsonl", AuditPaths.auditFileName(day))
        assertEquals(day, AuditPaths.dayFromFileName("audit-2026-05-01.jsonl"))
        assertNull(AuditPaths.dayFromFileName("audit-2026-13-01.jsonl"))
        assertNull(AuditPaths.dayFromFileName("spawn-diagnostics.log"))
        assertEquals(File(dir, "audit-2026-05-01.jsonl"), AuditPaths.auditFile(dir, day))
        assertEquals(day, AuditPaths.dayOf(tsOf(day)))
    }

    // ── 测试夹具 ──────────────────────────────────────────────────────────────

    private fun entry(
        ts: Long = tsOf(LocalDate.now()),
        kind: AuditKind = AuditKind.TOOL_START,
        toolCallId: String = "call-1",
        toolName: String = "bash",
        argsJson: String = """{"command":"ls -la"}""",
        isError: Boolean? = null,
        resultPreview: String? = null,
        cwd: String = "/data/user/0/works.earendil.pihost/files/projects/default",
        sessionId: String? = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
    ): AuditEntry = AuditEntry(
        ts = ts,
        kind = kind,
        toolCallId = toolCallId,
        toolName = toolName,
        argsJson = argsJson,
        isError = isError,
        resultPreview = resultPreview,
        cwd = cwd,
        sessionId = sessionId,
    )

    /**
     * LocalDate → epoch millis：取「那一天本地零点的瞬时」。
     *
     * 不用 `System.currentTimeMillis() - n*86400_000`：那算法在 DST 切换日会把日期算错一天，
     * 懒清理与按天分文件的断言就会变成一年红两次的时间炸弹。
     */
    private fun tsOf(day: LocalDate): Long = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
