package works.earendil.pihost.core.engine.git

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import works.earendil.pihost.core.engine.settings.AgentConfigFiles

/**
 * [GitIdentity]（P3C §3.4）10 条。纯 JVM + [TemporaryFolder]，与 `RelayEndpointStoreTest` 同档。
 *
 * 这里钉的是「写出去的确切字节」与「什么时候**不**写」。后者才是本类真正的价值：
 * `.gitconfig` 是用户自己的文件，宿主只在「从来没有过」的那一刻有权决定它的内容。
 */
class GitIdentityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val home: File get() = File(tmp.root, "home-${System.nanoTime()}")

    private fun names(dir: File): List<String> = dir.listFiles()?.map { it.name }?.sorted().orEmpty()

    // ── 内容与形状 ────────────────────────────────────────────────────────────

    @Test
    fun `01 the default content is exactly the shape git expects`() {
        assertEquals(
            "[user]\n" + "\tname = pi host\n" + "\temail = pi-host@localhost\n" +
                "\n" + "[init]\n" + "\tdefaultBranch = main\n",
            GitIdentity.DEFAULT_CONTENT,
        )
    }

    @Test
    fun `02 the file lands at HOME dot gitconfig and nowhere else`() {
        val h = home
        assertEquals(File(h, ".gitconfig"), GitIdentity.file(h))
        assertEquals("点开头是 git 的约定，不是我们的口味", ".gitconfig", GitIdentity.FILE_NAME)
    }

    /** §2 红线 13：署名不是秘密，凭据面仍只 `auth.json`。 */
    @Test
    fun `03 no credential-shaped text appears in the identity file`() {
        val text = GitIdentity.DEFAULT_CONTENT.lowercase()
        listOf("sk-", "token", "password", "apikey", "api_key", "bearer", "key =").forEach { forbidden ->
            assertFalse("「$forbidden」不该出现在 $text", text.contains(forbidden))
        }
    }

    @Test
    fun `04 the fallback email is address-shaped and the name carries no angle brackets`() {
        // git 对 user.email 只做形状最弱的检查，但 `<none>` 这类值会让 commit 对象里出现难看的署名
        assertTrue(GitIdentity.USER_EMAIL.contains('@'))
        assertFalse(GitIdentity.USER_EMAIL.contains('<'))
        assertFalse(GitIdentity.USER_NAME.contains('<'))
        assertFalse(GitIdentity.USER_NAME.contains('\n'))
    }

    // ── 幂等与「不覆盖」 ──────────────────────────────────────────────────────

    @Test
    fun `05 first call writes the file and reports Written`() {
        val h = home

        assertEquals(GitIdentity.Outcome.Written, GitIdentity.ensureConfigured(h))

        assertEquals(GitIdentity.DEFAULT_CONTENT, GitIdentity.file(h).readText())
    }

    /** 这条是「目录里只该有该在的文件」的老纪律（`RelayEndpointStoreTest` 01 同形）：tmp 必须被清。 */
    @Test
    fun `06 the write leaves exactly one file and no tmp debris`() {
        val h = home

        GitIdentity.ensureConfigured(h)

        assertEquals("home 目录里只该留下 .gitconfig（tmp 残骸说明 rename 那条路没走通）",
            listOf(".gitconfig"), names(h))
    }

    @Test
    fun `07 a second call is a no-op and reports AlreadyPresent`() {
        val h = home
        GitIdentity.ensureConfigured(h)
        val bytes = GitIdentity.file(h).readText()

        val outcome = GitIdentity.ensureConfigured(h)

        assertEquals(GitIdentity.Outcome.AlreadyPresent, outcome)
        assertEquals("第二次一个字节都不该变", bytes, GitIdentity.file(h).readText())
        assertEquals(listOf(".gitconfig"), names(h))
    }

    /**
     * 用户（或 pi 自己跑 `git config --global`）已经写过 → 永不覆盖。
     * 这条保护的是「署名的所有权」：宿主是使用者不是所有者。
     */
    @Test
    fun `08 a hand-authored identity file is never overwritten`() {
        val h = home.apply { mkdirs() }
        val mine = "[user]\n\tname = 我本人\n\temail = me@example.com\n"
        GitIdentity.file(h).writeText(mine)

        assertEquals(GitIdentity.Outcome.AlreadyPresent, GitIdentity.ensureConfigured(h))
        assertEquals(mine, GitIdentity.file(h).readText())
    }

    /**
     * 空文件也要放过。git 对「文件存在但为空」的处理是「没有 user 配置」，
     * 而宿主把它当成「不存在然后重写」会覆盖掉一个**故意的**留白
     * （有人用空 `.gitconfig` 来阻断继承，覆盖它等于替用户改了他的环境）。
     */
    @Test
    fun `09 a deliberately empty identity file is left alone`() {
        val h = home.apply { mkdirs() }
        GitIdentity.file(h).writeText("")

        assertEquals(GitIdentity.Outcome.AlreadyPresent, GitIdentity.ensureConfigured(h))
        assertEquals("", GitIdentity.file(h).readText())
    }

    // ── 失败面 ────────────────────────────────────────────────────────────────

    /**
     * home 位置被一个普通文件占了 → 目录建不出来 → 只能是 [GitIdentity.Outcome.Failed]，
     * 绝不能抛（它挂在 spawn 与建项目两条通路上），也不能留半截 tmp。
     */
    @Test
    fun `10 an unbuildable home dir yields Failed with a cause and throws nothing`() {
        val blocker = File(tmp.root, "blocked")
        blocker.writeText("我是文件不是目录")

        val outcome = GitIdentity.ensureConfigured(blocker)

        val failed = outcome as GitIdentity.Outcome.Failed
        assertTrue(failed.cause, failed.cause.isNotEmpty())
        assertFalse("失败路径不许留 tmp 残骸", names(tmp.root).any { it.contains(AgentConfigFiles.TMP_MARKER) })
    }
}
