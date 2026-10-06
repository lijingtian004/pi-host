package works.earendil.pihost.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import works.earendil.pihost.core.environment.MemTier
import works.earendil.pihost.feature.settings.ProbeState
import works.earendil.pihost.feature.settings.RuntimeInfo
import works.earendil.pihost.feature.settings.SettingsViewModel

/** 提示条自动收场的时长（与 `:feature:sessions` 同一档）。 */
private const val NOTICE_AUTO_DISMISS_MS = 4_000L

/**
 * 设置屏（规格书 §4.2）：memTier 三选 + 并行上限 stepper + `get_state` 探针卡片 + 版本卡片。
 *
 * 形状选择：
 * - **整屏一个 [Surface] + 垂直滚动**，不用 `Scaffold`：本页没有 FAB/Snackbar 需求，
 *   而 `collectAsState`（不是 `collectAsStateWithLifecycle`）与 `:feature:chat`/`:feature:sessions`
 *   同一个理由——RECON-P2D §4：Robolectric 的 Compose 宿主不保证提供 `LocalLifecycleOwner`；
 * - **memTier 用 RadioButton 而不是 `SingleChoiceSegmentedButtonRow`**：规格书 §4.2 两者都允许，
 *   取 RadioButton 是因为 segmented 那套 API 在 material3 1.4.0 仍是 experimental 家族，
 *   而每档后面要跟一行 `--max-old-space-size=` 的说明文本，纵向三行比横向三段更好排；
 * - **探针卡片显示 [ProbeState.Done.text]（VM 已截断到 500 字）**，monospace，
 *   这是 P2D 遗留 #5 的落地形态：入口在设置页，不在聊天页。
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val probe by viewModel.probe.collectAsState()
    val info by viewModel.runtimeInfoState.collectAsState()
    val notice by viewModel.notice.collectAsState()

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_AUTO_DISMISS_MS)
            viewModel.consumeNotice()
        }
    }

    Surface(modifier = Modifier.fillMaxSize().testTag(SettingsTags.SCREEN)) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    SettingsStrings.TITLE,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(
                    onClick = viewModel::refreshRuntimeInfo,
                    modifier = Modifier.testTag(SettingsTags.BUTTON_REFRESH_INFO),
                ) { Text(SettingsStrings.ACTION_REFRESH_INFO) }
                TextButton(onClick = onBack) { Text(SettingsStrings.ACTION_BACK) }
            }

            TierSection(selected = settings.memTier, onSelect = viewModel::setMemTier)
            HorizontalDivider()
            ParallelSection(
                value = settings.maxParallelSessions,
                onChange = viewModel::setMaxParallelSessions,
            )
            HorizontalDivider()
            ModelSection(defaultModel = settings.defaultModel)
            HorizontalDivider()
            ProbeCard(state = probe, onRun = viewModel::runProbe, onClear = viewModel::clearProbe)
            HorizontalDivider()
            VersionCard(info = info)

            notice?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(12.dp).testTag(SettingsTags.NOTICE),
                )
            }
        }
    }
}

/** 卡片外壳：一个标题 + 任意内容，四处共用，保证视觉与 tag 的一致。 */
@Composable
private fun Section(
    tag: String,
    title: String,
    content: @Composable () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().padding(12.dp).testTag(tag)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun TierSection(selected: MemTier, onSelect: (MemTier) -> Unit) {
    Section(tag = SettingsTags.TIER_GROUP, title = SettingsStrings.SECTION_TIER) {
        Text(SettingsStrings.TIER_HINT, style = MaterialTheme.typography.bodySmall)
        MemTier.entries.forEach { tier ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = tier == selected,
                    onClick = { onSelect(tier) },
                    modifier = Modifier.testTag(SettingsTags.tier(tier)),
                )
                Column {
                    Text(tier.name, style = MaterialTheme.typography.bodyLarge)
                    Text(tier.nodeOptions, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ParallelSection(value: Int, onChange: (Int) -> Unit) {
    Section(tag = SettingsTags.PARALLEL_VALUE, title = SettingsStrings.SECTION_PARALLEL) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { onChange(value - 1) },
                modifier = Modifier.testTag(SettingsTags.PARALLEL_DECREMENT),
                enabled = value > 1,
            ) { Text("−") }
            Text(String.format(SettingsStrings.PARALLEL_VALUE_FORMAT, value))
            OutlinedButton(
                onClick = { onChange(value + 1) },
                modifier = Modifier.testTag(SettingsTags.PARALLEL_INCREMENT),
                enabled = value < 4,
            ) { Text("+") }
        }
        Text(SettingsStrings.PARALLEL_HINT, style = MaterialTheme.typography.bodySmall)
    }
}

/** 默认模型只读显示（规格书 §3.4：空 = pi 内置默认；§13 偏差 7：P2E 不做模型切换）。 */
@Composable
private fun ModelSection(defaultModel: String?) {
    Section(tag = SettingsTags.SCREEN + "-model", title = SettingsStrings.SECTION_MODEL) {
        Text(defaultModel ?: SettingsStrings.MODEL_PI_DEFAULT)
        Text(SettingsStrings.MODEL_HINT, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ProbeCard(
    state: ProbeState,
    onRun: () -> Unit,
    onClear: () -> Unit,
) {
    Section(tag = SettingsTags.CARD_PROBE, title = SettingsStrings.SECTION_PROBE) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onRun,
                enabled = state != ProbeState.Running,
                modifier = Modifier.testTag(SettingsTags.PROBE_RUN),
            ) { Text(SettingsStrings.PROBE_RUN) }
            TextButton(
                onClick = onClear,
                enabled = state != ProbeState.Idle,
                modifier = Modifier.testTag(SettingsTags.PROBE_CLEAR),
            ) { Text(SettingsStrings.PROBE_CLEAR) }
        }
        when (state) {
            ProbeState.Idle -> Text(SettingsStrings.PROBE_IDLE)
            ProbeState.Running -> Text(SettingsStrings.PROBE_RUNNING)
            is ProbeState.Unavailable -> Text(
                state.message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(SettingsTags.PROBE_TEXT),
            )
            is ProbeState.Done -> {
                val prefix = if (state.success) {
                    SettingsStrings.PROBE_SESSION_SUFFIX + state.sessionId.take(8) + " · "
                } else {
                    SettingsStrings.PROBE_ERROR_PREFIX
                }
                Text(
                    prefix + state.text + if (state.truncated) SettingsStrings.PROBE_TRUNCATED_SUFFIX else "",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().testTag(SettingsTags.PROBE_TEXT),
                )
            }
        }
    }
}

@Composable
private fun VersionCard(info: RuntimeInfo?) {
    Section(tag = SettingsTags.CARD_VERSION, title = SettingsStrings.SECTION_VERSION) {
        if (info == null) {
            Text(SettingsStrings.VERSION_PENDING)
            return@Section
        }
        Text("${SettingsStrings.VERSION_APP}：${info.appVersion}")
        Text(
            "${SettingsStrings.VERSION_INSTALLED}：" +
                (info.installedRuntimeVersion ?: SettingsStrings.VERSION_NOT_INSTALLED),
        )
        Text(
            "${SettingsStrings.VERSION_BUNDLED}：" + when {
                info.manifestPending -> SettingsStrings.VERSION_PENDING
                info.bundledRuntimeVersion != null -> info.bundledRuntimeVersion
                else -> SettingsStrings.VERSION_MISSING
            },
        )
        if (info.bundledRuntimeVersion != null) {
            Text(
                if (info.matches) SettingsStrings.VERSION_MATCH else SettingsStrings.VERSION_MISMATCH,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
