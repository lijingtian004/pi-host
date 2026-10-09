package works.earendil.pihost.core.engine.git

import java.io.File
import works.earendil.pihost.core.engine.settings.AgentConfigFiles

/**
 * git 提交身份的落地（P3C §3.4）。
 *
 * ## 为什么必须有它
 * pi 在工作目录里跑 `git commit` 时，git 需要一个 author/committer。设备上**两处都没有**：
 * 系统级配置文件不存在（实测 Android 15：`/etc` 目录本身在，但 `/etc/gitconfig` 与
 * `/system/etc/gitconfig` 都 `No such file or directory`），`$HOME/.gitconfig` 宿主从不创建。
 * git 于是尝试从 `getpwuid` + hostname 自动推断，在 Android 上这一步必然失败，
 * 报
 * `fatal: unable to auto-detect email address (got 'u0_a401@localhost.(none)')`，
 * **拒绝提交**。也就是说：在 P3C 之前，即便 `git init` 成功、pi 也被允许跑 git，
 * 它也**永远提交不了任何东西**——本期「让 git 能被 agent 用起来」这条需求真正的堵点就是这里，
 * 而不是二进制（`libpi_git.so` 一直在，实测 `git version 2.56.0`）。
 *
 * ## 为什么落在文件而不是环境变量
 * 最直觉的做法是往 spawn env 里塞 `GIT_AUTHOR_NAME` / `GIT_COMMITTER_EMAIL`，但
 * [works.earendil.pihost.core.environment.EnvFactory] 在 §2 红线的**冻结区**里，本期一字不能动。
 * 而 `$HOME` 已经是 `filesDir/home`（`EnvFactory.kt:20`），git 的全局配置路径正是 `$HOME/.gitconfig`
 * ——同一条通道、不碰冻结面、`git config --global` 的读口天然一致。
 *
 * ## 为什么不写 safe.directory
 * `filesDir` 与 `~/.gitconfig` 的属主都是宿主自己的 uid（`u0_a401`），git 的
 * "dubious ownership" 检查（`/etc/gitconfig` 里那条）只在**目录属主 ≠ 当前 uid** 时触发。
 * 本项目自建目录必然同 uid，所以不需要。真机上若出现该报错，那是环境异常，
 * 应按 REPORT-P3C §8 的「只有真机可验」处理，而不是在这里预置一条来路不明的放行。
 *
 * ## 为什么不含凭据
 * `user.name` / `user.email` 是**署名**，不是秘密：它们会进每一个 commit，公开可见。
 * token/key 一律走 pi 的 `auth.json`（§2 红线 11），所以「`.gitconfig` 里没有凭据」是设计事实，
 * 也被 `GitIdentityTest` 的第 08 条钉住。
 */
object GitIdentity {

    /** 文件名（落 `PathLayout.homeDir(ctx)` 下，即 `$HOME/.gitconfig`）。点开头，git 认这个形状。 */
    const val FILE_NAME: String = ".gitconfig"

    const val USER_NAME: String = "pi host"
    const val USER_EMAIL: String = "pi-host@localhost"

    /**
     * 首启内容。缩进用 **tab**：git 的 ini 解析器不在意，但 `git config -l` 之后
     * 有人会用编辑器打开它，保持与 git 自己写出的形状一致。
     *
     * `[init] defaultBranch = main`：不写的话新仓库跟着 git 2.56 的默认值走，
     * 而那个值取决于 `init.defaultBranch` 是否被设过——宿主该给一个确定答案，
     * 别让分支名成为设备上看不见的随机量（顶栏 chip 显示的就是它）。
     */
    val DEFAULT_CONTENT: String = buildString {
        append("[user]\n")
        append("\tname = ").append(USER_NAME).append('\n')
        append("\temail = ").append(USER_EMAIL).append('\n')
        append('\n')
        append("[init]\n")
        append("\tdefaultBranch = main\n")
    }

    /** 四种结局都是正常返回值。本对象**永不抛**，也不打日志（理由见 [ProcessGitInitializer] 的类头）。 */
    sealed interface Outcome {

        /** 之前没有，本调用写出了默认身份。 */
        data object Written : Outcome

        /** 已经存在（包括**空文件**），一个字都没动。 */
        data object AlreadyPresent : Outcome

        /** **根本没尝试**：本次建项目没要 git，身份自然也不用写。与 [Failed] 分开，
         * 否则 UI 会对一个「用户本来就没勾」的事报失败。 */
        data object Skipped : Outcome

        /** 建目录或原子写失败；[cause] 交给调用方决定是提示还是忽略。 */
        data class Failed(val cause: String) : Outcome
    }

    fun file(homeDir: File): File = File(homeDir, FILE_NAME)

    /**
     * 幂等地保证 `$HOME/.gitconfig` 存在。
     *
     * [Outcome.Failed] 的两条来源（目录建不出来 / rename 失败）都是 **git 暂时不能用**，
     * 不是 **app 不能跑**：所以这里吞掉异常、把原因放进返回值，
     * 让 pi 自己在 commit 时撞上 git 的原话（那句报错比我们的日志对用户更有用）。
     */
    fun ensureConfigured(homeDir: File): Outcome {
        val target = file(homeDir)
        if (target.exists()) return Outcome.AlreadyPresent
        return runCatching {
            AgentConfigFiles.ensureDirectory(homeDir)
            AgentConfigFiles.writeTextAtomic(homeDir, target, DEFAULT_CONTENT)
        }.fold(
            onSuccess = { Outcome.Written },
            onFailure = { Outcome.Failed("${it.javaClass.simpleName}: ${it.message}") },
        )
    }
}
