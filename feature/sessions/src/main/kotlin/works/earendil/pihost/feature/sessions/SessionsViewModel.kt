package works.earendil.pihost.feature.sessions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import works.earendil.pihost.core.engine.ChatEngine
import works.earendil.pihost.core.engine.SessionState
import works.earendil.pihost.core.engine.git.GitInitOutcome
import works.earendil.pihost.core.engine.projects.ProjectNames
import works.earendil.pihost.core.engine.projects.ProjectOutcome
import works.earendil.pihost.core.rpc.RpcCommand
import works.earendil.pihost.feature.sessions.ui.SessionsStrings

/** 会话列表屏的界面状态（规格书 §4.1）。 */
sealed interface SessionsUiState {

    /** 首次扫描中（进屏即触发）。 */
    data object Loading : SessionsUiState

    /**
     * 扫描完成。[sessions] 为空 = **空态**（UI 显示引导文案 + 新建按钮，规格书 §4.1 末条）；
     * [refreshing] = 下拉刷新进行中的非破坏性态（不清列表，避免下拉时整屏闪白）。
     */
    data class Content(
        val sessions: List<ScannedSession>,
        val refreshing: Boolean = false,
        /** 现有项目名（字典序）。空表是合法形状：`projects/` 还没被建过。 */
        val projects: List<String> = emptyList(),
        /** 活动项目（新会话将落在它的目录里）。已经过形状清洗，永不为 null。 */
        val activeProject: String = ProjectNames.DEFAULT_NAME,
    ) : SessionsUiState

    /** 扫描本身出错（假体抛异常 / 未来换成远端来源时的失败面）。 */
    data class Failure(val cause: String) : SessionsUiState
}

/**
 * 一次性导航意图。[sessionId] = 引擎侧当前活会话的 id；
 * `null` = 「没有活会话」，此时路由落到 chat 的默认入口，由聊天页的 InstallGate 负责启动。
 */
data class SessionNavRequest(val sessionId: String?)

/**
 * 会话列表的 ViewModel（规格书 §4.1）：scanner + ChatEngine 注入，
 * switch/new 的命令发送走 `ChatEngine.send`（原文要求）。
 *
 * **三条纪律**：
 * 1. **不自己 `engine.start()`**。启动引擎是 ChatViewModel 的职责（它手里才有
 *   `RuntimeInstall`，才会在 spawn 前发 FGS 意图、才装运行时）。sessions 屏在没有活会话时
 *   **只导航、不命令**，并把原因写进 [notice]（记入收尾报告：这是 §4.1 的可执行化解释，
 *   越过这一步去 start 会绕过 §13 偏差 6 的 maxParallelSessions 门与安装门）。
 * 2. **发命令后不等待 [works.earendil.pihost.core.rpc.PiEvent.Response]**。等回执要先
 *   `engine.events(id)` 订阅、再退订，sessions 屏没有这条订阅生命周期；而 chat 屏接手后
 *   自然从事件流里看到切换结果。转录清场由 ChatViewModel 的 `switchSession`（§4.3）负责。
 * 3. **删除只删文件**（§13 偏差 3），删完立刻重扫，列表与磁盘不保留中间态。
 */
@HiltViewModel
class SessionsViewModel @Inject constructor(
    app: Application,
    private val scanner: SessionScanner,
    private val engine: ChatEngine,
    private val projects: ProjectsSource,
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow<SessionsUiState>(SessionsUiState.Loading)
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)

    /** 一次性提示（切换失败 / 删除失败 / 无活会话）；UI 消费后 [consumeNotice] 清掉。 */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _navigation = MutableStateFlow<SessionNavRequest?>(null)

    /** 一次性导航意图；UI 消费后 [consumeNavigation] 清掉，避免重组时重复导航。 */
    val navigation: StateFlow<SessionNavRequest?> = _navigation.asStateFlow()

    private val _pendingDelete = MutableStateFlow<ScannedSession?>(null)

    /**
     * 二步确认的第一态（§13 偏差 3：删除 = 删 jsonl）。
     * 放在 VM 而不是 composable 的 `remember` 里，是为了让「确认前绝不删文件」这条
     * 语义在**单测**里就能钉死——Robolectric 下长按手势注入不可靠（同 RECON-P2E §7.8
     * 对下拉刷新的判断），UI 测试只能覆盖到点击，长按的接线由这段状态 + 目视保证。
     */
    val pendingDelete: StateFlow<ScannedSession?> = _pendingDelete.asStateFlow()

    init {
        refresh()
    }

    /** 重扫目录（进屏一次 + 下拉刷新 + 删除后）。 */
    fun refresh() {
        viewModelScope.launch {
            val current = _uiState.value
            _uiState.value = if (current is SessionsUiState.Content) {
                current.copy(refreshing = true)
            } else {
                SessionsUiState.Loading
            }
            val outcome = runCatching { scanner.scan() }
            // 项目面单独兜：列目录失败不该把整屏说成 Failure（会话列表还在，只是顶部项目条空着），
            // 也不该让一次 fs 抖动把「你的历史会话」显示成「读不出来」。
            val names = runCatching { projects.list() }.getOrDefault(emptyList())
            val active = runCatching { projects.activeName() }.getOrDefault(ProjectNames.DEFAULT_NAME)
            outcome.fold(
                onSuccess = { list -> _uiState.value = SessionsUiState.Content(list, projects = names, activeProject = active) },
                onFailure = { t ->
                    _uiState.value = SessionsUiState.Failure((t.message ?: t::class.java.simpleName).take(200))
                },
            )
        }
    }

    /** 点击某条 → `SwitchSession(sessionPath)` → 导航 chat/{sessionId}（§4.1 原文顺序）。 */
    fun openSession(session: ScannedSession) {
        val id = liveSessionId()
        if (id == null) {
            _notice.value = NO_LIVE_SESSION
            _navigation.value = SessionNavRequest(null)
            return
        }
        // 跨项目续用**照常放行**，只提一句（P3C §3.7）。
        // 为什么不必拦：`switch_session` 只传 sessionPath（`rpc-mode.ts:603`），
        // pi 会按会话文件里记录的 cwd 重建自己的服务（`agent-session-runtime.ts:210-215`），
        // 所以工具本来就在正确的项目目录里跑；要提的是另一件事——
        // **之后**在这条进程里点「新建会话」，新会话落在当前活动项目，不是这条会话的项目。
        val owner = projects.projectOf(session.cwd)
        val current = (_uiState.value as? SessionsUiState.Content)?.activeProject
        if (owner != null && current != null && owner != current) {
            _notice.value = "这条会话属于项目 $owner，当前活动项目是 $current：继续可用，但新建会话会落在 $current"
        }
        viewModelScope.launch {
            val sent = runCatching { engine.send(id, RpcCommand.SwitchSession(session.sessionPath)) }
            val failure = sent.exceptionOrNull()
            if (failure != null) {
                _notice.value = "切换失败：${failure.message ?: failure::class.java.simpleName}"
                return@launch
            }
            _navigation.value = SessionNavRequest(id)
        }
    }

    /** FAB 新建 → `NewSession` → 导航 chat/{sessionId}（§4.1：命令 + 导航）。 */
    fun newSession() {
        val id = liveSessionId()
        if (id == null) {
            _notice.value = NO_LIVE_SESSION
            _navigation.value = SessionNavRequest(null)
            return
        }
        viewModelScope.launch {
            val sent = runCatching { engine.send(id, RpcCommand.NewSession()) }
            val failure = sent.exceptionOrNull()
            if (failure != null) {
                _notice.value = "新建失败：${failure.message ?: failure::class.java.simpleName}"
                return@launch
            }
            _navigation.value = SessionNavRequest(id)
        }
    }

    /**
     * 新建项目（P3C §3.7）：`projects/<name>/` + 可选 `git init`。
     *
     * **建成即设为活动项目**：让用户「建完还要再点一次才能用」是多余的摩擦，
     * 而这条路径上没有任何需要用户二次确认的破坏性动作（建目录是可撤销的、init 是幂等的）。
     * 名字非法/重名一律只出 notice，**不动任何磁盘状态**（拒绝发生在 [ProjectStore.create] 里）。
     */
    fun createProject(rawName: String, initGit: Boolean) {
        viewModelScope.launch {
            val outcome = runCatching { projects.create(rawName, initGit) }
            outcome.fold(
                onFailure = { t -> _notice.value = "创建失败：${t.message ?: t::class.java.simpleName}" },
                onSuccess = { result ->
                    when (result) {
                        is ProjectOutcome.Created -> {
                            projects.setActive(result.name)
                            _notice.value = createdNotice(result, initGit)
                        }

                        is ProjectOutcome.AlreadyExists -> _notice.value = "项目「${result.name}」已存在"
                        is ProjectOutcome.CaseCollision ->
                            _notice.value = "已有同名项目「${result.existing}」（只差大小写）"

                        is ProjectOutcome.Invalid -> _notice.value = SessionsStrings.PROJECT_INVALID_NAME
                        is ProjectOutcome.NotCreated -> _notice.value = "创建失败：${result.cause}"
                    }
                    refresh()
                },
            )
        }
    }

    /** 三种 init 结局要说三句话：用户没要 / 要了没成 / 成了，混在一起就是在骗用户。 */
    private fun createdNotice(created: ProjectOutcome.Created, initGit: Boolean): String = when {
        !initGit -> "已创建项目 ${created.name}（未初始化 git）"
        created.git is GitInitOutcome.Initialized -> "已创建项目 ${created.name}（git 仓库已建）"
        created.git is GitInitOutcome.AlreadyARepo -> "已创建项目 ${created.name}（目录里本来就有仓库）"
        else -> "已创建项目 ${created.name}，但 git init 没成：${(created.git as GitInitOutcome.Failed).cause}"
    }

    /** 切活动项目：只影响下一次 `engine.start()`，在跑的会话不受影响（见 [SessionSupervisor] 出生钉死）。 */
    fun useProject(name: String) {
        viewModelScope.launch {
            projects.setActive(name)
            _notice.value = "活动项目：$name（新会话落在它里面；在跑的会话不受影响）"
            refresh()
        }
    }

    /** 长按某行 → 挂出确认卡（第一步，**不碰磁盘**）。 */
    fun requestDelete(session: ScannedSession) {
        _pendingDelete.value = session
    }

    /** 确认卡上点「取消」→ 收起，什么都不删。 */
    fun cancelDelete() {
        _pendingDelete.value = null
    }

    /**
     * 确认删除（第二步，§13 偏差 3）。
     * 删的是 pi 存储里的那个 jsonl；正在被活动会话写着的文件删掉之后 pi 的行为不作保证，
     * 这一点写进确认卡文案（[works.earendil.pihost.feature.sessions.ui.SessionsStrings.DELETE_BODY]）。
     */
    fun deleteSession(session: ScannedSession) {
        _pendingDelete.value = null
        viewModelScope.launch {
            val deleted = runCatching { scanner.delete(session.sessionPath) }
            if (deleted.getOrDefault(false) != true) {
                _notice.value = "删除失败：${session.displayName()}"
            }
            refresh()
        }
    }

    fun consumeNotice() {
        _notice.value = null
    }

    fun consumeNavigation() {
        _navigation.value = null
    }

    /**
     * 命令该发给谁：优先已 [SessionState.Ready] 的会话，其次还在 [SessionState.Spawning]
     * 的（进程已起、握手未定，`send` 仍能写进 outbox）；`Failed`/`Stopped` 一律不算活。
     * 多条时取 [ChatEngine.sessionStates] 的插入序第一条 —— 单引擎多会话场景里这就是最早起的那条。
     */
    private fun liveSessionId(): String? {
        val states = engine.sessionStates.value
        return states.entries.firstOrNull { it.value is SessionState.Ready }?.key
            ?: states.entries.firstOrNull { it.value is SessionState.Spawning }?.key
    }

    companion object {
        const val NO_LIVE_SESSION: String = "引擎未启动：已跳到聊天页，在那里启动会话后再切换"
    }
}

/** 列表标题行的取名规则：有 name 用 name，否则用 firstMessage，再否则用 sessionId（UI 与日志共用）。 */
fun ScannedSession.displayName(): String = name ?: firstMessage ?: "会话 ${sessionId.take(8)}"
