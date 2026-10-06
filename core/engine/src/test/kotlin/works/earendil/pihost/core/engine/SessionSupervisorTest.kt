package works.earendil.pihost.core.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SessionSupervisor 状态机（规格书 §5.4 的 10 条用例 + 第 11 条纯虚拟超时路径）。
 *
 * **时序纪律（RECON §6，本机实测）**：`PiSession` 的读写/退出协程钉在 `Dispatchers.IO`
 * = 真实线程；runTest 在测试体挂起、任务队列空时会把虚拟时钟推到下一个定时器，
 * 15s 握手超时会抢在真实 IO（毫秒级）之前触发。所以本文件**绝不在测试体里挂起等待**：
 * - 观察 = [pump]：`Thread.sleep(2)` 给真实线程投递 + `runCurrent()` 只跑已排队任务（不跳时钟）；
 * - 推进虚拟退避/握手超时 = `testScheduler.advanceTimeBy(ms)` **显式**跳，值取自观察到的状态。
 *
 * 另一个已知现象：连续两次 setState 若发生在同一个任务里，StateFlow 会合并（conflation），
 * 中间的 `Spawning` 可能进不了 history——所以断言只针对**必然分开**的状态
 * （`Restarting` 序列、终态、starter 调用次数），不对 `Spawning` 的重复次数下断言。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionSupervisorTest {

    private val starters = CopyOnWriteArrayList<FakeStarter>()

    @After
    fun killFakeProcesses() {
        starters.forEach { it.closeAll() }
    }

    // ── 装配与观察 helper ─────────────────────────────────────────────────────

    private fun supervisorIn(
        scope: CoroutineScope,
        modes: List<PiMode>,
        stderrSeed: String? = null,
    ): Pair<SessionSupervisor, FakeStarter> {
        val starter = FakeStarter(modes, stderrSeed)
        starters.add(starter)
        val spawn = PiSpawn(LIB_DIR, RUNTIME_DIR)
        val supervisor = SessionSupervisor(
            scope = scope,
            starter = starter,
            commandFor = spawn::command,
            envFor = { ENV },
            workingDir = WORKING_DIR,
        )
        return supervisor to starter
    }

    /** 按会话记状态转移（只记「与上一条不同」的快照，等价于转移表）。 */
    private class Recorder {
        private val map = ConcurrentHashMap<String, CopyOnWriteArrayList<SessionState>>()

        fun consume(snapshot: Map<String, SessionState>) {
            snapshot.forEach { (sessionId, state) ->
                val list = map.getOrPut(sessionId) { CopyOnWriteArrayList() }
                if (list.lastOrNull() != state) list.add(state)
            }
        }

        fun history(sessionId: String): List<SessionState> = map[sessionId] ?: emptyList()
        fun restartings(sessionId: String): List<SessionState.Restarting> =
            history(sessionId).filterIsInstance<SessionState.Restarting>()
    }

    private fun TestScope.observe(supervisor: SessionSupervisor, recorder: Recorder): Job =
        backgroundScope.launch { supervisor.sessionStates.collect(recorder::consume) }

    private fun TestScope.pump(label: String, timeoutMs: Long = 4_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            testScheduler.runCurrent()
            if (condition()) return
            assertTrue(
                "pump[$label] 在 ${timeoutMs}ms 真实时间内条件未成立（虚拟时钟未被本 helper 推进，" +
                    "说明缺的是真实 IO 或状态机没走到该分支）",
                System.currentTimeMillis() < deadline,
            )
            Thread.sleep(2)
        }
    }

    private fun TestScope.launchStart(supervisor: SessionSupervisor): CompletableDeferred<String> {
        val result = CompletableDeferred<String>()
        backgroundScope.launch { result.complete(supervisor.start()) }
        return result
    }

    /** 跳一段退避（值取自观察到的 Restarting，绝不盲跳）。 */
    private fun TestScope.skipBackoff(restarting: SessionState.Restarting) {
        testScheduler.advanceTimeBy(restarting.backoffMs + 1)
    }

    private fun SessionSupervisor.stateOf(sessionId: String): SessionState? = sessionStates.value[sessionId]

    /**
     * 观察「第 n 次 spawn → 第 n 级退避定时器已挂上」，然后才显式跳这段虚拟退避。
     *
     * 顺序不能反：`advanceTimeBy` 只能点亮**已经注册**的定时器。若在 `Restarting`
     * 之前跳（此前就是这么挂的），退避定时器会落在已跳过的时间之后，
     * 下一次 spawn 永远等不来。
     */
    private fun TestScope.driveThreeBackoffs(
        supervisor: SessionSupervisor,
        sessionId: String,
        starter: FakeStarter,
    ) {
        for (attempt in 1..3) {
            pump("第 $attempt 次 spawn 已发生") { starter.invocationCount() == attempt }
            pump("Restarting($attempt) 的退避定时器已挂上") {
                supervisor.stateOf(sessionId) is SessionState.Restarting
            }
            val restarting = supervisor.stateOf(sessionId) as SessionState.Restarting
            assertEquals(BACKOFFS[attempt - 1], restarting.backoffMs)
            testScheduler.advanceTimeBy(restarting.backoffMs + 1)
        }
        pump("第 4 次 spawn 已发生") { starter.invocationCount() == 4 }
    }

    // ── 用例 1：start() 走 Spawning→Ready，argv 带 host 生成的 --session-id ──────

    @Test
    fun `case01 start spawns handshakes and returns Ready with uuid in argv`() = runTest {
        val (supervisor, starter) = supervisorIn(backgroundScope, listOf(PiMode.HandshakeThenHang))
        val recorder = Recorder()
        observe(supervisor, recorder)

        val started = launchStart(supervisor)
        pump("start 返回") { started.isCompleted }
        val sessionId = started.await()

        assertEquals(SessionState.Ready, supervisor.stateOf(sessionId))
        assertEquals(
            listOf(SessionState.Spawning, SessionState.Ready),
            recorder.history(sessionId),
        )
        assertEquals(1, starter.invocationCount())
        // §12.1：每次 spawn 都带 host 生成的 UUID（首次也是），argv 逐元素落地
        assertEquals(
            listOf(
                "$LIB_DIR/libpi_node.so", "$RUNTIME_DIR/pi/dist/bundle/cli.js",
                "--mode", "rpc", "--session-id", sessionId, "--no-extensions",
            ),
            starter.commands[0],
        )
        assertEquals(ENV, starter.envs[0])
        assertEquals(WORKING_DIR, starter.workingDirs[0])
        // 握手命令确实写进了 stdin（get_state + 会话内首个 id c-1）
        pump("stdin 收到 get_state") { starter.spawned[0].stdinLines.isNotEmpty() }
        assertTrue(starter.spawned[0].stdinLines[0].contains("get_state"))
        assertEquals(1, starter.spawned[0].replyCount())
    }

    // ── 用例 2：Ready 后进程 EOF = 崩溃 → Restarting(1,1000) → 同 id 再 spawn → Ready

    @Test
    fun `case02 natural exit after ready restarts once with the same session id`() = runTest {
        val (supervisor, starter) = supervisorIn(
            backgroundScope,
            listOf(PiMode.HandshakeThenExit, PiMode.HandshakeThenHang),
        )
        val recorder = Recorder()
        observe(supervisor, recorder)

        val started = launchStart(supervisor)
        pump("首个 Ready") { started.isCompleted }
        val sessionId = started.await()
        assertEquals(1, starter.invocationCount())

        // 假进程「答完 30ms 再退」：真实线程投递退出 → 崩溃定性 → Restarting(1,1000)
        pump("EOF 后判崩溃并进退避") {
            supervisor.stateOf(sessionId) is SessionState.Restarting
        }
        assertEquals(
            listOf(1_000L),
            recorder.restartings(sessionId).map { it.backoffMs },
        )
        assertEquals(listOf(1), recorder.restartings(sessionId).map { it.attempt })

        skipBackoff(supervisor.stateOf(sessionId) as SessionState.Restarting)
        pump("同 id 再 spawn 并再次 Ready") {
            starter.invocationCount() == 2 && supervisor.stateOf(sessionId) == SessionState.Ready
        }
        assertEquals(2, starter.invocationCount())
        // 两次 spawn 的 --session-id 必须是同一个 UUID（会话文件续用）
        assertEquals(sessionId, sessionIdArg(starter.commands[0]))
        assertEquals(sessionId, sessionIdArg(starter.commands[1]))
    }

    // ── 用例 3：SilentDie 连败 → 1s/4s/16s 三级退避用满 → Failed，spawn 恰 4 次 ──

    @Test
    fun `case03 silent die burns three backoffs then fails after four spawns`() = runTest {
        val (supervisor, starter) = supervisorIn(backgroundScope, listOf(PiMode.SilentDie))
        val recorder = Recorder()
        observe(supervisor, recorder)

        val started = launchStart(supervisor)
        pump("第一次 spawn 发生") { starter.invocationCount() >= 1 }
        val sessionId = sessionIdArg(starter.commands[0])!!

        driveThreeBackoffs(supervisor, sessionId, starter)
        pump("start 在 Failed 时返回") { started.isCompleted }
        assertEquals(started.await(), sessionId)

        val failed = supervisor.stateOf(sessionId)
        assertTrue("末态应为 Failed，实为 $failed", failed is SessionState.Failed)
        assertTrue("Failed.cause 必须非空：${(failed as SessionState.Failed).cause}", failed.cause.isNotBlank())
        assertEquals("初始 1 次 + 重启 3 次 = 4 次 spawn 上限", 4, starter.invocationCount())
        assertEquals(
            listOf(1_000L, 4_000L, 16_000L),
            recorder.restartings(sessionId).map { it.backoffMs },
        )
        assertEquals(listOf(1, 2, 3), recorder.restartings(sessionId).map { it.attempt })
        // 第 4 次失败后不再 spawn：再送 20s 虚拟时间也维持 4 次
        testScheduler.advanceTimeBy(20_001)
        pump("等一拍") { true }
        assertEquals(4, starter.invocationCount())
    }

    // ── 用例 4：Ready 中 stop() → Stopped，且绝不重启 ──────────────────────────

    @Test
    fun `case04 stop during ready closes gracefully and never respawns`() = runTest {
        val (supervisor, starter) = supervisorIn(backgroundScope, listOf(PiMode.HandshakeThenHang))
        val started = launchStart(supervisor)
        pump("Ready") { started.isCompleted }
        val sessionId = started.await()

        val stopped = CompletableDeferred<Unit>()
        backgroundScope.launch { supervisor.stop(sessionId); stopped.complete(Unit) }
        pump("Stopped") { supervisor.stateOf(sessionId) == SessionState.Stopped }
        pump("stop 返回") { stopped.isCompleted }

        assertEquals(1, starter.invocationCount())
        val fake = starter.spawned[0]
        assertTrue("优雅关：stdin 必须见到 EOF", fake.stdinClosed)
        assertFalse("用户停不该走 SIGKILL 兜底", fake.destroyed)

        // §5.4-4：等 20s 虚拟时间也不得再 spawn
        testScheduler.advanceTimeBy(20_001)
        pump("等一拍") { true }
        assertEquals(1, starter.invocationCount())
    }

    // ── 用例 5：stop 幂等（对 Failed / Stopped 无副作用、不抛） ──────────────────

    @Test
    fun `case05 stop is idempotent on terminal states`() = runTest {
        val (supervisor, starter) = supervisorIn(backgroundScope, listOf(PiMode.SilentDie))
        val started = launchStart(supervisor)
        pump("第一次 spawn") { starter.invocationCount() >= 1 }
        val sessionId = sessionIdArg(starter.commands[0])!!
        driveThreeBackoffs(supervisor, sessionId, starter)
        pump("Failed") { supervisor.stateOf(sessionId) is SessionState.Failed }
        val invocationsAtFailure = starter.invocationCount()

        // 终态上重复 stop：终态不变、不新增 spawn、不抛
        supervisor.stop(sessionId)
        supervisor.stop(sessionId)
        pump("stop 幂等跑完") { true }
        assertTrue("终态仍是 Failed", supervisor.stateOf(sessionId) is SessionState.Failed)
        assertEquals(invocationsAtFailure, starter.invocationCount())

        // 未知 id 也必须是静默返回（shutdownAll 的健壮性前提）
        supervisor.stop("00000000-0000-0000-0000-000000000000")
        pump("未知 id 无副作用") { starter.invocationCount() == invocationsAtFailure }
        assertEquals(invocationsAtFailure, starter.invocationCount())
    }

    // ── 用例 6：握手失败先优雅关残留进程，再退避；第二次握手成功 ────────────────

    @Test
    fun `case06 handshake failure closes residual process then backs off and recovers`() = runTest {
        val (supervisor, starter) = supervisorIn(
            backgroundScope,
            listOf(PiMode.SilentDie, PiMode.HandshakeThenHang),
        )
        val recorder = Recorder()
        observe(supervisor, recorder)
        val started = launchStart(supervisor)
        pump("第一次 spawn") { starter.invocationCount() == 1 }
        val sessionId = sessionIdArg(starter.commands[0])!!

        pump("判失败 → Restarting(1,1000)") { recorder.restartings(sessionId).isNotEmpty() }
        val restarting = recorder.restartings(sessionId).first()
        assertEquals(1, restarting.attempt)
        assertEquals(1_000L, restarting.backoffMs)
        val first = starter.spawned[0]
        pump("§12.6：残留进程已优雅关（stdin EOF）") { first.stdinClosed }
        assertFalse("优雅关优先，不该直接 SIGKILL", first.destroyed)

        skipBackoff(restarting)
        pump("第二次 spawn") { starter.invocationCount() == 2 }
        pump("第二次 Ready") { started.isCompleted }
        assertEquals(started.await(), sessionId)
        assertEquals(SessionState.Ready, supervisor.stateOf(sessionId))
        assertEquals(2, starter.invocationCount())
        // 两次 argv 的 session-id 一致
        assertEquals(sessionIdArg(starter.commands[0]), sessionIdArg(starter.commands[1]))
    }

    // ── 用例 7：error response 直接计为失败，不等 15s 超时 ──────────────────────

    @Test
    fun `case07 error response fails without waiting for handshake timeout`() = runTest {
        val (supervisor, starter) = supervisorIn(
            backgroundScope,
            listOf(PiMode.HandshakeFail, PiMode.HandshakeThenHang),
        )
        val started = launchStart(supervisor)
        pump("第一次 spawn") { starter.invocationCount() == 1 }
        val sessionId = sessionIdArg(starter.commands[0])!!

        pump("error 应答直接判失败 → Restarting(1,1000)") {
            supervisor.stateOf(sessionId) is SessionState.Restarting
        }
        // 强证据：整条失败路径没消耗任何虚拟时间 → 15s 握手定时器从未触发
        assertEquals("error 应答不该等超时", 0L, testScheduler.currentTime)
        assertEquals(1, starter.spawned[0].replyCount())
        pump("残留进程已优雅关") { starter.spawned[0].stdinClosed }

        skipBackoff(supervisor.stateOf(sessionId) as SessionState.Restarting)
        pump("第二次 spawn") { starter.invocationCount() == 2 }
        pump("第二次 Ready") { started.isCompleted }
        assertEquals(started.await(), sessionId)
        assertEquals(SessionState.Ready, supervisor.stateOf(sessionId))
        assertEquals(2, starter.invocationCount())
    }

    // ── 用例 8：shutdownAll 停全部并发会话 ─────────────────────────────────────

    @Test
    fun `case08 shutdownAll stops every concurrent session`() = runTest {
        val (supervisor, starter) = supervisorIn(backgroundScope, listOf(PiMode.HandshakeThenHang))
        val first = launchStart(supervisor)
        pump("会话一 Ready") { first.isCompleted }
        val second = launchStart(supervisor)
        pump("会话二 Ready") { second.isCompleted }
        val idA = first.await()
        val idB = second.await()
        pump("两条 Ready") {
            supervisor.sessionStates.value.values.count { it == SessionState.Ready } == 2
        }
        assertEquals(2, starter.invocationCount())

        val done = CompletableDeferred<Unit>()
        backgroundScope.launch { supervisor.shutdownAll(); done.complete(Unit) }
        pump("全部 Stopped 且 shutdownAll 返回") {
            done.isCompleted && supervisor.sessionStates.value.values.none { it is SessionState.Ready }
        }
        assertEquals(SessionState.Stopped, supervisor.stateOf(idA))
        assertEquals(SessionState.Stopped, supervisor.stateOf(idB))
        assertEquals("不该有任何重启", 2, starter.invocationCount())
        assertTrue(starter.spawned[0].stdinClosed && starter.spawned[1].stdinClosed)

        testScheduler.advanceTimeBy(20_001)
        pump("等一拍") { true }
        assertEquals(2, starter.invocationCount())
    }

    // ── 用例 9：stderr 尾部可取，且落进 Failed.cause ────────────────────────────

    @Test
    fun `case09 stderr tail is exposed and lands in the failed cause`() = runTest {
        val seed = "Error: Cannot find module 'pi/dist/bundle/cli.js'\n    at node.js:1:1"
        val (supervisor, starter) = supervisorIn(
            backgroundScope,
            listOf(PiMode.SilentDie),
            stderrSeed = seed,
        )
        val started = launchStart(supervisor)
        pump("第一次 spawn") { starter.invocationCount() >= 1 }
        val sessionId = sessionIdArg(starter.commands[0])!!
        driveThreeBackoffs(supervisor, sessionId, starter)
        pump("Failed") { supervisor.stateOf(sessionId) is SessionState.Failed }

        assertEquals(seed, supervisor.stderrTail(sessionId))
        val cause = (supervisor.stateOf(sessionId) as SessionState.Failed).cause
        assertTrue("Failed.cause 必须含 stderr 尾部，实为：$cause", cause.contains(seed))
        // 未知会话 → null，而不是抛
        assertNull(supervisor.stderrTail("nope"))
        assertNull(supervisor.events("nope"))
    }

    // ── 用例 10：两个 start() 并发互不干扰 ─────────────────────────────────────

    @Test
    fun `case10 concurrent starts stay independent sessions`() = runTest {
        val (supervisor, starter) = supervisorIn(backgroundScope, listOf(PiMode.HandshakeThenHang))
        val recorder = Recorder()
        observe(supervisor, recorder)
        val first = launchStart(supervisor)
        val second = launchStart(supervisor)
        pump("两个 start 都返回") { first.isCompleted && second.isCompleted }
        val idA = first.await()
        val idB = second.await()
        val sessionIdA = sessionIdArg(starter.commands[0])!!
        val sessionIdB = sessionIdArg(starter.commands[1])!!

        assertNotEquals(idA, idB)
        assertEquals(2, supervisor.sessionStates.value.size)
        assertEquals(SessionState.Ready, supervisor.stateOf(idA))
        assertEquals(SessionState.Ready, supervisor.stateOf(idB))
        // argv 里的 id 与各自 start() 返回值一一对应
        assertTrue(sessionIdA == idA || sessionIdB == idA)
        assertNotEquals(sessionIdA, sessionIdB)

        // 停一个：另一个仍 Ready，且没有多余 spawn
        supervisor.stop(idA)
        pump("会话一 Stopped") { supervisor.stateOf(idA) == SessionState.Stopped }
        assertEquals(SessionState.Ready, supervisor.stateOf(idB))
        assertEquals(2, starter.invocationCount())
        assertTrue(recorder.history(idB).contains(SessionState.Ready))
    }

    // ── 用例 11（附加）：真正的 15s 虚拟超时路径 + destroy 兜底 ──────────────────

    @Test
    fun `case11 handshake timeout fires on the virtual timer and destroy backstops close`() = runTest {
        val (supervisor, starter) = supervisorIn(
            backgroundScope,
            listOf(PiMode.SilentHang, PiMode.HandshakeThenHang),
        )
        val started = launchStart(supervisor)
        pump("spawn 完成且 get_state 已写但无应答") {
            starter.invocationCount() == 1 && starter.spawned[0].stdinLines.isNotEmpty()
        }
        val sessionId = sessionIdArg(starter.commands[0])!!
        assertEquals(SessionState.Spawning, supervisor.stateOf(sessionId))
        assertTrue("假进程仍活着（只是不回话）", starter.spawned[0].isAlive())

        // ① 跳握手超时（定时器此刻已注册）
        testScheduler.advanceTimeBy(HANDSHAKE_TIMEOUT_MS + 1)
        pump("close 已试优雅路径") { starter.spawned[0].stdinClosed }
        assertFalse("优雅关期间还没到 destroy 兜底", starter.spawned[0].destroyed)

        // ② 跳 gracefulClose 超时 → destroy 兜底 → 判失败 → Restarting(1,1000)
        testScheduler.advanceTimeBy(GRACEFUL_CLOSE_TIMEOUT_MS + 1)
        pump("进入 Restarting(1,1000)") {
            supervisor.stateOf(sessionId) is SessionState.Restarting
        }
        assertTrue("超时后必须 destroy 兜底，不留活进程", starter.spawned[0].destroyed)
        assertEquals(137, starter.spawned[0].waitFor())

        // ③ 跳退避（值取自观察到的状态）→ 第二次 spawn → 握手成功 → Ready
        skipBackoff(supervisor.stateOf(sessionId) as SessionState.Restarting)
        pump("第二次 spawn") { starter.invocationCount() == 2 }
        pump("第二次 Ready") { started.isCompleted }
        assertEquals(started.await(), sessionId)
        assertEquals(SessionState.Ready, supervisor.stateOf(sessionId))
        assertEquals(2, starter.invocationCount())
    }

    private companion object {
        const val LIB_DIR = "/data/user/0/works.earendil.pihost/lib"
        const val RUNTIME_DIR = "/data/user/0/works.earendil.pihost/files/runtime"
        const val WORKING_DIR = "/data/user/0/works.earendil.pihost/files/projects/default"
        val ENV: Map<String, String> = mapOf(
            "PATH" to "$RUNTIME_DIR/bin:$LIB_DIR:/system/bin",
            "HOME" to "/data/user/0/works.earendil.pihost/files/home",
            "NODE_OPTIONS" to "--max-old-space-size=192",
        )
        val BACKOFFS: List<Long> = listOf(1_000L, 4_000L, 16_000L)
        const val HANDSHAKE_TIMEOUT_MS = 15_000L
        const val GRACEFUL_CLOSE_TIMEOUT_MS = 5_000L
    }
}
