package works.earendil.pihost.core.engine.git

import java.io.File
import works.earendil.pihost.core.process.ProcessStarter

/**
 * 一次 `git init` 的结局（P3C §3.5/§3.6）。
 *
 * 四条分支都不是异常路径，且**都带得出原因**：本类不打 logcat——`core/engine` 里没有
 * `returnDefaultValues`，一调 `android.util.Log` 就只能整组用例挂 Robolectric
 * （`RelayEndpointStore`/`ProjectStore` 同为纯 JVM 可测，是同一个取舍）；
 * 更重要的是失败原因在设备上要**让用户看见**（新建项目卡的一条 notice），
 * 埋在日志里等于没报。
 */
sealed interface GitInitOutcome {

    /** 新仓库已建好（exit 0）。 */
    data object Initialized : GitInitOutcome

    /** 这个目录已经是仓库（exit 0，git 原话 `Reinitialized existing Git repository`）。 */
    data object AlreadyARepo : GitInitOutcome

    /** **根本没尝试**（建项目时没勾「初始化为仓库」）。与 [Failed] 必须分开：
     * 一个是「用户没要」，一个是「要了但没成」，UI 上说的话完全不同。 */
    data object Skipped : GitInitOutcome

    /** 没跑成：spawn 失败 / 超时 / 非零退出。[cause] 是 git 或内核的原话片段。 */
    data class Failed(val cause: String) : GitInitOutcome
}

/** 把「跑 git」这件事抽象出来，让 `ProjectStore` 的单测不需要任何真进程。 */
interface GitInitializer {
    fun init(dir: File): GitInitOutcome
}

/**
 * 真 init：跑 [GitSpawn.initCommand]（红线 12 允许的第 1 条，也是**唯一**一条写操作的 git 命令）。
 *
 * 与 [ProcessGitProbe] 分开两个类而不是合成一个「GitClient」，是有意的：
 * 读面要给 UI 用（suspend、每拍都可能调），写面只在建项目时调一次（blocking、失败可容忍）。
 * 合成一个类就会共享一个 `suspend` 形状，而 `ProjectStore` 是纯 blocking 的
 * （它的宿主 `FileProjectsSource` 才负责切 `Dispatchers.IO`）——
 * 把两种调度混进一个类，是 P2H §3.1 抽 `AgentConfigFiles` 时点名要避免的那类耦合。
 *
 * 「已存在的仓库」这条分支必须单独认：用户在 `projects/foo` 里已经 `git init` 过、
 * 又删掉宿主记录重建同名项目的路径不长，但 `init` 本身对已有仓库是**无害的**
 * （reinitialize），所以这里把它当成功，而不是当错误报给用户。
 */
class ProcessGitInitializer(
    starter: ProcessStarter,
    envFor: () -> Map<String, String>,
    private val spawn: GitSpawn,
    timeoutMs: Long = ShortProcessRunner.DEFAULT_TIMEOUT_MS,
) : GitInitializer {

    private val runner = ShortProcessRunner(starter, envFor, timeoutMs)

    override fun init(dir: File): GitInitOutcome = classify(runner.run(spawn.initCommand(dir.absolutePath), dir.absolutePath))

    /** 分类与 [ProcessGitProbe.parse] 同一条纪律：**认得出的才定性，认不出的带原文回落**。 */
    private fun classify(result: ShortResult): GitInitOutcome = when (result) {
        is ShortResult.Exited -> when {
            result.exitCode != 0 -> GitInitOutcome.Failed(
                "exit=${result.exitCode} ${result.stderr.lineSequence().firstOrNull().orEmpty()}",
            )
            result.stdout.contains(REINITIALIZED_MARK) || result.stderr.contains(REINITIALIZED_MARK) ->
                GitInitOutcome.AlreadyARepo
            else -> GitInitOutcome.Initialized
        }

        is ShortResult.TimedOut -> GitInitOutcome.Failed("init 超时（已读 ${result.stdout.length} 字）")
        is ShortResult.SpawnFailed -> GitInitOutcome.Failed("spawn 失败：${result.cause}")
        is ShortResult.InvalidRequest -> GitInitOutcome.Failed(result.cause)
    }

    private companion object {
        /** git 对「目录里已经有 .git」的原话（`Reinitialized existing Git repository in …`）。 */
        const val REINITIALIZED_MARK = "Reinitialized existing Git repository"
    }
}
