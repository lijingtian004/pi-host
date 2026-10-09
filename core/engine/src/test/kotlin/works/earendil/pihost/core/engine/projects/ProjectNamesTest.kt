package works.earendil.pihost.core.engine.projects

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import works.earendil.pihost.core.engine.di.EngineModule

/**
 * `ProjectNames`：P3C §3.1 的形状规则与 cwd 反查，逐条覆盖 30 项。
 *
 * 01–23 是形状规则与反查本身；24–30 是 §8.9 附那组——`/data/data` 与 `/data/user/0`
 * 是同一棵树的**两张脸（bind，不是符号链接）**，`canonicalFile` 统一不了它们，
 * 而 pi 用前一张、宿主经 `Context.filesDir` 用后一张。这组用例存在的原因是：
 * **修之前 01–23 全绿，而真机上跨项目告知对每一条会话都返回 null。**
 *
 * 纯 JVM，不碰文件系统也不碰 Android API —— 这个类的价值恰恰在于「同一份规则在 create 侧和读盘侧
 * 都跑得到」，一旦它需要 Context 就注定了某一条路径只能被 Robolectric 摸到一半。
 *
 * 断言风格跟 [works.earendil.pihost.core.engine.settings.RelayEndpointStoreTest]：
 * 消息在前、期望在中、实际在后；每条都写「为什么这条要紧」，不写「assertEquals 做了什么」。
 */
class ProjectNamesTest {

    private val root = "/data/data/works.earendil.pihost/files/projects"

    // ── validate：收什么 ──────────────────────────────────────────────────────

    @Test
    fun `01 plain alnum with punctuation is accepted verbatim`() {
        listOf("default", "pi-host", "app_v2", "notes.1", "A7", "my-project_2.x").forEach { name ->
            assertEquals("$name 该原样通过", name, ProjectNames.validate(name))
        }
    }

    /** 白名单用的是 Unicode 类别而不是 ASCII 区间：中文项目名是自用场景的主流输入。 */
    @Test
    fun `02 cjk names are letters not punctuation`() {
        assertEquals("中文项目", ProjectNames.validate("中文项目"))
        assertEquals("方案A-自用", ProjectNames.validate("方案A-自用"))
    }

    /** 返回 trim 后的值：建目录、落盘、显示三处必须是同一个字符串。 */
    @Test
    fun `03 surrounding whitespace is trimmed out of the returned name`() {
        assertEquals("foo", ProjectNames.validate("  foo  "))
        assertEquals("foo", ProjectNames.validate("\tfoo\n"))
    }

    // ── validate：拒什么 ──────────────────────────────────────────────────────

    @Test
    fun `04 null is rejected`() {
        assertNull("读盘可能是 null（键不存在），校验器必须吃得住", ProjectNames.validate(null))
    }

    @Test
    fun `05 blank is rejected including pure whitespace`() {
        assertNull(ProjectNames.validate(""))
        assertNull("纯空格不能当项目名：它会拼出一个目录名只有一个空格的目录", ProjectNames.validate("   "))
        assertNull(ProjectNames.validate("\t"))
    }

    /** 边界两侧都测：只测「超长拒」不测「刚好收」的话，off-by-one 会一路带到设备上。 */
    @Test
    fun `06 length boundary is inclusive at 48 and exclusive at 49`() {
        val atLimit = "a".repeat(ProjectNames.MAX_LENGTH)
        assertEquals("48 字符刚好合法", atLimit, ProjectNames.validate(atLimit))
        assertNull("49 字符拒，且绝不截断（截断会让两个输入撞同一个目录）",
            ProjectNames.validate(atLimit + "b"))
    }

    @Test
    fun `07 leading dot is rejected`() {
        assertNull("`.hidden` 会与 .tmp/.installed.json 那套按前后缀扫目录的逻辑撞名",
            ProjectNames.validate(".hidden"))
        assertNull(ProjectNames.validate("."))
    }

    @Test
    fun `08 doubled dot anywhere is rejected`() {
        assertNull("`a..b` 里藏着 `..`：单点合法不等于串点合法", ProjectNames.validate("a..b"))
        assertNull(ProjectNames.validate("foo.."))
    }

    /** 与 07/08 对立的一条：单个内部点是允许的，别把规则写成「凡点皆拒」。 */
    @Test
    fun `09 single interior dot stays legal`() {
        assertEquals("a.b", ProjectNames.validate("a.b"))
    }

    @Test
    fun `10 path separator is rejected in both directions`() {
        assertNull("正斜杠是穿越的主通道", ProjectNames.validate("a/b"))
        assertNull(ProjectNames.validate("/abs"))
        assertNull(ProjectNames.validate("a\\b"))
        assertNull(ProjectNames.validate("projects/x"))
    }

    /** 白名单的反面清单：这些字符在别的文件系统/别的消费场景里都有含义，逐个钉住。 */
    @Test
    fun `11 every non-whitelisted metacharacter is rejected`() {
        listOf("a:b", "a*b", "a?b", "a\"b", "a'b", "a<b", "a>b", "a|b", "a;b", "a\$b", "a b!", "a%b", "a#b", "a&b")
            .forEach { bad ->
                assertNull("「$bad」不该通过白名单", ProjectNames.validate(bad))
            }
    }

    @Test
    fun `12 traversal shapes are rejected whatever their spelling`() {
        listOf("..", "../home", "../../etc", "a/../b", "..foo").forEach { bad ->
            assertNull("穿越形状「$bad」必须被拒", ProjectNames.validate(bad))
        }
    }

    // ── sanitizeOrFallback：读盘那道门 ────────────────────────────────────────

    @Test
    fun `13 sanitizeOrFallback falls back on every dirty shape`() {
        listOf(null, "", "  ", "../x", "/abs", "a/b", ".hidden", "a".repeat(49), "a b")
            .forEach { dirty ->
                assertEquals("脏值「$dirty」该回落 default",
                    ProjectNames.DEFAULT_NAME, ProjectNames.sanitizeOrFallback(dirty))
            }
    }

    @Test
    fun `14 sanitizeOrFallback keeps a valid name trimmed`() {
        assertEquals("foo", ProjectNames.sanitizeOrFallback(" foo "))
        assertEquals("自用", ProjectNames.sanitizeOrFallback("自用"))
    }

    /** 折叠键属于格式纪律，不跟设备语言走：土耳其语环境下 `"I".lowercase()` 会掉那个点。 */
    @Test
    fun `15 foldKey uses ROOT locale not the device default`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            assertEquals("istanbul", ProjectNames.foldKey("Istanbul"))
            assertEquals("istanbul", ProjectNames.foldKey("ISTANBUL"))
            assertEquals("与纯 ASCII 输入折叠到同一个键", "istanbul", ProjectNames.foldKey("istanbul"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    /** 单一真相：`EngineModule` 里那个常量若与本类脱钩，「default」就会有两个定义点。 */
    @Test
    fun `16 DEFAULT_NAME is the same value EngineModule still exposes`() {
        assertEquals(EngineModule.DEFAULT_PROJECT, ProjectNames.DEFAULT_NAME)
    }

    // ── projectNameOfCwd：反查 ────────────────────────────────────────────────

    @Test
    fun `17 cwd inside the projects tree resolves to its project name`() {
        assertEquals("default", ProjectNames.projectNameOfCwd(root, "$root/default"))
        assertEquals("my-proj", ProjectNames.projectNameOfCwd(root, "$root/my-proj"))
    }

    /** 这条是本函数唯一真正的陷阱：只比前缀不比分隔符就会把兄弟目录认成自己的。 */
    @Test
    fun `18 a sibling root sharing the prefix is not a project`() {
        assertNull("`projects2` 不是 `projects` 的子目录", ProjectNames.projectNameOfCwd(root, "${root}2/default"))
        assertNull(ProjectNames.projectNameOfCwd(root, "${root}x"))
    }

    @Test
    fun `19 the projects root itself is not a project`() {
        assertNull("根目录没有名字可取", ProjectNames.projectNameOfCwd(root, root))
    }

    /** 宿主从不建嵌套项目；出现两层说明这个会话来自别处，宁缺毋滥。 */
    @Test
    fun `20 a nested cwd belongs to no project`() {
        assertNull(ProjectNames.projectNameOfCwd(root, "$root/default/sub"))
    }

    @Test
    fun `21 a foreign absolute cwd belongs to no project`() {
        assertNull(ProjectNames.projectNameOfCwd(root, "/data/data/com.termux/files/home"))
        assertNull(ProjectNames.projectNameOfCwd(root, ""))
        assertNull(ProjectNames.projectNameOfCwd(root, "   "))
    }

    @Test
    fun `22 null or blank root never matches whatever the cwd is`() {
        assertNull(ProjectNames.projectNameOfCwd(null, "$root/default"))
        assertNull(ProjectNames.projectNameOfCwd("", "$root/default"))
        assertNull(ProjectNames.projectNameOfCwd("   ", "$root/default"))
    }

    /** 调用方传的是 `File.absolutePath`，但 DataStore 里的历史值可能带尾斜杠，两者都该工作。 */
    @Test
    fun `23 a trailing separator on the root is tolerated`() {
        assertEquals("default", ProjectNames.projectNameOfCwd("$root/", "$root/default"))
        assertEquals("default", ProjectNames.projectNameOfCwd("$root///", "$root/default"))
    }

    // ── P3C §8.9 附：data 目录两张脸（真机逼出来的一组）─────────────────────────

    /**
     * 设备上实测：`/data/data/<pkg>` 与 `/data/user/0/<pkg>` 是同一棵树的两张脸（bind），
     * `readlink -f` 把两种拼法各自解析成自己，所以 canonicalFile 统一不了它们。
     * 而 pi 记 `/data/data/…`、宿主经 `Context.filesDir` 拿 `/data/user/0/…`。
     */
    @Test
    fun `24 foldDataAlias maps the multi-user spelling onto the data-data one`() {
        assertEquals(
            "/data/data/works.earendil.pihost/files/projects/3",
            ProjectNames.foldDataAlias("/data/user/0/works.earendil.pihost/files/projects/3"),
        )
    }

    /** 多用户安装（工作资料/分身）也是同一个形状，别只认 user/0。 */
    @Test
    fun `25 foldDataAlias folds any user id, not just 0`() {
        assertEquals(
            "/data/data/pkg/files/projects/x",
            ProjectNames.foldDataAlias("/data/user/11/pkg/files/projects/x"),
        )
    }

    @Test
    fun `26 foldDataAlias leaves the data-data spelling and foreign paths alone`() {
        val same = "/data/data/pkg/files/projects/3"
        assertEquals(same, ProjectNames.foldDataAlias(same))
        assertEquals("/tmp/junit123/projects/3", ProjectNames.foldDataAlias("/tmp/junit123/projects/3"))
        assertEquals("/data/data", ProjectNames.foldDataAlias("/data/data"))
    }

    /** 这条就是真机上失效的那个形状：根用宿主拼法、cwd 用 pi 拼法。修前返回 null。 */
    @Test
    fun `27 the host spelling of the root matches pi's spelling of the cwd`() {
        val hostRoot = "/data/user/0/works.earendil.pihost/files/projects"
        val piCwd = "/data/data/works.earendil.pihost/files/projects/3"

        assertEquals("3", ProjectNames.projectNameOfCwd(hostRoot, piCwd))
    }

    /** 反方向也要成（调用方哪一侧换拼法都不该改变结论）。 */
    @Test
    fun `28 the alias works in both directions`() {
        val piRoot = "/data/data/works.earendil.pihost/files/projects"
        val hostCwd = "/data/user/0/works.earendil.pihost/files/projects/default"

        assertEquals("default", ProjectNames.projectNameOfCwd(piRoot, hostCwd))
    }

    /**
     * 折叠不许顺手放宽归属判断：别的包的同形路径不能被认成本项目。
     * 这是这条修法的唯一真实风险 —— 把前缀变短了，所以必须钉住。
     */
    @Test
    fun `29 folding does not let another package's dir masquerade as a project`() {
        val root = "/data/user/0/works.earendil.pihost/files/projects"
        assertNull(
            "com.other 的目录不是本项目树的一部分",
            ProjectNames.projectNameOfCwd(root, "/data/data/com.other/files/projects/3"),
        )
        assertNull(
            "同包下多一层的兄弟目录也不算",
            ProjectNames.projectNameOfCwd(root, "/data/data/works.earendil.pihost/files2/projects/3"),
        )
    }

    /** 既有那 23 条用的是宿主页面的临时目录，折叠不该改变它们任何一条结论。 */
    @Test
    fun `30 folding is a no-op for the sandbox paths the other cases use`() {
        assertEquals("default", ProjectNames.projectNameOfCwd(root, "$root/default"))
        assertNull(ProjectNames.projectNameOfCwd(root, "${root}2/default"))
    }
}
