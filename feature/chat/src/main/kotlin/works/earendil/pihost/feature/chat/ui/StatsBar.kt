package works.earendil.pihost.feature.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import works.earendil.pihost.feature.chat.StatsUiState

/**
 * 一行式会话统计条（P3B §3.4）。
 *
 * 位置取规格书的第二个选项（InputBar 上方、转录区之下）而不是顶栏下方：顶栏已经排了
 * 会话名 / 状态点 / 模型 chip / 队列徽标，再塞一行数字就会把「这一轮在不在跑」挤没；
 * 而统计是**读完了才看**的东西，紧贴输入区正好回答「还要不要继续问」。
 *
 * 三处刻意的取舍：
 *
 * 1. **展开态是纯视图状态**（`remember` 在本地）：与 §3.2 的 `queueExpanded`、§3.3 的
 *    chip 对话框同一条判据——VM 一持有「展开与否」，转屏回来就带着用户没表达过的意图。
 * 2. **`autoCompaction == null` 时开关旁写「未知」**：探测里这个真值只在 `get_state` 里，
 *    而本页不主动拉；把「不知道」画成「关着」就是让用户以为自己不开了自动压缩，
 *    于是按下去反而可能把它**打开**。开关位置照常跟随 `== true`，措辞负责说出那层不确定。
 * 3. **压缩按钮在 `compacting` 时禁用**：pi 正在压缩时再发一条 `compact` 只会拿到一句
 *    失败回执（探测 p15 那种 `Nothing to compact`），点了没反应比灰掉更让人怀疑自己。
 */
@Composable
fun StatsBar(
    state: StatsUiState,
    onRefresh: () -> Unit,
    onCompact: () -> Unit,
    onAutoCompactionChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var confirmOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
        ) {
            Text(
                text = state.barText() + if (state.compacting) "｜正在压缩…" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .testTag(ChatTags.STATS_BAR)
                    .clickable { expanded = !expanded },
            )
            TextButton(
                onClick = onRefresh,
                modifier = Modifier.testTag(ChatTags.STATS_REFRESH),
            ) {
                Text("⟳")
            }
        }

        if (expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ChatTags.STATS_DETAILS)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                val stats = state.stats
                if (stats == null) {
                    // 「还没问过」与「问过但形状不对」都落在这儿：一句「统计未加载」+ 手动刷新入口，
                    // 比摆一排 0 诚实
                    Text(text = "统计未加载：点 ⟳ 问一次 pi", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(text = stats.detailsText(), style = MaterialTheme.typography.bodySmall)
                    Text(text = stats.tokensText(), style = MaterialTheme.typography.bodySmall)
                }
                Text(text = state.liveText(), style = MaterialTheme.typography.bodySmall)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = { confirmOpen = true },
                        enabled = !state.compacting,
                        modifier = Modifier.testTag(ChatTags.COMPACT_BUTTON),
                    ) {
                        Text("压缩上下文")
                    }
                    Text(
                        text = "自动压缩（${
                            when (state.autoCompaction) {
                                null -> "未知"
                                true -> "开"
                                false -> "关"
                            }
                        }）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Switch(
                        checked = state.autoCompaction == true,
                        onCheckedChange = onAutoCompactionChange,
                        modifier = Modifier.testTag(ChatTags.AUTO_COMPACT_SWITCH),
                    )
                }
            }
        }
    }

    if (confirmOpen) {
        CompactConfirmDialog(
            onConfirm = {
                confirmOpen = false
                onCompact()
            },
            onDismiss = { confirmOpen = false },
        )
    }
}

/**
 * 压缩确认框（§3.4 要求「说明压缩会重写上下文」）。
 *
 * 这句话要说足：压缩会把老消息换成一段摘要，**之后的回答看不到原文**，
 * 而 pi 侧是真实写会话文件的一次动作（与 §3.1 那条「回填纯读、绝不写会话文件」正好相反）。
 * 所以要确认；取消就是取消，不发任何命令。
 */
@Composable
fun CompactConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(ChatTags.COMPACT_DIALOG),
        title = { Text("压缩上下文") },
        text = {
            Text(
                "pi 会把较早的消息换成一段摘要，写回当前会话文件；" +
                    "压缩之后的回答将看不到这些消息的原文（转录文件本身不删）。" +
                    "会话太小时 pi 会直接回一句「Nothing to compact」。",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(ChatTags.COMPACT_CONFIRM)) {
                Text("压缩")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(ChatTags.COMPACT_CANCEL)) {
                Text("取消")
            }
        },
    )
}
