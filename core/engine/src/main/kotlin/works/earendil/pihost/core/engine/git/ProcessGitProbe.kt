package works.earendil.pihost.core.engine.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import works.earendil.pihost.core.process.ProcessStarter

/**
 * 一个目录的 git 快照（P3C §3.5）——顶栏分支 chip 唯一的显示依据。
 *
 * 四条状态而不是两条（「有分支 / 没分支」），因为 UI 必须能区分
 * 「这里不是仓库」（用户该知道，且 `新建项目` 时没勾 git init 就是这个形状）
 * 与「读不出来」（git 缺失、超时、spawn 被 SELinux 拒——**这些是真机上的常态**，
 * 把它们与 NotARepo 混为一谈会把环境问题说成用户操作问题）。
 */
sealed interface GitSnapshot {

    /**
     * 是个仓库。[branch] 是短分支名；[detached] 时 [branch] 是 git 给的 `HEAD` 字样，
     * UI 必须走 [GitSnapshot.displayName] 而不是直接读 [branch]。
     * [newRepo] = 还没有任何提交（`## No commits yet on main`）。
     */
    data class Repo(
        val branch: String,
        val dirty: Boolean,
        val detached: Boolean = false,
        val newRepo: Boolean = false,
    ) : GitSnapshot {
        val displayName: String get() = if (detached) DETACHED_LABEL else branch
    }

    /** 这个目录不是 git 仓库（exit 128 + `not a git repository`）。 */
    data object NotARepo : GitSnapshot

    /** 仓库正被别人操作（`.git/index.lock` 存在）。git 自己会给出「稍后重试」语义，这里照实呈现。 */
    data object Busy : GitSnapshot

    /** 读不出来：缺二进制、spawn 失败、超时、输出形状不认识。[cause] 只进 logcat，不进 UI 文案。 */
    data class Unavailable(val cause: String) : GitSnapshot

    companion object {
        /** 分离 HEAD 的界面文案。git 在这一态给的首行是 `## HEAD (no branch)`。 */
        const val DETACHED_LABEL: String = "分离 HEAD"

        /** 装配里没有探针时的说明串（[NoopGitProbe]），只用于日志。 */
        const val NO_PROBE_CAUSE: String = "本装配未提供 git 探针"
    }
}

/** 读一个目录的 git 快照。挂在 [works.earendil.pihost.core.engine.ChatEngine] 之外单开一面，理由见 [NoopGitProbe]。 */
interface GitProbe {
    suspend fun read(workingDir: String?): GitSnapshot
}

/**
 * 空实现：永远 [GitSnapshot.Unavailable]。
 *
 * 存在的意义是当 `ChatViewModel` 构造参数的**尾默认值**（P3A 给 `auditSink` 用的是同一招）：
 * 这样「新增一条注入边」不要求既有 4 份引擎假体、几十个 VM 构造点全改一遍——
 * 那是 §2 红线 10「每步都要有测试」在改动面上的税，能不交就不交。
 * 生产装配由 `EngineModule` 绑到 [ProcessGitProbe]。
 */
object NoopGitProbe : GitProbe {
    override suspend fun read(workingDir: String?): GitSnapshot = GitSnapshot.Unavailable(GitSnapshot.NO_PROBE_CAUSE)
}

/**
 * 真探针：跑 [GitSpawn.statusCommand] 一条命令，把 porcelain 文本读成 [GitSnapshot]。
 *
 * ## 为什么是宿主 shell-out，而不是问 pi
 * 三条路都堵着，实测过（P3C 探查记录）：
 * 1. `get_state` 的载荷里**没有 `cwd`**（`rpc-mode.ts:448-464`：model/thinkingLevel/…/sessionFile，
 *    只有 `sessionFile` 那条路径里含 cwd 的 slug 形式）；
 * 2. 反解 `--…--` slug 要动 `core/rpc` 与 `docs/protocol.md`，两个都在冻结区；
 * 3. 让 pi 用它的 bash 工具去跑 git，等于把「顶栏该显示什么」交给一次模型往返——
 *    每次进页面都要花钱、要等流式，而且模型可以拒绝。
 *
 * ## 为什么不碰 ChatEngine 接口
 * [works.earendil.pihost.core.engine.ChatEngine] 的 8 个成员被**四个模块各手抄一份假体**
 * （`testFixtures` 在他们的依赖白名单外）。给那个接口加一面 = 同时改四份 fake + 它们的全部用例。
 * 单开 [GitProbe] 只多一条注入边，且 `feature/chat` 自己就能给它做假体。
 *
 * ## 分类靠 stderr 文本，不靠退出码
 * git 对「不是仓库」「index.lock 被占」都给 **exit 128**，唯一可分的是 stderr 字样。
 * 所以下面这两条子串是**本类的解析常量**，改动要连 [GitSnapshot] 的语义一起改。
 * 认不出的形状一律 [GitSnapshot.Unavailable] 并带上原文片段——宁可不显示，不乱显示。
 */
class ProcessGitProbe(
    starter: ProcessStarter,
    envFor: () -> Map<String, String>,
    private val spawn: GitSpawn,
    timeoutMs: Long = ShortProcessRunner.DEFAULT_TIMEOUT_MS,
    maxBytes: Int = ShortProcessRunner.DEFAULT_MAX_BYTES,
) : GitProbe {

    private val runner = ShortProcessRunner(starter, envFor, timeoutMs, maxBytes)

    /** suspend 面：真机与生产调用方走这条（`viewModelScope` + IO，永不占 UI 线程）。 */
    override suspend fun read(workingDir: String?): GitSnapshot =
        withContext(Dispatchers.IO) { readBlocking(workingDir) }

    /**
     * 非 suspend 实体：单测直接调它，省掉一层 runTest 的调度歧义
     * （`SessionScanner.scanBlocking` / `RelayEndpointStore` 的 `*Blocking` 是同一套分工）。
     */
    fun readBlocking(workingDir: String?): GitSnapshot {
        val dir = workingDir?.takeIf { it.isNotBlank() }
            ?: return GitSnapshot.Unavailable("workingDir 为空")

        return when (val result = runner.run(spawn.statusCommand(dir), dir)) {
            is ShortResult.Exited -> parse(result)
            is ShortResult.TimedOut -> GitSnapshot.Unavailable("status 超时（已读 ${result.stdout.length} 字）")
            is ShortResult.SpawnFailed -> GitSnapshot.Unavailable("spawn 失败：${result.cause}")
            is ShortResult.InvalidRequest -> GitSnapshot.Unavailable(result.cause)
        }
    }

    private fun parse(result: ShortResult.Exited): GitSnapshot {
        if (result.stdoutTruncated) return GitSnapshot.Unavailable("status 输出被截断，行数不可信")

        if (result.exitCode != 0) {
            val stderr = result.stderr
            return when {
                stderr.contains(NOT_A_REPO_MARK) -> GitSnapshot.NotARepo
                stderr.contains(INDEX_LOCK_MARK) -> GitSnapshot.Busy
                else -> GitSnapshot.Unavailable("exit=${result.exitCode} ${stderr.lineSequence().firstOrNull().orEmpty()}")
            }
        }

        val lines = result.stdout.split('\n')
        val header = lines.firstOrNull().orEmpty()
        if (!header.startsWith(BRANCH_HEADER_PREFIX)) return GitSnapshot.Unavailable("首行形状不认识：$header")

        val dirty = lines.drop(1).any { it.isNotBlank() }
        val body = header.substring(BRANCH_HEADER_PREFIX.length)

        if (body.startsWith(NO_COMMITS_PREFIX)) {
            return GitSnapshot.Repo(
                branch = body.substring(NO_COMMITS_PREFIX.length).ifBlank { UNKNOWN_BRANCH },
                dirty = dirty,
                newRepo = true,
            )
        }
        if (body.contains(NO_BRANCH_MARK)) {
            return GitSnapshot.Repo(branch = "HEAD", dirty = dirty, detached = true)
        }
        // `## main...origin/main [ahead 1]` / `## main` / `## main...origin/x [gone]`：
        // 分支名永远是 `...` 之前那一段
        val branch = body.substringBefore(TRIPLE_DOT).trim()
        if (branch.isEmpty()) return GitSnapshot.Unavailable("分支名为空：$header")
        return GitSnapshot.Repo(branch = branch, dirty = dirty)
    }

    private companion object {
        /** porcelain v1 `--branch` 首行的固定前缀。 */
        const val BRANCH_HEADER_PREFIX = "## "

        /** 空仓库：`## No commits yet on main`。 */
        const val NO_COMMITS_PREFIX = "No commits yet on "

        /** 分离 HEAD：`## HEAD (no branch)`。 */
        const val NO_BRANCH_MARK = "(no branch)"

        /** 上游跟踪分隔符：`## main...origin/main`。 */
        const val TRIPLE_DOT = "..."

        /** 兜底名：`## No commits yet on `后面真的什么都没有时。 */
        const val UNKNOWN_BRANCH = "HEAD"

        // ── exit≠0 的两条分类标记（都取 git 的固定英文措辞）──
        // 没有第三条：`init`/`status` 都是 git 的 builtin，不经 GIT_EXEC_PATH 的外部程序查找，
        // 所以「libexec 指错」在这里不构成一条可区分的失败形状（真机上 libexec 已验在位）。
        const val NOT_A_REPO_MARK = "not a git repository"
        const val INDEX_LOCK_MARK = "index.lock"
    }
}
