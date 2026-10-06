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
    )

    private fun TestScope.rig(
        scanner: FakeScanner = FakeScanner(),
        engine: FakeEngine = FakeEngine(),
    ): Rig {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val app = RuntimeEnvironment.getApplication()
        return Rig(SessionsViewModel(app, scanner, engine), scanner, engine)
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
}
