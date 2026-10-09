package works.earendil.pihost.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import works.earendil.pihost.core.engine.settings.ModelCredentialStore
import works.earendil.pihost.core.engine.settings.ModelCredentialStore.ProviderEntry
import works.earendil.pihost.core.engine.settings.RelayEndpointStore
import works.earendil.pihost.core.engine.settings.RelayEndpointStore.RelayEntry
import works.earendil.pihost.core.engine.settings.RelayModel
import works.earendil.pihost.core.environment.MemTier
import works.earendil.pihost.feature.settings.parseRelayModels
import works.earendil.pihost.feature.settings.CrashReportSummary
import works.earendil.pihost.feature.settings.ProbeState
import works.earendil.pihost.feature.settings.RelayDraft
import works.earendil.pihost.feature.settings.RuntimeInfo
import works.earendil.pihost.feature.settings.SettingsViewModel

/** 提示条自动收场的时长（与 `:feature:sessions` 同一档）。 */
private const val NOTICE_AUTO_DISMISS_MS = 4_000L

/**
 * 设置屏（规格书 §4.2）：memTier 三选 + 并行上限 stepper + 默认模型只读 + **模型接入（P2G §4.2）**
 * + `get_state` 探针卡片 + 版本卡片。
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
    val credentials by viewModel.credentials.collectAsState()
    val draft by viewModel.credentialDraft.collectAsState()
    val relays by viewModel.relays.collectAsState()
    val defaultRelay by viewModel.defaultRelay.collectAsState()
    val relayDraft by viewModel.relayDraft.collectAsState()
    val reports by viewModel.crashReports.collectAsState()

    // 诊断面的两个「设备能力」入口（P3A §3.5）：剪贴板 + 一条挂在组合上的协程作用域。
    // 用 `LocalClipboardManager`（Compose 自带，走 `LocalClipboard` 抽象）而不是
    // `ClipboardManager`：后者在 API 31+ 上要 `CONTEXT_CLIPBOARD服务` 那套新签名，
    // Robolectric 里拿不到真剪贴板；前者在测试里可用 `clipboardManager.getText()` 直接读回。
    val clipboard = LocalClipboardManager.current
    val copyScope = rememberCoroutineScope()

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
            CredentialSection(
                configured = credentials,
                draft = draft,
                onDraftChange = viewModel::onCredentialDraftChange,
                onSave = viewModel::saveCredential,
                onClear = viewModel::clearCredential,
                onRefresh = viewModel::refreshCredentials,
            )
            HorizontalDivider()
            RelaySection(
                configured = relays,
                defaultProvider = defaultRelay,
                draft = relayDraft,
                onDraftChange = viewModel::onRelayDraftChange,
                onSave = viewModel::saveRelay,
                onEdit = viewModel::editRelay,
                onSetDefault = viewModel::setRelayDefault,
                onClear = viewModel::clearRelay,
                onRefresh = viewModel::refreshRelays,
            )
            HorizontalDivider()
            DiagnosticsSection(
                reports = reports,
                onRefresh = viewModel::refreshCrashReports,
                onClear = viewModel::clearCrashReports,
                onCopy = {
                    // 读盘是 suspend（`readLatestCrash`），剪贴板是 Compose 侧的东西：
                    // 两件事都发生在这一层，VM 只交回字符串（§3.5 的分工）。
                    copyScope.launch {
                        val text = viewModel.readLatestCrash()
                        if (text == null) {
                            viewModel.crashCopyMissed()
                        } else {
                            clipboard.setText(AnnotatedString(text))
                            viewModel.crashCopied()
                        }
                    }
                },
            )
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

/**
 * 模型接入（规格书 §4.2）：provider 选择 + key 输入 + 保存，下方是已配置列表（每行一个清除按钮）。
 *
 * 三条形状决定：
 * - **provider 选择用 Dialog 不是 `ExposedDropdownMenuBox`**（§4.2 两者都允许，取舍见 RECON-P2G §4.2）：
 *   编译机 gradle 缓存里同时有 material3 1.4.0 与 1.5.0-alpha22，而 `ExposedDropdownMenu`/`menuAnchor`
 *   在 1.3→1.5 之间有签名漂移；`AlertDialog` 这条路本仓已在 Robolectric 上跑绿（`:feature:chat` 的
 *   `PermissionDialog.kt:27`），`DropdownMenu` 走 `Popup` 窗口则零先例——不赌。
 * - **选中项是本 composable 的本地状态**（`remember`）：它是「这次要写给谁」的草稿，不是持久设置；
 *   key 的草稿反而在 VM 里（§4.2 要求保存成功后清空输入框，只有知道写盘结果的一方才该发起清空）。
 * - **列表只显示 providerId + 状态**：`ProviderEntry` 里没有 key（§2 红线「UI 永不回显已存 key」），
 *   所以这里连*尝试*显示密钥的字段都拿不到，不是靠 UI 克制。
 */
@Composable
private fun CredentialSection(
    configured: List<ProviderEntry>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSave: (providerId: String, apiKey: String) -> Unit,
    onClear: (providerId: String) -> Unit,
    onRefresh: () -> Unit,
) {
    var selectedProvider by remember { mutableStateOf(ModelCredentialStore.KNOWN_PROVIDERS.first().providerId) }
    var pickerOpen by remember { mutableStateOf(false) }

    Section(tag = SettingsTags.CREDENTIAL_SECTION, title = SettingsStrings.SECTION_CREDENTIAL) {
        Text(SettingsStrings.CREDENTIAL_HINT, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = { pickerOpen = true },
            modifier = Modifier.testTag(SettingsTags.CREDENTIAL_PROVIDER_BUTTON),
        ) {
            Text(
                "${SettingsStrings.CREDENTIAL_PROVIDER_LABEL}：" +
                    ModelCredentialStore.displayNameOf(selectedProvider),
            )
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            label = { Text(SettingsStrings.CREDENTIAL_KEY_LABEL) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().testTag(SettingsTags.CREDENTIAL_KEY_FIELD),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // 空 key 的第一道门（VM 里还有第二道，core 的 require 是第三道）
            OutlinedButton(
                onClick = { onSave(selectedProvider, draft) },
                enabled = draft.isNotBlank(),
                modifier = Modifier.testTag(SettingsTags.CREDENTIAL_SAVE),
            ) { Text(SettingsStrings.CREDENTIAL_SAVE) }
            TextButton(
                onClick = onRefresh,
                modifier = Modifier.testTag(SettingsTags.CREDENTIAL_REFRESH),
            ) { Text(SettingsStrings.CREDENTIAL_REFRESH) }
        }

        if (configured.isEmpty()) {
            Text(
                SettingsStrings.CREDENTIAL_NONE,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(SettingsTags.CREDENTIAL_EMPTY),
            )
        } else {
            configured.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth().testTag(SettingsTags.credentialRow(entry.providerId)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "${ModelCredentialStore.displayNameOf(entry.providerId)}（${entry.providerId}）",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (entry.keyConfigured) SettingsStrings.CREDENTIAL_CONFIGURED else SettingsStrings.CREDENTIAL_NO_KEY,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.keyConfigured) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    TextButton(
                        onClick = { onClear(entry.providerId) },
                        modifier = Modifier.testTag(SettingsTags.credentialClear(entry.providerId)),
                    ) { Text(SettingsStrings.CREDENTIAL_CLEAR) }
                }
            }
        }
    }

    if (pickerOpen) {
        CredentialPickerDialog(
            onPick = { providerId ->
                selectedProvider = providerId
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/** provider 选择对话框：10 项固定清单（§3 定案顺序），每行 tag 用 providerId，不依赖中文展示名。 */
@Composable
private fun CredentialPickerDialog(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(SettingsStrings.CREDENTIAL_PICKER_TITLE) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ModelCredentialStore.KNOWN_PROVIDERS.forEach { option ->
                    TextButton(
                        onClick = { onPick(option.providerId) },
                        modifier = Modifier.fillMaxWidth().testTag(SettingsTags.credentialProviderItem(option.providerId)),
                    ) {
                        Text("${option.displayName}（${option.providerId}）")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(SettingsStrings.CREDENTIAL_PICKER_CANCEL) }
        },
        modifier = Modifier.testTag(SettingsTags.CREDENTIAL_PICKER),
    )
}

/**
 * 列表行里 baseUrl 的显示上限（§3.2「截断显示」）。
 *
 * 为什么截断而不是 wrap：这一行还要放「设为默认」「清除」两个按钮和一枚「默认」徽标，
 * 而中转地址普遍是 `https://xxx.example.com/v1/xxx` 这种 40+ 字符的串，
 * 让它自由换行会把两个按钮挤出屏幕宽度。地址**不是**凭据（§3.1），所以完整值随时可以在
 * 「接口地址」输入框里重新读到（编辑同一行时先点它填回草稿即可）。
 */
private const val RELAY_URL_DISPLAY_LIMIT = 36

/**
 * 自定义中转（规格书 P2H §3.2）：四个输入框 + api 选择器 + 保存，下方是已配置列表。
 *
 * 四条形状决定：
 * - **api 选择器用 `AlertDialog` + `RadioButton`**（§3.2 原文「RadioButton Dialog 同现有 provider picker 形状」）：
 *   provider 那个下拉为什么不用 `ExposedDropdownMenu`，理由在 [CredentialSection] 的 KDoc 里已经交过
 *   （material3 1.3→1.5 的 `menuAnchor` 签名漂移 + `Popup` 窗口在 Robolectric 零先例），这里不重做一遍；
 * - **保存按钮的启用条件是三个必填项，密钥不在内**（§3.2：留空 = 沿用已存 key，所以空 key 是合法输入）；
 *   VM 里还有同一套三道门，UI 只是第一道；
 * - **行末「编辑」把已存条目播种回表单**（P3B-FIX1 §3.2 第 2 条）：模型清单里的思考尾缀 `*` 由
 *   [works.earendil.pihost.feature.settings.relayModelsToText] 逐字还回来，否则「带 `*` 存的、
 *   点开不带 `*`」，下一次保存就把思考档写没了（密钥仍**不**回填，§2 红线；用例 `h14`）；
 * - **行格式 = `providerId · baseUrl · N 个模型`**（§3.2 原文），默认行前面多一枚「默认」徽标，
 *   数据来自 `settings.json`（[defaultProvider]），不是本地布尔；
 * - **`RelayEntry` 里没有 apiKey**：这一层连*尝试*显示密钥的字段都拿不到，跟凭据通路一样不是靠 UI 克制
 *   （§2 红线）。密钥输入框带 `PasswordVisualTransformation`，且 hint 明写「留空 = 沿用已存密钥」。
 */
@Composable
private fun RelaySection(
    configured: List<RelayEntry>,
    defaultProvider: String?,
    draft: RelayDraft,
    onDraftChange: ((RelayDraft) -> RelayDraft) -> Unit,
    onSave: (providerId: String, baseUrl: String, api: String, models: List<RelayModel>, apiKey: String?) -> Unit,
    onEdit: (providerId: String) -> Unit,
    onSetDefault: (providerId: String, modelId: String) -> Unit,
    onClear: (providerId: String) -> Unit,
    onRefresh: () -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val models = parseRelayModels(draft.models)

    Section(tag = SettingsTags.RELAY_SECTION, title = SettingsStrings.SECTION_RELAY) {
        Text(SettingsStrings.RELAY_HINT, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = draft.providerId,
            onValueChange = { value -> onDraftChange { it.copy(providerId = value) } },
            label = { Text(SettingsStrings.RELAY_ID_LABEL) },
            supportingText = { Text(SettingsStrings.RELAY_ID_HINT, style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(SettingsTags.RELAY_ID_FIELD),
        )
        OutlinedTextField(
            value = draft.baseUrl,
            onValueChange = { value -> onDraftChange { it.copy(baseUrl = value) } },
            label = { Text(SettingsStrings.RELAY_URL_LABEL) },
            placeholder = { Text("https://relay.example.com/v1") },
            supportingText = { Text(SettingsStrings.RELAY_URL_HINT, style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(SettingsTags.RELAY_URL_FIELD),
        )
        OutlinedTextField(
            value = draft.models,
            onValueChange = { value -> onDraftChange { it.copy(models = value) } },
            label = { Text(SettingsStrings.RELAY_MODELS_LABEL) },
            placeholder = { Text(SettingsStrings.RELAY_MODELS_EXAMPLE) },
            supportingText = { Text(SettingsStrings.RELAY_MODELS_HINT, style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(SettingsTags.RELAY_MODELS_FIELD),
        )
        OutlinedTextField(
            value = draft.apiKey,
            onValueChange = { value -> onDraftChange { it.copy(apiKey = value) } },
            label = { Text(SettingsStrings.RELAY_KEY_LABEL) },
            supportingText = { Text(SettingsStrings.RELAY_KEY_HINT, style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().testTag(SettingsTags.RELAY_KEY_FIELD),
        )
        OutlinedButton(
            onClick = { pickerOpen = true },
            modifier = Modifier.testTag(SettingsTags.RELAY_API_BUTTON),
        ) {
            Text(
                "${SettingsStrings.RELAY_API_LABEL}：" + RelayEndpointStore.apiDisplayName(draft.api),
            )
        }
        // 选中项自带一句「为什么是它」——§3.2 要求默认项标注适用面，说在选择器旁边才看得见
        RelayEndpointStore.RELAY_APIS.firstOrNull { it.api == draft.api }?.note?.let { note ->
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // 三道门的第一道（VM 里还有第二道，core 的 require 是第三道）；空密钥**不在**条件里
            OutlinedButton(
                onClick = { onSave(draft.providerId, draft.baseUrl, draft.api, models, draft.apiKey) },
                enabled = draft.providerId.isNotBlank() && draft.baseUrl.isNotBlank() && models.isNotEmpty(),
                modifier = Modifier.testTag(SettingsTags.RELAY_SAVE),
            ) { Text(SettingsStrings.RELAY_SAVE) }
            TextButton(
                onClick = onRefresh,
                modifier = Modifier.testTag(SettingsTags.RELAY_REFRESH),
            ) { Text(SettingsStrings.RELAY_REFRESH) }
        }

        if (configured.isEmpty()) {
            Text(
                SettingsStrings.RELAY_NONE,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(SettingsTags.RELAY_EMPTY),
            )
        } else {
            configured.forEach { entry ->
                val isDefault = entry.providerId == defaultProvider
                Row(
                    modifier = Modifier.fillMaxWidth().testTag(SettingsTags.relayRow(entry.providerId)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isDefault) {
                        Text(
                            SettingsStrings.RELAY_DEFAULT_BADGE,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag(SettingsTags.RELAY_DEFAULT_BADGE),
                        )
                    }
                    Text(
                        relayRowText(entry),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    // §2 红线在这一行只剩两种字：已配置 / 无可用密钥。`RelayEntry` 里没有 key 字段，
                    // 所以这里连*可能*打印明文的数据都拿不到（与凭据列表同一套措辞）。
                    Text(
                        if (entry.keyConfigured) SettingsStrings.CREDENTIAL_CONFIGURED else SettingsStrings.CREDENTIAL_NO_KEY,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.keyConfigured) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.testTag(SettingsTags.relayRowKey(entry.providerId)),
                    )
                    OutlinedButton(
                        // 把这一行播种回表单（P3B-FIX1 §3.2 第 2 条）：思考尾缀 `*` 必须跟着回来，
                        // 所以文本由 VM 侧的 relayModelsToText 生成，不在这里手拼
                        onClick = { onEdit(entry.providerId) },
                        modifier = Modifier.testTag(SettingsTags.relayEdit(entry.providerId)),
                    ) { Text(SettingsStrings.RELAY_EDIT) }
                    OutlinedButton(
                        // 没有模型 id 就钉不了默认（pi 的 defaultModel 必须指向一个真实 id）
                        onClick = { entry.models.firstOrNull()?.id?.let { onSetDefault(entry.providerId, it) } },
                        enabled = entry.models.isNotEmpty(),
                        modifier = Modifier.testTag(SettingsTags.relaySetDefault(entry.providerId)),
                    ) { Text(SettingsStrings.RELAY_SET_DEFAULT) }
                    TextButton(
                        onClick = { onClear(entry.providerId) },
                        modifier = Modifier.testTag(SettingsTags.relayClear(entry.providerId)),
                    ) { Text(SettingsStrings.RELAY_CLEAR) }
                }
            }
        }
    }

    if (pickerOpen) {
        RelayApiPickerDialog(
            selected = draft.api,
            onPick = { api ->
                onDraftChange { it.copy(api = api) }
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/** §3.2 原文的行格式：`providerId · baseUrl（截断）· N 个模型`。 */
private fun relayRowText(entry: RelayEntry): String {
    val url = if (entry.baseUrl.length > RELAY_URL_DISPLAY_LIMIT) {
        entry.baseUrl.take(RELAY_URL_DISPLAY_LIMIT) + "…"
    } else {
        entry.baseUrl
    }
    return "${entry.providerId} · $url · " + String.format(SettingsStrings.RELAY_MODEL_COUNT, entry.models.size)
}

/** api 选择对话框：固定五项（§3.2），每行一个 RadioButton，tag 用 api 原值。 */
@Composable
private fun RelayApiPickerDialog(
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(SettingsStrings.RELAY_API_PICKER_TITLE) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RelayEndpointStore.RELAY_APIS.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // tag 打在 RadioButton 本身：`Selected` 语义只住在这个节点上，
                        // 「选中项展示」那条用例要断的是它，而不是行里那两行文字。
                        RadioButton(
                            selected = option.api == selected,
                            onClick = { onPick(option.api) },
                            modifier = Modifier.testTag(SettingsTags.relayApiItem(option.api)),
                        )
                        Column {
                            Text(option.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                option.note ?: option.api,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(SettingsStrings.RELAY_API_PICKER_CANCEL) }
        },
        modifier = Modifier.testTag(SettingsTags.RELAY_API_PICKER),
    )
}

/**
 * 诊断（规格书 P3A §3.5）：崩溃报告列表 + 空态 + 三个动作（重读 / 复制最近一份 / 清空）。
 *
 * 四条形状决定：
 * - **正文永不上屏**：一行只有「文件名 · 大小 · 时间」（[crashRowText]）。
 *   崩溃正文里是绝对路径 + 完整栈 + 用户当时的操作语境，把它渲染进屏幕等于
 *   把内部结构交给每一张截图；要读它只有「复制最近一份」这一条路（走剪贴板，不走 UI 文本）。
 * - **三个按钮都不因列表为空而禁用**：`readLatest` 的语义是「最近一份**读得动**的」，
 *   列表非空但全坏（掉电截成空文件）时按钮必须还能点，点了才拿得到 null，
 *   才有 [SettingsStrings.DIAGNOSTICS_NONE_NOTICE] 那条回执；禁用反而把这条通路堵死。
 * - **空态只有一句**：§3.5 原文要求的「无崩溃记录」。不在「还没读」与「读了没有」之间造第三态——
 *   `refreshCrashReports()` 在 `init` 里就发了出去，而它读的是本地 ≤10 个小文件，
 *   一帧之内必有结果；造一个「读取中…」占位要额外一个状态位，换来的是一句几乎没人看见的话。
 * - **清空说份数**：回执文案来自 [SettingsStrings.crashCleared]，参数是**实际删掉的份数**。
 */
@Composable
private fun DiagnosticsSection(
    reports: List<CrashReportSummary>,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
) {
    Section(tag = SettingsTags.DIAGNOSTICS_SECTION, title = SettingsStrings.SECTION_DIAGNOSTICS) {
        Text(SettingsStrings.DIAGNOSTICS_HINT, style = MaterialTheme.typography.bodySmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onCopy,
                modifier = Modifier.testTag(SettingsTags.DIAGNOSTICS_COPY),
            ) { Text(SettingsStrings.DIAGNOSTICS_COPY) }
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.testTag(SettingsTags.DIAGNOSTICS_CLEAR),
            ) { Text(SettingsStrings.DIAGNOSTICS_CLEAR) }
            TextButton(
                onClick = onRefresh,
                modifier = Modifier.testTag(SettingsTags.DIAGNOSTICS_REFRESH),
            ) { Text(SettingsStrings.DIAGNOSTICS_REFRESH) }
        }

        if (reports.isEmpty()) {
            Text(
                SettingsStrings.DIAGNOSTICS_NONE,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(SettingsTags.DIAGNOSTICS_EMPTY),
            )
        } else {
            reports.forEach { report ->
                Text(
                    crashRowText(report),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().testTag(SettingsTags.diagnosticsRow(report.fileName)),
                )
            }
        }
    }
}

/** 一行报告：`crash-1700000000000.txt · 1.2 KB · 10-09 12:34`（正文不在这里，永远不在这里）。 */
private fun crashRowText(report: CrashReportSummary): String =
    "${report.fileName} · ${crashSizeText(report.sizeBytes)} · ${crashTimeText(report.modified)}"

/** 字节数人性化：1024 以下给 B，往上 KB/MB 各一档，保留一位小数（诊断页不需要精确到字节的读数）。 */
private fun crashSizeText(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> String.format("%.1f KB", bytes / 1_024.0)
    else -> String.format("%.1f MB", bytes / 1_048_576.0)
}

/** mtime → 本地时间（固定 pattern，见 [SettingsStrings.DIAGNOSTICS_TIME_FORMAT] 的区域漂移理由）。 */
private fun crashTimeText(instant: Instant): String =
    DateTimeFormatter.ofPattern(SettingsStrings.DIAGNOSTICS_TIME_FORMAT)
        .withZone(ZoneId.systemDefault())
        .format(instant)

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
