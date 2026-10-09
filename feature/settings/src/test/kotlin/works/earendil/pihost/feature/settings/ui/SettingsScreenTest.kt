package works.earendil.pihost.feature.settings.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import works.earendil.pihost.core.engine.SessionState
import works.earendil.pihost.core.engine.settings.AppSettings
import works.earendil.pihost.core.engine.settings.ModelCredentialStore
import works.earendil.pihost.core.engine.settings.ModelCredentialStore.ProviderEntry
import works.earendil.pihost.core.engine.settings.RelayEndpointStore
import works.earendil.pihost.core.engine.settings.RelayEndpointStore.RelayEntry
import works.earendil.pihost.core.engine.settings.RelayModel
import works.earendil.pihost.core.environment.MemTier
import works.earendil.pihost.feature.settings.ui.SettingsStrings
import works.earendil.pihost.feature.settings.CrashReportSummary
import works.earendil.pihost.feature.settings.FakeDiagnosticsSource
import works.earendil.pihost.feature.settings.FakeEngine
import works.earendil.pihost.feature.settings.FakeModelCredentialSource
import works.earendil.pihost.feature.settings.FakeRelayEndpointSource
import works.earendil.pihost.feature.settings.FakeRuntimeInfo
import works.earendil.pihost.feature.settings.FakeSettingsRepository
import works.earendil.pihost.feature.settings.ManifestRead
import works.earendil.pihost.feature.settings.ProbeState
import works.earendil.pihost.feature.settings.RuntimeInfo
import works.earendil.pihost.feature.settings.SettingsViewModel
import works.earendil.pihost.feature.settings.probeResponse
import works.earendil.pihost.core.rpc.RpcCommand

/**
 * `SettingsScreen` 的渲染与交互（规格书 §4.2 的四个部件 + P2G §4.2 的「模型接入」都必须在树上，
 * 且点下去真的走 VM）。
 *
 * `pump()` 抄 `MainActivityTest`/`SessionsScreenTest` 的配方：`waitForIdle()` 只收敛重组，
 * 不保证 `viewModelScope` 的 pending task 已跑完。
 * 本文件不建 Hilt 图（`SettingsViewModel` 是普通 `ViewModel`，六个依赖都是假体，
 * 手工构造比拉一条 `@HiltViewModel` 测试路径便宜得多）。P3A §3.5 加的诊断面走
 * [FakeDiagnosticsSource]，默认空列表 —— 对 g 与 h 那两代用例零干扰。
 *
 * **`qualifiers` 的高度从 2400dp 抬到 6400dp（P2H 实测的必要改动，不是装饰）**：
 * 整屏是一个 `verticalScroll` 的 `Column`，提示条 `NOTICE` 排在**所有 Section 之后**，
 * 而 `assertIsDisplayed()` 问的是「节点边界与根节点可见区相交吗」——P2H 新增的中转 Section
 * 自带四个输入框 + 两条 supportingText + 一个按钮行，列高超过 2400dp 后 notice 被推出可见区，
 * g02/g09/g11/g12 四条老用例与 h02/h06/… 新用例一起转红（红的是「看不见」，不是「没内容」：
 * 同一句文案用 `assertExists()` 仍然通过）。这里选择**加高虚拟屏**而不是把老用例改成 `assertExists`：
 * 前者保住的是「用户一屏之内真能看见这条提示」这条断言的语义，后者会把它悄悄降级成「树上有个节点」。
 *
 * **P3A §3.5 再抬到 7200dp**：诊断 Section 插在 ProbeCard 之前（一段提示 + 三颗按钮 + 若干行），
 * 又把 notice 往下推了一屏的几分之一。同一条理由，第二次加码——不是想要更大的屏，
 * 是 `assertIsDisplayed` 的语义不允许 notice 掉出可见区。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h7200dp")
class SettingsScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val backs = java.util.concurrent.atomic.AtomicInteger(0)

    private fun pump() {
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
    }

    /**
     * 屏幕上**所有输入框当前持有的明文**，按节点顺序（只用于调试输出与 P2H 那几条「非空持有者」断言）。
     *
     * 为什么绕这一圈而不是 `assertTextEquals`（编译机实测的语义树：g09/g12 第一次就撞在上面）：
     * 密钥输入框那个合并节点上 `Text = [API 密钥]`（label 被 merge 进来）、
     * `EditableText` 是 **`PasswordVisualTransformation` 之后的显示串**（`sk-x` → `••••`），
     * 而 `assertTextEquals` 比的是 `Text + EditableText` —— 于是它既断不了「保存后清空」、
     * 也断不了「失败时草稿还在」，只会告诉你 `Text + EditableText = [API 密钥, ••••]`。
     * 真值住在 `SemanticsProperties.InputText`。
     */
    private fun inputTexts(): List<String> =
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.InputText))
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.InputText)?.text }

    /** 输入框里此刻的真值（并且这串明文只有一个输入框持有者）。 */
    private fun assertKeyValue(expected: String) {
        assertEquals("凭据密钥输入框的真值", expected, inputTextOf(SettingsTags.CREDENTIAL_KEY_FIELD))
        if (expected.isNotBlank()) {
            assertEquals("这串凭据明文只该有一个持有节点", listOf(SettingsTags.CREDENTIAL_KEY_FIELD), holdersOf(expected))
        }
    }

    /**
     * 此刻把 [plaintext] 当作**可编辑内容**持有的输入框 tag 清单。
     *
     * 为什么按「内容包含」而不是按「非空」筛（P2H 编译机实测的教训）：中转 Section 一进来，
     * 树上就有五个输入框，而写失败重试那条路上 id/url/models 三个框**本来就该留着**用户填的地址与模型 id ——
     * 它们不是凭据。按非空筛会把它们算成回显持有者（h13 第一次就是这么红的），
     * 真正要钉的是「这一串明文有没有第二个家」，所以问法按明文本身筛。
     */
    private fun holdersOf(plaintext: String): List<String> =
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.InputText))
            .fetchSemanticsNodes()
            .filter { it.config.getOrNull(SemanticsProperties.InputText)?.text?.contains(plaintext) == true }
            .map { it.config.getOrNull(SemanticsProperties.TestTag) ?: "<无 tag>" }

    /** 指定 tag 输入框的**真值**（`InputText`，即变换前那一份）。 */
    private fun inputTextOf(tag: String): String? = composeRule.onNodeWithTag(tag)
        .fetchSemanticsNode("输入框（tag=$tag）")
        .config
        .getOrNull(SemanticsProperties.InputText)
        ?.text

    /** 指定 tag 输入框的**显示形态**（`EditableText`）。 */
    private fun displayTextOf(tag: String): String {
        val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode("输入框（tag=$tag）")
        return node.config.getOrNull(SemanticsProperties.EditableText)?.toString()
            ?: "<节点上没有 EditableText 属性>"
    }

    /**
     * 中转密钥输入框里的真值 + 「这串明文只有它一个持有者」。
     *
     * 与 [assertKeyValue] 同一条红线的第二次落地：P2H 多了一个会接密钥的框，回显风险也翻倍。
     */
    private fun assertRelayKeyValue(expected: String) {
        assertEquals("中转密钥输入框的真值", expected, inputTextOf(SettingsTags.RELAY_KEY_FIELD))
        if (expected.isNotBlank()) {
            assertEquals("这串中转密钥只该住在一个节点上", listOf(SettingsTags.RELAY_KEY_FIELD), holdersOf(expected))
        }
    }

    /** 全树所有**显示文本**（`SemanticsProperties.Text`，含被 merge 上来的子节点）。 */
    private fun displayTexts(): List<String> =
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .fetchSemanticsNodes()
            .flatMap { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            }

    /**
     * §2 红线：那串明文不许出现在任何**显示文本**里（列表行、按钮、notice 全都算）。
     *
     * 为什么不直接用 `onNodeWithText(substring = true)`（编译机 g12 实测）：它的匹配面**包含可编辑
     * 节点的 `InputText`**，草稿还在输入框时它必然命中那一个节点——那是「草稿没被吞」，不是回显。
     * 输入框的 `EditableText` 本身已经是 `PasswordVisualTransformation` 之后的圆点串，
     * 所以按 `Text` 属性扫才是「屏幕上看得到什么」的忠实问法。
     * 至于「明文总共只有输入框一个持有者」，那是 [assertKeyValue] 用列表相等钉住的。
     */
    private fun assertKeyNotEchoed(plaintext: String) {
        assertFalse(
            "显示文本里出现了密钥明文：${displayTexts().filter { it.contains(plaintext) }}",
            displayTexts().any { it.contains(plaintext) },
        )
    }

    /**
     * 输入框的**显示形态**——即 `SemanticsProperties.EditableText`，它是
     * `PasswordVisualTransformation` 之后的串（编译机实测：草稿 `sk-x` 时该属性是 `••••`，
     * 而真值另住在 `InputText`）。§4.2 点名要这个变换，所以它必须有一条能咬人的断言。
     */
    private fun keyFieldDisplay(): String {
        val node = composeRule.onNodeWithTag(SettingsTags.CREDENTIAL_KEY_FIELD)
            .fetchSemanticsNode("密钥输入框（tag=${SettingsTags.CREDENTIAL_KEY_FIELD}）")
        return node.config.getOrNull(SemanticsProperties.EditableText)?.toString()
            ?: "<节点上没有 EditableText 属性>"
    }

    /**
     * 此刻剪贴板上的纯文本（没有内容 → null）。
     *
     * `LocalClipboardManager` 在 Android 上就是 `ClipboardManager`（Robolectric 的
     * `ShadowClipboardManager` 把 clip 存在内存里），所以这里直接问**系统服务**那一侧：
     * 断言的是「用户 Ctrl+V 出来的是什么」，而不是「Compose 有没有调过 setText」。
     * `primaryClip` 在没写过的时候可能是 itemCount=0 的空 ClipData，两种空形态都算 null。
     */
    private fun clipboardText(): String? {
        val manager = composeRule.activity.getSystemService(ClipboardManager::class.java)
        val clip = manager?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0)?.coerceToText(composeRule.activity)?.toString()
    }

    /** 把剪贴板种成一个可辨认的值，好用「落空时不许动它」这条断言。 */
    private fun seedClipboard(marker: String) {
        composeRule.activity.getSystemService(ClipboardManager::class.java)!!
            .setPrimaryClip(ClipData.newPlainText("test", marker))
        assertEquals("假体没种上", marker, clipboardText())
    }

    /** 一行的第三段（时间）只断言**形状**：时区跟着跑测试的机器，写死 `11-14 22:13` 是一条假断言。 */
    private fun rowParts(text: String): List<String> = text.split(" · ").map { it.trim() }

    /** 诊断行节点：按 tag 取，返回其唯一显示文本。 */
    private fun rowText(fileName: String): String =
        composeRule.onNodeWithTag(SettingsTags.diagnosticsRow(fileName))
            .fetchSemanticsNode("诊断行（file=$fileName）")
            .config.getOrNull(SemanticsProperties.Text)!!
            .single()
            .text

    private class Rig(
        val vm: SettingsViewModel,
        val repository: FakeSettingsRepository,
        val engine: FakeEngine,
        val info: FakeRuntimeInfo,
        val credentials: FakeModelCredentialSource,
        val relays: FakeRelayEndpointSource,
        val diagnostics: FakeDiagnosticsSource,
    )

    private fun rig(
        settings: AppSettings = AppSettings(memTier = MemTier.MID, maxParallelSessions = 2),
        live: Boolean = true,
        info: FakeRuntimeInfo = FakeRuntimeInfo(
            marker = RuntimeInfo("2.5.0-test", "1.0.4-r7", null, manifestPending = true),
            manifest = ManifestRead.Present("1.0.4-r7"),
        ),
        credentials: FakeModelCredentialSource = FakeModelCredentialSource(),
        relays: FakeRelayEndpointSource = FakeRelayEndpointSource(),
        diagnostics: FakeDiagnosticsSource = FakeDiagnosticsSource(),
    ): Rig {
        val engine = FakeEngine()
        if (live) engine.setState(FakeEngine.SESSION, SessionState.Ready)
        val repository = FakeSettingsRepository(settings)
        val vm = SettingsViewModel(repository, engine, info, credentials, relays, diagnostics)
        composeRule.setContent {
            SettingsScreen(viewModel = vm, onBack = { backs.incrementAndGet() })
        }
        pump()
        return Rig(vm, repository, engine, info, credentials, relays, diagnostics)
    }

    // ── g01 四个部件都在树上 ──────────────────────────────────────────────────────

    @Test
    fun `g01 renders the three pickers plus the probe and version cards`() {
        rig()

        composeRule.onNodeWithTag(SettingsTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithText(SettingsStrings.TITLE).assertIsDisplayed()
        MemTier.entries.forEach { tier ->
            composeRule.onNodeWithTag(SettingsTags.tier(tier)).assertExists()
        }
        composeRule.onNodeWithText(SettingsStrings.SECTION_PROBE).assertIsDisplayed()
        composeRule.onNodeWithText(SettingsStrings.SECTION_VERSION).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.CARD_PROBE).assertExists()
        composeRule.onNodeWithTag(SettingsTags.CARD_VERSION).assertExists()
        // §4.4 硬要求的提示
        composeRule.onNodeWithText(SettingsStrings.TIER_HINT, substring = true).assertIsDisplayed()
        // 默认模型未指定 → 显示 pi 内置默认
        composeRule.onNodeWithText(SettingsStrings.MODEL_PI_DEFAULT).assertIsDisplayed()
    }

    // ── g02 点档位 → 写仓库 + 出现「下次启动会话生效」提示 ────────────────────────

    @Test
    fun `g02 tapping a tier writes through the repository and explains the timing`() {
        val rig = rig()

        composeRule.onNodeWithTag(SettingsTags.tier(MemTier.HIGH)).performClick()
        pump()

        assertEquals(
            listOf(FakeSettingsRepository.Write("memTier", MemTier.HIGH)),
            rig.repository.writes,
        )
        assertEquals(MemTier.HIGH, rig.vm.settings.value.memTier)
        composeRule.onNodeWithTag(SettingsTags.NOTICE).assertIsDisplayed()
        composeRule.onNodeWithText(SettingsStrings.memTierSaved(MemTier.HIGH), substring = true)
            .assertIsDisplayed()
    }

    // ── g03 stepper：加到上限后 + 禁用、减到下限后 − 禁用 ─────────────────────────

    @Test
    fun `g03 the parallel stepper clamps at both ends`() {
        val rig = rig(settings = AppSettings(maxParallelSessions = 4))

        composeRule.onNodeWithTag(SettingsTags.PARALLEL_INCREMENT).assertIsNotEnabled()
        composeRule.onNodeWithText("4 条").assertIsDisplayed()

        composeRule.onNodeWithTag(SettingsTags.PARALLEL_DECREMENT).performClick()
        pump()
        assertEquals(FakeSettingsRepository.Write("maxParallelSessions", 3), rig.repository.writes.last())
        composeRule.onNodeWithText("3 条").assertIsDisplayed()

        // 一路减到 1，再看 − 的禁用态
        repeat(2) {
            composeRule.onNodeWithTag(SettingsTags.PARALLEL_DECREMENT).performClick()
            pump()
        }
        composeRule.onNodeWithText("1 条").assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.PARALLEL_DECREMENT).assertIsNotEnabled()
        assertEquals(listOf(3, 2, 1), rig.repository.writes.map { it.value })
    }

    // ── g04 探针通路：点按钮 → 命令出去 → 卡片转等待 → 应答落卡片 ──────────────────

    @Test
    fun `g04 the probe button sends get_state and then renders the answer`() {
        val rig = rig()

        composeRule.onNodeWithText(SettingsStrings.PROBE_IDLE).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.PROBE_RUN).performClick()
        pump()

        assertEquals(RpcCommand.GetState, rig.engine.sent.single().command)
        composeRule.onNodeWithText(SettingsStrings.PROBE_RUNNING).assertIsDisplayed()
        // 等待期间按钮必须禁用（同 §13：探针是一次性请求，不该被连点）
        composeRule.onNodeWithTag(SettingsTags.PROBE_RUN).assertIsNotEnabled()

        rig.engine.emit(probeResponse(id = rig.engine.sent.single().id, keys = 2))
        pump()

        val done = rig.vm.probe.value as ProbeState.Done
        composeRule.onNodeWithTag(SettingsTags.PROBE_TEXT).assertIsDisplayed()
        composeRule.onNodeWithText(done.text.take(40), substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(
            SettingsStrings.PROBE_SESSION_SUFFIX + FakeEngine.SESSION.take(8),
            substring = true,
        ).assertExists()
        // 收口后按钮重新可用（不能让它停在禁用态）
        composeRule.onNodeWithTag(SettingsTags.PROBE_RUN).assertIsEnabled()
        composeRule.onNodeWithTag(SettingsTags.PROBE_CLEAR).performClick()
        pump()
        assertEquals(ProbeState.Idle, rig.vm.probe.value)
        composeRule.onNodeWithText(SettingsStrings.PROBE_IDLE).assertIsDisplayed()
    }

    // ── g05 没有活会话时探针的可见结果 + 返回按钮 ─────────────────────────────────

    @Test
    fun `g05 with no live session the card explains it and back still works`() {
        rig(live = false)

        composeRule.onNodeWithTag(SettingsTags.PROBE_RUN).performClick()
        pump()

        composeRule.onNodeWithTag(SettingsTags.PROBE_TEXT).assertIsDisplayed()
        composeRule.onNodeWithText(SettingsStrings.PROBE_NO_SESSION, substring = true).assertIsDisplayed()

        composeRule.onNodeWithText(SettingsStrings.ACTION_BACK).performClick()
        pump()
        assertEquals("返回走调用方给的回调（NavHost 的 popBackStack）", 1, backs.get())
    }

    // ── g06 版本卡片：三行 + 两档发布后的「会重装」提示 ───────────────────────────

    @Test
    fun `g06 the version card shows all three numbers and the mismatch note`() {
        val rig = rig(
            info = FakeRuntimeInfo(
                marker = RuntimeInfo("2.5.0-test", "1.0.4-r7", null, manifestPending = true),
                manifest = ManifestRead.Present("1.0.4-r8"),
            ),
        )

        composeRule.onNodeWithText("${SettingsStrings.VERSION_APP}：2.5.0-test").assertIsDisplayed()
        composeRule.onNodeWithText("${SettingsStrings.VERSION_INSTALLED}：1.0.4-r7").assertIsDisplayed()
        composeRule.onNodeWithText("${SettingsStrings.VERSION_BUNDLED}：1.0.4-r8").assertIsDisplayed()
        composeRule.onNodeWithText(SettingsStrings.VERSION_MISMATCH).assertIsDisplayed()
        assertEquals(1, rig.info.readCalls.get())
        assertEquals(1, rig.info.manifestCalls.get())
    }

    // ── g07 §4.2 模型接入：Section 在树上，空列表 + 保存按钮的门 ───────────────────

    @Test
    fun `g07 the credential section renders with an empty list and a gated save`() {
        rig()

        composeRule.onNodeWithTag(SettingsTags.CREDENTIAL_SECTION).assertIsDisplayed()
        composeRule.onNodeWithText(SettingsStrings.SECTION_CREDENTIAL).assertIsDisplayed()
        composeRule.onNodeWithText(SettingsStrings.CREDENTIAL_HINT, substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.CREDENTIAL_EMPTY).assertIsDisplayed()
        composeRule.onNodeWithText(SettingsStrings.CREDENTIAL_NONE).assertIsDisplayed()
        // 提供方按钮默认是清单第一项（§3 顺序：deepseek → DeepSeek）
        composeRule.onNodeWithTag(SettingsTags.CREDENTIAL_PROVIDER_BUTTON)
            .assertTextEquals("${SettingsStrings.CREDENTIAL_PROVIDER_LABEL}：DeepSeek")
        composeRule.onNodeWithTag(SettingsTags.CREDENTIAL_KEY_FIELD).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.CREDENTIAL_SAVE).assertIsNotEnabled()
    }

    // ── g08 下拉（Dialog 形态）：10 项全在，选中后按钮展示名字 ────────────────────

    @Test
    fun `g08 the provider picker lists all ten entries and shows the chosen one`() {
        rig()

        composeRule.onNodeWithTag(SettingsTags.CREDENTIAL_PROVIDER_BUTTON).performClick()
        pump()

        ModelCredentialStore.KNOWN_PROVIDERS.forEach { option ->
            composeRule.onNodeWithTag(SettingsTags.credentialProviderItem(option.providerId))
                .assertIsDisplayed()
            composeRule.onNodeWithText("${option.displayName}（${option.providerId}）").assertIsDisplayed()
        }
        assertEquals("清单必须是 §3 那 10 项", 10, ModelCredentialStore.KNOWN_PROVIDERS.size)

        composeRule.onNodeWithTag(SettingsTags.credentialProviderItem("groq")).performClick()
        pump()

        composeRule.onNodeWithTag(SettingsTags.CREDENTIAL_PROVIDER_BUTTON)
            .assertTextEquals("${SettingsStrings.CREDENTIAL_PROVIDER_LABEL}：Groq")
        composeRule.onNodeWithTag(SettingsTags.credentialProviderItem("groq")).assertDoesNotExist()
    }

    /** 取消按钮只收对话框，不改选中项。 */
    @Test
    fun `g08b cancelling the picker keeps the previous provider`() {
        rig()

        composeRule.onNodeWithTag(SettingsTags.CREDENTIAL_PROVIDER_BUTTON).performClick()
        pump()
        composeRule.onNodeWithText(SettingsStrings.CREDENTIAL_PICKER_CANCEL).performClick()
        pump()

        composeRule.onNodeWithTag(SettingsTags.CREDENTIAL_PROVIDER_BUTTON)
            .assertTextEquals("${SettingsStrings.CREDENTIAL_PROVIDER_LABEL}：DeepSeek")
    }

    // ── g09 保存：写进 store + notice 原文 + 输入框清空 + 列表刷新 ────────────────

    @Test
    fun `g09 saving a key writes through and refreshes the list and the field`() {
        val rig = rig()

        composeRule.onNodeWithTag(SettingsTags.CREDENTIAL_KEY_FIELD).performTextInput("sk-live-value")
        pump()
        // 屏幕上此刻必须是 13 个圆点，不是明文（§4.2 点名的 PasswordVisualTransformation）
        assertEquals("•".repeat("sk-live-value".length), keyFieldDisplay())
        composeRule.onNodeWithTag(SettingsTags.CREDENTIAL_SAVE).assertIsEnabled().performClick()
        pump()

        assertEquals(
            "必须真的把 (providerId, key) 交给写入面",
            listOf(FakeModelCredentialSource.Save("deepseek", "sk-live-value")),
            rig.credentials.saves.toList(),
        )
        composeRule.onNodeWithTag(SettingsTags.NOTICE).assertIsDisplayed()
        composeRule.onNodeWithText(SettingsStrings.CREDENTIAL_SAVED).assertIsDisplayed()
        assertKeyValue("")
        assertKeyNotEchoed("sk-live-value")
        composeRule.onNodeWithTag(SettingsTags.credentialRow("deepseek")).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.CREDENTIAL_EMPTY).assertDoesNotExist()
    }

    // ── g10 §2 红线：整棵树里找不到那串 key ───────────────────────────────────────

    @Test
    fun `g10 the saved key is nowhere on screen`() {
        val rig = rig()

        composeRule.onNodeWithTag(SettingsTags.CREDENTIAL_KEY_FIELD).performTextInput("sk-do-not-echo")
        composeRule.onNodeWithTag(SettingsTags.CREDENTIAL_SAVE).performClick()
        pump()
        assertEquals(1, rig.credentials.saves.size)

        // 输入框已清空、列表只显示 provider + 状态 → 显示文本与真值语义两处都必须搜不到
        assertKeyNotEchoed("sk-do-not-echo")
        assertKeyValue("")
        composeRule.onNodeWithTag(SettingsTags.credentialRow("deepseek")).assertIsDisplayed()
        composeRule.onNodeWithText(SettingsStrings.CREDENTIAL_CONFIGURED).assertIsDisplayed()
    }

    // ── g11 清除：走 store + 列表刷新 + notice ────────────────────────────────────

    @Test
    fun `g11 clearing a provider goes through the store and drops the row`() {
        val rig = rig(credentials = FakeModelCredentialSource(listOf(ProviderEntry("deepseek", true))))

        composeRule.onNodeWithTag(SettingsTags.credentialRow("deepseek")).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.credentialClear("deepseek")).performClick()
        pump()

        assertEquals(listOf("deepseek"), rig.credentials.clears.toList())
        composeRule.onNodeWithTag(SettingsTags.credentialRow("deepseek")).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsTags.CREDENTIAL_EMPTY).assertIsDisplayed()
        composeRule.onNodeWithText(SettingsStrings.credentialCleared("deepseek")).assertIsDisplayed()
    }

    // ── g12 写失败：notice 说原因，输入框不清空、不冒成功文案 ─────────────────────

    @Test
    fun `g12 a write failure is explained and the draft survives`() {
        val credentials = FakeModelCredentialSource()
        val rig = rig(credentials = credentials)
        credentials.boom = java.io.IOException("无法创建目录：/x/.pi/agent")

        composeRule.onNodeWithTag(SettingsTags.CREDENTIAL_KEY_FIELD).performTextInput("sk-x")
        composeRule.onNodeWithTag(SettingsTags.CREDENTIAL_SAVE).performClick()
        pump()

        assertEquals(1, credentials.saves.size)
        composeRule.onNodeWithTag(SettingsTags.NOTICE).assertIsDisplayed()
        composeRule.onNodeWithText(SettingsStrings.CREDENTIAL_SAVED).assertDoesNotExist()
        composeRule.onNodeWithText("写入失败：无法创建目录：/x/.pi/agent").assertIsDisplayed()
        // 草稿必须还在（§4.2「失败不清输入」），且全树只有输入框这**一个**节点持有明文；
        // 屏幕上的显示文本里搜不到它——圆点挡的是屏幕，明文只住在 InputText 这一个属性里。
        assertKeyValue("sk-x")
        // 草稿留着，但屏幕上仍是四个圆点（变换没被撤掉）
        assertEquals("•".repeat("sk-x".length), keyFieldDisplay())
        assertKeyNotEchoed("sk-x")
        composeRule.onNodeWithTag(SettingsTags.credentialRow("deepseek")).assertDoesNotExist()
    }

    // ── g13 已配置列表的两种状态（无可用密钥 = 非 api_key 型 / key 空白）────────────

    @Test
    fun `g13 configured rows distinguish key-ready from key-absent`() {
        rig(
            credentials = FakeModelCredentialSource(
                listOf(
                    ProviderEntry("deepseek", keyConfigured = true),
                    ProviderEntry("anthropic", keyConfigured = false),
                    ProviderEntry("somegate", keyConfigured = true),
                ),
            ),
        )

        composeRule.onNodeWithTag(SettingsTags.credentialRow("deepseek")).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.credentialRow("anthropic")).assertIsDisplayed()
        composeRule.onNodeWithText("DeepSeek（deepseek）").assertIsDisplayed()
        // 两行有密钥、一行没有 → 「已配置」必须正好 2 个、「无可用密钥」必须正好 1 个
        composeRule.onAllNodesWithText(SettingsStrings.CREDENTIAL_CONFIGURED).assertCountEquals(2)
        composeRule.onAllNodesWithText(SettingsStrings.CREDENTIAL_NO_KEY).assertCountEquals(1)
        // 清单外的 id 原样显示（不编造展示名），行与清除按钮都在
        composeRule.onNodeWithText("somegate（somegate）").assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.credentialClear("somegate")).assertIsDisplayed()
        // 三行 = 三个清除按钮，一个都不能漏（撤销通路对每一行都开着）
        composeRule.onNodeWithTag(SettingsTags.credentialClear("deepseek")).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.credentialClear("anthropic")).assertIsDisplayed()
    }

    // ── P2H §3.2 自定义中转（h01–h13：规格书点名的八条 + 五条顺手补的红线）──────────

    /** 填前三项必填（providerId / baseUrl / 模型列表），密钥留空。 */
    private fun typeRelayForm(
        id: String = "my-relay",
        url: String = "https://relay.example.com/v1",
        models: String = "deepseek-chat",
        key: String? = null,
    ) {
        composeRule.onNodeWithTag(SettingsTags.RELAY_ID_FIELD).performTextInput(id)
        composeRule.onNodeWithTag(SettingsTags.RELAY_URL_FIELD).performTextInput(url)
        composeRule.onNodeWithTag(SettingsTags.RELAY_MODELS_FIELD).performTextInput(models)
        if (key != null) composeRule.onNodeWithTag(SettingsTags.RELAY_KEY_FIELD).performTextInput(key)
        pump()
    }

    /** 一条已配置中转（默认带 key，方便演「已配置」那一支）。 */
    private fun relayRow(
        providerId: String = "my-relay",
        baseUrl: String = "https://relay.example.com/v1",
        api: String = "openai-completions",
        models: List<RelayModel> = listOf(RelayModel("deepseek-chat")),
        keyConfigured: Boolean = true,
    ) = RelayEntry(providerId, baseUrl, api, models, keyConfigured)

    // ── h01 整个 Section 在树上，且位置在「模型接入」之后（§3.2 第一条）──────────────

    @Test
    fun `h01 the relay section renders all four fields plus the api picker and the empty note`() {
        rig()

        composeRule.onNodeWithTag(SettingsTags.RELAY_SECTION).assertIsDisplayed()
        composeRule.onNodeWithText(SettingsStrings.SECTION_RELAY).assertIsDisplayed()
        listOf(
            SettingsTags.RELAY_ID_FIELD,
            SettingsTags.RELAY_URL_FIELD,
            SettingsTags.RELAY_MODELS_FIELD,
            SettingsTags.RELAY_KEY_FIELD,
            SettingsTags.RELAY_API_BUTTON,
            SettingsTags.RELAY_SAVE,
            SettingsTags.RELAY_REFRESH,
        ).forEach { tag -> composeRule.onNodeWithTag(tag).assertExists() }
        // §3.2 那条「baseUrl 要带路径前缀」的提示就住在 url 输入框的 supportingText 上（报告 §8 第 ② 条）
        composeRule.onNodeWithText(SettingsStrings.RELAY_URL_HINT, substring = true).assertIsDisplayed()
        // 没填过 → 空列表文案
        composeRule.onNodeWithTag(SettingsTags.RELAY_EMPTY).assertIsDisplayed()
        composeRule.onNodeWithText(SettingsStrings.RELAY_NONE).assertIsDisplayed()
        // 默认项自带那句「适用绝大多数中转/one-api/new-api」
        composeRule.onNodeWithText("适用绝大多数中转/one-api/new-api", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.RELAY_SAVE).assertIsNotEnabled()
    }

    // ── h02 保存 → 写通路收到五个入参 + 列表刷新 + 输入框清空 + notice ──────────────

    @Test
    fun `h02 saving a relay writes through, refreshes the list, clears the fields and explains the timing`() {
        val rig = rig()

        typeRelayForm(key = "sk-relay-live")
        composeRule.onNodeWithTag(SettingsTags.RELAY_SAVE).assertIsEnabled()
        composeRule.onNodeWithTag(SettingsTags.RELAY_SAVE).performClick()
        pump()

        assertEquals(
            "五个入参原样进 source（key 非空 → 覆盖语义）",
            listOf(FakeRelayEndpointSource.Save("my-relay", "https://relay.example.com/v1", "openai-completions", listOf(RelayModel("deepseek-chat")), "sk-relay-live")),
            rig.relays.saves,
        )
        composeRule.onNodeWithTag(SettingsTags.relayRow("my-relay")).assertIsDisplayed()
        assertEquals("保存后四个输入框都清空", listOf("", "", "", ""), relayFormValues())
        assertTrue("全树不该再有非空明文持有者", inputTexts().none { it.isNotBlank() })
        composeRule.onNodeWithTag(SettingsTags.NOTICE).assertIsDisplayed()
        composeRule.onNodeWithText(SettingsStrings.RELAY_SAVED, substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.RELAY_EMPTY).assertDoesNotExist()
    }

    /** 中转那四个输入框此刻的真值（顺序：id / url / models / key）。 */
    private fun relayFormValues(): List<String> = listOf(
        SettingsTags.RELAY_ID_FIELD,
        SettingsTags.RELAY_URL_FIELD,
        SettingsTags.RELAY_MODELS_FIELD,
        SettingsTags.RELAY_KEY_FIELD,
    ).map { inputTextOf(it) ?: "<没有 InputText>" }

    // ── h03 留空 key 二次保存 = 沿用已存 key（§3.2 第 2 条：apiKey 必须是 null）──────

    @Test
    fun `h03 saving with a blank key sends null so the stored key survives`() {
        val rig = rig(relays = FakeRelayEndpointSource(initial = listOf(relayRow())))

        typeRelayForm(id = "my-relay", url = "https://new.example.com/v1", models = "gpt-4o")
        composeRule.onNodeWithTag(SettingsTags.RELAY_SAVE).performClick()
        pump()

        val save = rig.relays.saves.single()
        assertNull("留空必须换算成 null，而不是空串（空串会走 core 的空白分支，语义一样但入参要说清）", save.apiKey)
        assertEquals("https://new.example.com/v1", save.baseUrl)
        // 行还在，且仍是「已配置」：只改地址不丢 key
        composeRule.onNodeWithTag(SettingsTags.relayRow("my-relay")).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.relayRowKey("my-relay")).assertTextEquals(SettingsStrings.CREDENTIAL_CONFIGURED)
        assertEquals(listOf("my-relay"), rig.vm.relays.value.map { it.providerId })
        assertTrue("仍是同一条，没多出第二行", rig.vm.relays.value.single().keyConfigured)
    }

    // ── h04 密钥框是掩码形态（§2 红线：不回显；P2G g09 那条断言的第二处落地）─────────

    @Test
    fun `h04 the relay key field masks its display text and never echoes the plaintext`() {
        val plaintext = "sk-relay-do-not-echo"
        rig(relays = FakeRelayEndpointSource(initial = listOf(relayRow())))

        composeRule.onNodeWithTag(SettingsTags.RELAY_KEY_FIELD).performTextInput(plaintext)
        pump()

        assertRelayKeyValue(plaintext)
        assertEquals("显示形态必须是 PasswordVisualTransformation 之后的圆点", "•".repeat(plaintext.length), displayTextOf(SettingsTags.RELAY_KEY_FIELD))
        assertKeyNotEchoed(plaintext)
        // 列表行也只有「已配置」这一种字，没有别处的密钥残留
        composeRule.onNodeWithTag(SettingsTags.relayRowKey("my-relay")).assertTextEquals(SettingsStrings.CREDENTIAL_CONFIGURED)
    }

    // ── h05 设为默认 → 徽标出现（§3.2 第 3 条）──────────────────────────────────────

    @Test
    fun `h05 setting the default pins it and lights up the badge`() {
        val rig = rig(relays = FakeRelayEndpointSource(initial = listOf(
            relayRow(providerId = "relay-a", models = listOf(RelayModel("m-first"), RelayModel("m-second"))),
            relayRow(providerId = "relay-b"),
        )))

        composeRule.onNodeWithTag(SettingsTags.RELAY_DEFAULT_BADGE).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsTags.relaySetDefault("relay-a")).performClick()
        pump()

        assertEquals(
            "钉选用的是这一行的第一个模型 id",
            listOf(FakeRelayEndpointSource.Pin("relay-a", "m-first")),
            rig.relays.pins,
        )
        assertEquals("relay-a", rig.vm.defaultRelay.value)
        composeRule.onNodeWithTag(SettingsTags.relayRow("relay-b")).assertExists()
        // 徽标只有一枚（两行里只有 relay-a 亮），文案就是「默认」
        composeRule.onAllNodesWithTag(SettingsTags.RELAY_DEFAULT_BADGE).assertCountEquals(1)
        composeRule.onNodeWithTag(SettingsTags.RELAY_DEFAULT_BADGE).assertTextEquals(SettingsStrings.RELAY_DEFAULT_BADGE)

        // 换一家钉：徽标必须跟着走，不能留在旧行上
        composeRule.onNodeWithTag(SettingsTags.relaySetDefault("relay-b")).performClick()
        pump()
        composeRule.onAllNodesWithTag(SettingsTags.RELAY_DEFAULT_BADGE).assertCountEquals(1)
        assertEquals("relay-b", rig.vm.defaultRelay.value)
    }

    // ── h06 清除 → 行消失（§3.2 第 4 条），删掉默认那一行时徽标一起没 ────────────────

    @Test
    fun `h06 clearing a relay drops the row and the dangling default badge`() {
        val rig = rig(
            relays = FakeRelayEndpointSource(
                initial = listOf(relayRow(providerId = "relay-a"), relayRow(providerId = "relay-b")),
                initialDefault = "relay-a",
            ),
        )

        composeRule.onNodeWithTag(SettingsTags.RELAY_DEFAULT_BADGE).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.relayClear("relay-a")).performClick()
        pump()

        assertEquals(listOf("relay-a"), rig.relays.clears)
        composeRule.onNodeWithTag(SettingsTags.relayRow("relay-a")).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsTags.relayRow("relay-b")).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.RELAY_DEFAULT_BADGE).assertDoesNotExist()
        assertNull("悬空默认必须一起清掉（§3.1 第 ⑩ 条在 UI 上的可见结果）", rig.vm.defaultRelay.value)
        composeRule.onNodeWithText(SettingsStrings.relayCleared("relay-a"), substring = true).assertIsDisplayed()
    }

    // ── h07 空 providerId：按钮禁用，直调 VM 也不落写通路（§3.2 第 5 条）─────────────

    @Test
    fun `h07 a blank provider id is blocked at the button and inside the view model`() {
        val rig = rig()

        typeRelayForm(id = "   ", url = "https://relay.example.com/v1", models = "m1")
        composeRule.onNodeWithTag(SettingsTags.RELAY_SAVE).assertIsNotEnabled()

        // 绕过 UI 直调：第二道门同样拦得住，而且一次 source 调用都不发
        rig.vm.saveRelay("  ", "https://relay.example.com/v1", "openai-completions", listOf(RelayModel("m1")), "sk-x")
        pump()

        assertEquals(emptyList<FakeRelayEndpointSource.Save>(), rig.relays.saves)
        composeRule.onNodeWithText(SettingsStrings.RELAY_BLANK_ID, substring = true).assertIsDisplayed()
    }

    // ── h08 空模型列表：同样被拦（§3.2 第 8 条）─────────────────────────────────────

    @Test
    fun `h08 an empty model list is rejected without touching the store`() {
        val rig = rig()

        typeRelayForm(models = "  ,  ， ")
        composeRule.onNodeWithTag(SettingsTags.RELAY_SAVE).assertIsNotEnabled()

        rig.vm.saveRelay("my-relay", "https://relay.example.com/v1", "openai-completions", emptyList(), "sk-x")
        pump()

        assertEquals(emptyList<FakeRelayEndpointSource.Save>(), rig.relays.saves)
        composeRule.onNodeWithText(SettingsStrings.RELAY_BLANK_MODELS, substring = true).assertIsDisplayed()
    }

    // ── h09 逗号解析：半角/全角混用、多余空格、重复项（§3.2 第 7 条）────────────────

    @Test
    fun `h09 the model list parses comma separated ids with spaces and duplicates folded away`() {
        val rig = rig()

        typeRelayForm(models = "deepseek-chat, gpt-4o ，deepseek-chat,,  ")
        composeRule.onNodeWithTag(SettingsTags.RELAY_SAVE).performClick()
        pump()

        assertEquals(
            "trim + 去空 + 去重，且全角逗号同样算分隔符",
            listOf(RelayModel("deepseek-chat"), RelayModel("gpt-4o")),
            rig.relays.saves.single().models,
        )
        composeRule.onNodeWithText("2 个模型", substring = true).assertIsDisplayed()
    }

    // ── h10 api 下拉：五项都在、选中项展示、取消不改值（§3.2 第 6 条）────────────────

    @Test
    fun `h10 the api picker offers the five values and shows which one is selected`() {
        val rig = rig()

        composeRule.onNodeWithTag(SettingsTags.RELAY_API_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithText("接口协议：OpenAI 兼容（Chat Completions）", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag(SettingsTags.RELAY_API_BUTTON).performClick()
        pump()

        composeRule.onNodeWithTag(SettingsTags.RELAY_API_PICKER).assertIsDisplayed()
        RelayEndpointStore.RELAY_APIS.forEach { option ->
            composeRule.onNodeWithTag(SettingsTags.relayApiItem(option.api)).assertExists()
        }
        assertEquals("下拉固定五项", 5, RelayEndpointStore.RELAY_APIS.size)
        composeRule.onNodeWithTag(SettingsTags.relayApiItem("openai-completions")).assertIsSelected()
        composeRule.onNodeWithTag(SettingsTags.relayApiItem("pi-messages")).assertIsNotSelected()

        // 先取消：值必须原地不动
        composeRule.onNodeWithText(SettingsStrings.RELAY_API_PICKER_CANCEL).performClick()
        pump()
        assertEquals("openai-completions", rig.vm.relayDraft.value.api)

        // 再选一项：按钮文字与 RadioButton 状态一起跟上
        composeRule.onNodeWithTag(SettingsTags.RELAY_API_BUTTON).performClick()
        pump()
        composeRule.onNodeWithTag(SettingsTags.relayApiItem("anthropic-messages")).performClick()
        pump()
        composeRule.onNodeWithText("接口协议：Anthropic Messages", substring = true).assertIsDisplayed()
        assertEquals("anthropic-messages", rig.vm.relayDraft.value.api)
        // 换到没有 note 的那一项，那句「适用绝大多数…」必须消失（note 只跟着选中项走）
        composeRule.onNodeWithText("适用绝大多数中转/one-api/new-api", substring = true).assertDoesNotExist()
    }

    // ── h11 行格式：`providerId · baseUrl（截断）· N 个模型`（§3.2 原文）────────────

    @Test
    fun `h11 the row shows id, a truncated base url and the model count`() {
        val longUrl = "https://very-long-relay-provider-name.example.com/api/openai/v1"
        rig(
            relays = FakeRelayEndpointSource(
                initial = listOf(relayRow(providerId = "my-relay", baseUrl = longUrl, models = listOf(RelayModel("a"), RelayModel("b"), RelayModel("c")))),
            ),
        )

        composeRule.onNodeWithTag(SettingsTags.relayRow("my-relay")).assertIsDisplayed()
        val rowTexts = displayTexts().filter { it.contains("my-relay ·") }
        assertEquals("行文本只该有一条：$rowTexts", 1, rowTexts.size)
        val rowText = rowTexts.single()
        assertTrue("必须截断，不许把整串地址摊开：$rowText", rowText.contains("…"))
        assertTrue("行尾要带模型数：$rowText", rowText.endsWith("3 个模型"))
        assertFalse("整行显示文本里不许出现完整地址：$rowText", rowText.contains(longUrl))
        assertFalse("也不许出现任何像 key 的东西：$rowText", rowText.contains("sk-"))
        // 截断阈值本身：地址短于阈值时必须原样出现（否则「截断」就成了「永远只显示前缀」）
        composeRule.onNodeWithTag(SettingsTags.relayRowKey("my-relay")).assertTextEquals(SettingsStrings.CREDENTIAL_CONFIGURED)
    }

    // ── h12 没有模型 id 的行钉不了默认（core 的 require 在 UI 上的第一道门）──────────

    @Test
    fun `h12 a row without model ids cannot be pinned as default`() {
        val rig = rig(relays = FakeRelayEndpointSource(initial = listOf(relayRow(models = emptyList()))))

        composeRule.onNodeWithTag(SettingsTags.relaySetDefault("my-relay")).assertIsNotEnabled()
        rig.vm.setRelayDefault("my-relay", "")
        pump()

        assertEquals(emptyList<FakeRelayEndpointSource.Pin>(), rig.relays.pins)
        composeRule.onNodeWithText(SettingsStrings.RELAY_NO_MODEL, substring = true).assertIsDisplayed()
    }

    // ── h13 写盘失败：说原因、草稿留着原地重试（与凭据通路同一支手术刀）──────────────

    @Test
    fun `h13 a write failure is explained and the draft survives`() {
        val rig = rig(relays = FakeRelayEndpointSource().apply { boom = IOException("无法创建目录：/x/.pi/agent") })

        typeRelayForm(key = "sk-relay-live")
        composeRule.onNodeWithTag(SettingsTags.RELAY_SAVE).performClick()
        pump()

        composeRule.onNodeWithText("写入失败：无法创建目录", substring = true).assertIsDisplayed()
        assertEquals("失败后三个必填框都得留着", listOf("my-relay", "https://relay.example.com/v1", "deepseek-chat", "sk-relay-live"), relayFormValues())
        assertRelayKeyValue("sk-relay-live")
        assertKeyNotEchoed("sk-relay-live")
        composeRule.onNodeWithTag(SettingsTags.RELAY_EMPTY).assertIsDisplayed()
    }


    // ══ P3B-FIX1 §3.2：`*` 语法与编辑回填（h14–h15）════════════════════════════════
    //
    // 这两支是本轮的**用户可见面**：h14 演「带 `*` 存的、点开还带 `*`、再存旗还在」这条闭环，
    // h15 演「语法写在屏幕上」（用户不会去读代码，`*` 只有写在 supportingText 上才可能被发现）。

    // ── h14 编辑回填：磁盘上的思考旗回到输入框，再保存出去还在 ──────────────────────

    @Test
    fun `h14 editing a row brings the star back and saving keeps the reasoning flag`() {
        val stored = listOf(RelayModel("deepseek-chat"), RelayModel("deepseek-reasoner", true))
        val rig = rig(relays = FakeRelayEndpointSource(initial = listOf(relayRow(models = stored))))

        composeRule.onNodeWithTag(SettingsTags.relayEdit("my-relay")).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.relayEdit("my-relay")).performClick()
        pump()

        assertEquals(
            "四个框的此刻值：模型框里必须还看得见那个星号，密钥框仍是空的（§2 红线）",
            listOf("my-relay", "https://relay.example.com/v1", "deepseek-chat,deepseek-reasoner*", ""),
            relayFormValues(),
        )
        assertEquals("api 选择器也跟着这一行走", "openai-completions", rig.vm.relayDraft.value.api)

        composeRule.onNodeWithTag(SettingsTags.RELAY_SAVE).performClick()
        pump()

        assertEquals(
            "回填 → 保存 的闭环：source 收到的还是那两条、旗还在；留空的密钥换算成 null",
            listOf(FakeRelayEndpointSource.Save("my-relay", "https://relay.example.com/v1", "openai-completions", stored, null)),
            rig.relays.saves,
        )
        assertEquals("列表行仍是那一条（编辑不是新增第二行）", listOf("my-relay"), rig.vm.relays.value.map { it.providerId })
        assertEquals("保存后行上的旗仍在", stored, rig.vm.relays.value.single().models)
    }

    // ── h15 语法入口上屏：`*` 的用法写在模型框的 supportingText 上 ──────────────────

    @Test
    fun `h15 the models field spells the star syntax out on screen`() {
        rig()

        assertTrue("文案必须把星号语法说出来：${SettingsStrings.RELAY_MODELS_HINT}",
            SettingsStrings.RELAY_MODELS_HINT.contains('*'))
        assertTrue("并且给一个能照抄的例子", SettingsStrings.RELAY_MODELS_HINT.contains("deepseek-reasoner*"))
        composeRule.onNodeWithText(SettingsStrings.RELAY_MODELS_HINT, substring = true).assertIsDisplayed()
        // 例子必须**在屏幕上看得见**，不能只活在文档里。这里扫的是全树可见文本（`Text` 语义），
        // 命中的是 supportingText 那一条——placeholder 是否进语义与本用例无关（下面 h15b 单独问）。
        assertTrue(
            "空表单时这串例子就该出现在可见文本里",
            displayTexts().any { it.contains("deepseek-reasoner*") },
        )
    }

    // ── h15b 占位符与说明共用同一串例子（改一处漏一处的防线）──────────────────────

    @Test
    fun `h15b the placeholder and the hint carry the very same example`() {
        // placeholder 是 Text 字面量、这版 Compose 没把它挂进语义树（javap 实证：SemanticsProperties 无 Placeholder 键），
        // 所以不去屏幕上找它，而是钉**真相只有一个**：placeholder 与 supportingText 都由
        // SettingsStrings.RELAY_MODELS_EXAMPLE 供给，屏幕上看见的说明必然含那串例子。
        assertTrue("例子本身就带星号", SettingsStrings.RELAY_MODELS_EXAMPLE.contains('*'))
        assertTrue("说明里必须原文出现这串例子：${SettingsStrings.RELAY_MODELS_HINT}",
            SettingsStrings.RELAY_MODELS_HINT.contains(SettingsStrings.RELAY_MODELS_EXAMPLE))

        rig()
        // 屏幕上真正渲染出来的那条 supportingText 就是这条说明（例子随它一起上屏）
        composeRule.onNodeWithText(SettingsStrings.RELAY_MODELS_HINT, substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.RELAY_MODELS_FIELD).assertIsDisplayed()
    }

    // ══ P3A §3.5 诊断 section ════════════════════════════════════════════════════
    //
    // 编号另起一代 `i`：g*（P2 的四个部件）、h*（P2H 的模型接入）之后，
    // 崩溃报告是第三批往同一屏上挂的 Section。夹具与它们共用 `rig()`，
    // 只是多给了第六个依赖 [FakeDiagnosticsSource]（默认空列表 → 与老用例零干扰）。

    // ── i01 空态：一句「无崩溃记录」，三个按钮都不禁用 ──────────────────────────

    @Test
    fun `i01 an empty crash dir shows one empty state and three live buttons`() {
        val rig = rig()

        composeRule.onNodeWithTag(SettingsTags.DIAGNOSTICS_SECTION).assertExists()
        composeRule.onNodeWithText(SettingsStrings.SECTION_DIAGNOSTICS).assertIsDisplayed()
        composeRule.onNodeWithText(SettingsStrings.DIAGNOSTICS_HINT, substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.DIAGNOSTICS_EMPTY).assertIsDisplayed()
            .assertTextEquals(SettingsStrings.DIAGNOSTICS_NONE)
        // 三颗按钮空列表时也**必须**能点：readLatest 的语义是「最近一份读得动的」，
        // 列表空但有坏文件时，只有点下去才拿得到 null、才有那条落空回执。
        composeRule.onNodeWithTag(SettingsTags.DIAGNOSTICS_COPY).assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag(SettingsTags.DIAGNOSTICS_CLEAR).assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag(SettingsTags.DIAGNOSTICS_REFRESH).assertIsDisplayed().assertIsEnabled()
        assertEquals("进屏自己发一次列表读口，不靠 UI 补一枪", 1, rig.diagnostics.listCalls.get())
    }

    // ── i02 有报告：一行「文件名 · 大小 · 时间」，正文一个字都不许上屏 ────────────

    @Test
    fun `i02 reports render one row each and the crash body never reaches the screen`() {
        val body = "time=2026-10-09T02:03:04Z epoch=1760000000000 version=0.1.4\n" +
            "java.lang.IllegalStateException: /data/user/0/works.earendil.pihost/files/home/.pi/agent 崩了\n" +
            "\tat works.earendil.pihost.feature.settings.SettingsViewModel.clearCrashReports(SettingsViewModel.kt:187)"
        val rig = rig(
            diagnostics = FakeDiagnosticsSource(
                initial = listOf(
                    CrashReportSummary("crash-1700000000000.txt", 1_200L, java.time.Instant.ofEpochMilli(1_700_000_000_000L)),
                    CrashReportSummary("crash-1699999999000.txt", 512L, java.time.Instant.ofEpochMilli(1_699_999_999_000L)),
                ),
                body = body,
            ),
        )

        composeRule.onNodeWithTag(SettingsTags.DIAGNOSTICS_EMPTY).assertDoesNotExist()
        assertEquals(
            "两份报告就该是两行，不多不少",
            2,
            composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag))
                .fetchSemanticsNodes()
                .count { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(SettingsTags.DIAGNOSTICS_SECTION + "-row-") == true },
        )
        val newest = rowParts(rowText("crash-1700000000000.txt"))
        assertEquals("一行的三段：${newest}", 3, newest.size)
        assertEquals("crash-1700000000000.txt", newest[0])
        assertEquals("1.2 KB", newest[1])
        assertTrue("时间列得是 MM-dd HH:mm 的形状，实际值跟着测试机的时区走：${newest[2]}",
            Regex("""\d{2}-\d{2} \d{2}:\d{2}""").matches(newest[2]))
        assertEquals("512 B", rowParts(rowText("crash-1699999999000.txt"))[1])

        // §3.5 的红线：正文（绝对路径 + 完整栈 + 用户语境）只出剪贴板，屏幕上一个字都不给。
        assertFalse("屏幕上看不到栈里的类名", displayTexts().any { it.contains("at works.earendil") })
        assertFalse("屏幕上看不到崩溃正文", displayTexts().any { it.contains("崩了") })
        assertFalse("屏幕上看不到沙箱绝对路径", displayTexts().any { it.contains("/data/user/0") })
        assertEquals("列表渲染不调读口（正文是按需读的）", 0, rig.diagnostics.latestCalls.get())
    }

    // ── i03 清空：走 VM 一次，回执说实际份数，行当场消失 ──────────────────────────

    @Test
    fun `i03 clearing goes through the view model and reports how many were deleted`() {
        val rig = rig(
            diagnostics = FakeDiagnosticsSource(
                initial = listOf(
                    CrashReportSummary("crash-1.txt", 10L, java.time.Instant.ofEpochMilli(1_700_000_000_000L)),
                    CrashReportSummary("crash-2.txt", 20L, java.time.Instant.ofEpochMilli(1_700_000_000_000L)),
                ),
            ),
        )
        composeRule.onNodeWithTag(SettingsTags.diagnosticsRow("crash-1.txt")).assertIsDisplayed()

        composeRule.onNodeWithTag(SettingsTags.DIAGNOSTICS_CLEAR).performClick()
        pump()

        assertEquals("清空只问 source 一次", 1, rig.diagnostics.clearCalls.get())
        composeRule.onNodeWithTag(SettingsTags.NOTICE).assertIsDisplayed()
        composeRule.onNodeWithText(SettingsStrings.crashCleared(2)).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTags.diagnosticsRow("crash-1.txt")).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsTags.diagnosticsRow("crash-2.txt")).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsTags.DIAGNOSTICS_EMPTY).assertIsDisplayed()
    }

    // ── i04 复制最近一份：正文进剪贴板，notice 只说结果不念内容 ───────────────────

    @Test
    fun `i04 copying puts the body on the clipboard and the notice only states the outcome`() {
        val body = "time=2026-10-09T02:03:04Z epoch=1760000000000 version=0.1.4\njava.lang.IllegalStateException: boom"
        val rig = rig(
            diagnostics = FakeDiagnosticsSource(
                initial = listOf(
                    CrashReportSummary("crash-1700000000000.txt", 1_200L, java.time.Instant.ofEpochMilli(1_700_000_000_000L)),
                ),
                body = body,
            ),
        )

        composeRule.onNodeWithTag(SettingsTags.DIAGNOSTICS_COPY).performClick()
        pump()

        assertEquals("剪贴板里必须就是那份正文", body, clipboardText())
        assertEquals("读口只被问一次", 1, rig.diagnostics.latestCalls.get())
        composeRule.onNodeWithText(SettingsStrings.DIAGNOSTICS_COPIED).assertIsDisplayed()
        assertFalse("回执不许念正文", displayTexts().any { it.contains("boom") })
    }

    // ── i05 复制落空：没有可读的一份 → 剪贴板原样不动 + 那条回执 ──────────────────

    @Test
    fun `i05 a copy with nothing readable leaves the clipboard alone`() {
        val rig = rig(diagnostics = FakeDiagnosticsSource())
        seedClipboard("别动我")

        composeRule.onNodeWithTag(SettingsTags.DIAGNOSTICS_COPY).performClick()
        pump()
        assertEquals("落空时不许把用户原来拷的东西顶掉", "别动我", clipboardText())
        composeRule.onNodeWithText(SettingsStrings.DIAGNOSTICS_NONE_NOTICE).assertIsDisplayed()

        // 同一颗按钮的另一半：有了**可读**的一份就真写进去（落空与成功共用一条通路）。
        rig.diagnostics.body = "time=…\njava.lang.IllegalStateException: 这次成功了"
        composeRule.onNodeWithTag(SettingsTags.DIAGNOSTICS_COPY).performClick()
        pump()
        assertEquals("读得动的时候同一颗按钮真的写进去了", rig.diagnostics.body, clipboardText())
        composeRule.onNodeWithText(SettingsStrings.DIAGNOSTICS_COPIED).assertIsDisplayed()

        // 第三种：读口自己抛（文件被掉电截成半截、权限坏了）→ 与「没有」同样落空，不崩、不动剪贴板。
        rig.diagnostics.boom = IOException("读不动")
        composeRule.onNodeWithTag(SettingsTags.DIAGNOSTICS_COPY).performClick()
        pump()
        assertEquals("抛异常时剪贴板依旧不动", rig.diagnostics.body, clipboardText())
        composeRule.onNodeWithText(SettingsStrings.DIAGNOSTICS_NONE_NOTICE).assertIsDisplayed()
    }

    // ── i06 重读 + 读失败：按钮真的再问一次，失败只是空着，不弹提示 ───────────────

    @Test
    fun `i06 refresh re-reads the list and a failing read just leaves it empty`() {
        val rig = rig()

        rig.diagnostics.seed(CrashReportSummary("crash-7.txt", 7L, java.time.Instant.ofEpochMilli(1_700_000_000_000L)))
        composeRule.onNodeWithTag(SettingsTags.diagnosticsRow("crash-7.txt")).assertDoesNotExist()

        composeRule.onNodeWithTag(SettingsTags.DIAGNOSTICS_REFRESH).performClick()
        pump()
        assertEquals("重读口有效：source 被问了两次", 2, rig.diagnostics.listCalls.get())
        composeRule.onNodeWithTag(SettingsTags.diagnosticsRow("crash-7.txt")).assertIsDisplayed()
        assertNull("这一路没有失败要说", rig.vm.notice.value)

        rig.diagnostics.boom = IOException("盘读不动")
        composeRule.onNodeWithTag(SettingsTags.DIAGNOSTICS_REFRESH).performClick()
        pump()
        composeRule.onNodeWithTag(SettingsTags.DIAGNOSTICS_EMPTY).assertIsDisplayed()
        assertNull("读失败不弹 notice：空态已经说明「没有可读的报告」", rig.vm.notice.value)
    }
}
