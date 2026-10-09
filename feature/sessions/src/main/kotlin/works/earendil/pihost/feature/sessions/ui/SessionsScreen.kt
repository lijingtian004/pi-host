package works.earendil.pihost.feature.sessions.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import works.earendil.pihost.feature.sessions.ScannedSession
import works.earendil.pihost.feature.sessions.SessionsUiState
import works.earendil.pihost.feature.sessions.SessionsViewModel
import works.earendil.pihost.feature.sessions.displayName

/** 提示条自动收场的时长（毫秒）。 */
private const val NOTICE_AUTO_DISMISS_MS = 4_000L

/**
 * 会话列表屏（规格书 §4.1）。
 *
 * - **进屏即重扫**：`LaunchedEffect(Unit)` 调 [SessionsViewModel.refresh]；
 * - **下拉刷新**：material3 `PullToRefreshBox`（§2 白名单内的 compose-bom 自带件，不新增依赖）。
 *   顶栏同时留一条 [SessionsStrings.ACTION_REFRESH] 按钮 —— RECON-P2E §7.8 的理由：
 *   Robolectric 下 `performTouchInput` 的下拉手势不可靠，测试走按钮这条确定路径；
 * - **长按 → 二步确认**：确认形态是**列表内的一条确认卡**，不是 `AlertDialog`
 *   （Dialog 是独立 window，Compose UI 测试定位不稳；确认语义完全一致：
 *   长按只显示确认卡，点「删除」才真的调 [SessionsViewModel.deleteSession]）；
 * - 点击行 = `SwitchSession` + 导航，FAB = `NewSession` + 导航，两者都由 VM 决策
 *   （没有活会话时只导航到聊天页，见 [SessionsViewModel] 的纪律 1）。
 *
 * `collectAsState` 而非 `collectAsStateWithLifecycle`：与 `:feature:chat` 同一个理由
 * （RECON-P2D §4：Robolectric 的 Compose 宿主不保证提供 LocalLifecycleOwner）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    viewModel: SessionsViewModel,
    onOpenChat: (sessionId: String?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val navigation by viewModel.navigation.collectAsState()
    val pendingDelete by viewModel.pendingDelete.collectAsState()

    // 「新建项目」卡的开合是**纯 UI 态**，不进 VM：它不携带任何事实（磁盘上有没有这个项目
    // 由 VM 的 Content 说），进 VM 只会多一条与磁盘无关的状态。
    // 与 pendingDelete 相反——那个必须进 VM，因为「确认前绝不删文件」要在单测里钉死。
    var creatingProject by remember { mutableStateOf(false) }
    var draftName by remember(creatingProject) { mutableStateOf("") }
    var draftWithGit by remember(creatingProject) { mutableStateOf(true) }

    // 进屏重扫（规格书 §4.1「下拉刷新（LaunchedEffect 重扫）」的进入时机）
    LaunchedEffect(Unit) { viewModel.refresh() }

    LaunchedEffect(navigation) {
        navigation?.let {
            onOpenChat(it.sessionId)
            viewModel.consumeNavigation()
        }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_AUTO_DISMISS_MS)
            viewModel.consumeNotice()
        }
    }

    // 待删除的那条由 VM 持有（[SessionsViewModel.pendingDelete] 的 KDoc 记了为什么不放 remember）
    Surface(modifier = Modifier.fillMaxSize().testTag(SessionsTags.SCREEN)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(
                onRefresh = viewModel::refresh,
                onSettings = onOpenSettings,
                onNewProject = { creatingProject = true },
            )

            notice?.let { text ->
                Text(
                    text = "${SessionsStrings.NOTICE_PREFIX}$text",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SessionsTags.NOTICE)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            // P3C §3.7：项目条 + 「新建项目」卡。放在 notice 之后、列表之前——
            // 它是「这一屏在哪个项目里看」的上下文，必须早于列表被读到；
            // 也保证 notice（含创建失败的原因原文）不被卡片挤到屏幕外。
            val snapshot = uiState
            if (snapshot is SessionsUiState.Content) {
                ProjectsSection(
                    content = snapshot,
                    creating = creatingProject,
                    draft = draftName,
                    withGit = draftWithGit,
                    onDraftChange = { draftName = it },
                    onWithGitChange = { draftWithGit = it },
                    onCreate = {
                        viewModel.createProject(draftName, draftWithGit)
                        creatingProject = false
                    },
                    onCancel = { creatingProject = false },
                    onPick = { viewModel.useProject(it) },
                )
            }

            val refreshing = (uiState as? SessionsUiState.Content)?.refreshing == true
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                Body(
                    state = uiState,
                    pendingDelete = pendingDelete,
                    onOpen = viewModel::openSession,
                    onLongPress = viewModel::requestDelete,
                    onKeep = viewModel::cancelDelete,
                    onConfirmDelete = { pendingDelete?.let(viewModel::deleteSession) },
                    onNew = viewModel::newSession,
                )
            }

            Box(modifier = Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.CenterEnd) {
                FloatingActionButton(
                    onClick = viewModel::newSession,
                    modifier = Modifier.testTag(SessionsTags.FAB_NEW),
                ) { Text("+") }
            }
        }
    }
}

@Composable
private fun TopBar(onRefresh: () -> Unit, onSettings: () -> Unit, onNewProject: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = SessionsStrings.TITLE, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onNewProject, modifier = Modifier.testTag(SessionsTags.BUTTON_NEW_PROJECT)) {
                Text(SessionsStrings.BUTTON_NEW_PROJECT_LABEL)
            }
            TextButton(onClick = onRefresh, modifier = Modifier.testTag(SessionsTags.BUTTON_REFRESH)) {
                Text(SessionsStrings.ACTION_REFRESH)
            }
            TextButton(onClick = onSettings, modifier = Modifier.testTag(SessionsTags.BUTTON_SETTINGS)) {
                Text(SessionsStrings.ENTRY_SETTINGS)
            }
        }
    }
}

/**
 * 项目条与「新建项目」内联卡（P3C §3.7）。
 *
 * **为什么是内联卡而不是 AlertDialog**：本屏的删除确认已经是内联卡形态
 * （[SessionsScreen] 类头 §4.1 那条理由：Dialog 是独立 window，Compose UI 测试定位不稳），
 * 同一个屏幕里两种确认形态只会让人不知道该学哪一个。
 * 需要说明的是仓库里**确有**可用的 Dialog 先例（`ModelChip` 的 material3 AlertDialog，
 * `ChatScreenUiTest` 里能点到里面节点）——本期仍然选内联，是为了与本屏一致，
 * 不是因为 Dialog 测不了。
 *
 * **创建按钮的门是 `draft.isNotBlank()`**（`SettingsScreen.kt:302` 同一条先例）：
 * 这只是手感上的第一道门，真正的拒绝在 `ProjectStore.create`（第二道）与读盘回落（第三道）。
 * 空白输入点不到按钮，但 `../x` 这种「看着不像空白」的输入一定会走到第二道门——
 * 那道门是必须的，也是本期唯一的安全边界。
 */
@Composable
private fun ProjectsSection(
    content: SessionsUiState.Content,
    creating: Boolean,
    draft: String,
    withGit: Boolean,
    onDraftChange: (String) -> Unit,
    onWithGitChange: (Boolean) -> Unit,
    onCreate: () -> Unit,
    onCancel: () -> Unit,
    onPick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${SessionsStrings.PROJECTS_TITLE}：${content.activeProject}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag(SessionsTags.LABEL_ACTIVE_PROJECT),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (content.projects.isEmpty()) {
            Text(
                text = SessionsStrings.PROJECTS_EMPTY,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(SessionsTags.ROW_PROJECT),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                content.projects.forEach { name ->
                    val active = name == content.activeProject
                    OutlinedButton(
                        onClick = { if (!active) onPick(name) },
                        modifier = Modifier.testTag(SessionsTags.project(name)),
                        enabled = !active,
                    ) { Text(if (active) "$name（${SessionsStrings.PROJECT_ACTIVE}）" else name) }
                }
            }
        }

        if (creating) {
            Column(
                modifier = Modifier.fillMaxWidth().testTag(SessionsTags.CARD_NEW_PROJECT),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    label = { Text(SessionsStrings.PROJECT_FIELD_LABEL) },
                    supportingText = { Text(SessionsStrings.PROJECT_FIELD_HINT) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(SessionsTags.FIELD_PROJECT_NAME),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = withGit, onCheckedChange = onWithGitChange,
                        modifier = Modifier.testTag(SessionsTags.CHECK_PROJECT_GIT))
                    Text(SessionsStrings.PROJECT_CHECK_GIT, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onCreate, enabled = draft.isNotBlank(),
                        modifier = Modifier.testTag(SessionsTags.BUTTON_PROJECT_CREATE)) {
                        Text(SessionsStrings.PROJECT_CREATE)
                    }
                    TextButton(onClick = onCancel, modifier = Modifier.testTag(SessionsTags.BUTTON_PROJECT_CANCEL)) {
                        Text(SessionsStrings.PROJECT_CANCEL)
                    }
                }
            }
        }
    }
}

@Composable
private fun Body(
    state: SessionsUiState,
    pendingDelete: ScannedSession?,
    onOpen: (ScannedSession) -> Unit,
    onLongPress: (ScannedSession) -> Unit,
    onKeep: () -> Unit,
    onConfirmDelete: () -> Unit,
    onNew: () -> Unit,
) {
    when (state) {
        SessionsUiState.Loading -> Text(
            text = SessionsStrings.LOADING,
            modifier = Modifier.padding(16.dp).testTag(SessionsTags.LOADING),
        )

        is SessionsUiState.Failure -> Text(
            text = "${SessionsStrings.FAILURE_PREFIX}${state.cause}",
            color = Color.Red,
            modifier = Modifier.padding(16.dp).testTag(SessionsTags.FAILURE),
        )

        is SessionsUiState.Content -> {
            if (state.sessions.isEmpty()) {
                EmptyState(onNew = onNew)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag(SessionsTags.LIST),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(state.sessions, key = { it.sessionPath }) { session ->
                        if (pendingDelete?.sessionPath == session.sessionPath) {
                            DeleteConfirmCard(
                                session = session,
                                onKeep = onKeep,
                                onConfirm = onConfirmDelete,
                            )
                        } else {
                            SessionRow(
                                session = session,
                                onOpen = { onOpen(session) },
                                onLongPress = { onLongPress(session) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onNew: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(SessionsTags.EMPTY),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = SessionsStrings.EMPTY_TITLE, style = MaterialTheme.typography.titleMedium)
        Text(text = SessionsStrings.EMPTY_BODY, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onNew) { Text(SessionsStrings.ACTION_NEW) }
    }
}

@Composable
private fun SessionRow(session: ScannedSession, onOpen: () -> Unit, onLongPress: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SessionsTags.row(session.sessionPath))
            .pointerInput(Unit) { detectTapGestures(onTap = { onOpen() }, onLongPress = { onLongPress() }) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = session.name ?: SessionsStrings.NAME_FALLBACK,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = session.firstMessage ?: SessionsStrings.NO_MESSAGES,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = rowMeta(session),
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            maxLines = 1,
        )
    }
}

/** 行的第三行：时间 + 消息数 + 体积 + cwd。 */
private fun rowMeta(session: ScannedSession): String = rowMetaOf(
    SessionsTime.relative(session.modified),
    session.messageCount,
    SessionsTime.size(session.sizeBytes),
    session.cwd,
)

/** 纯函数出口：单测断言这一行的拼法，不依赖 [Instant.now]（时间串由参数喂进来）。 */
internal fun rowMetaOf(relative: String, messageCount: Int, size: String, cwd: String): String =
    "$relative · $messageCount 条消息 · $size" + if (cwd.isBlank()) "" else " · $cwd"

@Composable
private fun DeleteConfirmCard(session: ScannedSession, onKeep: () -> Unit, onConfirm: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.Red)
            .background(MaterialTheme.colorScheme.errorContainer)
            .testTag(SessionsTags.DELETE_DIALOG)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = SessionsStrings.DELETE_TITLE, style = MaterialTheme.typography.titleSmall)
        Text(text = "${SessionsTime.truncate(session.displayName(), 40)}\n${SessionsStrings.DELETE_BODY}",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onKeep, modifier = Modifier.testTag(SessionsTags.DELETE_CANCEL)) {
                Text(SessionsStrings.DELETE_CANCEL)
            }
            Button(onClick = onConfirm, modifier = Modifier.testTag(SessionsTags.DELETE_CONFIRM)) {
                Text(SessionsStrings.DELETE_CONFIRM)
            }
        }
    }
}
