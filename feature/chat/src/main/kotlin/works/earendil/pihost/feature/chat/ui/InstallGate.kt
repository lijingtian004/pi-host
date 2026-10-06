package works.earendil.pihost.feature.chat.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
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
    val clipboard = LocalClipboardManager.current
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
                // P2F §4.2(a)：**取消 maxLines=6 + Ellipsis 的截断**。
                // cause 全文（libcore 的 IOException 消息里有 `error=N`，外加多行 stderr 尾巴）
                // 是本轮唯一的定位弹药，截到 errno 之前等于没报（P2F §1 成功判据②）。
                // 做法：外层 Box 限高 280.dp，Text 自己 verticalScroll ——
                // testTag 仍挂在 Text 上（既有 `onNodeWithTag(INSTALL_CAUSE)` 断言不漂），
                // 默认换行保留，不折叠、不打码。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline)
                        .padding(8.dp),
                ) {
                    Text(
                        text = state.cause,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .testTag(ChatTags.INSTALL_CAUSE),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onInstall, modifier = Modifier.testTag(ChatTags.INSTALL_RETRY_BUTTON)) {
                        Text("重试")
                    }
                    // P2F §4.2(b)：一键复制全文，用户可以直接贴给助手（剪贴板内容 == state.cause，不加工）。
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(state.cause)) },
                        modifier = Modifier.testTag(ChatTags.INSTALL_COPY),
                    ) {
                        Text("复制详情")
                    }
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
