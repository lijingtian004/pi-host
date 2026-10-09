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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

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
 *
 * **P2F 追加 case12–14**（§4.1(a) spawn 前 mkdirs）：这三条要碰真实文件系统，
 * 因此借用 `TemporaryFolder` 造「不存在 / 已存在 / 中途被删」三种 cwd，
 * 断言取 `FakeStarter.workingDirExistedAtStart` 的**调用瞬间采样**。
 * 注意本文件的 `WORKING_DIR` 是设备路径常量（`/data/user/0/...`），在构建机上不存在也建不出来
 * （非 root 时 `mkdirs()` 失败被 runCatching 吞掉，root 时会真建）——这正是 `runCatching` 的分工：
 * **cwd 建不成不是 spawn 的失败理由，真实 errno 由 delegate 上抛**。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionSupervisorTest {

    /** P2F case12–14 的沙箱根（每条用例 `newFolder` 独立，互不干扰）。 */
    @get:Rule
    val tmp = TemporaryFolder()

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
        workingDir: String = WORKING_DIR,
    ): Pair<SessionSupervisor, FakeStarter> {
        val starter = FakeStarter(modes, stderrSeed)
        starters.add(starter)
        val spawn = PiSpawn(LIB_DIR, RUNTIME_DIR)
        val supervisor = SessionSupervisor(
            scope = scope,
            starter = starter,
            commandFor = spawn::command,
            envFor = { ENV },
            // P3C §3.3：这个 helper 的 workingDir 参数是「静态值」，包成 lambda 之后
            // 语义与旧的一字面量构造完全一致（case15 才用会变的 supplier 测出生钉死）。
            workingDirFor = { workingDir },
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

    // ── 用例 12（P2F §4.1(a) / §4.4）：workingDir 不存在 → start() 被调时它必须已是目录 ──

    @Test
    fun `case12 missing workingDir is created before starter is invoked`() = runTest {
        val root = tmp.newFolder("sandbox")
        val workingDir = File(root, "projects/default")
        assertFalse("前置条件：两层目录都不存在（正是真机上的 filesDir/projects 形状）", workingDir.exists())

        val (supervisor, starter) = supervisorIn(
            backgroundScope,
            listOf(PiMode.HandshakeThenHang),
            workingDir = workingDir.absolutePath,
        )
        val started = launchStart(supervisor)
        pump("第一次 spawn 已发生") { starter.invocationCount() == 1 }

        // 断言点在 start() **被调的那一瞬间**（FakeStarter 现场采样），不是事后 isDirectory：
        // 事后检查分不清「supervisor 建的」与「进程起来后别人建的」，也证明不了 chdir 之前已就位。
        assertTrue(
            "chdir 先于 execve：start() 被调时 cwd 必须已经是目录，否则 error=2 与 exe 缺失无法区分",
            starter.workingDirExistedAtStart[0],
        )
        assertTrue("cwd 确实落成了真实目录", workingDir.isDirectory)
        assertEquals(workingDir.absolutePath, starter.workingDirs[0])

        pump("握手完成") { started.isCompleted }
        assertEquals(SessionState.Ready, supervisor.stateOf(started.await()))
    }

    // ── 用例 13（P2F §4.1(a) 边界）：workingDir 已存在 → mkdirs 幂等、不改内容、原样透传 ──

    @Test
    fun `case13 existing workingDir survives the idempotent mkdirs and is passed through`() = runTest {
        val root = tmp.newFolder("existing-sandbox")
        val keeper = File(root, "keep.txt").apply { writeText("重要内容") }
        assertFalse("前置条件：没有名为 projects/default 的子目录（mkdirs 只会补这一层）",
            File(root, "projects/default").exists())

        val (supervisor, starter) = supervisorIn(
            backgroundScope,
            listOf(PiMode.HandshakeThenHang),
            workingDir = root.absolutePath,
        )
        val started = launchStart(supervisor)
        pump("第一次 spawn 已发生") { starter.invocationCount() == 1 }

        assertTrue(starter.workingDirExistedAtStart[0])
        assertEquals(root.absolutePath, starter.workingDirs[0])
        assertEquals("重要内容", keeper.readText())
        assertFalse(File(root, "projects/default").exists())

        pump("握手完成") { started.isCompleted }
        val sessionId = started.await()
        assertEquals(SessionState.Ready, supervisor.stateOf(sessionId))
        supervisor.stop(sessionId)
        pump("Stopped") { supervisor.stateOf(sessionId) == SessionState.Stopped }
    }

    // ── 用例 14（P2F §4.1(a) 重启循环覆盖）：退避期间目录被外部删除 → 下次 spawn 前重建 ──

    @Test
    fun `case14 workingDir deleted between attempts is recreated on the next spawn`() = runTest {
        val root = tmp.newFolder("reroll-sandbox")
        val workingDir = File(root, "projects/default")
        val (supervisor, starter) = supervisorIn(
            backgroundScope,
            listOf(PiMode.SilentDie, PiMode.HandshakeThenHang),
            workingDir = workingDir.absolutePath,
        )
        val started = launchStart(supervisor)
        pump("第一次 spawn 已发生且 cwd 已建") {
            starter.invocationCount() == 1 && workingDir.isDirectory
        }
        assertEquals(true, starter.workingDirExistedAtStart[0])

        val sessionId = sessionIdArg(starter.commands[0])!!
        // 模拟「安装器/清理逻辑在两次尝试之间把 projects 目录抹掉」——P2F §9 诊断文件同理的坑
        assertTrue(workingDir.delete())
        assertFalse(workingDir.exists())

        pump("第一次静默退出 → Restarting") { supervisor.stateOf(sessionId) is SessionState.Restarting }
        skipBackoff(supervisor.stateOf(sessionId) as SessionState.Restarting)
        pump("第二次 spawn") { starter.invocationCount() == 2 }

        assertTrue("重启循环天然覆盖：第二次 start() 时 cwd 必须又被建出来", starter.workingDirExistedAtStart[1])
        assertTrue(workingDir.isDirectory)
        pump("第二次握手完成") { started.isCompleted }
        assertEquals(SessionState.Ready, supervisor.stateOf(sessionId))
    }

    // ── P3C §3.3：工作目录出生钉死（supplier 只在 start() 取一次）──────────────────

    /**
     * 与 [supervisorIn] 同一条装配，唯一区别是 cwd 来自一个**会变的** supplier。
     *
     * 单独开一个 helper 而不是给 [supervisorIn] 加参数：那 14 条既有用例断的是
     * 「mkdirs 时机」与「状态机」，让它们都改走 lambda 形态就再也测不出「静态值」这一档
     * （静态 supplier 与出生钉死在断言上不可区分，等于把新语义摊进旧用例里稀释掉）。
     */
    private fun supervisorWithSupplier(
        scope: CoroutineScope,
        modes: List<PiMode>,
        supplier: () -> String?,
    ): Pair<SessionSupervisor, FakeStarter> {
        val starter = FakeStarter(modes)
        starters.add(starter)
        val spawn = PiSpawn(LIB_DIR, RUNTIME_DIR)
        val supervisor = SessionSupervisor(
            scope = scope,
            starter = starter,
            commandFor = spawn::command,
            envFor = { ENV },
            workingDirFor = supplier,
        )
        return supervisor to starter
    }

    /**
     * 这条用例存在的唯一理由：pi 的 `--session-id` 查找按 cwd 分域，
     * **未命中不报错，而是新建一个同 id 的空会话**（`main.ts:435-448`）。
     * 所以 supplier 若在每次 spawn 重取，「切了项目 + 旧会话崩溃重启」会把旧 UUID
     * 拿到新目录里找 → 找不到 → 造一个空会话 → 用户的历史从 UI 消失且没有任何错误可看。
     *
     * 断法：supplier 第一次返回 A、之后每次返回 B；四次 spawn 全部必须是 A，
     * 且 supplier 的调用次数恰好为 1（多一次就是语义破了）。
     */
    @Test
    fun `case15 the cwd supplier is sampled exactly once at start and reused by every respawn`() = runTest {
        val dirA = tmp.newFolder("pin-a")
        val dirB = tmp.newFolder("pin-b")
        val calls = AtomicInteger(0)
        val (supervisor, starter) = supervisorWithSupplier(
            backgroundScope,
            listOf(PiMode.SilentDie, PiMode.SilentDie, PiMode.SilentDie, PiMode.HandshakeThenHang),
        ) {
            if (calls.getAndIncrement() == 0) dirA.absolutePath else dirB.absolutePath
        }

        val started = launchStart(supervisor)
        pump("第一次 spawn") { starter.invocationCount() == 1 }
        val sessionId = sessionIdArg(starter.commands[0])!!

        repeat(3) { round ->
            pump("第 ${round + 1} 次退避已挂上") {
                supervisor.stateOf(sessionId) is SessionState.Restarting
            }
            skipBackoff(supervisor.stateOf(sessionId) as SessionState.Restarting)
            pump("第 ${round + 2} 次 spawn") { starter.invocationCount() == round + 2 }
        }
        pump("最后一次握手完成") { started.isCompleted }

        assertEquals(
            "supplier 只能被调一次：出生时那一次。之后任何一次重取都可能把旧 UUID 落到新目录",
            1, calls.get(),
        )
        assertEquals(
            "四次 spawn 的 cwd 必须全是出生那个",
            listOf(dirA.absolutePath, dirA.absolutePath, dirA.absolutePath, dirA.absolutePath),
            starter.workingDirs.toList(),
        )
        assertNotEquals("反证：若实现走的是活 supplier，第 2 次起会拿到 B —— 这条断言就是那台警报器",
            dirB.absolutePath, starter.workingDirs[1])
    }

    /** 切活动项目只影响**之后新开的**会话；已经在跑的会话不动其落点（与 memTier 同一档语义）。 */
    @Test
    fun `case16 two sessions started around a project switch each keep their own cwd`() = runTest {
        val dirA = tmp.newFolder("own-a")
        val dirB = tmp.newFolder("own-b")
        var current: String = dirA.absolutePath
        val (supervisor, starter) = supervisorWithSupplier(
            backgroundScope,
            listOf(PiMode.HandshakeThenHang),
            supplier = { current },
        )

        val first = launchStart(supervisor)
        pump("第一个会话 start 返回") { first.isCompleted }
        current = dirB.absolutePath
        val second = CompletableDeferred<String>()
        backgroundScope.launch { second.complete(supervisor.start()) }
        pump("第二个会话 spawn") { starter.invocationCount() == 2 }
        pump("第二个会话 start 返回") { second.isCompleted }

        assertEquals(listOf(dirA.absolutePath, dirB.absolutePath), starter.workingDirs.toList())
        assertEquals(SessionState.Ready, supervisor.stateOf(first.await()))
        assertEquals(SessionState.Ready, supervisor.stateOf(second.await()))
    }

    /**
     * 比 case15 更接近事故现场的一条：切项目发生在「崩溃」与「重启」之间。
     *
     * 除了 spawn 不能落到 B，还有一条同样重要：**不能把 B 建出来**。
     * `spawn 前 mkdirs` 是 P2F 定的，它建的是 entry 出生时那个目录；若这里跟着 supplier 走，
     * 宿主就会在用户没同意的情况下凭空多出一个项目目录（P3C §2 红线的落点纪律）。
     */
    @Test
    fun `case17 a respawn after a project switch neither spawns into nor creates the new project dir`() = runTest {
        val dirA = tmp.newFolder("stale-a")
        val dirB = File(tmp.root, "stale-b")
        assertFalse("前置条件：B 此刻还不存在（它只可能被供应商路径误建出来）", dirB.exists())

        var current: String = dirA.absolutePath
        val (supervisor, starter) = supervisorWithSupplier(
            backgroundScope,
            listOf(PiMode.SilentDie, PiMode.HandshakeThenHang),
            supplier = { current },
        )
        val started = launchStart(supervisor)
        pump("第一次 spawn") { starter.invocationCount() == 1 }
        val sessionId = sessionIdArg(starter.commands[0])!!

        // 事故顺序：会话崩了 → 用户在这段退避里切了项目 → 重启
        pump("判失败 → Restarting") { supervisor.stateOf(sessionId) is SessionState.Restarting }
        current = "$dirB/never"
        skipBackoff(supervisor.stateOf(sessionId) as SessionState.Restarting)
        pump("第二次 spawn") { starter.invocationCount() == 2 }
        pump("握手完成") { started.isCompleted }

        assertEquals("重启必须回到出生目录", dirA.absolutePath, starter.workingDirs[1])
        assertTrue("出生目录照常 mkdirs（P2F 那条纪律不受影响）", starter.workingDirExistedAtStart[1])
        assertFalse("切项目不得在旧会话的重启路径上顺手建出新项目目录", dirB.exists())
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
