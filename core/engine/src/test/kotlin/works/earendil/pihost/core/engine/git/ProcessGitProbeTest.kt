package works.earendil.pihost.core.engine.git

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProcessGitProbe]（P3C §3.5 读面）22 条。
 *
 * 输入一律是**git `--porcelain=v1 --branch` 的真实文本形状**，不是本类自己造的中间结构——
 * 解析器的价值就在于认得外面那个世界的样子，所以每条用例的头行都按 man page 与实测抄本写。
 * 这些文本在真机上还会再验一遍（REPORT-P3C §8 第 4 条：空仓库/分离 HEAD 的首行实际措辞），
 * 本文件钉的是「给定这些形状，UI 该拿到什么」。
 */
class ProcessGitProbeTest {

    private val libDir = "/data/app/~~abc/works.earendil.pihost-def/lib/arm64"
    private val dir = "/data/data/works.earendil.pihost/files/projects/my-proj"
    private val spawn = GitSpawn(libDir)
    private val env = mapOf("PATH" to "/x/bin", "GIT_EXEC_PATH" to "/x/libexec")

    private fun probe(
        vararg fakes: FakeShortProcess,
        failWith: (() -> Throwable)? = null,
    ): Pair<ProcessGitProbe, FakeCommandStarter> {
        val starter = FakeCommandStarter(fakes.toList(), failWith)
        return ProcessGitProbe(starter, { env }, spawn) to starter
    }

    private fun read(vararg fakes: FakeShortProcess): GitSnapshot =
        probe(*fakes).first.readBlocking(dir)

    // ── argv 形状 ────────────────────────────────────────────────────────────

    @Test
    fun `01 the probe runs exactly the permitted status argv from nativeLibraryDir`() {
        val (probe, starter) = probe(FakeShortProcess("## main"))

        probe.readBlocking(dir)

        assertEquals(
            listOf("$libDir/libpi_git.so", "-C", dir, "status", "--porcelain=v1", "--branch"),
            starter.commandAt(0),
        )
        assertEquals(dir, starter.calls[0].workingDir)
        assertEquals("git 需要 GIT_EXEC_PATH 才能起 fsmonitor/ssh 一类外部程序", env, starter.calls[0].env)
    }

    @Test
    fun `02 no other git verb ever leaves this class`() {
        // 红线 12 的可执行表达：本类跑过的 argv 只可能是 status 那一条
        val (probe, starter) = probe(FakeShortProcess("## main"), FakeShortProcess("## x"))

        listOf(dir, dir).forEach { probe.readBlocking(it) }

        assertEquals(2, starter.calls.size)
        starter.calls.forEach { call ->
            assertEquals("status", call.command[3])
            assertFalse("宿主不许跑写操作的 git 动词",
                call.command.any { it in listOf("add", "commit", "push", "checkout", "reset", "gc", "clean") })
        }
    }

    // ── 干净仓库 ─────────────────────────────────────────────────────────────

    @Test
    fun `03 a bare branch header is a clean repo on that branch`() {
        val snapshot = read(FakeShortProcess("## main\n"))

        assertEquals(GitSnapshot.Repo(branch = "main", dirty = false), snapshot)
        assertEquals("main", (snapshot as GitSnapshot.Repo).displayName)
    }

    @Test
    fun `04 the upstream suffix is stripped off the branch name`() {
        listOf("## main...origin/main", "## main...origin/main [ahead 1]", "## fix/x...origin/fix/x [gone]")
            .forEach { header ->
                val expected = header.removePrefix("## ").substringBefore("...")
                val snapshot = read(FakeShortProcess("$header\n")) as GitSnapshot.Repo
                assertEquals("「$header」的分支名该取 ... 之前那段", expected, snapshot.branch)
                assertFalse("只看头行不该判成脏", snapshot.dirty)
            }
    }

    // ── 脏 ───────────────────────────────────────────────────────────────────

    @Test
    fun `05 a modified tracked file marks the repo dirty`() {
        val snapshot = read(FakeShortProcess("## main\n M core/engine/src/main/A.kt\n")) as GitSnapshot.Repo

        assertTrue(snapshot.dirty)
        assertEquals("main", snapshot.branch)
    }

    @Test
    fun `06 an untracked path also marks the repo dirty`() {
        // ?? 也是「这里和 HEAD 不一样」，自用场景里它就是「有活没提交」
        val snapshot = read(FakeShortProcess("## main\n?? notes/\n")) as GitSnapshot.Repo

        assertTrue(snapshot.dirty)
    }

    @Test
    fun `07 a deleted file marks the repo dirty`() {
        assertTrue((read(FakeShortProcess("## main\n D gone.txt\n")) as GitSnapshot.Repo).dirty)
    }

    @Test
    fun `08 a trailing blank line alone is not dirt`() {
        assertFalse((read(FakeShortProcess("## main\n")) as GitSnapshot.Repo).dirty)
        assertFalse((read(FakeShortProcess("## main\n\n")) as GitSnapshot.Repo).dirty)
    }

    // ── 特殊头行 ─────────────────────────────────────────────────────────────

    @Test
    fun `09 detached head is reported as detached with the ui label`() {
        val snapshot = read(FakeShortProcess("## HEAD (no branch)\n M a.txt\n")) as GitSnapshot.Repo

        assertTrue(snapshot.detached)
        assertTrue(snapshot.dirty)
        assertEquals("分离 HEAD", snapshot.displayName)
    }

    @Test
    fun `10 a fresh repo with no commits still carries its branch name`() {
        val snapshot = read(FakeShortProcess("## No commits yet on main\n?? a.txt\n")) as GitSnapshot.Repo

        assertTrue("新建项目 + git init 之后就是这个形状", snapshot.newRepo)
        assertEquals("main", snapshot.branch)
        assertEquals("main", snapshot.displayName)
        assertTrue(snapshot.dirty)
    }

    @Test
    fun `11 a legacy master default parses the same way`() {
        assertEquals("master", (read(FakeShortProcess("## No commits yet on master\n")) as GitSnapshot.Repo).branch)
    }

    // ── 不是仓库 / 忙 ────────────────────────────────────────────────────────

    @Test
    fun `12 exit 128 with the not-a-repo line is NotARepo not Unavailable`() {
        val snapshot = read(
            FakeShortProcess(
                "",
                "fatal: not a git repository (or any of the parent directories): .git",
                exitCode = 128,
            ),
        )

        assertEquals(GitSnapshot.NotARepo, snapshot)
    }

    @Test
    fun `13 an index lock is Busy`() {
        val snapshot = read(
            FakeShortProcess("", "fatal: Unable to create '/x/.git/index.lock': File exists.", exitCode = 128),
        )

        assertEquals(GitSnapshot.Busy, snapshot)
    }

    @Test
    fun `14 an unclassified non-zero exit keeps its code and first stderr line in the cause`() {
        val (probe, _) = probe(FakeShortProcess("", "error: cannot lock ref 'HEAD'\nfurther noise", exitCode = 128))

        val snapshot = probe.readBlocking(dir) as GitSnapshot.Unavailable

        assertTrue(snapshot.cause, snapshot.cause.contains("exit=128"))
        assertTrue(snapshot.cause, snapshot.cause.contains("cannot lock ref"))
        assertFalse("第二行不该被拼进一行文案", snapshot.cause.contains("further noise"))
    }

    // ── 读不出来 ─────────────────────────────────────────────────────────────

    @Test
    fun `15 a spawn errno is Unavailable and quotes the errno text`() {
        val (probe, _) = probe(failWith = { IOException("Parse error: error=2, No such file or directory") })

        val snapshot = probe.readBlocking(dir) as GitSnapshot.Unavailable

        assertTrue(snapshot.cause, snapshot.cause.contains("error=2"))
    }

    @Test
    fun `16 a blank working dir never reaches the starter`() {
        val (probe, starter) = probe(FakeShortProcess("## main"))

        assertTrue(probe.readBlocking("   ") is GitSnapshot.Unavailable)
        assertTrue(probe.readBlocking(null) is GitSnapshot.Unavailable)
        assertEquals(0, starter.calls.size)
    }

    @Test
    fun `17 a header that is not a branch line is Unavailable`() {
        // 例如忘了带 --branch：porcelain 首行就会是普通的 XY path 行
        val snapshot = read(FakeShortProcess(" M a.txt\n"))

        assertTrue("$snapshot", snapshot is GitSnapshot.Unavailable)
    }

    @Test
    fun `18 a truncated stdout is Unavailable because the line count is untrustworthy`() {
        val starter = FakeCommandStarter(listOf(FakeShortProcess("## main\n" + " M f\n".repeat(200))))
        val capped = ProcessGitProbe(starter, { env }, spawn, maxBytes = 16)

        assertTrue(capped.readBlocking(dir) is GitSnapshot.Unavailable)
    }

    // ── suspend 面与空假体 ────────────────────────────────────────────────────

    @Test
    fun `19 the suspend face returns the same snapshot as the blocking face`() = runBlocking {
        val (probe, _) = probe(FakeShortProcess("## main\n M a\n"))

        assertEquals(GitSnapshot.Repo("main", dirty = true), probe.read(dir))
    }

    @Test
    fun `20 the noop probe is Unavailable for every input including a valid dir`() = runBlocking {
        assertTrue(NoopGitProbe.read(dir) is GitSnapshot.Unavailable)
        assertTrue(NoopGitProbe.read(null) is GitSnapshot.Unavailable)
        assertEquals(GitSnapshot.NO_PROBE_CAUSE, (NoopGitProbe.read(dir) as GitSnapshot.Unavailable).cause)
    }

    @Test
    fun `21 the git binary path comes from nativeLibraryDir never from the runtime bin farm`() {
        // 方案 §2.1：exec 一个 filesDir 里的符号链接会把 app_data_file 的禁令重新引回来
        val (probe, starter) = probe(FakeShortProcess("## main"))

        probe.readBlocking(dir)

        val argv0 = starter.commandAt(0).first()
        assertTrue(argv0, argv0.endsWith("/lib/arm64/libpi_git.so"))
        assertFalse("不得走 runtime/bin/git 那条符号链接", argv0.contains("/files/runtime/bin/"))
    }

    /** IOException 的 message 为 null 时也要有可辨识的 cause，不许拼出 "null"。 */
    @Test
    fun `22 a spawn failure with no message still reports a class name`() {
        val (probe, _) = probe(failWith = { IOException() })

        val snapshot = probe.readBlocking(dir) as GitSnapshot.Unavailable

        assertTrue(snapshot.cause, snapshot.cause.contains("IOException"))
    }
}
