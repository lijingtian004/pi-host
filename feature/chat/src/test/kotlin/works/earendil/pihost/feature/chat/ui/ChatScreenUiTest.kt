package works.earendil.pihost.feature.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import works.earendil.pihost.core.engine.InstallProgress
import works.earendil.pihost.core.engine.SessionState
import works.earendil.pihost.core.rpc.PiEvent
import works.earendil.pihost.feature.chat.ChatUiState
import works.earendil.pihost.feature.chat.ChatViewModel
import works.earendil.pihost.feature.chat.FakeChatEngine
import works.earendil.pihost.feature.chat.FakeRuntimeInstall
import works.earendil.pihost.feature.chat.QueueState
import works.earendil.pihost.feature.chat.FakeSettingsRepository
import works.earendil.pihost.feature.chat.ToolCallRecord
import works.earendil.pihost.feature.chat.ToolStatus
import works.earendil.pihost.feature.chat.TranscriptItem
import works.earendil.pihost.feature.chat.TurnState

/**
 * Compose UI 用例（规格书 §7 交付物 D 的 UI 侧 ≥3 条，实交 5 条）。
 *
 * **配方（RECON-P2D §4 探针实测）**：`createComposeRule()` + Robolectric 4.16 +
 * `ui-test-junit4`/`ui-test-manifest` + `isIncludeAndroidResources`；容器节点用
 * `testTag` + `assertIsDisplayed`，文本用 `onNodeWithText`（`assertTextEquals` 只看本节点、不聚合子节点）。
 *
 * **`notificationGate = {}`**：§6 的 POST_NOTIFICATIONS 分支硬要求真 LifecycleOwner，
 * 纯 Compose 宿主给不了（见 NotificationGate.kt 的 KDoc）；该分支由 :app 侧 Activity 测试覆盖。
 *
 * `Dispatchers.setMain(UnconfinedTestDispatcher())` 放 @Before：VM 装配就地执行；
 * 这个假体 dispatcher 自带 scheduler 且**无人推进虚拟时钟**，权限倒计时协程就停在 `delay` 上——
 * UI 用例要的正是「弹框挂住直到被点击」，超时路径归 ChatViewModelTest case09。
 *
 * **`qualifiers` 不是装饰**：Robolectric 默认屏 320×470dp，LazyColumn 只组合视口内的条目，
 * 首条用户气泡会被判成「节点不存在」（`performScrollTo` 也救不了——滚动目标本身没进组成树）。
 * 这里把视口拉高到 2400dp，让转录的 5 条全部参与组合，`assertIsDisplayed` 才真正在验渲染。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h2400dp")
class ChatScreenUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val engine = FakeChatEngine()
    private val installer = FakeRuntimeInstall()
    private val settings = FakeSettingsRepository()
    private lateinit var vm: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = ChatViewModel(
            RuntimeEnvironment.getApplication(),
            engine,
            installer,
            settings,
            buildMarkwon(RuntimeEnvironment.getApplication()),
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** 走一遍安装门（点「安装运行时」）并挂载 ChatScreen。 */
    private fun showReadyScreen() {
        composeRule.setContent { PiHostTheme { ChatScreen(viewModel = vm, notificationGate = {}) } }
        composeRule.onNodeWithTag(ChatTags.INSTALL_BUTTON).performClick()
        composeRule.waitForIdle()
        assertEquals(ChatUiState.Ready(FakeChatEngine.SESSION), vm.uiState.value)
    }

    // ── ① InputBar 可用性矩阵随 turnState（§7 UI-①）──────────────────────────────

    @Test
    fun inputBarEnablementFollowsTheTurnStateMatrix() {
        val sent = CopyOnWriteArrayList<String>()
        var aborts by mutableIntStateOf(0)
        var turn by mutableStateOf(TurnState.Idle)
        composeRule.setContent {
            PiHostTheme {
                InputBar(turnState = turn, onSend = { sent.add(it) }, onAbort = { aborts++ })
            }
        }
        // Idle：可输入；无草稿不能发；中止禁用
        composeRule.onNodeWithTag(ChatTags.INPUT).assertIsEnabled()
        composeRule.onNodeWithTag(ChatTags.SEND).assertIsNotEnabled()
        composeRule.onNodeWithTag(ChatTags.ABORT).assertIsNotEnabled()

        composeRule.onNodeWithTag(ChatTags.INPUT).performTextInput("git branch")
        composeRule.onNodeWithTag(ChatTags.SEND).assertIsEnabled()
        composeRule.onNodeWithTag(ChatTags.SEND).performClick()
        assertEquals(listOf("git branch"), sent.toList())
        // 草稿已清空的可观测证据 = 发送键回到禁用（assertTextEquals 对 OutlinedTextField
        // 会把 placeholder 一起聚合进来，实测拿到「给 pi 发一条消息」，不是本节点的 value）
        composeRule.onNodeWithTag(ChatTags.SEND).assertIsNotEnabled()

        // §3.2 改的就是这一档：流式期间输入框**不再锁死**，主按钮换成「插入」走 steer。
        turn = TurnState.Streaming
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatTags.INPUT).assertIsEnabled()
        composeRule.onNodeWithTag(ChatTags.ABORT).assertIsEnabled()
        composeRule.onNodeWithText(PLACEHOLDER_STREAMING).assertExists()

        turn = TurnState.PermissionPending
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatTags.INPUT).assertIsNotEnabled()
        composeRule.onNodeWithTag(ChatTags.SEND).assertIsNotEnabled()
        composeRule.onNodeWithTag(ChatTags.ABORT).assertIsNotEnabled()
        composeRule.onNodeWithText(PLACEHOLDER_PENDING).assertExists()
    }

    // ── ② PermissionDialog 三按钮各自回写（§7 UI-②）───────────────────────────────

    @Test
    fun permissionDialogButtonsEachWriteBackThroughTheEngine() {
        showReadyScreen()
        vm.sendPrompt("ls")
        composeRule.waitForIdle()

        // 允许一次 → confirmed=true，且不写记忆
        engine.emit(uiRequest("u1", "confirm"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatTags.PERMISSION_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithText("method=confirm｜剩余 15s").assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.PERMISSION_ALLOW_ONCE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatTags.PERMISSION_DIALOG).assertDoesNotExist()
        assertEquals(listOf("u1"), engine.uiResponses.map { it.id })
        assertEquals(true, engine.uiResponses.single().confirmed)
        assertTrue("「允许一次」不得污染本会话记忆", vm.remembered.isEmpty())

        // 本会话允许 → confirmed=true + method 进记忆；同 method 的下一条自动应答、不弹框
        engine.emit(uiRequest("u2", "select"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatTags.PERMISSION_ALLOW_SESSION).performClick()
        composeRule.waitForIdle()
        assertEquals(true, engine.uiResponses.last().confirmed)
        engine.emit(uiRequest("u3", "select"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatTags.PERMISSION_DIALOG).assertDoesNotExist()
        assertEquals(listOf("u2", "u3"), engine.uiResponses.takeLast(2).map { it.id })

        // 拒绝 → cancelled=true
        engine.emit(uiRequest("u4", "input"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatTags.PERMISSION_DENY).performClick()
        composeRule.waitForIdle()
        assertEquals("u4", engine.uiResponses.last().id)
        assertEquals(true, engine.uiResponses.last().cancelled)
    }

    // ── ③ transcript：用户气泡 / thinking 折叠 / 工具卡与状态灯（§7 UI-③）──────────

    @Test
    fun transcriptRendersUserBubblesThinkingAndToolCards() {
        val items = listOf<TranscriptItem>(
            TranscriptItem.UserItem(id = 1, text = "跑一下分支命令"),
            TranscriptItem.AssistantItem(
                id = 2,
                text = "已在 main 分支\n```bash\ngit status\n```",
                thinking = "先看 git 状态",
                toolCalls = listOf(ToolCallRecord("call-1", "bash", """{"command":"git status"}""")),
                settled = true,
            ),
            TranscriptItem.ToolItem(3, "call-1", "bash", ToolStatus.Ok, "On branch main"),
            TranscriptItem.ToolItem(4, "call-2", "read", ToolStatus.Error, "ENOENT"),
            TranscriptItem.ToolItem(5, "call-3", "edit", ToolStatus.Running, ""),
        )
        composeRule.setContent {
            PiHostTheme { TranscriptList(items = items, markwon = vm.markwon) }
        }

        // TranscriptList 的自动滚动会把视口贴到底（§6），首条因此在屏幕外：
        // 断言可见之前先 performScrollTo()——它沿祖先链对每个可滚动容器做 scrollUntilVisible，
        // 对子节点（Text）同样有效。
        composeRule.onNodeWithTag(ChatTags.TRANSCRIPT).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.user(1)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("跑一下分支命令").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.assistant(2)).performScrollTo().assertIsDisplayed()
        // §4.3 等价调整：正文改由 markwon 经 AndroidView 渲染，`onNodeWithText` 只看 Compose
        // 语义树的 Text 节点、看不见 interop 里的 TextView，故这里的判据换成「渲染体在场」；
        // **渲染出的文字内容**由 MarkwonRenderTest（createAndroidComposeRule + 视图遍历）覆盖。
        composeRule.onNodeWithTag(ChatTags.markdown(2)).performScrollTo().assertIsDisplayed()
        // thinking 默认收起 → 点击展开
        composeRule.onNodeWithText("先看 git 状态").assertDoesNotExist()
        composeRule.onNodeWithTag(ChatTags.thinking(2)).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("先看 git 状态").performScrollTo().assertIsDisplayed()
        // toolcall 卡（End 解析出的名字 + callId 短码）
        composeRule.onNodeWithText("调用 bash（call-1）").performScrollTo().assertIsDisplayed()
        // 三张工具卡（Ok / Error / Running=转圈）都必须在树里
        composeRule.onNodeWithTag(ChatTags.tool("call-1")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.tool("call-2")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.tool("call-3")).performScrollTo().assertIsDisplayed()
        // 输出区默认收起 → 展开后才见文本
        composeRule.onNodeWithText("On branch main").assertDoesNotExist()
        composeRule.onNodeWithTag(ChatTags.toolOutput("call-1")).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("On branch main").performScrollTo().assertIsDisplayed()
    }

    // ── ④ 安装门三档（§6；:app 侧另有等价覆盖）────────────────────────────────────

    @Test
    fun installGateShowsEachBranchWithItsOwnTexts() {
        var clicks = 0
        // 三档分支靠状态切换，不靠第二次 setContent：composeTestRule 的宿主 Activity
        // 只允许设置一次内容（重复调用抛 "has already set content"）。
        var gate by mutableStateOf<ChatUiState>(ChatUiState.NotInstalled)
        composeRule.setContent { PiHostTheme { InstallGateHost(gate) { clicks++ } } }

        composeRule.onNodeWithText("运行时未安装").assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.INSTALL_BUTTON).performClick()
        assertEquals(1, clicks)

        gate = ChatUiState.Installing(InstallProgress.Extracting(3, 50))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("正在安装运行时").assertIsDisplayed()
        composeRule.onNodeWithText("解包 3/50").assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.INSTALL_BUTTON).assertDoesNotExist()

        gate = ChatUiState.Failed("sha256 mismatch on bin/node")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("启动失败").assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.INSTALL_CAUSE).assertIsDisplayed()
        composeRule.onNodeWithText("sha256 mismatch on bin/node").assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.INSTALL_RETRY_BUTTON).performClick()
        assertEquals(2, clicks)
    }

    // ── ⑤ 顶栏短码 + 队列徽标（§6 Ready 主界面）───────────────────────────────────

    @Test
    fun topBarShowsEngineShortCodeAndQueueBadge() {
        showReadyScreen()
        composeRule.onNodeWithTag(ChatTags.TOP_BAR).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.ENGINE_STATUS)
            .assertTextEquals("就绪 ${FakeChatEngine.SESSION.take(8)}")
        composeRule.onNodeWithTag(ChatTags.QUEUE_BADGE).assertDoesNotExist()

        engine.emit(PiEvent.QueueUpdate(steering = listOf("a"), followUp = listOf("b")))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatTags.QUEUE_BADGE).assertIsDisplayed()
        composeRule.onNodeWithText(QueueState(listOf("a"), listOf("b")).badgeText() + " ▸").assertIsDisplayed()

        engine.setState(SessionState.Restarting(attempt = 3, backoffMs = 16_000L))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatTags.ENGINE_STATUS).assertTextEquals("重启第 3 次")
    }

    // ── ⑤b §3.2 流式插话的两颗按钮与徽标展开 ─────────────────────────────────────

    @Test
    fun streamingInputRoutesToSteerAndFollowUpAndTheBadgeExpands() {
        val steered = CopyOnWriteArrayList<String>()
        val followed = CopyOnWriteArrayList<String>()
        var turn by mutableStateOf(TurnState.Streaming)
        var queue by mutableStateOf(QueueState.EMPTY)
        var clears by mutableIntStateOf(0)
        composeRule.setContent {
            PiHostTheme {
                InputBar(
                    turnState = turn,
                    queueState = queue,
                    onSend = { error("流式期间不该走 send") },
                    onSteer = { steered.add(it) },
                    onFollowUp = { followed.add(it) },
                    onClearQueue = { clears++ },
                    onAbort = {},
                )
            }
        }

        // 「清空」只在队列非空时出现
        composeRule.onNodeWithTag(ChatTags.CLEAR_QUEUE).assertDoesNotExist()
        queue = QueueState(listOf("插一句"), listOf("等这轮完"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatTags.CLEAR_QUEUE).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.CLEAR_QUEUE).performClick()
        assertEquals(1, clears)

        // 主按钮在流式档的文案是「插入」，点击路由到 steer（不是 send）；
        // 「追问」同步在场，但两个按钮都受「空草稿不发」这一条管着。
        composeRule.onNodeWithText("插入").assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.FOLLOW_UP).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.FOLLOW_UP).assertIsNotEnabled()
        composeRule.onNodeWithTag(ChatTags.INPUT).performTextInput("慢着，别动 main")
        composeRule.onNodeWithTag(ChatTags.SEND).performClick()
        assertEquals(listOf("慢着，别动 main"), steered.toList())

        // 「追问」与「插入」共用同一格输入框，但落到不同的队列；
        // steer 之后草稿已清空 → 这里再打字，追问键重新点亮
        composeRule.onNodeWithTag(ChatTags.FOLLOW_UP).assertIsNotEnabled()
        composeRule.onNodeWithTag(ChatTags.INPUT).performTextInput("这轮完了再看覆盖率")
        composeRule.onNodeWithTag(ChatTags.FOLLOW_UP).assertIsEnabled()
        composeRule.onNodeWithTag(ChatTags.FOLLOW_UP).performClick()
        assertEquals(listOf("这轮完了再看覆盖率"), followed.toList())

        // 回到 Idle：主按钮换回「发送」，追问键收起（避免两条几乎一样的路）
        turn = TurnState.Idle
        queue = QueueState.EMPTY
        composeRule.waitForIdle()
        composeRule.onNodeWithText("发送").assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.FOLLOW_UP).assertDoesNotExist()
        composeRule.onNodeWithTag(ChatTags.CLEAR_QUEUE).assertDoesNotExist()
    }

    @Test
    fun tappingTheQueueBadgeRevealsTheQueuedSnippetsInOrder() {
        showReadyScreen()
        engine.emit(PiEvent.QueueUpdate(steering = listOf("改成跑 test"), followUp = listOf("再看覆盖率")))
        composeRule.waitForIdle()

        // 默认收起：徽标在、明细不在
        composeRule.onNodeWithTag(ChatTags.QUEUE_BADGE).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.QUEUE_DETAILS).assertDoesNotExist()

        composeRule.onNodeWithTag(ChatTags.QUEUE_BADGE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatTags.QUEUE_DETAILS).assertIsDisplayed()
        // steering 在前、followUp 在后（两列的生效时机不同，顺序就是语义）
        composeRule.onNodeWithTag(ChatTags.queueItem(0)).assertTextEquals("插话 ▸ 改成跑 test")
        composeRule.onNodeWithTag(ChatTags.queueItem(1)).assertTextEquals("追问 ▸ 再看覆盖率")

        // 队列空了：明细整块消失，不留一行空壳
        composeRule.onNodeWithTag(ChatTags.QUEUE_BADGE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatTags.QUEUE_DETAILS).assertDoesNotExist()

        engine.emit(PiEvent.QueueUpdate(steering = emptyList(), followUp = emptyList()))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatTags.QUEUE_BADGE).assertDoesNotExist()
    }

    // ── ⑥ 并行上限门的 UI 反馈（§4.3：提示 + 不 spawn + 放宽后可重试）─────────────

    @Test
    fun parallelGateSurfacesTheNoticeBannerAndStaysRetryable() {
        engine.setStateFor("another-session", SessionState.Ready)
        settings.setMaxParallel(1)

        composeRule.setContent { PiHostTheme { ChatScreen(viewModel = vm, notificationGate = {}) } }
        composeRule.onNodeWithTag(ChatTags.INSTALL_BUTTON).performClick()
        composeRule.waitForIdle()

        // 同一条文案会出现在**两个**节点上（banner 与安装门的 cause），
        // 所以按 tag 定位、不做全文搜索（onNodeWithText 要求唯一命中）。
        composeRule.onNodeWithTag(ChatTags.NOTICE).assertIsDisplayed()
        val cause = (vm.uiState.value as ChatUiState.Failed).cause
        assertTrue("cause 要写明上限，实为 $cause", cause.contains("并行上限 1"))
        composeRule.onNodeWithTag(ChatTags.NOTICE).assertTextEquals(cause)
        // 门拦下 = Failed 分支（「引擎起不来」），重试键必须在
        composeRule.onNodeWithText("启动失败").assertIsDisplayed()
        assertEquals("被拦住时一次都不许碰引擎", 0, engine.startCalls.get())

        // 放宽上限后点重试 = 正常走完安装门进主界面（starting 闩已放开）
        settings.setMaxParallel(2)
        composeRule.onNodeWithTag(ChatTags.INSTALL_RETRY_BUTTON).performClick()
        composeRule.waitForIdle()
        assertEquals(ChatUiState.Ready(FakeChatEngine.SESSION), vm.uiState.value)
        composeRule.onNodeWithTag(ChatTags.TRANSCRIPT).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.NOTICE).assertDoesNotExist()
    }

    private companion object {
        const val PLACEHOLDER_PENDING = "等待权限确认…"

        /** §3.2：流式档的占位文案——输入框可用了，提示语也必须换成「能做事」的口吻。 */
        const val PLACEHOLDER_STREAMING = "插一句，或排队追问"

        fun uiRequest(id: String, method: String): PiEvent.ExtensionUiRequest =
            PiEvent.ExtensionUiRequest(
                id = id,
                method = method,
                title = "确认执行",
                message = "pi 请求执行 $method",
            )
    }
}

/** 安装门的独立渲染宿主（只验 InstallGate 的三档分支，不牵 VM）。 */
@Composable
private fun InstallGateHost(state: ChatUiState, onInstall: () -> Unit) {
    InstallGate(state = state, onInstall = onInstall)
}
