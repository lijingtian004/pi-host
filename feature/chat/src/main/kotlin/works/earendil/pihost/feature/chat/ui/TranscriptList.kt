package works.earendil.pihost.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.noties.markwon.Markwon
import works.earendil.pihost.feature.chat.ToolCallRecord
import works.earendil.pihost.feature.chat.ToolStatus
import works.earendil.pihost.feature.chat.TranscriptItem

/**
 * 转录列表（规格书 §6）：用户右对齐气泡 + assistant（thinking 折叠 + **markwon 正文** + toolcall 卡）
 * + 工具卡 + 会话切换占位行。
 *
 * **自动滚动**只在「用户已经在底部」时进行（§6 的简单实现）：
 * `derivedStateOf { !listState.canScrollForward }` 就是「已贴底」的事实判据，
 * 用户往上翻历史时新消息不会把视口拽走。
 *
 * **折叠态用本地 remember**：thinking 与工具输出的展开是纯 UI 偏好，
 * 塞进 VM 会让「不可变快照替换」这条纪律多出一倍分支，而 P2E 不需要跨滚动保持。
 *
 * **markwon 实例走参数**（§9.5）：@Singleton 的那一份由 [ChatViewModel] 转交（见其 KDoc），
 * 本文件不自建——列表滚动时每条 assistant 各自 build 一次语法表是最容易犯的错。
 */
@Composable
fun TranscriptList(items: List<TranscriptItem>, markwon: Markwon, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    LaunchedEffect(items.size, items.lastOrNull()) {
        if (atBottom && items.isNotEmpty()) {
            listState.animateScrollToItem(items.lastIndex)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .testTag(ChatTags.TRANSCRIPT),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        items(count = items.size, key = { index -> items[index].id }) { index ->
            when (val item = items[index]) {
                is TranscriptItem.UserItem -> UserBubble(item)
                is TranscriptItem.AssistantItem -> AssistantBlock(item, markwon)
                is TranscriptItem.ToolItem -> ToolCard(item)
                is TranscriptItem.NoteItem -> NoteRow(item)
            }
        }
    }
}

@Composable
private fun UserBubble(item: TranscriptItem.UserItem) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .widthIn(max = 280.dp)
                .testTag(ChatTags.user(item.id)),
        ) {
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

@Composable
private fun AssistantBlock(item: TranscriptItem.AssistantItem, markwon: Markwon) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ChatTags.assistant(item.id)),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (item.thinking.isNotEmpty()) ThinkingSection(item)
        // §4.3：正文交给 markwon（P2D 的 BodyText/围栏切段已随冒烟件一起退场）
        MarkdownBody(
            text = item.text,
            markwon = markwon,
            modifier = Modifier.testTag(ChatTags.markdown(item.id)),
        )
        item.toolCalls.forEach { record -> ToolCallChip(record) }
    }
}

@Composable
private fun ThinkingSection(item: TranscriptItem.AssistantItem) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag(ChatTags.thinking(item.id))) {
        Text(text = if (expanded) "思考（收起）" else "思考（展开）", style = MaterialTheme.typography.labelMedium)
    }
    if (expanded) {
        Text(
            text = item.thinking,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * 会话切换占位行（§4.3）。
 *
 * 一行居中的灰字，不带气泡、不带折叠交互——它的存在时间以秒计，
 * 形状越不像消息，用户越不会误读成「pi 回了这句」。
 */
@Composable
private fun NoteRow(item: TranscriptItem.NoteItem) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ChatTags.note(item.id)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = item.text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
}

/** toolcall 记录（Delta 累积 + End 解析后的名字，§5.1）。 */
@Composable
private fun ToolCallChip(record: ToolCallRecord) {
    val name = record.toolName.ifEmpty { record.parsedName?.trim('"').orEmpty() }.ifEmpty { "tool" }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "调用 $name（${record.callId.take(8)}）",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * 工具执行卡（§6）：工具名 + callId 短码 + 状态灯（Running 转圈 / Ok 绿点 / Error 红点），
 * 输出区默认收起、可展开，流式中实时追加。
 */
@Composable
private fun ToolCard(item: TranscriptItem.ToolItem) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ChatTags.tool(item.callId)),
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusLight(item.status)
                Text(text = item.toolName.ifEmpty { "tool" }, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "#${item.callId.take(8)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (item.output.isNotEmpty()) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.testTag(ChatTags.toolOutput(item.callId)),
                ) {
                    Text(text = if (expanded) "输出（收起）" else "输出（展开）", style = MaterialTheme.typography.labelMedium)
                }
                if (expanded) {
                    Text(
                        text = item.output,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLight(status: ToolStatus) {
    when (status) {
        ToolStatus.Running -> CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.size(14.dp),
        )

        ToolStatus.Ok -> Box(
            modifier = Modifier
                .size(10.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )

        ToolStatus.Error -> Box(
            modifier = Modifier
                .size(10.dp)
                .background(MaterialTheme.colorScheme.error, CircleShape),
        )
    }
}
