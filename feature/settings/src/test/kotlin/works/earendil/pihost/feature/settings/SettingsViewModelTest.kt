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
import works.earendil.pihost.core.engine.settings.ModelCredentialStore.ProviderEntry
import works.earendil.pihost.core.engine.settings.RelayEndpointStore.RelayEntry
import works.earendil.pihost.core.engine.settings.RelayModel
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
        val credentials: FakeModelCredentialSource,
        val relays: FakeRelayEndpointSource,
        val diagnostics: FakeDiagnosticsSource,
    )

    private fun TestScope.rig(
        settings: AppSettings = AppSettings(),
        engine: FakeEngine = FakeEngine(),
        info: FakeRuntimeInfo = FakeRuntimeInfo(),
        credentials: FakeModelCredentialSource = FakeModelCredentialSource(),
        relays: FakeRelayEndpointSource = FakeRelayEndpointSource(),
        diagnostics: FakeDiagnosticsSource = FakeDiagnosticsSource(),
    ): Rig {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repository = FakeSettingsRepository(settings)
        return Rig(
            SettingsViewModel(repository, engine, info, credentials, relays, diagnostics),
            repository,
            engine,
            info,
            credentials,
            relays,
            diagnostics,
        )
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
        val vm = SettingsViewModel(repository, FakeEngine(), FakeRuntimeInfo(), FakeModelCredentialSource(), FakeRelayEndpointSource(), FakeDiagnosticsSource())

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

    // ── t13 凭据（P2G §4.2）：进屏读一次 + 保存的成功面 ───────────────────────────

    @Test
    fun `t13 entering the screen reads the credential list once`() = runTest {
        val stored = listOf(ProviderEntry("deepseek", true), ProviderEntry("anthropic", false))
        val rig = rig(credentials = FakeModelCredentialSource(stored))

        assertEquals("构造里的 refreshCredentials 必须已经跑完（Unconfined main）", stored, rig.vm.credentials.value)
        assertEquals(1, rig.credentials.snapshotCalls.get())
    }

    @Test
    fun `t14 a saved credential refreshes the list, clears the draft and explains the timing`() = runTest {
        val rig = rig()

        rig.vm.onCredentialDraftChange("sk-secret-value")
        assertEquals("sk-secret-value", rig.vm.credentialDraft.value)

        rig.vm.saveCredential("deepseek", "sk-secret-value")
        runCurrent()

        assertEquals(listOf(FakeModelCredentialSource.Save("deepseek", "sk-secret-value")), rig.credentials.saves.toList())
        assertEquals(listOf(ProviderEntry("deepseek", keyConfigured = true)), rig.vm.credentials.value)
        assertEquals("§4.2：保存成功后清空输入框", "", rig.vm.credentialDraft.value)
        assertEquals("§4.2 原文那条 notice", SettingsStrings.CREDENTIAL_SAVED, rig.vm.notice.value)
        assertTrue(
            "生效时机必须写清「新会话」",
            rig.vm.notice.value!!.contains("新会话生效") && rig.vm.notice.value!!.contains("停止后重开"),
        )
    }

    @Test
    fun `t15 a blank key never reaches the store`() = runTest {
        val rig = rig()

        for (blank in listOf("", "   ", "\t")) {
            rig.vm.onCredentialDraftChange(blank)
            rig.vm.saveCredential("deepseek", blank)
            runCurrent()
        }

        assertTrue("§4.2：空 key 保存不触发写入", rig.credentials.saves.isEmpty())
        assertEquals(SettingsStrings.CREDENTIAL_BLANK_KEY, rig.vm.notice.value)
        assertEquals("没写成功就不该冒出已配置行", emptyList<ProviderEntry>(), rig.vm.credentials.value)
    }

    @Test
    fun `t16 a store blowup surfaces the reason and keeps the draft`() = runTest {
        val credentials = FakeModelCredentialSource()
        val rig = rig(credentials = credentials)

        credentials.boom = java.io.IOException("无法创建目录：/x/.pi/agent")
        rig.vm.onCredentialDraftChange("sk-x")
        rig.vm.saveCredential("deepseek", "sk-x")
        runCurrent()

        val notice = rig.vm.notice.value!!
        assertTrue("失败要带原因：$notice", notice.startsWith("写入失败：") && notice.contains("无法创建目录"))
        assertEquals("写失败时输入框不清空，让人原地重试", "sk-x", rig.vm.credentialDraft.value)
        assertEquals(emptyList<ProviderEntry>(), rig.vm.credentials.value)

        rig.vm.consumeNotice()
        credentials.boom = null
        rig.vm.clearCredential("groq")
        runCurrent()
        assertEquals(listOf("groq"), credentials.clears.toList())
        assertTrue("清除也要说生效时机", rig.vm.notice.value!!.contains("新会话生效"))
    }

    @Test
    fun `t17 a failed first read shows an empty list instead of a notice`() = runTest {
        val credentials = FakeModelCredentialSource()
        credentials.boom = java.io.IOException("读不动")

        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = SettingsViewModel(FakeSettingsRepository(), FakeEngine(), FakeRuntimeInfo(), credentials, FakeRelayEndpointSource(), FakeDiagnosticsSource())

        assertEquals("进屏读失败 → 空列表（不是半成品）", emptyList<ProviderEntry>(), vm.credentials.value)
        assertNull("读失败不弹 notice：列表空着已经说明问题", vm.notice.value)

        credentials.boom = null
        vm.refreshCredentials()
        runCurrent()
        assertEquals("重读口是有效的", 2, credentials.snapshotCalls.get())
    }

    // ── t18…t25 自定义中转（P2H §3.2 的 VM 面）─────────────────────────────────────

    private fun relay(providerId: String = "my-relay", models: List<RelayModel> = listOf(RelayModel("deepseek-chat"))) =
        RelayEntry(providerId, "https://relay.example.com/v1", "openai-completions", models, true)

    @Test
    fun `t18 entering the screen reads both the relay rows and the default pointer`() = runTest {
        val relays = FakeRelayEndpointSource(initial = listOf(relay()), initialDefault = "my-relay")
        val rig = rig(relays = relays)

        assertEquals(listOf("my-relay"), rig.vm.relays.value.map { it.providerId })
        assertEquals("徽标的数据源必须是 settings.json 那一次读", "my-relay", rig.vm.defaultRelay.value)
        assertEquals(1, relays.snapshotCalls.get())
        assertEquals(1, relays.defaultCalls.get())
    }

    @Test
    fun `t19 saving a relay refreshes rows, clears the text drafts and keeps the chosen api`() = runTest {
        val rig = rig()
        rig.vm.onRelayDraftChange {
            it.copy(providerId = "my-relay", baseUrl = "https://relay.example.com/v1", models = "deepseek-chat", apiKey = "sk-x", api = "pi-messages")
        }

        rig.vm.saveRelay("my-relay", "https://relay.example.com/v1", "pi-messages", listOf(RelayModel("deepseek-chat")), "sk-x")
        runCurrent()

        assertEquals(listOf("my-relay"), rig.vm.relays.value.map { it.providerId })
        val draft = rig.vm.relayDraft.value
        assertEquals("四个文本框全清空", listOf("", "", "", ""), listOf(draft.providerId, draft.baseUrl, draft.models, draft.apiKey))
        assertEquals("api 是选择器，不跟着清空", "pi-messages", draft.api)
        assertEquals(SettingsStrings.RELAY_SAVED, rig.vm.notice.value)
    }

    @Test
    fun `t20 a blank relay key is converted to null so the stored key is reused`() = runTest {
        val relays = FakeRelayEndpointSource(initial = listOf(relay()))
        val rig = rig(relays = relays)

        for (blank in listOf(null, "", "   ", "\t")) {
            rig.vm.saveRelay("my-relay", "https://new.example.com/v1", "openai-completions", listOf(RelayModel("gpt-4o")), blank)
            runCurrent()
        }

        assertTrue(relays.saves.isNotEmpty())
        assertTrue("空白 key 一律换算成 null", relays.saves.all { it.apiKey == null })
        assertTrue("沿用下来的行仍然「已配置」", rig.vm.relays.value.single().keyConfigured)
        assertEquals("https://new.example.com/v1", rig.vm.relays.value.single().baseUrl)
    }

    @Test
    fun `t21 the three blank gates never reach the source`() = runTest {
        val relays = FakeRelayEndpointSource()
        val rig = rig(relays = relays)

        rig.vm.saveRelay("", "https://r.example.com/v1", "openai-completions", listOf(RelayModel("m1")), "sk-x")
        runCurrent()
        assertEquals(SettingsStrings.RELAY_BLANK_ID, rig.vm.notice.value)

        rig.vm.saveRelay("my-relay", "   ", "openai-completions", listOf(RelayModel("m1")), "sk-x")
        runCurrent()
        assertEquals(SettingsStrings.RELAY_BLANK_URL, rig.vm.notice.value)

        rig.vm.saveRelay("my-relay", "https://r.example.com/v1", "openai-completions", emptyList(), "sk-x")
        runCurrent()
        assertEquals(SettingsStrings.RELAY_BLANK_MODELS, rig.vm.notice.value)

        assertEquals("三道门一次都不许打到磁盘", emptyList<FakeRelayEndpointSource.Save>(), relays.saves)
        assertEquals("只有进屏那一次读取", 1, relays.snapshotCalls.get())
    }

    @Test
    fun `t22 pinning the default re-reads settings json instead of trusting the argument`() = runTest {
        val relays = FakeRelayEndpointSource(initial = listOf(relay("relay-a"), relay("relay-b")))
        val rig = rig(relays = relays)

        rig.vm.setRelayDefault("relay-a", "deepseek-chat")
        runCurrent()

        assertEquals(listOf(FakeRelayEndpointSource.Pin("relay-a", "deepseek-chat")), relays.pins.toList())
        assertEquals("relay-a", rig.vm.defaultRelay.value)
        assertEquals(SettingsStrings.RELAY_DEFAULT_SET, rig.vm.notice.value)

        // 参数说一套、磁盘回一套时以磁盘为准：假体的 defaultProvider 仍然返回 relay-a，
        // 这里改成「钉不存在的 id」走空白门，VM 不许发写入
        rig.vm.setRelayDefault("ghost", "")
        runCurrent()
        assertEquals(1, relays.pins.size)
        assertEquals(SettingsStrings.RELAY_NO_MODEL, rig.vm.notice.value)
    }

    @Test
    fun `t23 clearing a relay drops the row and the badge pointing at it`() = runTest {
        val relays = FakeRelayEndpointSource(initial = listOf(relay("relay-a"), relay("relay-b")), initialDefault = "relay-a")
        val rig = rig(relays = relays)

        rig.vm.clearRelay("relay-a")
        runCurrent()

        assertEquals(listOf("relay-b"), rig.vm.relays.value.map { it.providerId })
        assertNull("悬空默认不能亮着", rig.vm.defaultRelay.value)
        assertTrue(rig.vm.notice.value!!.contains("relay-a"))
        // 空白 id 直接吞掉：既不写磁盘也不留一条莫名其妙的 notice
        val notices = rig.vm.notice.value
        rig.vm.clearRelay("  ")
        runCurrent()
        assertEquals(notices, rig.vm.notice.value)
        assertEquals(listOf("relay-a"), relays.clears.toList())
    }

    @Test
    fun `t24 a default pointer to a vanished row is filtered out on refresh`() = runTest {
        // 手改磁盘能造出这个脏状态：settings.json 指着一条 models.json 里已经没有的 provider。
        // 假体不演它（那是 core 侧的语义），所以这里用一条匿名 source 直接喂给 VM。
        val dirty = object : RelayEndpointSource {
            override suspend fun snapshot(): List<RelayEntry> = listOf(relay("still-here"))
            override suspend fun defaultProvider(): String? = "ghost-relay"
            override suspend fun save(providerId: String, baseUrl: String, api: String, models: List<RelayModel>, apiKey: String?) = Unit
            override suspend fun clear(providerId: String) = Unit
            override suspend fun setDefault(providerId: String, modelId: String) = Unit
        }
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = SettingsViewModel(FakeSettingsRepository(), FakeEngine(), FakeRuntimeInfo(), FakeModelCredentialSource(), dirty, FakeDiagnosticsSource())

        assertEquals(listOf("still-here"), vm.relays.value.map { it.providerId })
        assertNull("settings.json 指着一条已经不存在的行 → 徽标不该亮", vm.defaultRelay.value)
    }

    @Test
    fun `t25 a relay write failure keeps the draft and says why`() = runTest {
        val relays = FakeRelayEndpointSource()
        relays.boom = java.io.IOException("无法创建目录：/x/.pi/agent")
        val rig = rig(relays = relays)
        rig.vm.onRelayDraftChange { it.copy(providerId = "my-relay", baseUrl = "https://r.example.com/v1", models = "m1", apiKey = "sk-x") }

        rig.vm.saveRelay("my-relay", "https://r.example.com/v1", "openai-completions", listOf(RelayModel("m1")), "sk-x")
        runCurrent()

        val notice = rig.vm.notice.value!!
        assertTrue("失败要带原因：$notice", notice.startsWith("写入失败：") && notice.contains("无法创建目录"))
        val draft = rig.vm.relayDraft.value
        assertEquals("写失败时四个框都留着，让人原地重试", listOf("my-relay", "https://r.example.com/v1", "m1", "sk-x"), listOf(draft.providerId, draft.baseUrl, draft.models, draft.apiKey))
        assertEquals(emptyList<RelayEntry>(), rig.vm.relays.value)

        // 清除与钉选走同一套失败通路
        rig.vm.consumeNotice()
        relays.boom = java.io.IOException("磁盘满")
        rig.vm.clearRelay("my-relay")
        runCurrent()
        assertTrue(rig.vm.notice.value!!.contains("磁盘满"))
    }

    /** 旧那支 `parseModelIds` 的六条断言**一字不改**搬到新签名上（P2H 的逗号语义是回归面，不是新增面）。 */
    @Test
    fun `t26 parseRelayModels folds commas spaces blanks and duplicates`() {
        assertEquals(listOf(RelayModel("a"), RelayModel("b")), parseRelayModels("a, b"))
        assertEquals(listOf(RelayModel("a"), RelayModel("b")), parseRelayModels("a，b"))
        assertEquals(listOf(RelayModel("a")), parseRelayModels("  a ,,  ，a "))
        assertEquals(emptyList<RelayModel>(), parseRelayModels(""))
        assertEquals(emptyList<RelayModel>(), parseRelayModels(" , ， "))
        assertEquals(listOf(RelayModel("deepseek-chat")), parseRelayModels("deepseek-chat"))
    }

    // ── t33…t36 P3B-FIX1 §3.2：`*` 语法、逆函数、编辑回填 ─────────────────────────

    /** §3.2 第 1 条的语法面：尾缀 `*` = 思考模型；`a**` 合法；只认半角 `*`；后缀在 trim 之后判定。 */
    @Test
    fun `t33 the star suffix marks a reasoning model and tolerates repeats and padding`() {
        assertEquals(
            "规格书原文那一串：三个模型、后两个带思考档",
            listOf(RelayModel("deepseek-chat", false), RelayModel("deepseek-reasoner", true), RelayModel("gpt-5", true)),
            parseRelayModels("deepseek-chat,deepseek-reasoner*,gpt-5*"),
        )
        assertEquals("a** 合法，等价于 a*", listOf(RelayModel("a", true)), parseRelayModels("a**"))
        assertEquals("空格在后缀外面也要认", listOf(RelayModel("a", true)), parseRelayModels(" a* "))
        assertEquals("全角逗号 + 全角星号：星号不是语法，id 原样留着", listOf(RelayModel("a＊")), parseRelayModels("a＊"))
        assertEquals("星号在中间不算后缀", listOf(RelayModel("a*b")), parseRelayModels("a*b"))
        assertEquals("光一个星号没有 id → 丢掉", emptyList<RelayModel>(), parseRelayModels("*"))
        assertEquals("裸星号与空白混在一起也只剩无效项", emptyList<RelayModel>(), parseRelayModels("*, ,* *"))
    }

    /** §3.2 第 1 条的第二道门语义：同 id 混后缀 → reasoning **取或**（顺序 = 首次出现）。 */
    @Test
    fun `t34 duplicate ids merge with OR instead of letting false swallow true`() {
        assertEquals(
            "先 false 后 true 也要是 true",
            listOf(RelayModel("a", true), RelayModel("b", false)),
            parseRelayModels("a,b,a*"),
        )
        assertEquals(
            "先 true 后 false 同样要是 true",
            listOf(RelayModel("a", true)),
            parseRelayModels("a*,a"),
        )
        assertEquals("两条都 false 保持 false", listOf(RelayModel("a", false)), parseRelayModels("a,a"))
    }

    /** §3.2 第 2 条「往返无损」的函数级证据：`parse(relay(x)) == x`，且反向文本里不带空格。 */
    @Test
    fun `t35 the draft text and the parse are exact inverses`() {
        val stored = listOf(RelayModel("deepseek-chat"), RelayModel("deepseek-reasoner", true), RelayModel("gpt-5", true))
        val text = relayModelsToText(stored)

        assertEquals("回填文本的写法也是定的（半角逗号、无空格）", "deepseek-chat,deepseek-reasoner*,gpt-5*", text)
        assertEquals("解析回去必须与存进去的一字不差", stored, parseRelayModels(text))
        assertEquals(emptyList<RelayModel>(), parseRelayModels(relayModelsToText(emptyList())))
        assertEquals("空清单渲染成空串，不是 null 也不是空格", "", relayModelsToText(emptyList()))
        assertEquals("单项不带思考档时不写后缀", "solo", relayModelsToText(listOf(RelayModel("solo"))))
    }

    /** §3.2 第 2 条的 VM 面：点「编辑」播种草稿——`*` 跟着回来、密钥**不**回来。 */
    @Test
    fun `t36 editing a relay seeds the draft with the star and never the key`() = runTest {
        val relays = FakeRelayEndpointSource(
            initial = listOf(
                RelayEntry(
                    "my-relay",
                    "https://relay.example.com/v1",
                    "anthropic-messages",
                    listOf(RelayModel("deepseek-chat"), RelayModel("deepseek-reasoner", true)),
                    keyConfigured = true,
                ),
            ),
        )
        val rig = rig(relays = relays)
        rig.vm.onRelayDraftChange { it.copy(providerId = "别家的草稿", models = "别家的模型") }

        rig.vm.editRelay("my-relay")

        val draft = rig.vm.relayDraft.value
        assertEquals("四个字段都播种回来（api 也跟上这一行的协议）", "my-relay", draft.providerId)
        assertEquals("https://relay.example.com/v1", draft.baseUrl)
        assertEquals(
            "思考尾缀必须原样回填——否则下一次保存把 reasoning 写没了",
            "deepseek-chat,deepseek-reasoner*",
            draft.models,
        )
        assertEquals("anthropic-messages", draft.api)
        assertEquals("§2 红线：密钥永不回显，草稿里是空串", "", draft.apiKey)
        assertEquals("播种不发任何写入", emptyList<FakeRelayEndpointSource.Save>(), relays.saves)

        // 播种的那份文本再解析，必须与磁盘上的那份清单相等（往返闭环，用例 t35 是它的函数级证明）
        assertEquals(
            "回填 → 解析 == 磁盘",
            listOf(RelayModel("deepseek-chat"), RelayModel("deepseek-reasoner", true)),
            parseRelayModels(draft.models),
        )
    }

    /** 编辑一条已经不存在的行（列表刚被别处清掉）：静默不动草稿，也不发写入。 */
    @Test
    fun `t37 editing a vanished row leaves the draft alone`() = runTest {
        val relays = FakeRelayEndpointSource(initial = listOf(relay()))
        val rig = rig(relays = relays)
        rig.vm.onRelayDraftChange { it.copy(providerId = "手填的", models = "m*") }

        rig.vm.editRelay("ghost-relay")

        val draft = rig.vm.relayDraft.value
        assertEquals("列表里没有这一行 → 草稿一个字段都不许改", "手填的", draft.providerId)
        assertEquals("m*", draft.models)
        assertEquals(emptyList<FakeRelayEndpointSource.Save>(), relays.saves)
    }

    // ── P3A §3.5 诊断面（崩溃报告列表 / 清空 / 复制） ───────────────────────────

    @Test
    fun `t27 entering the screen reads the crash list once and keeps the source order`() = runTest {
        val diagnostics = FakeDiagnosticsSource(
            initial = listOf(summary("crash-2000.txt", 2_048), summary("crash-1000.txt", 512)),
        )
        val rig = rig(diagnostics = diagnostics)

        assertEquals("进屏读一次（与 credentials/relays 同一条 init 通路）", 1, diagnostics.listCalls.get())
        assertEquals(
            "顺序由 source 定（新的在前），VM 不重排",
            listOf("crash-2000.txt", "crash-1000.txt"),
            rig.vm.crashReports.value.map { it.fileName },
        )
        assertEquals(2_048L, rig.vm.crashReports.value.first().sizeBytes)
        assertEquals(1_700_000_000_000L, rig.vm.crashReports.value.first().modified.toEpochMilli())

        rig.vm.refreshCrashReports()
        runCurrent()
        assertEquals("手动重读口是有效的", 2, diagnostics.listCalls.get())
    }

    @Test
    fun `t28 clearing reports the deleted count and empties the list`() = runTest {
        val diagnostics = FakeDiagnosticsSource(
            initial = listOf(summary("crash-1.txt", 10), summary("crash-2.txt", 20)),
        )
        val rig = rig(diagnostics = diagnostics)
        assertEquals(2, rig.vm.crashReports.value.size)

        rig.vm.clearCrashReports()
        runCurrent()

        assertTrue("回执说的是**实际删掉的份数**：${rig.vm.notice.value}", rig.vm.notice.value!!.contains("2 份"))
        assertEquals(emptyList<CrashReportSummary>(), rig.vm.crashReports.value)
        assertEquals(1, diagnostics.clearCalls.get())
        assertEquals("清完由 VM 自己重读列表，不靠 UI 再点一次", 2, diagnostics.listCalls.get())
    }

    @Test
    fun `t29 readLatestCrash covers present, absent and unreadable`() = runTest {
        val diagnostics = FakeDiagnosticsSource(initial = listOf(summary("crash-9.txt", 9)), body = "time=…\njava.lang.IllegalStateException: boom")
        val rig = rig(diagnostics = diagnostics)

        assertEquals("有一份可读 → 原样交回正文", diagnostics.body, rig.vm.readLatestCrash())

        diagnostics.body = null
        assertNull("一份都没有 → null（不是抛、不是空串）", rig.vm.readLatestCrash())

        diagnostics.boom = java.io.IOException("读不动")
        assertNull("读口自己抛也被 VM 的 runCatching 兜住", rig.vm.readLatestCrash())
        assertEquals("三次都只问 source，VM 不自己碰磁盘", 3, diagnostics.latestCalls.get())
    }

    @Test
    fun `t30 a failed list read leaves an empty list and says nothing`() = runTest {
        val diagnostics = FakeDiagnosticsSource(initial = listOf(summary("crash-1.txt", 1)))
        diagnostics.boom = java.io.IOException("读不动")
        val rig = rig(diagnostics = diagnostics)

        assertEquals("读失败 → 空列表（不是半成品）", emptyList<CrashReportSummary>(), rig.vm.crashReports.value)
        assertNull("读失败不弹 notice：这一页是诊断页，空着已经说明问题", rig.vm.notice.value)
        assertNull(rig.vm.readLatestCrash())
    }

    @Test
    fun `t31 a failed clear keeps the list and reports the reason`() = runTest {
        val diagnostics = FakeDiagnosticsSource(
            initial = listOf(summary("crash-1.txt", 1), summary("crash-2.txt", 2)),
        )
        val rig = rig(diagnostics = diagnostics)

        diagnostics.boom = java.io.IOException("只读文件系统")
        rig.vm.clearCrashReports()
        runCurrent()

        val notice = rig.vm.notice.value!!
        assertTrue("清失败要带原因：$notice", notice.startsWith("清除失败：") && notice.contains("只读文件系统"))
        assertEquals("失败时列表原样留着，让人原地重试", 2, rig.vm.crashReports.value.size)

        diagnostics.boom = null
        rig.vm.clearCrashReports()
        runCurrent()
        assertEquals("恢复后同一颗按钮真的清掉了", emptyList<CrashReportSummary>(), rig.vm.crashReports.value)
        assertTrue(rig.vm.notice.value!!.contains("2 份"))
    }

    @Test
    fun `t32 both copy outcomes get their notice text from the VM`() = runTest {
        val rig = rig()

        rig.vm.crashCopied()
        assertEquals(SettingsStrings.DIAGNOSTICS_COPIED, rig.vm.notice.value)
        rig.vm.consumeNotice()
        assertNull("一次性提示消费后归零（与其余 section 同一条通路）", rig.vm.notice.value)

        rig.vm.crashCopyMissed()
        assertEquals(SettingsStrings.DIAGNOSTICS_NONE_NOTICE, rig.vm.notice.value)
    }

    /** 一行崩溃报告的假体形态：文件名带毫秒，大小与 mtime 都是定值（断言点不在时间上）。 */
    private fun summary(fileName: String, sizeBytes: Long) =
        CrashReportSummary(fileName, sizeBytes, java.time.Instant.ofEpochMilli(1_700_000_000_000L))
}
