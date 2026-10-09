package works.earendil.pihost.feature.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsNotDisplayed
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
import works.earendil.pihost.core.rpc.RpcCommand
import works.earendil.pihost.feature.chat.CHIP_NO_MODELS
import works.earendil.pihost.feature.chat.CHIP_PICK
import works.earendil.pihost.feature.chat.CHIP_PICK_LOADING
import works.earendil.pihost.feature.chat.ChatUiState
import works.earendil.pihost.feature.chat.ChatViewModel
import works.earendil.pihost.feature.chat.FakeChatEngine
import works.earendil.pihost.core.engine.git.GitSnapshot
import works.earendil.pihost.feature.chat.FakeGitProbe
import works.earendil.pihost.feature.chat.FakeRuntimeInstall
import works.earendil.pihost.feature.chat.QueueState
import works.earendil.pihost.feature.chat.FakeSettingsRepository
import works.earendil.pihost.feature.chat.ToolCallRecord
import works.earendil.pihost.feature.chat.ToolStatus
import works.earendil.pihost.feature.chat.TranscriptItem
import works.earendil.pihost.feature.chat.TurnState
import works.earendil.pihost.feature.chat.probeCompactionEnd
import works.earendil.pihost.feature.chat.probeCompactionStart
import works.earendil.pihost.feature.chat.probeEmptyModelsData
import works.earendil.pihost.feature.chat.probeLevelsData
import works.earendil.pihost.feature.chat.probeModelsData
import works.earendil.pihost.feature.chat.probeStateFlags
import works.earendil.pihost.feature.chat.probeStatsData
import works.earendil.pihost.feature.chat.probeStateData
import works.earendil.pihost.feature.chat.wireName

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

    private val gitProbe = FakeGitProbe()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = ChatViewModel(
            RuntimeEnvironment.getApplication(),
            engine,
            installer,
            settings,
            buildMarkwon(RuntimeEnvironment.getApplication()),
            // P3C §3.6：注入项目假体。默认那份返回 Repo("main", clean)，
            // 于是「chip 该出现」的用例一字不改就能跑，「该沉默」的用例各自换掉它。
            gitProbe = gitProbe,
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

    // ── §3.3 模型 chip：三档文案 + 点开才拉列表 + 选择/轮换/刷新 ────────────────────

    @Test
    fun modelChipStaysSilentUntilTappedAndThenAsksPiThreeThings() {
        showReadyScreen()
        // 脚本必须**先挂再点**：FakeChatEngine 在 send 那一刻才把脚本回执攒进 outbox
        // （见其 scriptedOutbox 的 KDoc），点完再挂 = 这条命令永远等不到回执，
        // 对话框会一直停在「加载中」——一个看起来像「实现没做」的假失败。
        engine
            .scriptReply("get_available_models", data = probeModelsData())
            .scriptReply("get_available_thinking_levels", data = probeLevelsData("off"))
            .scriptReply("get_state", data = probeStateData("mock-1", "Mock Model 1", "off"))

        // 初始态：chip 在，文案是「选择模型」，且一条模型命令都没发（§3.3 不主动拉）
        composeRule.onNodeWithTag(ChatTags.MODEL_CHIP).assertTextEquals(CHIP_PICK)
        assertEquals("没点 chip 就不许问 pi", 0, engine.sent.count { wireName(it.command) in MODEL_REFRESH_WIRES })

        composeRule.onNodeWithTag(ChatTags.MODEL_CHIP).performClick()
        composeRule.waitForIdle()

        assertEquals(MODEL_REFRESH_WIRES, engine.sent.map { wireName(it.command) }.filter { it in MODEL_REFRESH_WIRES })

        // 回执未到之前先说「加载中」：列表空有两种原因，chip 与对话框都得把话说清
        composeRule.onNodeWithTag(ChatTags.MODEL_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithText(CHIP_PICK_LOADING).assertIsDisplayed()

        engine.deliverScripted()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ChatTags.modelItem(0)).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.modelItem(1)).assertIsDisplayed()
        composeRule.onNodeWithText("Mock Model 1").assertIsDisplayed()
        composeRule.onNodeWithText("Mock Model 2").assertIsDisplayed()
        // 高亮只有一条，且就是 get_state 认出来的那条（列表里本身没有 current 标记，探测 p01）
        composeRule.onAllNodesWithText(MARK_CURRENT).assertCountEquals(1)
        composeRule.onNodeWithTag(ChatTags.MODEL_CHIP).assertTextEquals("Mock Model 1 · 思考 off")
        // 白名单只有一条 off → 只渲染一个档位，不给用户点出 reasoning 档的出口
        composeRule.onNodeWithTag(ChatTags.thinkingItem(0)).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.thinkingItem(1)).assertDoesNotExist()
    }

    @Test
    fun pickingTheSecondRowSendsItsOwnProviderAndIdAndClosesTheDialog() {
        showReadyScreen()
        engine
            .scriptReply("get_available_models", data = probeModelsData())
            .scriptReply("get_available_thinking_levels", data = probeLevelsData("off"))
            .scriptReply("get_state", data = probeStateData("mock-1", "Mock Model 1", "off"))
        composeRule.onNodeWithTag(ChatTags.MODEL_CHIP).performClick()
        engine.deliverScripted()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ChatTags.modelItem(1)).performClick()
        composeRule.waitForIdle()

        assertEquals(
            "点第二行 = 发这一行的 provider/id，不是列表第一条",
            RpcCommand.SetModel("mock", "mock-2"),
            engine.sent.last().command,
        )
        assertEquals(1, engine.sent.count { it.command == RpcCommand.SetModel("mock", "mock-2") })
        composeRule.onNodeWithTag(ChatTags.MODEL_DIALOG).assertDoesNotExist()
    }

    @Test
    fun thinkingRowsAndTheThreeFooterButtonsEachSendTheirOwnCommand() {
        showReadyScreen()
        engine
            .scriptReply("get_available_models", data = probeModelsData())
            .scriptReply(
                "get_available_thinking_levels",
                data = probeLevelsData("off", "minimal", "low", "medium", "high"),
            )
            .scriptReply("get_state", data = probeStateData("mock-2", "Mock Model 2", "off"))
        composeRule.onNodeWithTag(ChatTags.MODEL_CHIP).performClick()
        engine.deliverScripted()
        composeRule.waitForIdle()

        // 五个档位都来自白名单，第 4 项（high）点下去就是那条 set_thinking_level
        composeRule.onNodeWithTag(ChatTags.thinkingItem(4)).performClick()
        composeRule.waitForIdle()
        assertEquals(RpcCommand.SetThinkingLevel("high"), engine.sent.last().command)

        // 「换档」与「轮换模型」是两条不同的命令，且都不关框（档位还要连点）
        composeRule.onNodeWithTag(ChatTags.THINKING_CYCLE).performClick()
        composeRule.waitForIdle()
        assertEquals(RpcCommand.CycleThinkingLevel, engine.sent.last().command)
        composeRule.onNodeWithTag(ChatTags.MODEL_DIALOG).assertIsDisplayed()

        // 轮换模型会关掉对话框：pi 自己挑下一条，用户要看结果而不是接着点
        composeRule.onNodeWithTag(ChatTags.MODEL_CYCLE).performClick()
        composeRule.waitForIdle()
        assertEquals(RpcCommand.CycleModel, engine.sent.last().command)
        composeRule.onNodeWithTag(ChatTags.MODEL_DIALOG).assertDoesNotExist()

        // 「刷新」= 再问一次列表而不关框（关再开等于把同一串请求发两遍）
        composeRule.onNodeWithTag(ChatTags.MODEL_CHIP).performClick()
        composeRule.waitForIdle()
        val before = engine.sent.count { it.command == RpcCommand.GetAvailableModels }
        composeRule.onNodeWithTag(ChatTags.MODEL_REFRESH).performClick()
        composeRule.waitForIdle()
        assertEquals(before + 1, engine.sent.count { it.command == RpcCommand.GetAvailableModels })
        composeRule.onNodeWithTag(ChatTags.MODEL_DIALOG).assertIsDisplayed()
    }

    @Test
    fun anEmptyCatalogTurnsTheChipIntoALabelInsteadOfAnInvisibleButton() {
        showReadyScreen()
        engine.scriptReply("get_available_models", data = probeEmptyModelsData())
        engine.scriptReply("get_available_thinking_levels", data = probeLevelsData())
        composeRule.onNodeWithTag(ChatTags.MODEL_CHIP).performClick()
        engine.deliverScripted()
        composeRule.waitForIdle()

        // 「拉过了但空」与「还没拉」必须分得开：前者点名去看认证，后者只是再等一拍
        composeRule.onNodeWithTag(ChatTags.MODEL_CHIP).assertTextEquals(CHIP_NO_MODELS)
        composeRule.onNodeWithTag(ChatTags.modelItem(0)).assertDoesNotExist()
        // chip 与对话框讲同一句话（两处措辞一致），所以这里数的是「两处都有」
        composeRule.onAllNodesWithText(CHIP_NO_MODELS).assertCountEquals(2)

        composeRule.onNodeWithText("关闭").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatTags.MODEL_DIALOG).assertDoesNotExist()
    }
    // ── §3.4 统计条：未加载态、展开明细、压缩确认、自动压缩开关 ────────────────────

    @Test
    fun statsBarSaysUnloadedUntilTheRefreshButtonGetsAnAnswer() {
        showReadyScreen()
        // 一行数字都没问过就摆出来 = 用户以为这条会话真的没花钱；这里必须先说实话
        composeRule.onNodeWithTag(ChatTags.STATS_BAR).assertTextEquals(STATS_UNLOADED)
        assertEquals(0, engine.sent.count { wireName(it.command) == "get_session_stats" })

        engine.scriptReply("get_session_stats", data = probeStatsData())
        composeRule.onNodeWithTag(ChatTags.STATS_REFRESH).performClick()
        composeRule.waitForIdle()
        engine.deliverScripted()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ChatTags.STATS_BAR).assertTextEquals("240 tok｜\$0.0000｜120/128000 (0.09%)")
        // 没展开之前不给明细：那一行是「扫一眼」的东西，明细要点开才给
        composeRule.onNodeWithTag(ChatTags.STATS_DETAILS).assertDoesNotExist()
    }

    @Test
    fun expandingTheStatsBarRevealsCountsAndCompactingStillNeedsAConfirmation() {
        showReadyScreen()
        engine.scriptReply("get_session_stats", data = probeStatsData())
        composeRule.onNodeWithTag(ChatTags.STATS_REFRESH).performClick()
        composeRule.waitForIdle()
        engine.deliverScripted()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ChatTags.STATS_BAR).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatTags.STATS_DETAILS).assertIsDisplayed()
        composeRule.onNodeWithText("user 1｜assistant 2｜toolCalls 1｜toolResults 1｜total 5").assertIsDisplayed()
        composeRule.onNodeWithText("in 200｜out 40｜cacheR 0｜cacheW 0｜合计 240").assertIsDisplayed()

        // 「压缩上下文」不是即时动作：先弹确认，取消就一条命令都不发
        composeRule.onNodeWithTag(ChatTags.COMPACT_BUTTON).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatTags.COMPACT_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.COMPACT_CANCEL).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatTags.COMPACT_DIALOG).assertDoesNotExist()
        assertEquals("取消就是取消", 0, engine.sent.count { wireName(it.command) == "compact" })

        composeRule.onNodeWithTag(ChatTags.COMPACT_BUTTON).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatTags.COMPACT_CONFIRM).performClick()
        composeRule.waitForIdle()
        assertEquals("compact", wireName(engine.sent.last().command))
        composeRule.onNodeWithTag(ChatTags.COMPACT_DIALOG).assertDoesNotExist()
    }

    @Test
    fun theAutoCompactionSwitchStaysUnknownUntilGetStateAnswers() {
        showReadyScreen()
        composeRule.onNodeWithTag(ChatTags.STATS_BAR).performClick()
        composeRule.waitForIdle()

        // get_state 没问过 → 措辞说「未知」，而不是把开关画成「关」让用户以为不用管
        composeRule.onNodeWithText("自动压缩（未知）").assertIsDisplayed()

        engine.scriptReply("get_state", data = probeStateFlags(autoCompaction = true))
        composeRule.onNodeWithTag(ChatTags.AUTO_COMPACT_SWITCH).performClick()
        composeRule.waitForIdle()
        assertEquals(
            listOf("set_auto_compaction", "get_state"),
            engine.sent.map { wireName(it.command) }.takeLast(2),
        )
        // 回执没到之前按下去的那一下不算数
        composeRule.onNodeWithText("自动压缩（未知）").assertIsDisplayed()

        engine.deliverScripted()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("自动压缩（开）").assertIsDisplayed()
    }

    @Test
    fun compactionInFlightMarksTheBarAndGreysOutTheCompactButton() {
        showReadyScreen()
        composeRule.onNodeWithTag(ChatTags.STATS_BAR).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatTags.COMPACT_BUTTON).assertIsEnabled()

        engine.emit(PiEvent.Unknown(probeCompactionStart()))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatTags.STATS_BAR).assertTextEquals("$STATS_UNLOADED｜正在压缩…")
        composeRule.onNodeWithTag(ChatTags.COMPACT_BUTTON).assertIsNotEnabled()

        // 压缩结束会自己重问一次统计（§3.7 验收第 4 条靠这一步：ctx 那一格要换成新数）
        engine.scriptReply(
            "get_session_stats",
            data = probeStatsData(totalTokens = 90, contextTokens = 40, percent = 0.03125),
        )
        engine.emit(PiEvent.Unknown(probeCompactionEnd()))
        composeRule.waitForIdle()
        assertEquals(
            "压缩结束的那一条重问是唯一的一次",
            1,
            engine.sent.count { wireName(it.command) == "get_session_stats" },
        )
        composeRule.onNodeWithTag(ChatTags.COMPACT_BUTTON).assertIsEnabled()
        engine.deliverScripted()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatTags.STATS_BAR).assertTextEquals("90 tok｜\$0.0000｜40/128000 (0.03%)")
    }
    private companion object {
        const val PLACEHOLDER_PENDING = "等待权限确认…"

        /** §3.2：流式档的占位文案——输入框可用了，提示语也必须换成「能做事」的口吻。 */
        const val PLACEHOLDER_STREAMING = "插一句，或排队追问"

        /**
         * §3.3 一次点开应当发出的三条命令（线名，顺序即 [works.earendil.pihost.feature.chat.ChatViewModel.refreshModels] 的发出顺序）。
         *
         * 断言按这张表**过滤**，不是比对整条 `sent` 流水：Ready 时回填还会发 `get_messages`，
         * 把它一起比进去就等于把 §3.1 的用例绑到 §3.3 上——一边改一边红，看不出是谁坏了。
         */
        val MODEL_REFRESH_WIRES = listOf(
            "get_available_models",
            "get_available_thinking_levels",
            "get_state",
        )

        /** 当前项的高亮标记（ModelChip 里非当前项用的是全角空格，不会撞车）。 */
        const val MARK_CURRENT = "● "

        /** §3.4：一行式统计条在「还没问过 pi」时的那句实话。 */
        const val STATS_UNLOADED = "统计未加载"

        fun uiRequest(id: String, method: String): PiEvent.ExtensionUiRequest =
            PiEvent.ExtensionUiRequest(
                id = id,
                method = method,
                title = "确认执行",
                message = "pi 请求执行 $method",
            )
    }

    // ── P3C §3.6：顶栏分支 chip ────────────────────────────────────────────────

    /**
     * 分支 + 脏标记的那颗星。
     *
     * 断言走 `assertTextEquals(tag)` 而不是 `onNodeWithText`：顶栏一行里同时有好几处短文本
     * （会话短码、重试条、模型 chip），按文本找节点会撞上「多个匹配」——`topBarShows...`
     * 那条既有用例用的是同一个理由。
     */
    @Test
    fun gitChipShowsBranchAndDirtyStar() {
        gitProbe.answer = GitSnapshot.Repo(branch = "main", dirty = true)

        showReadyScreen()

        composeRule.onNodeWithTag(ChatTags.GIT_CHIP).assertIsDisplayed().assertTextEquals("main*")
    }

    /**
     * 「说不清」的形状不渲染节点。
     *
     * 拆成三条而不是循环一遍：`showReadyScreen()` 会点掉安装门、走一次 Ready，
     * 而快照**只在 Ready 那一刻推一次**——同一个组合里翻 answer 不会产生新的读，
     * 循环写法测的其实是「第一次」。三条各自从未 Ready 起步，才是三种独立事实。
     *
     * 这条同时是既有断言的保险丝：`topBarShowsEngineShortCodeAndQueueBadge` 用
     * `assertDoesNotExist` 查队列徽标，若我为了放 git 而加了个常驻带文本的节点，那里就会红。
     */
    @Test
    fun notARepoRendersNothing() {
        gitProbe.answer = GitSnapshot.NotARepo

        showReadyScreen()

        assertTrue("读了才敢说不渲染", gitProbe.reads() > 0)
        composeRule.onNodeWithTag(ChatTags.GIT_CHIP).assertIsNotDisplayed()
    }

    @Test
    fun busyIndexRendersNothing() {
        gitProbe.answer = GitSnapshot.Busy

        showReadyScreen()

        composeRule.onNodeWithTag(ChatTags.GIT_CHIP).assertIsNotDisplayed()
    }

    @Test
    fun anUnavailableProbeRendersNothing() {
        gitProbe.answer = GitSnapshot.Unavailable("error=13")

        showReadyScreen()

        composeRule.onNodeWithTag(ChatTags.GIT_CHIP).assertIsNotDisplayed()
    }

    /** Ready 之前（还停在安装门）一次都不该读：那时工作目录都可能还没建出来。 */
    @Test
    fun nothingIsReadBeforeReady() {
        composeRule.setContent { PiHostTheme { ChatScreen(viewModel = vm, notificationGate = {}) } }
        composeRule.waitForIdle()

        assertEquals(0, gitProbe.reads())
        composeRule.onNodeWithTag(ChatTags.GIT_CHIP).assertIsNotDisplayed()
    }

    /**
     * 点 chip → VM 再读一次，且**不牵动模型 chip**。
     *
     * 后半句是这条用例存在的理由：两颗 chip 在同一行、都用 labelMedium，
     * 若我为了放 git 而动过 ModelChip 的调用点，这里就会红。
     */
    @Test
    fun tappingTheGitChipReReadsAndLeavesTheModelChipAlone() {
        gitProbe.answer = GitSnapshot.Repo(branch = "dev", dirty = false)
        showReadyScreen()
        val before = gitProbe.reads()

        composeRule.onNodeWithTag(ChatTags.GIT_CHIP).performClick()
        composeRule.waitForIdle()

        assertEquals("点一次多读一次", before + 1, gitProbe.reads())
        composeRule.onNodeWithTag(ChatTags.GIT_CHIP).assertTextEquals("dev")
        composeRule.onNodeWithTag(ChatTags.MODEL_CHIP).assertIsDisplayed()
    }

}

/** 安装门的独立渲染宿主（只验 InstallGate 的三档分支，不牵 VM）。 */
@Composable
private fun InstallGateHost(state: ChatUiState, onInstall: () -> Unit) {
    InstallGate(state = state, onInstall = onInstall)
}
