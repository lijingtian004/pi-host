package works.earendil.pihost

import android.Manifest
import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import works.earendil.pihost.core.engine.AssetInstaller
import works.earendil.pihost.core.engine.AssetSource
import works.earendil.pihost.core.engine.BundledAssets
import works.earendil.pihost.core.engine.EngineRuntime
import works.earendil.pihost.core.engine.InstallProgress
import works.earendil.pihost.core.engine.InstallResult
import works.earendil.pihost.core.engine.InstalledRuntime
import works.earendil.pihost.core.engine.PiEngineService
import works.earendil.pihost.core.engine.SessionState
import works.earendil.pihost.core.engine.SessionSupervisor
import works.earendil.pihost.core.engine.engineStartIntent
import works.earendil.pihost.core.engine.engineStopIntent
import works.earendil.pihost.core.rpc.PiEvent
import works.earendil.pihost.core.rpc.PiJsonOut
import works.earendil.pihost.core.rpc.RpcCommand
import java.util.concurrent.atomic.AtomicInteger

/**
 * P2C 壳：把「安装运行时 → 起 FGS 引擎 → spawn + 握手 → 探一次 RPC → 停机」全链路摆到屏上。
 *
 * 保持纯 View（[android.app.Activity] + [LinearLayout] + [Button] + [TextView]，
 * 不引 androidx/Compose，方案 §13 P2 出口判据）；真正的导航与进度 UI 属 P3/P2D。
 *
 * 线程纪律（与 [PiEngineService] 同一套，为了让 Robolectric 能确定性推进）：
 * 耗时动作一律 `EngineRuntime.scope`（Dispatchers.Default 真实线程），
 * 回 UI 只经 [mainHandler] post；屏上状态的渲染集中在 [renderState]，
 * 由 500ms 轮询驱动，测试直接调 [renderState] 断言，不依赖轮询时序。
 */
class MainActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var probeJob: Job? = null
    private val probeCounter = AtomicInteger(0)

    internal lateinit var versionView: TextView
        private set
    internal lateinit var statusView: TextView
        private set
    internal lateinit var startButton: Button
        private set
    internal lateinit var probeButton: Button
        private set
    internal lateinit var stopButton: Button
        private set

    /** 安装协程句柄：装完/失败前「启动引擎」置灰防重入。 */
    private var installJob: Job? = null

    /** 终局已定标志：置位后不再采纳进度快照（见 [installThenStart]）。 */
    @Volatile
    private var installFinished = false

    /**
     * 最近一次安装失败的原因；null = 没有失败。
     * 这是「安装失败」区别于「未安装/空闲」的唯一状态位，重试开始时清零。
     */
    internal var installFailure: String? = null
        private set

    /**
     * 资产来源工厂：默认真 APK assets；测试注入坏来源即可走**真实** AssetInstaller
     * 失败路径（installer 本体在冻结区，不 mock，只换它的输入）。
     */
    internal var assetSourceFactory: (Context) -> AssetSource = { BundledAssets(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val margin = dp(16f)

        versionView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(margin, margin, margin, 0)
        }
        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(margin, margin, margin, margin)
        }
        startButton = button(BUTTON_START) { onStartEngine() }
        probeButton = button(BUTTON_PROBE) { onProbeRpc() }
        stopButton = button(BUTTON_STOP) { onStopEngine() }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(margin, margin, margin, margin)
                addView(versionView)
                addView(startButton)
                addView(probeButton)
                addView(stopButton)
                addView(statusView)
            },
        )
        renderVersion()
        renderState()
        mainHandler.postDelayed(poll, POLL_INTERVAL_MS)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        probeJob?.cancel()
        probeJob = null
        installJob?.cancel()
        installJob = null
        super.onDestroy()
    }

    /** 500ms 轮询渲染。显式声明类型（Runnable 里自引用 `poll` 会让类型推断递归）。 */
    private val poll: Runnable = object : Runnable {
        override fun run() {
            renderState()
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    // ── 三个动作 ───────────────────────────────────────────────────────────────

    /**
     * 启动引擎：需要时装运行时（首启 50MB 级解包，走后台线程 + 进度回显），
     * 然后 initialize → 申请通知权限 → startForegroundService(ACTION_START)。
     */
    private fun onStartEngine() {
        if (InstalledRuntime.version(this) == null) {
            installThenStart()
            return
        }
        // 运行时已在（可能上一次安装失败过）→ 陈旧失败位清掉，走正常启动
        installFailure = null
        startEngine()
    }

    /**
     * 安装期间按钮置灰；**成功/失败都恢复可点**（规格书 §7：安装失败必须可重试），
     * 并把真实原因落到屏上 + `Log`（`AssetInstaller` 的失败走 `InstallResult.Failed.message`，
     * 协程外抛只是兜底，两条都要留痕，不许静默吞）。
     *
     * 进度回显与终局回显的竞态处理：`installFinished` 置位后不再采纳进度快照，
     * 免得已取消的 watcher 把「失败：xxx」刷回「解包中 n/m」。
     */
    private fun installThenStart() {
        if (installJob != null) return
        startButton.isEnabled = false
        installFailure = null
        installFinished = false
        statusView.text = STATUS_INSTALLING
        val installer = AssetInstaller(assetSourceFactory(this))
        installJob = EngineRuntime.scope.launch {
            val progressWatcher = launch {
                installer.progress.collect { progress ->
                    if (!installFinished) mainHandler.post { statusView.text = renderProgress(progress) }
                }
            }
            val outcome = runCatching { installer.ensureInstalled(this@MainActivity) }
            progressWatcher.cancel()
            installFinished = true
            mainHandler.post {
                installJob = null
                startButton.isEnabled = true
                installFailure = describeOutcome(outcome)
                renderVersion()
                if (installFailure == null) startEngine() else renderState()
            }
        }
    }

    /**
     * 把安装结果压成「null=成功 / 非 null=可显示的失败原因」。
     * 两条通路都要记：`InstallResult.Failed.message`（安装器的正常失败）与
     * 抛出的 Throwable（理论上不该发生，发生就是 bug，必须带类型名进日志与屏显）。
     */
    private fun describeOutcome(outcome: Result<InstallResult>): String? {
        val throwable = outcome.exceptionOrNull()
        if (throwable != null) {
            Log.e(TAG, "运行时安装抛出未捕获异常", throwable)
            val reason = "${throwable.javaClass.simpleName}: ${throwable.message}"
            return reason
        }
        return when (val result = outcome.getOrNull()) {
            is InstallResult.Installed -> {
                Log.i(TAG, "运行时已安装 version=${result.version}")
                null
            }
            is InstallResult.UpToDate -> {
                Log.i(TAG, "运行时版本一致，无需解包 version=${result.version}")
                null
            }
            is InstallResult.Failed -> {
                Log.e(TAG, "运行时安装失败：${result.message}")
                result.message
            }
            null -> {
                Log.e(TAG, "安装协程既没抛异常也没返回结果")
                "未知结果（安装协程未返回值）"
            }
        }
    }

    /** 初始化静态桥 → API 33+ 申请 POST_NOTIFICATIONS → 起 FGS。 */
    private fun startEngine() {
        EngineRuntime.initialize(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        startForegroundService(engineStartIntent(this))
        renderState()
    }

    /**
     * 探一次 RPC：挑一条 Ready 会话，**先订阅再发命令**（[SessionSupervisor.events] 的
     * 总线无 replay，与 PiSession 同一条纪律）。
     *
     * 不靠「Unconfined 一定先注册」这种推断来保证时序：收集协程起好后，
     * 发送侧显式等 [SessionSupervisor.eventSubscriptions] 的计数相比发起前**确实增长**（[SUBSCRIBE_TIMEOUT_MS]
     * 内等不到就报错且不发送）——这样丢应答的窗口是被验证的，不是被假设的。
     * 计数按增量比较而非「> 0」，别的服务/上一次探针的订阅者不会造成误判。
     */
    private fun onProbeRpc() {
        val supervisor = EngineRuntime.supervisor
        val sessionId = supervisor?.readySessionId()
        val bus = sessionId?.let { supervisor?.events(it) }
        if (supervisor == null || sessionId == null || bus == null) {
            statusView.text = "测试 RPC：没有活动会话（先点「启动引擎」并等就绪）"
            return
        }
        val requestId = "ui-${probeCounter.incrementAndGet()}"
        statusView.text = "测试 RPC：发送 get_state（id=$requestId）…"
        probeJob?.cancel()
        val subscriptions = supervisor.eventSubscriptions(sessionId)
            ?: run { statusView.text = "测试 RPC：会话总线不可用"; return }
        val subscribersBefore = subscriptions.value
        probeJob = EngineRuntime.scope.launch(Dispatchers.Unconfined) {
            val response = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                bus.first { event -> event is PiEvent.Response && event.id == requestId }
            }
            mainHandler.post {
                statusView.text = response?.let { "$PROBE_PREFIX${renderEvent(it)}" }
                    ?: "测试 RPC：${PROBE_TIMEOUT_MS}ms 内没有应答（timeout）"
            }
        }
        EngineRuntime.scope.launch {
            val subscribed = withTimeoutOrNull(SUBSCRIBE_TIMEOUT_MS) {
                subscriptions.first { count -> count > subscribersBefore }
            } != null
            if (!subscribed) {
                mainHandler.post {
                    statusView.text =
                        "测试 RPC：订阅未注册（${SUBSCRIBE_TIMEOUT_MS}ms 内 subscriptionCount " +
                            "仍是 $subscribersBefore），未发送命令"
                }
                return@launch
            }
            runCatching { supervisor.send(sessionId, RpcCommand.GetState, requestId) }
                .onFailure { error ->
                    Log.e(TAG, "测试 RPC 发送失败", error)
                    mainHandler.post { statusView.text = "测试 RPC 发送失败：${error.message}" }
                }
        }
    }

    private fun onStopEngine() {
        startService(engineStopIntent(this))
        renderState()
    }

    // ── 渲染 ───────────────────────────────────────────────────────────────────

    /** 首行文本必须与 P2A/P2B 的既有断言逐字一致，改造只动布局不动文案。 */
    private fun renderVersion() {
        val version = InstalledRuntime.version(this) ?: "not installed"
        versionView.text = "pi-android host (P2 shell)\nruntime: $version"
    }

    /**
     * 状态行渲染（测试直接调用，不依赖 500ms 轮询）。
     *
     * 分支顺序即优先级：**安装失败 → 未安装 → 未初始化 → 空闲 → 会话表**。
     * 安装失败排第一，是为了让「失败」与「还没装/装了没用」在屏上可区分
     * （三者文案不同，测试分别断言），并且 500ms 轮询不会把失败原因冲掉。
     */
    internal fun renderState() {
        installFailure?.let { reason ->
            statusView.text = "$STATUS_INSTALL_FAILED_PREFIX$reason$RETRY_HINT"
            return
        }
        if (InstalledRuntime.version(this) == null) {
            statusView.text = STATUS_NOT_INSTALLED
            return
        }
        val supervisor = EngineRuntime.supervisor
        if (supervisor == null) {
            statusView.text = STATUS_NOT_INITIALIZED
            return
        }
        val states = supervisor.sessionStates.value
        if (states.isEmpty()) {
            statusView.text = STATUS_IDLE
            return
        }
        statusView.text = buildString {
            append("会话 ").append(states.size).append("：")
            states.entries.joinTo(this, separator = "，") { (sessionId, state) ->
                "${sessionId.take(8)}=${describe(state)}"
            }
        }
    }

    private fun describe(state: SessionState): String = when (state) {
        SessionState.Spawning -> "启动中"
        SessionState.Ready -> "就绪"
        is SessionState.Restarting -> "重启第${state.attempt}次(退避${state.backoffMs}ms)"
        is SessionState.Failed -> "失败"
        SessionState.Stopped -> "已停止"
    }

    private fun renderProgress(progress: InstallProgress): String = when (progress) {
        InstallProgress.Idle -> STATUS_INSTALLING
        is InstallProgress.Extracting -> "解包中 ${progress.done}/${progress.total}"
        is InstallProgress.Verifying -> "校验中 ${progress.done}/${progress.total}"
        InstallProgress.Finalizing -> "收尾中（原子切换）"
        InstallProgress.Done -> "完成"
        is InstallProgress.Failed -> "失败：${progress.message}"
    }

    /**
     * 应答 → 可显示的 JSON 文本。
     *
     * **记录为偏差**（最终报告同一条）：规格书 §7 说「显示其原始 JSON 前 500 字符」，
     * 但冻结区的 [PiEvent] 是**类型化**解码结果、不保留原始行文本
     * （`RpcWire.parsePiEvent` 直接产出 data class，原文只存在于 `PiEvent.Unknown` 的 JsonObject），
     * 所以这里用 [PiJsonOut] 把同一事件**重新序列化**——形状与线格式一致
     * （`encodeDefaults=false`），但不是字节级原文。
     */
    internal fun renderEvent(event: PiEvent): String =
        PiJsonOut.encodeToString(PiEvent.serializer(), event).take(PROBE_RENDER_CHARS)

    /** 取第一条 Ready 会话（§7「测试 RPC」的选路规则）。 */
    private fun SessionSupervisor.readySessionId(): String? =
        sessionStates.value.entries.firstOrNull { it.value == SessionState.Ready }?.key

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val BUTTON_START: String = "启动引擎"
        const val BUTTON_PROBE: String = "测试 RPC"
        const val BUTTON_STOP: String = "停止引擎"

        const val STATUS_NOT_INSTALLED: String = "运行时未安装"
        const val STATUS_NOT_INITIALIZED: String = "引擎未初始化"
        const val STATUS_IDLE: String = "引擎空闲（0 个会话）"
        const val STATUS_INSTALLING: String = "解包中"
        const val STATUS_INSTALL_FAILED_PREFIX: String = "运行时安装失败："
        const val RETRY_HINT: String = "（可点「启动引擎」重试）"

        /** 日志 tag：安装/发送的失败原因必须在 logcat 留痕。 */
        const val TAG: String = "PiHostMain"

        const val PROBE_PREFIX: String = "测试 RPC 应答："
        const val PROBE_TIMEOUT_MS: Long = 5_000L

        /** 等订阅注册的上限：远小于探针超时，失败要早于「timeout」暴露。 */
        const val SUBSCRIBE_TIMEOUT_MS: Long = 1_000L
        const val PROBE_RENDER_CHARS: Int = 500
        const val POLL_INTERVAL_MS: Long = 500L
        const val REQUEST_NOTIFICATIONS: Int = 0x51F0
    }
}
