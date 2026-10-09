package works.earendil.pihost.feature.sessions.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import works.earendil.pihost.feature.sessions.FakeEngine
import works.earendil.pihost.feature.sessions.FakeProjectsSource
import works.earendil.pihost.feature.sessions.FakeScanner
import works.earendil.pihost.feature.sessions.ScannedSession
import works.earendil.pihost.feature.sessions.SessionNavRequest
import works.earendil.pihost.feature.sessions.SessionsViewModel
import works.earendil.pihost.feature.sessions.session

/**
 * `SessionsScreen` 的渲染与两条交互通路（长按二步确认 + 空态新建）。
 *
 * 规格书 §4.5 没有强制要求 sessions 的 UI 用例，这 3 条是自我加罚：
 * 步骤 6 的 app 测试会在 NavHost 里渲染本屏，与其那时才崩，不如现在就用最便宜的三条
 * 把「Compose 面立不立得住」钉住（PullToRefreshBox / LazyColumn / detectTapGestures
 * 这三个 API 在 Robolectric 下的行为都在这三条里现形）。
 *
 * `pump()` 抄 MainActivityTest 的配方：`waitForIdle()` 只收敛重组，
 * 不保证 `viewModelScope`（跑在 Robolectric main looper 上）的 pending task 已跑完。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h2400dp")
class SessionsScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val navigated = CopyOnWriteArrayList<SessionNavRequest?>()
    private val settingsClicks = java.util.concurrent.atomic.AtomicInteger(0)

    private fun pump() {
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
    }

    private class Rig(
        val vm: SessionsViewModel,
        val scanner: FakeScanner,
        val engine: FakeEngine,
        val projects: FakeProjectsSource,
    )

    private fun rig(vararg rows: ScannedSession): Rig {
        val scanner = FakeScanner(rows.toList())
        val engine = FakeEngine()
        val projects = FakeProjectsSource()
        val vm = SessionsViewModel(RuntimeEnvironment.getApplication(), scanner, engine, projects)
        composeRule.setContent {
            SessionsScreen(
                viewModel = vm,
                onOpenChat = { navigated.add(SessionNavRequest(it)) },
                onOpenSettings = { settingsClicks.incrementAndGet() },
            )
        }
        pump()
        return Rig(vm, scanner, engine, projects)
    }

    /**
     * 两行的元信息**必须互不相同**：`onNodeWithText` 要求唯一匹配，
     * 若 a/b 都是「3 条消息 · 2 KB」，u01 会被「found 2 nodes」绊倒（编译机实测）。
     * 于是按 id 的字母序推出条数与体积：a=3 条/2 KB，b=4 条/4 KB。
     */
    private fun row(id: String): ScannedSession {
        val n = id.single() - 'a'
        return session(
            id,
            path = "/files/home/.pi/agent/sessions/--p--/$id.jsonl",
            name = "名字 $id",
            firstMessage = "正文 $id",
            messageCount = 3 + n,
            sizeBytes = 2048L * (1 + n),
            cwd = "/p",
        )
    }

    // ── u01 列表渲染：标题/正文/元信息三行都在树上 ───────────────────────────────

    @Test
    fun `u01 renders one row per session with title body and meta`() {
        val a = row("a")
        rig(a, row("b"))
        pump()

        composeRule.onNodeWithTag(SessionsTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(SessionsTags.row(a.sessionPath)).assertExists()
        composeRule.onNodeWithText("名字 a").assertIsDisplayed()
        composeRule.onNodeWithText("正文 a").assertIsDisplayed()
        composeRule.onNodeWithText("名字 b").assertIsDisplayed()
        composeRule.onNodeWithText("正文 b").assertIsDisplayed()
        // 元信息行：时间由相对化函数给（时钟是 now，所以只断言后半截）；两行各自唯一
        composeRule.onNodeWithText("3 条消息 · 2 KB · /p", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("4 条消息 · 4 KB · /p", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(SessionsStrings.TITLE).assertIsDisplayed()
    }

    // ── u02 空态：引导文案 + 新建按钮（无活会话 → 只导航不命令）──────────────────

    @Test
    fun `u02 the empty state offers a new session and navigates without a live engine`() {
        rig()
        composeRule.onNodeWithText(SessionsStrings.EMPTY_TITLE).assertIsDisplayed()
        composeRule.onNodeWithTag(SessionsTags.EMPTY).assertExists()

        composeRule.onNodeWithText(SessionsStrings.ACTION_NEW).performClick()
        pump()

        assertEquals("没有活会话 → 默认 chat 入口", SessionNavRequest(null), navigated.last())
        composeRule.onNodeWithText(SessionsViewModel.NO_LIVE_SESSION, substring = true).assertExists()
    }

    // ── u03 二步确认：挂出确认卡 → 取消回列表 → 确认才真删 ──────────────────────

    /**
     * 第一步的触发方式：这里直接调 [SessionsViewModel.requestDelete]（= 长按回调本体），
     * 不注入长按手势。理由与 RECON-P2E §7.8 对下拉刷新同一条：
     * ui-test 1.9.2 的 `TouchInjectionScope` 没有 `performLongClick`，
     * `down()/advanceEventTime()/up()` 手工拼手势在 Robolectric 下要跨 `withTimeout` 的
     * 帧时钟，属于「测不出真东西的脆测试」；`performTouchInput` 的真实签名已用 javap
     * 在编译机上核对过（见收尾报告）。长按 → requestDelete 这根线由 composable 里唯一一行
     * lambda 保证，语义覆盖在 VM 层，渲染覆盖在本用例。
     */
    @Test
    fun `u03 the two step confirm only deletes on the second tap`() {
        val a = row("a")
        val rig = rig(a)
        val rowTag = SessionsTags.row(a.sessionPath)

        composeRule.onNodeWithTag(SessionsTags.DELETE_DIALOG).assertDoesNotExist()

        rig.vm.requestDelete(a)
        pump()

        composeRule.onNodeWithTag(SessionsTags.DELETE_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithText(SessionsStrings.DELETE_TITLE).assertIsDisplayed()
        assertTrue("确认前绝不删文件", rig.scanner.deleted.isEmpty())
        assertEquals("确认态就是那一条", a, rig.vm.pendingDelete.value)

        composeRule.onNodeWithTag(SessionsTags.DELETE_CANCEL).performClick()
        pump()
        composeRule.onNodeWithTag(SessionsTags.DELETE_DIALOG).assertDoesNotExist()
        composeRule.onNodeWithTag(rowTag).assertExists()
        assertTrue("取消同样不许删", rig.scanner.deleted.isEmpty())
        assertNull(rig.vm.pendingDelete.value)

        rig.vm.requestDelete(a)
        pump()
        composeRule.onNodeWithTag(SessionsTags.DELETE_CONFIRM).performClick()
        pump()

        assertEquals(listOf(a.sessionPath), rig.scanner.deleted)
        assertNull("确认后确认态收起", rig.vm.pendingDelete.value)
        composeRule.onNodeWithText(SessionsStrings.EMPTY_TITLE).assertIsDisplayed()
        assertEquals("顶栏设置入口没被点过", 0, settingsClicks.get())
    }

    // ── P3C §3.7：项目条与新建项目卡（u04–u08）────────────────────────────────

    @Test
    fun `u04 the new-project button opens the inline card`() {
        val rig = rig(session("s1"))

        composeRule.onNodeWithTag(SessionsTags.BUTTON_NEW_PROJECT).performClick()
        pump()

        composeRule.onNodeWithTag(SessionsTags.CARD_NEW_PROJECT).assertIsDisplayed()
        composeRule.onNodeWithTag(SessionsTags.FIELD_PROJECT_NAME).assertIsDisplayed()
    }

    /** 第一道门：空白输入点不到「创建」。安全边界不在这（在 ProjectStore），这条只管手感。 */
    @Test
    fun `u05 the create button stays disabled while the name field is blank`() {
        rig(session("s1"))
        composeRule.onNodeWithTag(SessionsTags.BUTTON_NEW_PROJECT).performClick()
        pump()

        composeRule.onNodeWithTag(SessionsTags.BUTTON_PROJECT_CREATE).assertIsNotEnabled()

        composeRule.onNodeWithTag(SessionsTags.FIELD_PROJECT_NAME).performTextInput("shop")
        pump()
        composeRule.onNodeWithTag(SessionsTags.BUTTON_PROJECT_CREATE).assertIsEnabled()
    }

    @Test
    fun `u06 typing a name and creating reaches the view model and closes the card`() {
        val rig = rig(session("s1"))
        composeRule.onNodeWithTag(SessionsTags.BUTTON_NEW_PROJECT).performClick()
        pump()
        composeRule.onNodeWithTag(SessionsTags.FIELD_PROJECT_NAME).performTextInput("shop")
        pump()

        composeRule.onNodeWithTag(SessionsTags.BUTTON_PROJECT_CREATE).performClick()
        pump()

        assertEquals(listOf("shop" to true), rig.projects.created)
        composeRule.onNodeWithTag(SessionsTags.CARD_NEW_PROJECT).assertIsNotDisplayed()
    }

    @Test
    fun `u07 cancel closes the card without creating anything`() {
        val rig = rig(session("s1"))
        composeRule.onNodeWithTag(SessionsTags.BUTTON_NEW_PROJECT).performClick()
        pump()
        composeRule.onNodeWithTag(SessionsTags.FIELD_PROJECT_NAME).performTextInput("shop")
        pump()

        composeRule.onNodeWithTag(SessionsTags.BUTTON_PROJECT_CANCEL).performClick()
        pump()

        assertTrue("取消不该建目录", rig.projects.created.isEmpty())
        composeRule.onNodeWithTag(SessionsTags.CARD_NEW_PROJECT).assertIsNotDisplayed()
    }

    /** 项目条必须早于会话列表被读到，且活动项要带「活动」标记（用户得知道新会话落哪儿）。 */
    @Test
    fun `u08 the project bar names the active project and lists the others as pickable`() {
        val rig = rig(session("s1"))
        rig.projects.names.addAll(listOf("alpha", "shop"))
        rig.projects.activeValue = "shop"

        rig.vm.refresh()
        pump()

        composeRule.onNodeWithTag(SessionsTags.LABEL_ACTIVE_PROJECT).assertIsDisplayed()
        composeRule.onNodeWithText("${SessionsStrings.PROJECTS_TITLE}：shop").assertIsDisplayed()
        composeRule.onNodeWithTag(SessionsTags.project("alpha")).assertIsDisplayed()
        composeRule.onNodeWithTag(SessionsTags.project("shop")).assertIsNotEnabled()
    }
}
