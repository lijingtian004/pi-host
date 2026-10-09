package works.earendil.pihost.feature.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import works.earendil.pihost.feature.chat.CHIP_NO_MODELS
import works.earendil.pihost.feature.chat.CHIP_PICK_LOADING
import works.earendil.pihost.feature.chat.ModelPickerState
import works.earendil.pihost.feature.chat.ProviderModel

/**
 * 顶栏模型 chip + 选择器对话框（P3B §3.3）。
 *
 * 三点设计取舍：
 *
 * 1. **列表是点开才拉的**：规格书写「`hasConfiguredAuth` 链未配置时不主动拉」，而这条链
 *    在 App 侧没有可读的现成面（`SettingsRepository` 只有 memTier/并行上限/默认模型三键）。
 *    于是取它的**等价实现**：VM 不在 Ready 时发 `get_available_models`，由用户这一次点击来触发——
 *    「用户主动要的」是比「猜一个认证状态」更可靠的判据，且少配一条注入边（§3.5 清点）。
 * 2. **当前项高亮靠 [ModelPickerState.indexOfCurrent]**：探测显示 `get_available_models`
 *    的列表里**没有** `current`/`selected` 标记，所以「哪条是当前」只能拿 `get_state` 的
 *    `provider/id` 回来比对；比不上（-1）就不高亮任何一条，而不是猜第一条。
 * 3. **思考档只列白名单**：`set_thinking_level` 服务端不校验（protocol.md §3.1 + 探测 p09
 *    对 reasoning:false 模型设 "high" 也回 success），把非法档位在 UI 里给出口就是让用户
 *    点出一个「pi 说成功、模型说没变」的假动作。
 *
 * 对话框状态（开/关）是**纯视图状态**，`remember` 在本地，不进 VM——与 §3.2 的
 * `queueExpanded` 同一条判据：VM 一持有「展开与否」，转个屏幕回来就带着用户没表达过的意图。
 */
@Composable
fun ModelChip(
    state: ModelPickerState,
    onOpen: () -> Unit,
    onPickModel: (ProviderModel) -> Unit,
    onCycleModel: () -> Unit,
    onPickThinkingLevel: (String) -> Unit,
    onCycleThinkingLevel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Text(
        text = state.chipText(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .testTag(ChatTags.MODEL_CHIP)
            .clickable {
                expanded = true
                // 每次点开都重问一次：模型列表会在配置新 provider 之后变，
                // 缓存一份「上次点开的列表」就是让用户对着过期菜单做决定
                onOpen()
            },
    )

    if (!expanded) return

    val currentIndex = state.indexOfCurrent()
    AlertDialog(
        onDismissRequest = { expanded = false },
        modifier = Modifier.testTag(ChatTags.MODEL_DIALOG),
        title = { Text(text = "模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.models.isEmpty()) {
                    // 拉过了但空 → §3.3 点名的措辞；还没拉回来（首点之后回执未到）说「加载中」，
                    // 两种空在用户眼里是同一件事吗？不是——前者要去看认证，后者再等一拍就好。
                    Text(
                        text = if (state.isEmpty) CHIP_NO_MODELS else CHIP_PICK_LOADING,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.models.forEachIndexed { index, model ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ChatTags.modelItem(index))
                            .clickable {
                                onPickModel(model)
                                expanded = false
                            }
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            text = if (index == currentIndex) "● " else "　 ",
                            color = if (index == currentIndex) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            text = model.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // provider/id 是 set_model 的两个线字段，摆出来用户才知道切过去会发什么
                        Text(
                            text = "  ${model.wireKey}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.thinkingLevels.isNotEmpty()) {
                    Text(
                        text = "思考档",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.thinkingLevels.forEachIndexed { index, level ->
                            Text(
                                text = if (level == state.thinkingLevel) "[$level]" else level,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (level == state.thinkingLevel) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier
                                    .testTag(ChatTags.thinkingItem(index))
                                    .clickable { onPickThinkingLevel(level) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        onCycleModel()
                        expanded = false
                    },
                    modifier = Modifier.testTag(ChatTags.MODEL_CYCLE),
                ) {
                    Text("轮换下一个")
                }
                TextButton(
                    onClick = {
                        // 「刷新」= 再点一次而不关框：用户要的是「列表回来了我就接着选」，
                        // 关掉再重开等于把同一个请求发两遍
                        onOpen()
                    },
                    modifier = Modifier.testTag(ChatTags.MODEL_REFRESH),
                ) {
                    Text("刷新")
                }
                TextButton(
                    onClick = onCycleThinkingLevel,
                    modifier = Modifier.testTag(ChatTags.THINKING_CYCLE),
                ) {
                    Text("换档")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { expanded = false }) {
                Text("关闭")
            }
        },
    )
}
