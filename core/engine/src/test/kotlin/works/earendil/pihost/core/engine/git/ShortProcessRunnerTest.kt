package works.earendil.pihost.core.engine.git

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ShortProcessRunner]（P3C §3.5 底座）14 条。
 *
 * 全部假进程、零 Robolectric：这里要钉的是**编排**（透传、超时、上限、双管道不互锁），
 * 不是任何真实 git 行为。真实行为在 REPORT-P3C §8 列为「只有真机可验」。
 *
 * 超时类用例把 `timeoutMs` 压到 40ms：本类的轮询间隔是 5ms，40ms 足够跨过 6~7 拍；
 * 用默认 3s 测超时会把整套矩阵拖慢，而「跨过若干拍才判超时」这个形状不变。
 */
class ShortProcessRunnerTest {

    private val env = mapOf("PATH" to "/x/bin", "GIT_EXEC_PATH" to "/x/libexec")

    private fun runner(
        vararg fakes: FakeShortProcess,
        timeoutMs: Long = 40L,
        maxBytes: Int = ShortProcessRunner.DEFAULT_MAX_BYTES,
        failWith: (() -> Throwable)? = null,
    ): Pair<ShortProcessRunner, FakeCommandStarter> {
        val starter = FakeCommandStarter(fakes.toList(), failWith)
        return ShortProcessRunner(starter, { env }, timeoutMs, maxBytes) to starter
    }

    // ── 透传与正常返回 ────────────────────────────────────────────────────────

    @Test
    fun `01 argv env and workingDir are passed through untouched and the exit is read back`() {
        val (runner, starter) = runner(FakeShortProcess("out", "err", exitCode = 0))

        val result = runner.run(listOf("/lib/git", "-C", "/d", "status"), "/d")

        assertEquals(listOf("/lib/git", "-C", "/d", "status"), starter.commandAt(0))
        assertEquals(env, starter.calls[0].env)
        assertEquals("/d", starter.calls[0].workingDir)
        assertEquals(ShortResult.Exited(0, "out", "err", false), result)
    }

    @Test
    fun `02 a non-zero exit is still an Exited result, not a failure`() {
        // 128 是 git「不是仓库」的正常表达，分类是探针的事，底座不许抢先吞掉
        val (runner, _) = runner(FakeShortProcess("", "fatal: not a git repository", exitCode = 128))

        val result = runner.run(listOf("git", "status"), "/d")

        val exited = result as ShortResult.Exited
        assertEquals(128, exited.exitCode)
        assertTrue(exited.stderr.contains("not a git repository"))
    }

    @Test
    fun `03 empty stdout reads back as empty string not null`() {
        val (runner, _) = runner(FakeShortProcess(""))

        val result = runner.run(listOf("git", "init"), "/d") as ShortResult.Exited

        assertEquals("", result.stdout)
        assertFalse(result.stdoutTruncated)
    }

    @Test
    fun `04 multi-line stdout arrives whole`() {
        val body = "## main\n M a.txt\n?? b/\n"
        val (runner, _) = runner(FakeShortProcess(body))

        val result = runner.run(listOf("git", "status"), "/d") as ShortResult.Exited

        assertEquals(body, result.stdout)
        // 结尾的 \n 会分出第 4 个空段：porcelain 每行都以换行收尾，这是格式的本来的样子，
        // 不是实现吞了字节——断在 3 上等于把「按行 split 的语义」写错给未来的自己看
        assertEquals(4, result.stdout.split('\n').size)
    }

    @Test
    fun `05 a normally exited process is never destroyed`() {
        val (runner, starter) = runner(FakeShortProcess("ok"))

        runner.run(listOf("git", "status"), "/d")

        assertEquals("正常退出的进程不该被 SIGKILL", 0, starter.destroyTotal())
    }

    // ── 调用方参数不对 ────────────────────────────────────────────────────────

    /** 这两条的价值全在后半句：**一次都不碰 starter**。VM 传错参数不该换来一条真 spawn。 */
    @Test
    fun `06 empty argv is rejected without touching the starter`() {
        val (runner, starter) = runner(FakeShortProcess("never"))

        val result = runner.run(emptyList(), "/d")

        assertTrue(result is ShortResult.InvalidRequest)
        assertEquals("不该尝试任何 spawn", 0, starter.calls.size)
    }

    @Test
    fun `07 blank workingDir is rejected without touching the starter`() {
        listOf(null, "", "   ").forEach { bad ->
            val (runner, starter) = runner(FakeShortProcess("never"))

            val result = runner.run(listOf("git", "status"), bad)

            assertTrue("「$bad」必须是 InvalidRequest", result is ShortResult.InvalidRequest)
            assertEquals("「$bad」不该尝试任何 spawn", 0, starter.calls.size)
        }
    }

    // ── spawn 失败：原样带出，不抛、不分类 ────────────────────────────────────

    @Test
    fun `08 an ENOENT spawn failure surfaces as SpawnFailed carrying the errno text`() {
        val (runner, _) = runner(
            failWith = { IOException("Cannot run program \"/lib/git\": Parse error: error=2, No such file or directory") },
        )

        val result = runner.run(listOf("git", "status"), "/d")

        val failed = result as ShortResult.SpawnFailed
        assertTrue("P2F §4.1(a)：error=2 同时是 exe 缺失与 cwd 缺失，分类必须留给外面那层",
            failed.cause.contains("error=2"))
    }

    @Test
    fun `09 an EACCES spawn failure keeps its errno for ExecFallbackStarter to act on`() {
        val (runner, _) = runner(
            failWith = { IOException("Cannot run program \"/lib/git\": Parse error: error=13, Permission denied") },
        )

        val failed = runner.run(listOf("git", "status"), "/d") as ShortResult.SpawnFailed

        assertTrue(failed.cause.contains("error=13"))
    }

    @Test
    fun `10 a non-IO throwable from the starter is reported, never thrown at the caller`() {
        val (runner, _) = runner(failWith = { IllegalStateException("multiple DataStores active") })

        val failed = runner.run(listOf("git", "status"), "/d") as ShortResult.SpawnFailed

        assertTrue("保留类名，logcat 里认得出是哪一类", failed.cause.startsWith("IllegalStateException"))
    }

    // ── 超时与双管道 ──────────────────────────────────────────────────────────

    @Test
    fun `11 a process that never exits times out and is destroyed exactly once`() {
        val (runner, starter) = runner(FakeShortProcess("partial", neverExit = true), timeoutMs = 40L)

        val result = runner.run(listOf("git", "status"), "/d")

        val timedOut = result as ShortResult.TimedOut
        assertEquals("partial", timedOut.stdout)
        assertEquals("destroy 是叫醒阻塞中读的唯一手段，且必须幂等一次", 1, starter.destroyTotal())
    }

    /**
     * 类头第 3 条的对拍：stderr 一满，git 就阻塞在 write 上；
     * 若实现是「先读完 stdout 再 waitFor 再读 stderr」，这条用例会挂到超时。
     * 所以这里给 8KB 的 stderr + 一小段 stdout，并要求两者都完整读回。
     */
    @Test
    fun `12 a flooded stderr pipe does not deadlock the stdout reader`() {
        val flood = "x".repeat(8 * 1024)
        val (runner, _) = runner(FakeShortProcess("## main", flood, exitCode = 0), timeoutMs = 4_000L)

        val result = runner.run(listOf("git", "status"), "/d")

        val exited = result as ShortResult.Exited
        assertEquals("## main", exited.stdout)
        assertEquals(flood.length, exited.stderr.length)
    }

    @Test
    fun `13 output beyond the cap is drained but marked truncated, never left unread`() {
        val long = "y".repeat(4 * 1024)
        val (runner, starter) = runner(FakeShortProcess(long, long), maxBytes = 8)

        val result = runner.run(listOf("git", "status"), "/d")

        val exited = result as ShortResult.Exited
        assertEquals(8, exited.stdout.length)
        assertTrue(exited.stdoutTruncated)
        assertEquals("超限不等于提前收工：进程照常退出", 0, starter.destroyTotal())
    }

    @Test
    fun `14 ioFailure helper produces the documented IOException shape`() {
        // 这条钉的是测试基座本身：FakeCommandStarter.ioFailure 造出的异常类型与消息形态
        val (runner, _) = runner(failWith = FakeCommandStarter.ioFailure("boom error=13"))

        val failed = runner.run(listOf("git", "status"), "/d") as ShortResult.SpawnFailed

        assertTrue(failed.cause.contains("boom error=13"))
    }
}
