package works.earendil.pihost.feature.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import works.earendil.pihost.feature.chat.QueueState
import works.earendil.pihost.feature.chat.TurnState

/**
 * 输入条（规格书 §6 + P3B §3.2）。
 *
 * 可用性矩阵（Compose UI 测试第 ① 条就验这张表）：
 *
 * | turnState         | 文本框 | 主按钮            | 追问 | 中止 |
 * | ----------------- | ------ | ----------------- | ---- | ---- |
 * | Idle              | 可用   | 「发送」→ onSend  | 可用 | 禁用 |
 * | Streaming         | **可用** | 「插入」→ onSteer | 可用 | 可用 |
 * | PermissionPending | 禁用   | 禁用              | 禁用 | 禁用 |
 *
 * §3.2 改的那一格：**流式期间输入框不再锁死**。pi 支持在轮中插话（`steer`）与排队追问
 * （`follow_up`），把输入框禁掉等于把这两条能力从用户手里拿走——而这一轮可能正在跑十几分钟
 * 的构建，用户看到方向错了却什么都做不了。
 * 主按钮随状态换**文案**（「发送」/「插入」），不是再摊开两个按钮让用户猜哪个生效；
 * tag 始终是 [ChatTags.SEND]，测试按文案验换档、按 tag 验点击。
 *
 * 「清空队列」只在**队列非空**时出现：一个永远点不动的按钮比没有按钮更糟
 * （用户会以为点了没反应是自己操作错了）。
 *
 * 草稿只活在本地 `remember`，不进 VM——VM 一持有草稿，配置变化保存职责就跑偏了（P2E 再说）。
 * 「空文本不发」与 [works.earendil.pihost.feature.chat.ChatViewModel.sendPrompt] 的
 * `isBlank` 守卫是同一条规则的两端：UI 先拦住，VM 再兜一层。
 */
@Composable
fun InputBar(
    turnState: TurnState,
    onSend: (String) -> Unit,
    onAbort: () -> Unit,
    onSteer: (String) -> Unit = { onSend(it) },
    onFollowUp: (String) -> Unit = { onSend(it) },
    onClearQueue: () -> Unit = {},
    queueState: QueueState = QueueState.EMPTY,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    val pending = turnState == TurnState.PermissionPending
    val streaming = turnState == TurnState.Streaming
    val editable = turnState == TurnState.Idle || streaming

    fun submit() {
        if (!editable) return
        val text = draft
        if (text.isBlank()) return
        draft = ""
        // 同一段字，Idle 时是「新问题」、Streaming 时是「插话」——差别由 turnState 单点决定，
        // 不给用户第二次确认的机会（多点一次按钮就多排一条）。
        if (streaming) onSteer(text) else onSend(text)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            enabled = editable,
            maxLines = 4,
            placeholder = {
                Text(
                    text = when {
                        pending -> "等待权限确认…"
                        streaming -> "插一句，或排队追问"
                        else -> "给 pi 发一条消息"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            modifier = Modifier
                .weight(1f)
                .testTag(ChatTags.INPUT),
        )
        TextButton(
            onClick = { submit() },
            enabled = editable && draft.isNotBlank(),
            modifier = Modifier.testTag(ChatTags.SEND),
        ) {
            Text(if (streaming) "插入" else "发送")
        }
        // 「追问」不只在流式时给：Idle 时按 pi 的语义同样是排队（follow_up 本就允许轮外预排），
        // 但主按钮在 Idle 时已经是「发送」，所以这一档把追问收起来，避免两条几乎一样的路。
        if (streaming) {
            TextButton(
                onClick = {
                    val text = draft
                    if (!editable || text.isBlank()) return@TextButton
                    draft = ""
                    onFollowUp(text)
                },
                enabled = editable && draft.isNotBlank(),
                modifier = Modifier.testTag(ChatTags.FOLLOW_UP),
            ) {
                Text("追问")
            }
        }
        if (!queueState.isEmpty) {
            TextButton(
                onClick = onClearQueue,
                modifier = Modifier.testTag(ChatTags.CLEAR_QUEUE),
            ) {
                Text("清空")
            }
        }
        TextButton(
            onClick = onAbort,
            enabled = streaming,
            modifier = Modifier.testTag(ChatTags.ABORT),
        ) {
            Text("中止")
        }
    }
}
