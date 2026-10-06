package works.earendil.pihost.feature.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import works.earendil.pihost.core.engine.InstallProgress
import works.earendil.pihost.feature.chat.ChatUiState

/**
 * 安装门（规格书 §6）：uiState 非 Ready 时的整屏替身。
 *
 * 三档各有独立文案，**不允许**把失败冲成「未安装」或「空闲」——P2C 时代
 * MainActivity 就是靠「失败原因上屏 + 可重试 + 与未安装可区分」三条断言立的规矩，
 * 这里原样继承（app 侧等价重写测试会再验一次）。
 */
@Composable
fun InstallGate(state: ChatUiState, onInstall: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(20.dp)
            .testTag(ChatTags.INSTALL_GATE),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        when (state) {
            is ChatUiState.NotInstalled -> {
                Text(text = "运行时未安装", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "首次启动需要解压约 50MB 的 node + pi 运行时",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onInstall, modifier = Modifier.testTag(ChatTags.INSTALL_BUTTON)) {
                    Text("安装运行时")
                }
            }

            is ChatUiState.Installing -> {
                Text(text = "正在安装运行时", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = installStepText(state.step),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            is ChatUiState.Failed -> {
                Text(text = "启动失败", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = state.cause,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(ChatTags.INSTALL_CAUSE),
                )
                Button(onClick = onInstall, modifier = Modifier.testTag(ChatTags.INSTALL_RETRY_BUTTON)) {
                    Text("重试")
                }
            }

            is ChatUiState.Ready -> Text(text = "引擎就绪", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** [InstallProgress] 各档文案（测试按同一函数断言，不重复字面量）。 */
fun installStepText(step: InstallProgress): String = when (step) {
    InstallProgress.Idle -> "准备中…"
    is InstallProgress.Extracting -> "解包 ${step.done}/${step.total}"
    is InstallProgress.Verifying -> "校验 ${step.done}/${step.total}"
    InstallProgress.Finalizing -> "切换运行目录"
    InstallProgress.Done -> "安装完成"
    is InstallProgress.Failed -> "失败：${step.message}"
}
