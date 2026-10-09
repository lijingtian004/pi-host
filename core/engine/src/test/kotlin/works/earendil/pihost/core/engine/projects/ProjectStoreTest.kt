package works.earendil.pihost.core.engine.projects

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import works.earendil.pihost.core.engine.git.GitIdentity
import works.earendil.pihost.core.engine.git.GitInitOutcome
import works.earendil.pihost.core.engine.git.GitInitializer

/**
 * [ProjectStore]（P3C §3.3）17 条。纯 JVM + [TemporaryFolder]。
 *
 * git 那一面走 [FakeGitInitializer]：本文件测的是**编排**（什么时候该调、调几次、
 * 它失败了本层怎么定性），不是 git 行为本身。`git init` 在设备上的真实结果属于
 * REPORT-P3C §8 的「只有真机可验」第一条，这里不许拿假进程冒充它。
 *
 * 与 `RelayEndpointStoreTest` 同一条断言纪律：**不用被测读口当唯一真相**
 * （04/08 直接 `File.isDirectory` / `listFiles`，不经 [ProjectStore.list]）。
 */
class ProjectStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val git = FakeGitInitializer()
    private lateinit var root: File
    private lateinit var home: File
    private lateinit var store: ProjectStore

    /** 每条用例一套独立沙盒：`projects/` 与 `$HOME/` 都是临时目录里的普通目录，一开始都不存在。 */
    private fun fresh(): ProjectStore {
        val stamp = System.nanoTime()
        root = File(tmp.root, "projects-$stamp")
        home = File(tmp.root, "home-$stamp")
        git.calls.clear()
        git.outcome = GitInitOutcome.Initialized
        store = ProjectStore(root, home, git)
        return store
    }

    private fun filesUnder(dir: File): List<String> = dir.listFiles()?.map { it.name }?.sorted().orEmpty()

    // ── list ──────────────────────────────────────────────────────────────────

    @Test
    fun `01 listing a missing root is empty and creates nothing`() {
        val s = fresh()
        assertFalse("前置条件：根目录不存在", root.exists())

        assertEquals(emptyList<String>(), s.list())

        assertFalse("列一次就建一个目录 = 把「打开列表」变成「创建项目」", root.exists())
    }

    @Test
    fun `02 list returns directories only, sorted`() {
        val s = fresh()
        File(root, "zeta").mkdirs()
        File(root, "alpha").mkdirs()
        File(root, "Mid").mkdirs()
        File(root, "a-file").writeText("x")

        assertEquals(listOf("Mid", "alpha", "zeta"), s.list())
    }

    /** 目录里可能有别的版本、别的 app、或人手工放的怪名字——它们不该被当成项目。 */
    @Test
    fun `03 list skips entries that fail the name rules`() {
        val s = fresh()
        File(root, ".git").mkdirs()
        File(root, "a b").mkdirs()
        File(root, "..weird").mkdirs()
        File(root, "ok-name").mkdirs()
        File(root, "x".repeat(ProjectNames.MAX_LENGTH + 1)).mkdirs()

        assertEquals("只有合规则的名字才算项目", listOf("ok-name"), s.list())
    }

    // ── create：成功面 ────────────────────────────────────────────────────────

    @Test
    fun `04 create makes exactly one empty directory`() {
        val s = fresh()

        val outcome = s.create("my-proj", initGit = false)

        assertEquals("my-proj", (outcome as ProjectOutcome.Created).name)
        assertTrue(File(root, "my-proj").isDirectory)
        assertEquals("新建项目不该留下任何文件", emptyList<String>(), filesUnder(File(root, "my-proj")))
    }

    @Test
    fun `05 create trims the name before touching the disk`() {
        val s = fresh()

        val outcome = s.create("  spaced  ", initGit = false)

        assertEquals("spaced", (outcome as ProjectOutcome.Created).name)
        assertTrue(File(root, "spaced").isDirectory)
        assertFalse("不得用未 trim 的名字建目录", File(root, "  spaced  ").exists())
    }

    @Test
    fun `06 create builds the whole missing path including the root`() {
        val s = fresh()
        assertFalse(root.exists())

        s.create("deep", initGit = false)

        assertTrue("projects/ 根不存在时也要能建（首启全新设备就是这个形状）", File(root, "deep").isDirectory)
    }

    @Test
    fun `07 a cjk project name is a first-class citizen`() {
        val s = fresh()

        val outcome = s.create("方案A-自用", initGit = false)

        assertEquals("方案A-自用", (outcome as ProjectOutcome.Created).name)
        assertTrue(File(root, "方案A-自用").isDirectory)
    }

    // ── create：拒绝面（一条都不许碰盘）────────────────────────────────────────

    @Test
    fun `08 invalid names are refused and nothing is written`() {
        val s = fresh()
        listOf(null, "", "   ", "../x", "/abs", "a/b", ".hidden", "..", "a b", "x".repeat(49))
            .forEach { bad ->
                val outcome = s.create(bad, initGit = true)

                assertTrue("「$bad」必须是 Invalid，实际 $outcome", outcome is ProjectOutcome.Invalid)
                assertFalse("非法名不许让 projects/ 根出现", root.exists())
                assertTrue("非法名不该调过 git", git.calls.isEmpty())
            }
    }

    @Test
    fun `09 an existing project name is refused`() {
        val s = fresh()
        s.create("dup", initGit = false)

        val outcome = s.create("dup", initGit = false)

        assertEquals("dup", (outcome as ProjectOutcome.AlreadyExists).name)
    }

    /** 比文件系统严：ext4 上 `Foo`/`foo` 是两个目录，但在用户眼里是同一个项目。 */
    @Test
    fun `10 a case-folded collision is refused and names the existing project`() {
        val s = fresh()
        s.create("MyProj", initGit = false)

        val outcome = s.create("myproj", initGit = false)

        val collision = outcome as ProjectOutcome.CaseCollision
        assertEquals("MyProj", collision.existing)
        assertFalse("折叠冲突不得建出第二个目录", File(root, "myproj").exists())
    }

    @Test
    fun `11 a case collision is detected regardless of the direction`() {
        val s = fresh()
        s.create("lower", initGit = false)

        assertTrue(s.create("LOWER", initGit = false) is ProjectOutcome.CaseCollision)
    }

    // ── create 与 git 的分工 ──────────────────────────────────────────────────

    @Test
    fun `12 initGit true initializes the new dir and seeds the identity file`() {
        val s = fresh()

        val outcome = s.create("repo", initGit = true)

        val created = outcome as ProjectOutcome.Created
        assertEquals(GitInitOutcome.Initialized, created.git)
        assertEquals(GitIdentity.Outcome.Written, created.identity)
        assertEquals("git 只该被叫一次，且叫的是这个目录",
            listOf(File(root, "repo").absolutePath), git.calls.map { it.absolutePath })
        assertTrue("身份文件落在 home 而不是项目里", GitIdentity.file(home).isFile)
    }

    @Test
    fun `13 initGit false touches neither git nor the identity file`() {
        val s = fresh()

        val outcome = s.create("plain", initGit = false)

        val created = outcome as ProjectOutcome.Created
        assertEquals(GitInitOutcome.Skipped, created.git)
        assertEquals(GitIdentity.Outcome.Skipped, created.identity)
        assertTrue(git.calls.isEmpty())
        assertFalse("没要 git 就不该在 HOME 里留文件", GitIdentity.file(home).exists())
    }

    /** 关键定性：目录已经建成了，就不能因为 init 失败把整个操作说成失败。 */
    @Test
    fun `14 a failed init still reports the project as Created with the failure carried along`() {
        val s = fresh()
        git.outcome = GitInitOutcome.Failed("exit=128 fatal: detected dubious ownership")

        val outcome = s.create("half", initGit = true)

        val created = outcome as ProjectOutcome.Created
        assertEquals("half", created.name)
        assertEquals(
            "原因必须原样带出来，UI 才说得出人话",
            GitInitOutcome.Failed("exit=128 fatal: detected dubious ownership"),
            created.git,
        )
        assertTrue("目录确实在", File(root, "half").isDirectory)
    }

    @Test
    fun `15 an already-a-repo outcome is passed through unchanged`() {
        val s = fresh()
        git.outcome = GitInitOutcome.AlreadyARepo

        val created = s.create("again", initGit = true) as ProjectOutcome.Created

        assertEquals(GitInitOutcome.AlreadyARepo, created.git)
    }

    // ── pathOf / exists ───────────────────────────────────────────────────────

    @Test
    fun `16 pathOf resolves a valid name under the root and rejects everything else`() {
        val s = fresh()

        assertEquals(File(root, "ok"), s.pathOf("ok"))
        assertEquals("$root/ok", s.pathStringOf("ok"))
        assertNull(s.pathOf("../ok"))
        assertNull(s.pathOf("/tmp/ok"))
        assertNull(s.pathOf("a/b"))
        assertNull(s.pathOf(null))

        s.pathOf("ok")
        assertFalse("pathOf 只算路径，绝不建目录", root.exists())
    }

    @Test
    fun `17 exists follows the directory and nothing else`() {
        val s = fresh()
        s.create("dir", initGit = false)
        File(root, "file").writeText("x")

        assertTrue(s.exists("dir"))
        assertFalse("同名普通文件不算项目", s.exists("file"))
        assertFalse(s.exists("nope"))
        assertFalse("非法名一律 false，不抛", s.exists("../dir"))
    }

    /** 假 git：记调用、按脚本回outcome、可强制抛。 */
    private class FakeGitInitializer : GitInitializer {
        val calls = ArrayList<File>()
        var outcome: GitInitOutcome = GitInitOutcome.Initialized
        var boom: Throwable? = null

        override fun init(dir: File): GitInitOutcome {
            calls.add(dir)
            boom?.let { throw it }
            return outcome
        }
    }
}
