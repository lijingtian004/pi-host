package works.earendil.pihost.feature.settings.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import works.earendil.pihost.core.environment.MemTier
import works.earendil.pihost.feature.settings.ui.SettingsStrings
import works.earendil.pihost.feature.settings.FakeEngine
import works.earendil.pihost.feature.settings.FakeModelCredentialSource
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
 * 本文件不建 Hilt 图（`SettingsViewModel` 是普通 `ViewModel`，四个依赖都是假体，
 * 手工构造比拉一条 `@HiltViewModel` 测试路径便宜得多）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h2400dp")
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
     * 屏幕上**所有输入框当前持有的明文**，按节点顺序。
     *
     * 为什么绕这一圈而不是 `assertTextEquals`（编译机实测的语义树：g09/g12 第一次就撞在上面）：
     * 密钥输入框那个合并节点上 `Text = [API 密钥]`（label 被 merge 进来）、
     * `EditableText` 是 **`PasswordVisualTransformation` 之后的显示串**（`sk-x` → `••••`），
     * 而 `assertTextEquals` 比的是 `Text + EditableText` —— 于是它既断不了「保存后清空」、
     * 也断不了「失败时草稿还在」，只会告诉你 `Text + EditableText = [API 密钥, ••••]`。
     * 真值住在 `SemanticsProperties.InputText`。
     *
     * 返回的是**列表**而不是单个值，所以「已保存 / 失败」两条用例顺带钉住了
     * 「全树只有输入框这一个节点持有明文」——多一个持有者就等于多一处回显。
     */
    private fun inputTexts(): List<String> =
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.InputText))
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.InputText)?.text }

    /** 输入框里此刻的真值（并且全树只有它一个明文持有者）。 */
    private fun assertKeyValue(expected: String) {
        assertEquals("明文只应住在输入框这一个节点上", listOf(expected), inputTexts())
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

    private class Rig(
        val vm: SettingsViewModel,
        val repository: FakeSettingsRepository,
        val engine: FakeEngine,
        val info: FakeRuntimeInfo,
        val credentials: FakeModelCredentialSource,
    )

    private fun rig(
        settings: AppSettings = AppSettings(memTier = MemTier.MID, maxParallelSessions = 2),
        live: Boolean = true,
        info: FakeRuntimeInfo = FakeRuntimeInfo(
            marker = RuntimeInfo("2.5.0-test", "1.0.4-r7", null, manifestPending = true),
            manifest = ManifestRead.Present("1.0.4-r7"),
        ),
        credentials: FakeModelCredentialSource = FakeModelCredentialSource(),
    ): Rig {
        val engine = FakeEngine()
        if (live) engine.setState(FakeEngine.SESSION, SessionState.Ready)
        val repository = FakeSettingsRepository(settings)
        val vm = SettingsViewModel(repository, engine, info, credentials)
        composeRule.setContent {
            SettingsScreen(viewModel = vm, onBack = { backs.incrementAndGet() })
        }
        pump()
        return Rig(vm, repository, engine, info, credentials)
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
}
