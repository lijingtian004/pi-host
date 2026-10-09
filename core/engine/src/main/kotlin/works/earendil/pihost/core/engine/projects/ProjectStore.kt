package works.earendil.pihost.core.engine.projects

import java.io.File
import works.earendil.pihost.core.engine.git.GitIdentity
import works.earendil.pihost.core.engine.git.GitInitOutcome
import works.earendil.pihost.core.engine.git.GitInitializer

/** 一次建项目的结局。四条失败分支都必须是**可以对用户说清**的形状。 */
sealed interface ProjectOutcome {

    /**
     * 目录已建成。[git] / [identity] 记录两条附属动作的结局——**它们失败不把项目变成失败**：
     * 「项目建好了但这次没 init 成功」是既真实又更可恢复的事实，
     * 比「整体报错、用户以为什么都没发生、其实目录已经在了」诚实。
     */
    data class Created(val name: String, val git: GitInitOutcome, val identity: GitIdentity.Outcome) : ProjectOutcome

    /** 同名目录已存在。 */
    data class AlreadyExists(val name: String) : ProjectOutcome

    /** 与 [existing] 只差大小写。拒绝它是因为在用户眼里它们是同一个项目。 */
    data class CaseCollision(val name: String, val existing: String) : ProjectOutcome

    /** 名字不合 [ProjectNames] 的规则。此时**磁盘一个字节都没动**。 */
    data class Invalid(val raw: String) : ProjectOutcome

    /** 校验与查重都过了，但 `mkdirs` 没成。[cause] 原样进日志。 */
    data class NotCreated(val name: String, val cause: String) : ProjectOutcome
}

/**
 * 项目目录的账本（P3C §3.3）：`filesDir/projects/<name>/`，一个目录 = 一个项目。
 *
 * ## 形状为什么是「blocking + 无 Android API + 两个 File 入参」
 * 与 `RelayEndpointStore` / `ModelCredentialStore` 完全同构（那两者的构造入参就是 `homeDir: File`）：
 * 单测用 `TemporaryFolder` 造根目录，**绝不碰设备路径常量**（P2F §7.1 的同一课）。
 * 挂起与线程切换归 `:feature:sessions` 的 `FileProjectsSource`，本类不掺。
 *
 * ## 三道门在这层的落点
 * 校验规则本身在 [ProjectNames]（纯函数）；本类负责的是**规则管不到的那一半**：
 * 文件系统上的实际包含关系。名字合法不等于路径落在 `projects/` 里面——
 * `File(projectsRoot, "a/b")` 里的 `/` 已被 [ProjectNames.validate] 挡住，但
 * 「`projectsRoot` 本身被换成一个符号链接」这类事实只有 canonical 化才看得见。
 * 于是 [pathOf] 是「校验 + canonical 包含断言」两道，缺一不可。
 *
 * ## `list()` 绝不动磁盘
 * UI 每次进会话页都要列一次。列一次就 mkdir 一次，等于把「用户打开了列表」
 * 变成「用户创建了一个项目」——P3C 重划的红线（私有目录只许两类新落点）第一个破的就是这条。
 *
 * ## 为什么这里没有删除
 * `projects/<name>` 是**用户的代码**。删项目等于删代码，而宿主对「哪些文件是 pi 生成的、
 * 哪些是用户自己拷进来的」一无所知。宁可让孤儿目录留在盘上（它们会出现在列表里、可辨认），
 * 也不做一件不可逆又不可恢复的事。要做也得是「先把目录改名成 `<name>.deleted` 再由用户确认」，
 * 那是另一期的规模。
 */
class ProjectStore(
    private val projectsRoot: File,
    private val homeDir: File,
    private val git: GitInitializer,
) {

    /** 现有项目名，按 [String] 字典序；不建目录、不跟随符号链接、忽略不合 [ProjectNames] 规则的条目。 */
    fun list(): List<String> =
        projectsRoot.listFiles { f -> f.isDirectory }
            ?.mapNotNull { f -> ProjectNames.validate(f.name) }
            ?.sorted()
            .orEmpty()

    /**
     * 名字 → 目录，或者 null（名字非法，或解析后落不出 `projects/`）。
     *
     * 两道检查的分工：[ProjectNames.validate] 管词法（`..`、`/`、空、超长），
     * 下面的 canonical 包含断言只管**路径法**。词法过不了连 `File` 都不该拼出来，
     * 所以顺序不能反。
     */
    fun pathOf(rawName: String?): File? {
        val name = ProjectNames.validate(rawName) ?: return null
        val candidate = File(projectsRoot, name)
        val root = projectsRoot.canonicalOrNull() ?: return null
        val resolved = candidate.canonicalOrNull() ?: return null
        if (resolved.path != root.path && !resolved.path.startsWith(root.path + File.separator)) return null
        return candidate
    }

    /**
     * 建项目：校验 → 查重（含大小写折叠）→ canonical 包含 → mkdirs → 身份 → `git init`。
     *
     * 顺序就是语义：
     * - **先 [ProjectNames.validate] 后一切**。非法名绝不走到 `File` 拼接，更不走到 mkdirs；
     * - **查重在 mkdirs 之前**。否则 `exists()` 会在一个已经建到一半的目录上返回 true，
     *   而 [ProjectOutcome.NotCreated] 与 [ProjectOutcome.AlreadyExists] 就分不开了；
     * - **canonical 包含放在校验之后、mkdirs 之前**，词法过、路径法也过，才准碰盘；
     * - 后两步（身份 / init）**失败不改写前一步的结论**，见 [ProjectOutcome.Created] 的字段说明。
     */
    fun create(rawName: String?, initGit: Boolean): ProjectOutcome {
        val name = ProjectNames.validate(rawName) ?: return ProjectOutcome.Invalid(rawName.orEmpty())
        if (projectsRoot.isFile) return ProjectOutcome.NotCreated(name, "projects 根被一个普通文件占了")

        val existing = list()
        if (existing.contains(name)) return ProjectOutcome.AlreadyExists(name)
        val key = ProjectNames.foldKey(name)
        existing.firstOrNull { ProjectNames.foldKey(it) == key }
            ?.let { return ProjectOutcome.CaseCollision(name, it) }

        val dir = pathOf(name) ?: return ProjectOutcome.Invalid(name)
        if (dir.exists()) return ProjectOutcome.AlreadyExists(name)

        if (!dir.mkdirs() && !dir.isDirectory) {
            return ProjectOutcome.NotCreated(name, "无法创建 ${dir.absolutePath}")
        }

        if (!initGit) return ProjectOutcome.Created(name, GitInitOutcome.Skipped, GitIdentity.Outcome.Skipped)

        val identity = GitIdentity.ensureConfigured(homeDir)
        val gitOutcome = git.init(dir)
        return ProjectOutcome.Created(name, gitOutcome, identity)
    }

    /** 目录在不在（不校验名字的合法性之外的任何事）。 */
    fun exists(name: String): Boolean = pathOf(name)?.isDirectory == true

    /** 一个项目目录的绝对路径字符串；非法名给 null。 */
    fun pathStringOf(name: String?): String? = pathOf(name)?.absolutePath
}

/** [File.canonicalFile] 的失败不抛版本——路径法检查不该因为一次 IO 异常崩掉整个 UI。 */
private fun File.canonicalOrNull(): File? = runCatching { canonicalFile }.getOrNull()
