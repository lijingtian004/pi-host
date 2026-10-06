package works.earendil.pihost.feature.settings.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import works.earendil.pihost.core.engine.SessionState
import works.earendil.pihost.core.engine.settings.AppSettings
import works.earendil.pihost.core.environment.MemTier
import works.earendil.pihost.feature.settings.ui.SettingsStrings
import works.earendil.pihost.feature.settings.FakeEngine
import works.earendil.pihost.feature.settings.FakeRuntimeInfo
import works.earendil.pihost.feature.settings.FakeSettingsRepository
import works.earendil.pihost.feature.settings.ManifestRead
import works.earendil.pihost.feature.settings.ProbeState
import works.earendil.pihost.feature.settings.RuntimeInfo
import works.earendil.pihost.feature.settings.SettingsViewModel
import works.earendil.pihost.feature.settings.probeResponse
import works.earendil.pihost.core.rpc.RpcCommand

/**
 * `SettingsScreen` 的渲染与四条交互（规格书 §4.2 的四个部件都必须在树上，且点下去真的走 VM）。
 *
 * `pump()` 抄 `MainActivityTest`/`SessionsScreenTest` 的配方：`waitForIdle()` 只收敛重组，
 * 不保证 `viewModelScope` 的 pending task 已跑完。
 * 本文件不建 Hilt 图（`SettingsViewModel` 是普通 `ViewModel`，三个依赖都是假体，
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

    private class Rig(
        val vm: SettingsViewModel,
        val repository: FakeSettingsRepository,
        val engine: FakeEngine,
        val info: FakeRuntimeInfo,
    )

    private fun rig(
        settings: AppSettings = AppSettings(memTier = MemTier.MID, maxParallelSessions = 2),
        live: Boolean = true,
        info: FakeRuntimeInfo = FakeRuntimeInfo(
            marker = RuntimeInfo("2.5.0-test", "1.0.4-r7", null, manifestPending = true),
            manifest = ManifestRead.Present("1.0.4-r7"),
        ),
    ): Rig {
        val engine = FakeEngine()
        if (live) engine.setState(FakeEngine.SESSION, SessionState.Ready)
        val repository = FakeSettingsRepository(settings)
        val vm = SettingsViewModel(repository, engine, info)
        composeRule.setContent {
            SettingsScreen(viewModel = vm, onBack = { backs.incrementAndGet() })
        }
        pump()
        return Rig(vm, repository, engine, info)
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
}
