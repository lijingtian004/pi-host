package works.earendil.pihost.core.engine

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import works.earendil.pihost.core.engine.git.GitIdentity
import works.earendil.pihost.core.environment.PathLayout

/**
 * 运行时安装面的可注入形态（规格书 §4.4）。
 *
 * [AssetInstaller] 本体是 P2A/P2B 交付的冻结语义（红线 §2：`AssetInstallerTest` 5 +
 * `RealRuntimeAssetsInstallTest` 2 必须原样绿），所以这里**只做包装**：
 * 把 `ensureInstalled(ctx): InstallResult` 压成 `Boolean`，进度原样转出去。
 * 失败原因留在 [InstallProgress.Failed] 里，由 ChatViewModel 取作 `ChatUiState.Failed.cause`。
 */
interface RuntimeInstall {
    val progress: StateFlow<InstallProgress>

    /** 已装且版本一致 = true；任何失败 = false（且 progress 变 Failed）。 */
    suspend fun ensureInstalled(): Boolean
}

/**
 * [RuntimeInstall] 的 production 实现：包 [AssetInstaller] + applicationContext
 * （ctx 经 `@ApplicationContext` 注入，规格书 §4.4）。
 *
 * 为什么不直接把 `progress` 写成 `installer.progress` 的别名：
 * `AssetInstaller` 只在**它自己捕获到异常**时才写 [InstallProgress.Failed]；
 * 若 `ensureInstalled` 这个 suspend 调用整体抛出（CancellationException 之外的 bug、
 * 或 `withContext(Dispatchers.IO)` 派发失败），安装器的状态会**停在 Extracting**，
 * 首启 UI 就永远转圈。这里持一份镜像：转发的同时，异常路径由本类补写 Failed。
 * 镜像的收集协程挂在 [EngineScope]（进程级 `@Singleton` scope），与引擎同寿。
 */
@Singleton
class RuntimeInstallImpl @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val installer: AssetInstaller,
    @EngineScope scope: CoroutineScope,
) : RuntimeInstall {

    private val _progress = MutableStateFlow<InstallProgress>(installer.progress.value)

    override val progress: StateFlow<InstallProgress> = _progress.asStateFlow()

    init {
        scope.launch { installer.progress.collect { _progress.value = it } }
    }

    override suspend fun ensureInstalled(): Boolean {
        val result = runCatching { installer.ensureInstalled(ctx) }.getOrElse { throwable ->
            if (throwable is kotlinx.coroutines.CancellationException) throw throwable
            val message = throwable.message ?: throwable.javaClass.simpleName
            _progress.value = InstallProgress.Failed(message)
            return false
        }
        return when (result) {
            // P3C §3.4：两条成功支都要补一次 git 身份。
            // UpToDate 尤其关键——你这台设备早就装好了 runtime，升级后走的就是这条支，
            // 若只在 Installed 分支播种，`.gitconfig` 永远不会出现，pi 的 commit 继续死在
            // "unable to auto-detect email address"。ensureConfigured 幂等且只 exists() 一次。
            is InstallResult.UpToDate -> seedGitIdentity().let { true }
            is InstallResult.Installed -> seedGitIdentity().let { true }
            is InstallResult.Failed -> {
                // AssetInstaller 自己已把同一个 message 写进它的 progress；这里再写一次是幂等的
                // （StateFlow conflate），只为保证「镜像一定见过 Failed」。
                _progress.value = InstallProgress.Failed(result.message)
                false
            }
        }
    }

    /**
     * 只当事实记录，不改本方法的返回值：git 身份写不成 = pi 暂时不能 commit，
     * 不等于「运行时没装好」，更不该让首启 UI 卡在 Failed 上——
     * 真正的失败原因由 [GitIdentity] 放进返回值，`新建项目` 那条路会把它显示出来。
     */
    private fun seedGitIdentity(): GitIdentity.Outcome = GitIdentity.ensureConfigured(PathLayout.homeDir(ctx))
}
