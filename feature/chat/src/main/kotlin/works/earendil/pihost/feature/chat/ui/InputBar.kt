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
import works.earendil.pihost.feature.chat.TurnState

/**
 * 输入条（规格书 §6）：文本框 + 发送 + 中止。
 *
 * 可用性矩阵（Compose UI 测试第 ① 条就验这张表）：
 *
 * | turnState           | 文本框/发送 | 中止 |
 * | ------------------- | ----------- | ---- |
 * | Idle                | 可用        | 禁用 |
 * | Streaming           | 禁用        | 可用 |
 * | PermissionPending   | 全禁用 + 占位「等待权限确认…」 | 禁用 |
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
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    val pending = turnState == TurnState.PermissionPending
    val editable = turnState == TurnState.Idle

    fun submit() {
        if (!editable) return
        val text = draft
        if (text.isBlank()) return
        onSend(text)
        draft = ""
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
                    text = if (pending) "等待权限确认…" else "给 pi 发一条消息",
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
            Text("发送")
        }
        TextButton(
            onClick = onAbort,
            enabled = turnState == TurnState.Streaming,
            modifier = Modifier.testTag(ChatTags.ABORT),
        ) {
            Text("中止")
        }
    }
}
