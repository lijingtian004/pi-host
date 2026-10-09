package works.earendil.pihost.feature.sessions

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import works.earendil.pihost.core.engine.projects.ProjectNames
import works.earendil.pihost.core.engine.projects.ProjectOutcome
import works.earendil.pihost.core.engine.projects.ProjectStore
import works.earendil.pihost.core.engine.settings.SettingsRepository
import works.earendil.pihost.core.environment.PathLayout

/**
 * 会话页看到的项目面（P3C §3.7）。
 *
 * **为什么是接口而不是直接注 [ProjectStore]**：与 `ModelCredentialSource` /
 * `RelayEndpointSource` / `DiagnosticsSource` 三条完全同构（P2G/P2H/P3A 各立一条）——
 * `ProjectStore` 是 blocking 的文件系统对象，UI 侧要的是 suspend；而「活动项目」这一半
 * 归 [SettingsRepository] 管，`ProjectStore` 根本不知道它存在。本接口是这两半的接缝。
 *
 * **为什么这里没有 `StateFlow<String> active`**：本屏的 [SessionsUiState.Content] 每次重扫
 * 都会整体替换，活动项目跟着进 `Content.activeProject` 就够了。另挂一条派生流要么引入一个
 * 藏在单例里的进程级 scope，要么与快照产生两份真相——两者都比省下的那一行读取贵。
 *
 * 假体只在本模块的测试源集里（`Fakes.kt`），**一份**就够：本接口不并进
 * [works.earendil.pihost.core.engine.ChatEngine]，所以不会重演那次「加一面逼四份假体」。
 */
interface ProjectsSource {

    /** 现有项目名（字典序）。 */
    suspend fun list(): List<String>

    /** 当前活动项目名（读的是设置快照，已洗过形状）。 */
    suspend fun activeName(): String

    /** 建项目；[initGit] = 是否顺手 `git init`（并播种 git 身份）。 */
    suspend fun create(rawName: String, initGit: Boolean): ProjectOutcome

    /** 切活动项目。只影响下一次 `engine.start()`，不打断在跑的会话。 */
    suspend fun setActive(name: String)

    /**
     * 会话的 cwd 属于哪个项目；认不出（外部路径、嵌套过深）给 null。
     *
     * 非 suspend：它只是字符串前缀判断，且 [openSession] 要用它在**发命令之前**决定
     * 要不要给用户提一句「这条会话不归当前项目」。
     */
    fun projectOf(cwd: String?): String?
}

/**
 * [ProjectsSource] 的落地实现：目录归 [ProjectStore]，活动项目归 [SettingsRepository]。
 *
 * blocking 调用一律包 `withContext(Dispatchers.IO)`（`FileModelCredentialSource` 同形）。
 * 本类**不自建 scope**：调用方已经在 `viewModelScope` 里，这里只负责把线程切对。
 */
@Singleton
class FileProjectsSource @Inject constructor(
    @ApplicationContext ctx: Context,
    private val store: ProjectStore,
    private val settings: SettingsRepository,
) : ProjectsSource {

    private val projectsRootPath: String = PathLayout.projectsDir(ctx).absolutePath

    /** 供跨项目判定用：会话的 cwd 落在哪个项目（认不出给 null）。 */
    override fun projectOf(cwd: String?): String? =
        ProjectNames.projectNameOfCwd(projectsRootPath, cwd)

    override suspend fun list(): List<String> = withContext(Dispatchers.IO) { store.list() }

    override suspend fun activeName(): String = settings.activeProject

    override suspend fun create(rawName: String, initGit: Boolean): ProjectOutcome =
        withContext(Dispatchers.IO) { store.create(rawName, initGit) }

    override suspend fun setActive(name: String) {
        settings.setActiveProject(name)
    }
}
