package works.earendil.pihost.feature.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import works.earendil.pihost.core.engine.SessionState
import works.earendil.pihost.feature.chat.ChatUiState
import works.earendil.pihost.feature.chat.ChatViewModel

/**
 * 聊天主界面（规格书 §6）。
 *
 * `notificationGate` 默认走真实的 POST_NOTIFICATIONS 请求，UI 测试注入空 lambda
 * （理由见 [PostNotificationsPermissionGate] 的 KDoc）。
 *
 * **为什么是 `collectAsState` 而不是 `collectAsStateWithLifecycle`**：
 * 后者要求 `LocalLifecycleOwner.current` 是 LifecycleOwner，而 Robolectric 下
 * `createComposeRule()` 的宿主不保证提供（RECON-P2D §4 实测边界），Compose UI 测试会直接抛。
 * 语义损失可接受：ViewModel 侧 `viewModelScope` 已经在 `onCleared` 取消了事件收集，
 * 后台停采属于 P2E 的优化项（记录在收尾报告）。
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    notificationGate: @Composable (Boolean) -> Unit = { active -> PostNotificationsPermissionGate(active) },
) {
    val uiState by viewModel.uiState.collectAsState()
    val turnState by viewModel.turnState.collectAsState()
    val transcript by viewModel.transcript.collectAsState()
    val queueDepth by viewModel.queueDepth.collectAsState()
    val permission by viewModel.permissionRequest.collectAsState()
    val engineStatus by viewModel.engineStatus.collectAsState()
    val retryNote by viewModel.retryNote.collectAsState()
    val notice by viewModel.notice.collectAsState()

    val ready = uiState is ChatUiState.Ready
    notificationGate(ready)

    // §4.3 的 snackbar 语义用「banner + 4s 自动收起」实现：Robolectric 下 SnackbarHost
    // 的浮层不参与语义树（onNodeWithText 拿不到），banner 是普通节点，可测且与设置页同形。
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_AUTO_DISMISS_MS)
            viewModel.consumeNotice()
        }
    }

    Surface(modifier = Modifier
            .fillMaxSize()
            .testTag(ChatTags.SCREEN)) {
        Column(modifier = Modifier.fillMaxSize()) {
            notice?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag(ChatTags.NOTICE),
                )
            }
            when {
                !ready -> InstallGate(
                    state = uiState,
                    onInstall = viewModel::installAndStart,
                    modifier = Modifier.padding(12.dp),
                )

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    EngineTopBar(
                        sessionId = (uiState as ChatUiState.Ready).sessionId,
                        engineStatus = engineStatus,
                        queueDepth = queueDepth,
                        retryNote = retryNote,
                    )
                    TranscriptList(
                        items = transcript,
                        markwon = viewModel.markwon,
                        modifier = Modifier.weight(1f),
                    )
                    InputBar(
                        turnState = turnState,
                        onSend = viewModel::sendPrompt,
                        onAbort = viewModel::abort,
                    )
                }
            }
        }
    }

    permission?.let { pending ->
        PermissionDialog(pending = pending, onAnswer = { once, session, deny ->
            viewModel.answerPermission(allowOnce = once, allowSession = session, deny = deny)
        })
    }
}

/**
 * 顶栏（§6）：引擎状态短码 + 队列徽标。
 *
 * 短码规则写死在这里，app 侧测试按同一函数断言（不重复字面量）。
 */
@Composable
private fun EngineTopBar(
    sessionId: String,
    engineStatus: SessionState?,
    queueDepth: Int,
    retryNote: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(ChatTags.TOP_BAR),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = engineShortCode(sessionId, engineStatus),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(ChatTags.ENGINE_STATUS),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            retryNote?.let {
                Text(text = it, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
            if (queueDepth > 0) {
                Text(
                    text = "$queueDepth queued",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag(ChatTags.QUEUE_BADGE),
                )
            }
        }
    }
}

/** 顶栏短码：Ready→id 前 8 位；Restarting→attempt；Failed→cause 截断（§6）。 */
fun engineShortCode(sessionId: String, state: SessionState?): String = when (state) {
    null -> "会话 ${sessionId.take(8)}"
    SessionState.Spawning -> "启动中 ${sessionId.take(8)}"
    SessionState.Ready -> "就绪 ${sessionId.take(8)}"
    is SessionState.Restarting -> "重启第 ${state.attempt} 次"
    is SessionState.Failed -> "失败：${state.cause.take(40)}"
    SessionState.Stopped -> "已停止"
}

/** notice banner 的自动收起时长（与 `:feature:sessions`/`:feature:settings` 同一档）。 */
private const val NOTICE_AUTO_DISMISS_MS = 4_000L
