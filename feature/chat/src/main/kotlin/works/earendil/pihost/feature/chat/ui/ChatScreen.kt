package works.earendil.pihost.feature.chat.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import works.earendil.pihost.core.engine.SessionState
import works.earendil.pihost.feature.chat.ChatUiState
import works.earendil.pihost.feature.chat.ChatViewModel
import works.earendil.pihost.feature.chat.ModelPickerState
import works.earendil.pihost.feature.chat.ProviderModel
import works.earendil.pihost.feature.chat.QueueState

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
    val queueState by viewModel.queueState.collectAsState()
    val modelPicker by viewModel.modelPicker.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val permission by viewModel.permissionRequest.collectAsState()
    val engineStatus by viewModel.engineStatus.collectAsState()
    val retryNote by viewModel.retryNote.collectAsState()
    val notice by viewModel.notice.collectAsState()

    val ready = uiState is ChatUiState.Ready
    notificationGate(ready)

    // §3.2：队列明细默认收起（徽标一行字就够顶栏用），点开才列条目。
    // 它是**纯视图状态**，不进 VM——VM 一持有「展开与否」，转成后台再回来就会带着这份
    // 用户没表达过的意图回来（与 InputBar 的草稿同一判据）。
    var queueExpanded by remember { mutableStateOf(false) }

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
                        queueState = queueState,
                        queueExpanded = queueExpanded,
                        onQueueToggle = { queueExpanded = !queueExpanded },
                        retryNote = retryNote,
                        modelPicker = modelPicker,
                        onModelOpen = viewModel::refreshModels,
                        onModelPick = viewModel::setModel,
                        onModelCycle = viewModel::cycleModel,
                        onThinkingLevelPick = viewModel::setThinkingLevel,
                        onThinkingLevelCycle = viewModel::cycleThinkingLevel,
                    )
                    if (queueExpanded && !queueState.isEmpty) {
                        QueueDetails(queueState = queueState)
                    }
                    TranscriptList(
                        items = transcript,
                        markwon = viewModel.markwon,
                        modifier = Modifier.weight(1f),
                    )
                    // §3.4：统计条紧贴输入区（读完了才看的东西，摆在那里回答「还要不要继续问」）
                    StatsBar(
                        state = stats,
                        onRefresh = viewModel::refreshStats,
                        onCompact = viewModel::compactContext,
                        onAutoCompactionChange = viewModel::setAutoCompaction,
                    )
                    InputBar(
                        turnState = turnState,
                        queueState = queueState,
                        onSend = viewModel::sendPrompt,
                        onSteer = viewModel::steer,
                        onFollowUp = viewModel::followUp,
                        onClearQueue = viewModel::clearQueue,
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
 * 顶栏（§6）：引擎状态短码 + 模型 chip + 队列徽标。
 *
 * 短码规则写死在这里，app 侧测试按同一函数断言（不重复字面量）。
 *
 * **§3.3 的模型 chip 放在徽标前面**：这一行从左到右是「会话在哪 → 会话用什么 → 会话排了什么」，
 * 后两者都是可点开的入口，顺序按「改错代价」排——切错模型会让下一轮跑在错误的 provider 上，
 * 而队列里的东西还没发出去。
 */
@Composable
private fun EngineTopBar(
    sessionId: String,
    engineStatus: SessionState?,
    queueState: QueueState,
    queueExpanded: Boolean,
    onQueueToggle: () -> Unit,
    retryNote: String?,
    modelPicker: ModelPickerState,
    onModelOpen: () -> Unit,
    onModelPick: (ProviderModel) -> Unit,
    onModelCycle: () -> Unit,
    onThinkingLevelPick: (String) -> Unit,
    onThinkingLevelCycle: () -> Unit,
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
            ModelChip(
                state = modelPicker,
                onOpen = onModelOpen,
                onPickModel = onModelPick,
                onCycleModel = onModelCycle,
                onPickThinkingLevel = onThinkingLevelPick,
                onCycleThinkingLevel = onThinkingLevelCycle,
            )
            if (!queueState.isEmpty) {
                // 徽标本身就是开关：§3.2 要的「点开看内容片段」不给它另找一处入口，
                // 否则用户要在屏幕上找一个「看不见但存在」的展开按钮。
                Text(
                    text = queueState.badgeText() + if (queueExpanded) " ▾" else " ▸",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .testTag(ChatTags.QUEUE_BADGE)
                        .clickable(onClick = onQueueToggle),
                )
            }
        }
    }
}

/**
 * 队列明细（§3.2）：steering 在前、followUp 在后，逐条一行小字。
 *
 * 内容**原样**来自 `queue_update`（pi 把文本放进事件里了），这里只做「截到 60 字 + 前缀标列」，
 * 不改写、不归纳——用户要确认的是「我那句到底排进去了没有」，任何加工都会让这件事变得不可信。
 */
@Composable
private fun QueueDetails(queueState: QueueState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .testTag(ChatTags.QUEUE_DETAILS),
    ) {
        (queueState.steering.map { "插话 ▸ $it" to true } + queueState.followUp.map { "追问 ▸ $it" to false })
            .forEachIndexed { index, (line, isSteering) ->
                Text(
                    text = line.take(60),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSteering) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(ChatTags.queueItem(index)),
                )
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
