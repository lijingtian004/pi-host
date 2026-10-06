package works.earendil.pihost.feature.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import works.earendil.pihost.core.engine.SessionState
import works.earendil.pihost.core.engine.settings.AppSettings
import works.earendil.pihost.core.environment.MemTier
import works.earendil.pihost.core.rpc.PiEvent
import works.earendil.pihost.core.rpc.RpcCommand
import works.earendil.pihost.feature.settings.ui.SettingsStrings

/**
 * `SettingsViewModel`：`get_state` 探针（命令捕获 + 应答显示）+ 设置写回 + 版本卡片两档发布。
 *
 * 时序配方与 `:feature:sessions` 同一套（RECON-P2D §4 / RECON-P2E §6）：
 * `Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))` 让 `viewModelScope` **就地执行**，
 * 于是「调一个方法 → 立刻断言状态」成立；要看中间档就 `runCurrent()`/`advanceTimeBy()` 手动推，
 * 不引入额外的收集器作用域（探针这条请求-应答是**一次性**的，用不着长驻观察）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsViewModelTest {

    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private class Rig(
        val vm: SettingsViewModel,
        val repository: FakeSettingsRepository,
        val engine: FakeEngine,
        val info: FakeRuntimeInfo,
    )

    private fun TestScope.rig(
        settings: AppSettings = AppSettings(),
        engine: FakeEngine = FakeEngine(),
        info: FakeRuntimeInfo = FakeRuntimeInfo(),
    ): Rig {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repository = FakeSettingsRepository(settings)
        return Rig(SettingsViewModel(repository, engine, info), repository, engine, info)
    }

    /** 就绪的引擎：探针只有在有活会话时才发得出命令。 */
    private fun liveEngine(): FakeEngine = FakeEngine().apply {
        setState(FakeEngine.SESSION, SessionState.Ready)
    }

    // ── t01 探针：命令 + 应答显示（§3.4「发 GetState → 显示 data 前 500 字」）──────

    @Test
    fun `t01 the probe sends get_state and shows the first 500 chars of data`() = runTest {
        val rig = rig(engine = liveEngine())
        rig.vm.runProbe()

        assertEquals("发出后卡片进等待态", ProbeState.Running, rig.vm.probe.value)
        val sent = rig.engine.sent.single()
        assertEquals(RpcCommand.GetState, sent.command)
        assertEquals(FakeEngine.SESSION, sent.sessionId)
        assertNotNull("必须自带 requestId，否则无法把应答对上号", sent.id)

        // 60 个字符 × 8 键 = 远超 500，用来把截断这条语义撑出来
        rig.engine.emit(probeResponse(id = sent.id, keys = 8))
        runCurrent()

        val done = rig.vm.probe.value as ProbeState.Done
        assertEquals(FakeEngine.SESSION, done.sessionId)
        assertTrue("success 原样透传", done.success)
        assertEquals("§3.4 原文：只显示前 500 字", 500, done.text.length)
        assertTrue("被截断要说出来，别让人以为看到了全部", done.truncated)
    }

    // ── t02 无活会话 / 无总线：不发命令，卡片直接说原因 ──────────────────────────

    @Test
    fun `t02 without a live session the probe refuses to send`() = runTest {
        val rig = rig() // 引擎里一条会话都没有

        rig.vm.runProbe()

        assertTrue(rig.engine.sent.isEmpty())
        val state = rig.vm.probe.value as ProbeState.Unavailable
        assertEquals(SettingsStrings.PROBE_NO_SESSION, state.message)
        assertEquals("没有会话时重试也没意义", false, state.retryable)
    }

    @Test
    fun `t02b a live session whose bus is gone says so`() = runTest {
        val engine = FakeEngine()
        // 「ghost」有状态但没有总线（假体只给自己的 sessionId 发总线）= 会话已被回收的形状
        engine.setState("ghost", SessionState.Ready)
        val rig = rig(engine = engine)

        rig.vm.runProbe()

        assertTrue(rig.engine.sent.isEmpty())
        val state = rig.vm.probe.value as ProbeState.Unavailable
        assertEquals(SettingsStrings.PROBE_NO_BUS, state.message)
        assertEquals(false, state.retryable)
    }

    @Test
    fun `t02c spawning counts as live so the probe can still be sent`() = runTest {
        val engine = FakeEngine()
        engine.setState(FakeEngine.SESSION, SessionState.Spawning)
        val rig = rig(engine = engine)

        rig.vm.runProbe()

        assertEquals(RpcCommand.GetState, rig.engine.sent.single().command)
        assertEquals(ProbeState.Running, rig.vm.probe.value)
    }

    // ── t03 发送阶段就炸（无活动进程）：不卡 Running ─────────────────────────────

    @Test
    fun `t03 a local send failure surfaces the reason instead of a spinning card`() = runTest {
        val engine = liveEngine()
        engine.sendError = IllegalStateException("会话没有活动进程")
        val rig = rig(engine = engine)

        rig.vm.runProbe()

        val state = rig.vm.probe.value as ProbeState.Unavailable
        assertTrue("原因要带出来：" + state.message, state.message.startsWith(SettingsStrings.PROBE_SEND_FAILED))
        assertTrue("这种失败值得再点一次", state.retryable)
    }

    // ── t04 应答超时：5 秒后落 Unavailable（protocol.md 保证有 response，但进程死了不会来）──

    @Test
    fun `t04 the probe times out instead of hanging forever`() = runTest {
        val rig = rig(engine = liveEngine())
        rig.vm.runProbe()
        assertEquals(ProbeState.Running, rig.vm.probe.value)

        advanceTimeBy(SettingsViewModel.PROBE_TIMEOUT_MS)
        runCurrent()

        val state = rig.vm.probe.value as ProbeState.Unavailable
        assertEquals(SettingsStrings.PROBE_TIMEOUT, state.message)
        assertTrue(state.retryable)
    }

    // ── t05 只认自己那条 id：别人的应答不算数 ────────────────────────────────────

    @Test
    fun `t05 a response for a different request id is ignored`() = runTest {
        val rig = rig(engine = liveEngine())
        rig.vm.runProbe()
        val requestId = rig.engine.sent.single().id

        rig.engine.emit(probeResponse(id = "not-$requestId"))
        runCurrent()
        assertEquals("对不上号就不许收口", ProbeState.Running, rig.vm.probe.value)

        rig.engine.emit(probeResponse(id = requestId))
        runCurrent()
        assertTrue(rig.vm.probe.value is ProbeState.Done)
    }

    // ── t06 success=false：显示 error 文本，卡片仍算拿到应答 ──────────────────────

    @Test
    fun `t06 a failed response shows pi's error text`() = runTest {
        val rig = rig(engine = liveEngine())
        rig.vm.runProbe()

        rig.engine.emit(probeResponse(id = rig.engine.sent.single().id, success = false, error = "engine busy"))
        runCurrent()

        val done = rig.vm.probe.value as ProbeState.Done
        assertTrue("失败也是一条 Done（UI 用颜色区分，不是 Unavailable）", !done.success)
        assertEquals("engine busy", done.text)
        assertEquals(false, done.truncated)
    }

    // ── t07 memTier 写回：仓库收到 + 提示说清生效时机（§4.4 要求的文案）────────────

    @Test
    fun `t07 writing memTier goes to the repository and says when it takes effect`() = runTest {
        val rig = rig(settings = AppSettings(memTier = MemTier.MID))

        rig.vm.setMemTier(MemTier.HIGH)
        runCurrent()

        assertEquals(listOf(FakeSettingsRepository.Write("memTier", MemTier.HIGH)), rig.repository.writes)
        assertEquals(MemTier.HIGH, rig.vm.settings.value.memTier)
        val notice = rig.vm.notice.value!!
        assertTrue("§4.4：必须写明「下次启动会话生效」：" + notice, notice.contains("下次启动会话生效"))
        assertTrue(notice.contains("HIGH"))

        rig.vm.consumeNotice()
        assertNull(rig.vm.notice.value)
    }

    @Test
    fun `t07b a write failure says nothing changed`() = runTest {
        val repository = FakeSettingsRepository(AppSettings(memTier = MemTier.MID))
        repository.writeError = IllegalStateException("disk full")
        repository.applyToSnapshot = false
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = SettingsViewModel(repository, FakeEngine(), FakeRuntimeInfo())

        vm.setMemTier(MemTier.LOW)
        runCurrent()

        assertEquals(SettingsStrings.SETTING_WRITE_FAILED, vm.notice.value)
        assertEquals("写失败时快照不许动", MemTier.MID, vm.settings.value.memTier)
    }

    // ── t08 并行上限：VM 夹紧后才交给仓库（stepper 之外的第二道门）────────────────

    @Test
    fun `t08 the parallel stepper is clamped before it reaches the repository`() = runTest {
        val rig = rig(settings = AppSettings(maxParallelSessions = 2))

        rig.vm.setMaxParallelSessions(9)
        runCurrent()
        assertEquals(listOf(FakeSettingsRepository.Write("maxParallelSessions", 4)), rig.repository.writes)
        assertEquals(
            SettingsStrings.parallelClamped(4),
            rig.vm.notice.value,
        )
        rig.vm.consumeNotice()

        rig.vm.setMaxParallelSessions(3)
        runCurrent()
        assertEquals(2, rig.repository.writes.size)
        assertNull("合法值不给「已夹紧」提示", rig.vm.notice.value)

        rig.vm.setMaxParallelSessions(0)
        runCurrent()
        assertEquals(3, rig.repository.writes.size)
        assertEquals(
            "越界值交给仓库再夹一次",
            1,
            rig.repository.writes.last().value,
        )
    }

    // ── t09 版本卡片两档发布：marker 先到、清单补写 ──────────────────────────────

    @Test
    fun `t09 the version card publishes the marker first and the manifest after`() = runTest {
        val info = FakeRuntimeInfo(
            marker = RuntimeInfo("2.5.0-test", "1.0.4-r7", null, manifestPending = true),
            manifest = ManifestRead.Present("1.0.4-r8"),
            readPauseMs = 1L,
        )
        val rig = rig(info = info)

        // 两次挂起（marker 一次、manifest 一次），中间档必须真的能被观察到
        runCurrent()
        assertNull("还没读到 marker 之前，卡片是空的（不是半成品）", rig.vm.runtimeInfoState.value)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(
            "marker 落的第一版还带着「清单读取中」",
            RuntimeInfo("2.5.0-test", "1.0.4-r7", null, manifestPending = true),
            rig.vm.runtimeInfoState.value,
        )

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(
            "清单补写后 pending 旗落下",
            RuntimeInfo("2.5.0-test", "1.0.4-r7", "1.0.4-r8", manifestPending = false),
            rig.vm.runtimeInfoState.value,
        )
        assertEquals("两档不一致 → 下次进聊天页会重装", false, rig.vm.runtimeInfoState.value!!.matches)

        rig.vm.refreshRuntimeInfo()
        advanceTimeBy(1L)
        runCurrent()
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, info.readCalls.get())
        assertEquals(2, info.manifestCalls.get())
    }

    @Test
    fun `t09b a marker blowup still lands a readable card rather than a crash`() = runTest {
        val info = FakeRuntimeInfo(readPauseMs = 1L).apply { boom = IllegalStateException("selinux") }
        val rig = rig(info = info)

        runCurrent()
        advanceTimeBy(1L)
        runCurrent()

        // read() 抛 → 用兜底那版（UNKNOWN + 未安装），然后清单仍按假体的脚本补上
        assertEquals(RuntimeInfo.UNKNOWN, rig.vm.runtimeInfoState.value!!.appVersion)
        assertNull(rig.vm.runtimeInfoState.value!!.installedRuntimeVersion)
        assertEquals("1.0.4-r7", rig.vm.runtimeInfoState.value!!.bundledRuntimeVersion)
    }

    // ── t10 默认模型：显示走仓库流，写口留给 P3 ─────────────────────────────────

    @Test
    fun `t10 the default model is read from the repository and writable on demand`() = runTest {
        val rig = rig(settings = AppSettings(defaultModel = "anthropic/claude-sonnet-4"))
        assertEquals("anthropic/claude-sonnet-4", rig.vm.settings.value.defaultModel)

        rig.vm.setDefaultModel(null)
        assertEquals(listOf(FakeSettingsRepository.Write("defaultModel", null)), rig.repository.writes)
        assertNull(rig.vm.settings.value.defaultModel)
    }

    // ── t11 manifest 纯函数：不碰 assets 也能钉住解析形状 ────────────────────────

    @Test
    fun `t11 the bundled manifest version is parsed defensively`() {
        val json = """{"runtimeVersion":"1.0.4-r7","files":[{"path":"bin/node","sha256":"aa"}],"symlinks":[]}"""
        assertEquals("1.0.4-r7", AndroidRuntimeInfo.manifestVersionOf(json))
        assertNull("坏 JSON 不抛", AndroidRuntimeInfo.manifestVersionOf("{oops"))
        assertNull("空版本号等于没有", AndroidRuntimeInfo.manifestVersionOf("""{"runtimeVersion":"","files":[]}"""))
        assertNull("缺字段等于没有", AndroidRuntimeInfo.manifestVersionOf("""{"files":[]}"""))
        assertEquals(
            "清单未来加字段不破坏旧宿主（与 core/engine 的 ignoreUnknownKeys 同一套）",
            "9.9.9",
            AndroidRuntimeInfo.manifestVersionOf("""{"runtimeVersion":"9.9.9","files":[],"brandNew":{"a":1}}"""),
        )
    }

    // ── t12 事件流里的非 Response 不该把收集协程掀掉 ─────────────────────────────

    @Test
    fun `t12 unrelated events on the bus do not settle the probe`() = runTest {
        val rig = rig(engine = liveEngine())
        rig.vm.runProbe()

        rig.engine.emit(PiEvent.TurnStart)
        rig.engine.emit(PiEvent.AgentStart)
        runCurrent()
        assertEquals(ProbeState.Running, rig.vm.probe.value)

        val requestId = rig.engine.sent.single().id!!
        val response = PiEvent.Response(id = requestId, command = "get_state", success = true, data = null)
        rig.engine.emit(response)
        runCurrent()

        val done = rig.vm.probe.value as ProbeState.Done
        assertEquals("data 为空时退回 command 名，卡片不能是空白", "get_state", done.text)
    }
}
