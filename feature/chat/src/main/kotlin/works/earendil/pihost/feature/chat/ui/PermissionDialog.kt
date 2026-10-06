package works.earendil.pihost.feature.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import works.earendil.pihost.feature.chat.PendingPermission

/**
 * 权限桥弹框（规格书 §6）：title/message + method 与剩余秒数 + 三按钮。
 *
 * 三按钮各占一个 testTag（UI 测试第 ② 条按 tag 点，再断 FakeChatEngine 捕获到的
 * `ExtensionUiResponse` 形状）。`onDismissRequest` 等价「拒绝」——
 * 线协议只有 confirmed/cancelled 两种结算（protocol.md §3.7），没有「关掉不算」这一档，
 * 关掉却不应答只会让 pi 侧白等一个超时。
 */
@Composable
fun PermissionDialog(pending: PendingPermission, onAnswer: PermissionAnswer, modifier: Modifier = Modifier) {
    AlertDialog(
        onDismissRequest = { onAnswer(false, false, true) },
        modifier = modifier.testTag(ChatTags.PERMISSION_DIALOG),
        title = { Text(text = pending.title ?: "权限确认") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = pending.message ?: "pi 请求执行 ${pending.method}")
                Text(
                    text = "method=${pending.method}｜剩余 ${pending.remainingSeconds}s",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { onAnswer(true, false, false) },
                    modifier = Modifier.testTag(ChatTags.PERMISSION_ALLOW_ONCE),
                ) {
                    Text("允许一次")
                }
                TextButton(
                    onClick = { onAnswer(false, true, false) },
                    modifier = Modifier.testTag(ChatTags.PERMISSION_ALLOW_SESSION),
                ) {
                    Text("本会话允许")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onAnswer(false, false, true) },
                modifier = Modifier.testTag(ChatTags.PERMISSION_DENY),
            ) {
                Text("拒绝")
            }
        },
    )
}
