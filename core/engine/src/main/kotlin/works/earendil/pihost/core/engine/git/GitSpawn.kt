package works.earendil.pihost.core.engine.git

/**
 * 宿主 permitted 的两条 git argv（P3C §3.5，形状照 [works.earendil.pihost.core.engine.PiSpawn]）。
 *
 * ## 为什么单开一个类
 * §2 红线 12 是「宿主只许跑 `git init` 与 `git status --porcelain=v1 --branch` 两条命令」——
 * 因为本期定的分工是 **git 的使用主体是 pi，宿主只让它可用 + 把分支显示出来**。
 * 一条「不许出现 add/commit/push/checkout/reset/gc/clean」的红线，如果 argv 在各处就地拼接，
 * 就只能靠人工通读全仓来复核；收进本类之后它变成两件事：
 * 1. grep 本文件即可（`git add` 这类词根本不该出现在 core/engine/git/ 下）；
 * 2. 想加第三条命令的人必须动这个类，也就必然看见下面这段注释。
 *
 * ## 为什么是 `nativeLibraryDir` 而不是 `runtime/bin/git`
 * `runtime/bin/git` 是指向 `lib/arm64/libpi_git.so` 的符号链接，链接本体在 `filesDir`（`app_data_file`）里。
 * 走它等于把「exec 一个 app_data_file 路径」重新引入——而方案 §2.1 整条基石就是**不碰**这条禁令。
 * node 那边（[works.earendil.pihost.core.engine.PiSpawn] `:28`）同样是直取 `libpi_node.so`，
 * 两边保持同一个取法。设备上已实测：直接 exec `libpi_git.so --version` → `git version 2.56.0`。
 *
 * ## `-C <dir>` 而不是 `cd`
 * 不依赖 chdir 是否成功（P2F §4.1(a) 那条 errno 歧义：cwd 不存在与 exe 不存在都是 `error=2`），
 * 也让「目录不存在」以 git 自己的退出码+stderr 呈现，宿主读得懂。
 */
class GitSpawn(private val libDir: String) {

    /** git 主二进制：与 `libpi_node.so` 同一通道（P2B 的 jniLibs 提升产物）。 */
    val gitExecutable: String = "$libDir/$GIT_SO"

    /** 红线 12 允许的第 1 条：把已建好的项目目录变成仓库。 */
    fun initCommand(dir: String): List<String> = listOf(gitExecutable, GLOBAL_C, dir, COMMAND_INIT)

    /**
     * 红线 12 允许的第 2 条：一次拿到「当前分支 + 有无改动」。
     *
     * 一条而不是两条（`rev-parse --abbrev-ref HEAD` + `status --porcelain`）：
     * 探针每次刷新都要跑，两条命令 = 两倍超时面与两倍失败面；`--branch` 的首行就是分支，
     * 后续行天然就是脏标记。
     * `--porcelain=v1` 显式写版本号：这是 git 承诺稳定的机器可读格式，未来 v2 落地时
     * 不靠默认值变化来通知我们。
     */
    fun statusCommand(dir: String): List<String> =
        listOf(gitExecutable, GLOBAL_C, dir, COMMAND_STATUS, PORCELAIN_V1, BRANCH_FLAG)

    companion object {
        const val GIT_SO: String = "libpi_git.so"
        const val GLOBAL_C: String = "-C"
        const val COMMAND_INIT: String = "init"
        const val COMMAND_STATUS: String = "status"
        const val PORCELAIN_V1: String = "--porcelain=v1"
        const val BRANCH_FLAG: String = "--branch"
    }
}
