package works.earendil.pihost.feature.sessions

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import works.earendil.pihost.core.engine.SessionState
import works.earendil.pihost.core.engine.git.GitIdentity
import works.earendil.pihost.core.engine.git.GitInitOutcome
import works.earendil.pihost.core.engine.projects.ProjectOutcome
import works.earendil.pihost.feature.sessions.ui.SessionsStrings
import works.earendil.pihost.core.rpc.RpcCommand

/**
 * `SessionsViewModel` 的列表/命令/删除编排（规格书 §4.5 的 ≥5 条，实交 12 条）。
 *
 * 时序配方与 `ChatViewModelTest` 同一套（RECON-P2D §4）：引擎与扫描器都是假体，
 * `viewModelScope` 经 `Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))` **就地执行**，
 * 所以构造函数的 `init { refresh() }` 在 `rig()` 返回时已经跑完，不需要 pump；
 * 要看「过程」用 [record] 逐值收集（StateFlow 会被 conflation 合并，只断言终值会漏掉中间档）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionsViewModelTest {

    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private class Rig(
        val vm: SessionsViewModel,
        val scanner: FakeScanner,
        val engine: FakeEngine,
        val projects: FakeProjectsSource,
    )

    private fun TestScope.rig(
        scanner: FakeScanner = FakeScanner(),
        engine: FakeEngine = FakeEngine(),
    ): Rig {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val app = RuntimeEnvironment.getApplication()
        val projects = FakeProjectsSource()
        return Rig(SessionsViewModel(app, scanner, engine, projects), scanner, engine, projects)
    }

    private fun <T> TestScope.record(flow: StateFlow<T>): CopyOnWriteArrayList<T> {
        val seen = CopyOnWriteArrayList<T>()
        // backgroundScope：收集协程永不结束，挂在测试协程上会让 runTest 等不到收尾（RECON-P2D §4）
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { seen.add(it) } }
        return seen
    }

    private fun sessions(vararg ids: String) = ids.map { session(it, name = "会话 $it") }

    // ── v01 进屏即扫描 ───────────────────────────────────────────────────────────

    @Test
    fun `v01 constructing the view model scans once and lands on Content`() = runTest {
        val rig = rig(scanner = FakeScanner(sessions("a", "b")))
        val state = rig.vm.uiState.value
        assertTrue("期望 Content，实际 $state", state is SessionsUiState.Content)
        assertEquals(listOf("a", "b"), (state as SessionsUiState.Content).sessions.map { it.sessionId })
        assertEquals(1, rig.scanner.scanCalls.get())
        assertEquals("会话 a", state.sessions.first().name)
    }

    // ── v02 空目录 → Content(空表)（空态由 UI 分支渲染）─────────────────────────

    @Test
    fun `v02 an empty sessions directory yields an empty Content rather than a failure`() = runTest {
        val rig = rig(scanner = FakeScanner(emptyList()))
        val state = rig.vm.uiState.value as SessionsUiState.Content
        assertTrue("空表是**正常态**，不是 Failure", state.sessions.isEmpty())
        assertFalse(rig.scanner.deleted.isNotEmpty())
    }

    // ── v03 点击某条 → SwitchSession(sessionPath) + 导航意图 ─────────────────────

    @Test
    fun `v03 tapping a row sends SwitchSession with the jsonl path and raises a navigation`() = runTest {
        val row = session("s3", path = "/files/home/.pi/agent/sessions/--p--/s3.jsonl")
        val rig = rig(scanner = FakeScanner(listOf(row)))
        rig.engine.setState(FakeEngine.SESSION, SessionState.Ready)

        rig.vm.openSession(row)

        val sent = rig.engine.sent.single()
        assertEquals(FakeEngine.SESSION, sent.sessionId)
        assertEquals(
            "SwitchSession 的参数是 sessionPath（§3.2：不是 sessionId）",
            RpcCommand.SwitchSession("/files/home/.pi/agent/sessions/--p--/s3.jsonl"),
            sent.command,
        )
        assertEquals(SessionNavRequest(FakeEngine.SESSION), rig.vm.navigation.value)
        assertNull(rig.vm.notice.value)
    }

    // ── v04 FAB 新建 → NewSession + 导航 ─────────────────────────────────────────

    @Test
    fun `v04 the new-session action sends NewSession and navigates to the live session`() = runTest {
        val rig = rig(scanner = FakeScanner(sessions("a")))
        rig.engine.setState(FakeEngine.SESSION, SessionState.Ready)

        rig.vm.newSession()

        assertEquals(RpcCommand.NewSession(), rig.engine.sent.single().command)
        assertEquals(SessionNavRequest(FakeEngine.SESSION), rig.vm.navigation.value)
        assertEquals("新建不该起第二条引擎进程", 0, rig.engine.startCalls.get())
    }

    // ── v05 二步删除：request 不碰磁盘，confirm 才删 + 立刻重扫 ──────────────────

    @Test
    fun `v05 deleting a session requires two steps then deletes and rescans`() = runTest {
        val keep = session("keep")
        val gone = session("gone", path = "/files/--p--/gone.jsonl")
        val rig = rig(scanner = FakeScanner(listOf(keep, gone)))
        assertEquals(1, rig.scanner.scanCalls.get())

        rig.vm.requestDelete(gone)
        assertEquals("第一步只挂确认态", gone, rig.vm.pendingDelete.value)
        assertTrue("第一步绝不碰磁盘", rig.scanner.deleted.isEmpty())
        assertEquals(1, rig.scanner.scanCalls.get())

        rig.vm.deleteSession(gone)
        assertEquals(listOf("/files/--p--/gone.jsonl"), rig.scanner.deleted)
        assertNull("确认后确认态收起", rig.vm.pendingDelete.value)
        assertEquals("删完必须重扫，不留中间态", 2, rig.scanner.scanCalls.get())
        assertEquals(listOf("keep"), (rig.vm.uiState.value as SessionsUiState.Content).sessions.map { it.sessionId })
        assertNull("删成功不给提示", rig.vm.notice.value)
    }

    @Test
    fun `v05a cancelling the confirm deletes nothing`() = runTest {
        val row = session("keep")
        val rig = rig(scanner = FakeScanner(listOf(row)))
        rig.vm.requestDelete(row)
        rig.vm.cancelDelete()
        assertNull(rig.vm.pendingDelete.value)
        assertTrue(rig.scanner.deleted.isEmpty())
        assertEquals("取消也不触发重扫", 1, rig.scanner.scanCalls.get())
    }

    @Test
    fun `v05b a failed delete says so and still rescans`() = runTest {
        val rig = rig(scanner = FakeScanner(sessions("x")))
        rig.scanner.deleteResult = false
        rig.vm.deleteSession(session("x"))
        assertNotNull(rig.vm.notice.value)
        assertTrue(rig.vm.notice.value!!.startsWith("删除失败"))
        assertEquals(2, rig.scanner.scanCalls.get())
        assertEquals(listOf("x"), (rig.vm.uiState.value as SessionsUiState.Content).sessions.map { it.sessionId })
    }

    // ── v06 没有活会话：只导航、不命令 ───────────────────────────────────────────

    @Test
    fun `v06 with no live session the view model only navigates and never sends`() = runTest {
        val row = session("s6")
        val rig = rig(scanner = FakeScanner(listOf(row)))
        // engine.sessionStates 为空 = 引擎未启动

        rig.vm.openSession(row)

        assertTrue("绝不能对着不存在的进程发命令", rig.engine.sent.isEmpty())
        assertEquals("导航意图指向默认 chat 入口", SessionNavRequest(null), rig.vm.navigation.value)
        assertEquals(SessionsViewModel.NO_LIVE_SESSION, rig.vm.notice.value)
        assertEquals("sessions 屏不越权起引擎（§13 偏差 6 的门在 ChatViewModel）", 0, rig.engine.startCalls.get())

        rig.vm.newSession()
        assertTrue(rig.engine.sent.isEmpty())
    }

    @Test
    fun `v06b failed and stopped sessions do not count as live`() = runTest {
        val row = session("s6c")
        val rig = rig(scanner = FakeScanner(listOf(row)))
        rig.engine.setState("dead-1", SessionState.Failed("node died"))
        rig.engine.setState("dead-2", SessionState.Stopped)

        rig.vm.openSession(row)
        assertTrue("Failed/Stopped 都不算活会话", rig.engine.sent.isEmpty())
        assertEquals(SessionNavRequest(null), rig.vm.navigation.value)

        // Spawning（进程已起、握手未定）算活：send 仍能进 outbox
        rig.engine.setState("spawning-1", SessionState.Spawning)
        rig.vm.openSession(row)
        assertEquals(listOf("spawning-1"), rig.engine.sent.map { it.sessionId })
        assertEquals(SessionNavRequest("spawning-1"), rig.vm.navigation.value)
    }

    // ── v07 本地发送失败 → 提示，不发导航 ────────────────────────────────────────

    @Test
    fun `v07 a local send failure surfaces a notice and suppresses navigation`() = runTest {
        val row = session("s7")
        val rig = rig(scanner = FakeScanner(listOf(row)))
        rig.engine.setState(FakeEngine.SESSION, SessionState.Ready)
        rig.engine.sendError = IllegalStateException("会话没有活动进程")

        rig.vm.openSession(row)
        assertNotNull(rig.vm.notice.value)
        assertTrue(rig.vm.notice.value!!.startsWith("切换失败"))
        assertNull("命令没发出去就不许导航", rig.vm.navigation.value)

        rig.vm.consumeNotice()
        assertNull("提示可被 UI 消费清掉", rig.vm.notice.value)
    }

    // ── v08 扫描抛异常 → Failure(cause) ──────────────────────────────────────────

    @Test
    fun `v08 a scanner blowup becomes a Failure state instead of a crash`() = runTest {
        val scanner = FakeScanner(sessions("a"))
        scanner.scanError = RuntimeException("磁盘读取失败")
        val rig = rig(scanner = scanner)

        assertTrue("期望 Failure，实际 ${rig.vm.uiState.value}", rig.vm.uiState.value is SessionsUiState.Failure)
        assertEquals("磁盘读取失败", (rig.vm.uiState.value as SessionsUiState.Failure).cause)
    }

    // ── v09 下拉刷新：保留列表 + refreshing 标记，完成后收起 ─────────────────────

    @Test
    fun `v09 pull to refresh keeps the list and toggles the refreshing flag`() = runTest {
        // scanPauseMs=1 让 scan() **真的挂起一次**：StateFlow 只保最新值，
        // 一次不挂起的 refresh() 会把 refreshing=true 直接 conflate 掉（本轮编译机实测到 [false]）。
        val rig = rig(scanner = FakeScanner(sessions("a")).apply { scanPauseMs = 1L })
        val states = record(rig.vm.uiState)
        // 构造期那次 init{refresh()} 现在停在 delay 上，先推进虚拟时钟让它落地
        runCurrent()
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(SessionsUiState.Content(sessions("a")), rig.vm.uiState.value)

        rig.vm.refresh()
        runCurrent()                       // 收集协程取走 refreshing=true
        advanceTimeBy(1L)                  // scan 返回
        runCurrent()                       // 再取 refreshing=false
        val flags = states.filterIsInstance<SessionsUiState.Content>().map { it.refreshing }
        assertEquals("刷新中→刷新完的两档都要被看见", listOf(false, true, false), flags)
        assertEquals("刷新不许清空列表（不闪白）", 1, (rig.vm.uiState.value as SessionsUiState.Content).sessions.size)
        assertEquals(2, rig.scanner.scanCalls.get())
    }

    // ── P3C §3.7：项目面（v13–v20）──────────────────────────────────────────────

    @Test
    fun `v13 createProject activates the new project and never sends an engine command`() = runTest {
        val rig = rig()

        rig.vm.createProject("shop", initGit = true)
        runCurrent()

        assertEquals(listOf("shop" to true), rig.projects.created)
        assertEquals("建成即设为活动项目", listOf("shop"), rig.projects.activated)
        assertTrue("sessions 屏不启动引擎（纪律 1）", rig.engine.sent.isEmpty())
        assertEquals("shop", (rig.vm.uiState.value as SessionsUiState.Content).activeProject)
    }

    /** 三种 init 结局必须说三句不同的话——「用户没要」与「要了没成」混在一起就是在骗用户。 */
    @Test
    fun `v14 the notice differs for not-requested and failed git init`() = runTest {
        val rig = rig()

        rig.vm.createProject("plain", initGit = false)
        runCurrent()
        val plain = rig.vm.notice.value
        assertTrue("没勾 git 就不该说有仓库：$plain", plain != null && plain.contains("未初始化 git"))

        rig.projects.responder = { raw, _ ->
            ProjectOutcome.Created(raw, GitInitOutcome.Failed("exit=128 nope"), GitIdentity.Outcome.Written)
        }
        rig.vm.createProject("half", initGit = true)
        runCurrent()
        val half = rig.vm.notice.value
        assertTrue("失败原因要原样说得出：$half", half != null && half.contains("exit=128 nope"))
        assertTrue("目录建成了就得设活动（这是既成事实）", rig.projects.activated.contains("half"))
    }

    @Test
    fun `v15 an invalid name lands as a notice without touching the active project`() = runTest {
        val rig = rig()
        rig.projects.responder = { _, _ -> ProjectOutcome.Invalid("../../escape") }

        rig.vm.createProject("../../escape", initGit = true)
        runCurrent()

        assertEquals("default", rig.projects.activeValue)
        assertTrue(rig.projects.activated.isEmpty())
        assertEquals(SessionsStrings.PROJECT_INVALID_NAME, rig.vm.notice.value)
    }

    @Test
    fun `v16 duplicate and case-collided names each get their own wording`() = runTest {
        val rig = rig()

        rig.projects.responder = { _, _ -> ProjectOutcome.AlreadyExists("dup") }
        rig.vm.createProject("dup", initGit = false)
        runCurrent()
        assertEquals("项目「dup」已存在", rig.vm.notice.value)

        rig.projects.responder = { _, _ -> ProjectOutcome.CaseCollision("Foo", "foo") }
        rig.vm.createProject("Foo", initGit = false)
        runCurrent()
        assertEquals("已有同名项目「foo」（只差大小写）", rig.vm.notice.value)
    }

    @Test
    fun `v17 a throwing source is caught into a notice, not a crash`() = runTest {
        val rig = rig()
        rig.projects.boom = IllegalStateException("disk busy")

        rig.vm.createProject("x", initGit = false)
        runCurrent()

        assertTrue("来源异常必须兜住并说给人话", rig.vm.notice.value!!.contains("disk busy"))
    }

    @Test
    fun `v18 a listing failure still shows the session list and only blanks the project bar`() = runTest {
        val rig = rig(scanner = FakeScanner(sessions("a", "b")))
        rig.projects.boom = IllegalStateException("cannot read projects")

        rig.vm.refresh()
        runCurrent()

        val content = rig.vm.uiState.value as SessionsUiState.Content
        assertEquals(2, content.sessions.size)
        assertEquals("列项目失败不炸整屏", emptyList<String>(), content.projects)
        assertEquals("活动项回落 default", "default", content.activeProject)
    }

    /**
     * 跨项目续用是**放行 + 告知**，不是拦（P3C 修正）。
     * 拦它是错的：`switch_session` 只带 sessionPath，pi 会按会话记录的 cwd 重建服务，
     * 工具本来就在正确目录里跑；要提的是「之后在这条进程里新建会话会落在当前活动项目」。
     */
    @Test
    fun `v19 opening a foreign-project session sends switch_session and only notes the mismatch`() = runTest {
        val engine = FakeEngine()
        val foreign = session("f", path = "/files/home/.pi/agent/sessions/--p2--/f.jsonl", cwd = "/files/projects/other")
        val rig = rig(scanner = FakeScanner(listOf(foreign)), engine = engine)
        rig.projects.owners = mapOf(foreign.cwd to "other")
        engine.setState(FakeEngine.SESSION, SessionState.Ready)

        rig.vm.openSession(foreign)
        runCurrent()

        assertEquals("仍要真的发切换命令", 1, engine.sent.size)
        assertEquals("告知之后照常导航", SessionNavRequest(FakeEngine.SESSION), rig.vm.navigation.value)
        assertTrue("告知要说清两边名字", rig.vm.notice.value!!.contains("other") && rig.vm.notice.value!!.contains("default"))
    }

    /** 反面：同项目的会话不该被多塞一条提示（噪声会教用户忽略所有提示）。 */
    @Test
    fun `v20 opening a session of the active project stays silent`() = runTest {
        val engine = FakeEngine()
        val own = session("o", cwd = "/files/projects/default")
        val rig = rig(scanner = FakeScanner(listOf(own)), engine = engine)
        rig.projects.owners = mapOf(own.cwd to "default")
        engine.setState(FakeEngine.SESSION, SessionState.Ready)

        rig.vm.openSession(own)
        runCurrent()

        assertNull(rig.vm.notice.value)
        assertEquals(1, engine.sent.size)
    }
}
