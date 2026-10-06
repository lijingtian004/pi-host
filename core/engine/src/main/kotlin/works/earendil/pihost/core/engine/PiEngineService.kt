package works.earendil.pihost.core.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * pi 引擎的前台服务（P2C 规格书 §6.2；方案 §3.5）。
 *
 * 职责只有一件事：**让引擎在屏灭/切后台时不被系统杀掉**，并把会话数摆进通知。
 * 状态机、argv、握手都不在这里——那是 [SessionSupervisor]，P2D 起经 Hilt 注入
 * （P2C 的 `EngineRuntime` 静态桥已随 §4.2.4 删除）。
 *
 * 注入的是 [ChatEngine] 而不是 `SessionSupervisor`（规格书 §4.4 末「qoder 自定，报告记录」）：
 * 本服务只用 `sessionStates / start / shutdownAll` 三件事，接口够用；
 * 更重要的是测试侧可以 `@UninstallModules(EngineModule::class)` + 绑一个 [ChatEngine] 假体，
 * 不用在 Robolectric 里凑出 `nativeLibraryDir` 才能构造真监督者。
 *
 * FGS 类型走 `specialUse`（方案 §3.5 定案：本地 AI coding agent 引擎不属于
 * dataSync/mediaPlayback/location 等既有类型），API 34+ 必须配
 * `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` `<property>`（见 app 的 Manifest）。
 * 三档 API 分流见 [startForegroundCompat]：minSdk 26 拿不到 34 的
 * `ForegroundServiceParams` 重载（RECON-P2C §1 实测 android-35 jar 只有两个重载），
 * 故 26..28 用两参、29..33 用 `FOREGROUND_SERVICE_TYPE_MANIFEST`、34+ 用 `SPECIAL_USE`。
 */
@AndroidEntryPoint
class PiEngineService : Service() {

    /** 引擎面（Hilt 注入，`@Singleton`，与 Activity/ViewModel 拿到的是同一个对象）。 */
    @Inject
    lateinit var engine: ChatEngine

    /**
     * 服务自建 scope：与 Service 同寿（[onDestroy] cancel）。
     * 注意与 [EngineScope] 那条进程级 scope 区分——会话协程挂在进程级 scope 上，
     * 服务被停掉不等于子进程被杀；这里只管「通知刷新 + 启停命令派发」。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var observeJob: Job? = null

    /** 不提供绑定通路：只用 start 语义驱动（ACTION_START/ACTION_STOP），绑定即无意义。 */
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // 先占住前台再干活：onCreate 起 5s 内必须 startForeground，否则 ANR/ForegroundServiceDidNotStart
        startForegroundCompat(buildNotification(SESSIONS_NONE))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        observeSessions(engine)
        when (intent?.action) {
            ACTION_START -> scope.launch {
                // 引擎起不来（退避用完/spawn 失败）不崩服务：状态经 sessionStates 进通知，
                // 详细 cause 由 ChatViewModel 的 ChatUiState.Failed 呈现。
                runCatching { engine.start() }
                    .onFailure { throwable -> Log.w(TAG, "engine.start() 失败", throwable) }
            }

            ACTION_STOP -> scope.launch {
                runCatching { engine.shutdownAll() }
                    .onFailure { throwable -> Log.w(TAG, "engine.shutdownAll() 失败", throwable) }
                mainHandler.post {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

            // 既不是 START 也不是 STOP：没有可派的命令，直接收摊（P2C「没有 supervisor」用例
            // 在 Hilt 化后的等价面——注入恒成功，「无引擎可跑」不再是可达状态，
            // 可达状态只剩「意图不带已知 action」，见 PiEngineServiceTest 等价重写）。
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observeJob?.cancel()
        observeJob = null
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    /** 收集会话表 → 通知文案。只观察一条会话表，重复 start command 不重订阅。 */
    private fun observeSessions(engine: ChatEngine) {
        if (observeJob != null) return
        observeJob = scope.launch {
            engine.sessionStates.collect { states ->
                mainHandler.post { updateNotification(states.size) }
            }
        }
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel() {
        // minSdk 26：无需 SDK_INT 判断，通道一定存在
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun updateNotification(sessions: Int) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(sessions))
    }

    @Suppress("DEPRECATION") // addAction(int, CharSequence, PendingIntent) 在 android-35 标记弃用，
    // 但替代版要 Icon/Notification.Action 且 minSdk 26 无收益，这里保留旧签名
    private fun buildNotification(sessions: Int): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIconRes())
            .setContentTitle(TITLE)
            .setContentText(notificationText(sessions))
            .setOngoing(true)
            .setContentIntent(stopEngineIntent())
            .addAction(0, STOP_LABEL, stopEngineIntent())
            .build()

    /**
     * 「停止引擎」通知动作：回到本服务的 ACTION_STOP。
     * Service PendingIntent 必须显式指定组件（Android 12+ 不许隐式服务意图）。
     */
    internal fun stopEngineIntent(): PendingIntent = PendingIntent.getService(
        this,
        REQUEST_STOP,
        Intent(this, PiEngineService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * 应用图标在 P2 壳里没配（Manifest 无 android:icon）→ 回落到框架自带图标，
     * 免得 `startForeground` 因 smallIcon=0 在真机抛异常。
     */
    private fun smallIconRes(): Int =
        applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info

    /**
     * 三档 API 分流（RECON §1）：
     * - 34+：`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`（必须声明 specialUse 类型 + subtype property）；
     * - 29..33：`FOREGROUND_SERVICE_TYPE_MANIFEST`（= 用 Manifest 里声明的类型，-1）；
     * - 26..28：两参重载（那时还没有 FGS 类型概念）。
     */
    private fun startForegroundCompat(notification: Notification) {
        when (val type = foregroundTypeForApi(Build.VERSION.SDK_INT)) {
            null -> startForeground(NOTIFICATION_ID, notification)
            else -> startForeground(NOTIFICATION_ID, notification, type)
        }
    }

    /**
     * 纯函数形式的档位分流，**因为测试读不到类型**：
     * `ShadowService.getForegroundServiceType()` 是 protected（编译机 javap 实测），
     * Robolectric 侧无法断言真实的 3 参调用；所以把「哪个 SDK 用哪个类型」抽成
     * 可直接调用断言的函数（见 PiEngineServiceTest 的 foregroundTypeForApi 用例）。
     *
     * @return null 表示该档位只有两参重载（API 26..28）
     */
    internal fun foregroundTypeForApi(sdk: Int): Int? = when {
        sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        sdk >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        else -> null
    }

    companion object {
        /** 动作常量：调用方一律经 [engineStartIntent]/[engineStopIntent] 构造，避免散落字符串。 */
        const val ACTION_START: String = "works.earendil.pihost.action.START_ENGINE"
        const val ACTION_STOP: String = "works.earendil.pihost.action.STOP_ENGINE"

        /** 日志 tag：启停失败必须在 logcat 留痕（不崩服务，但不能静默）。 */
        const val TAG: String = "PiEngineService"

        const val CHANNEL_ID: String = "pi_engine"
        const val NOTIFICATION_ID: Int = 0x51E0

        // 文案不走 res（core/engine 是库模块，P2 不引资源合并），集中成常量供测试同源断言
        const val TITLE: String = "pi 引擎运行中"
        const val STOP_LABEL: String = "停止引擎"
        const val SESSIONS_TEMPLATE: String = "%d 个会话"

        private const val CHANNEL_NAME = "pi 引擎"
        private const val REQUEST_STOP = 0x51E1

        /** 空表时通知仍要建（FGS 必须有通知），会话数显示 0。 */
        private const val SESSIONS_NONE = 0

        /** 「N 个会话」渲染（测试按同一函数断言，不重复字面量）。 */
        fun notificationText(sessions: Int): String =
            SESSIONS_TEMPLATE.replace("%d", sessions.toString())
    }
}

/** 跨组件构造启动意图（MainActivity 用），保证 action/组件只有一个出处。 */
fun engineStartIntent(ctx: Context): Intent =
    Intent(ctx, PiEngineService::class.java).setAction(PiEngineService.ACTION_START)

/** 跨组件构造停止意图。 */
fun engineStopIntent(ctx: Context): Intent =
    Intent(ctx, PiEngineService::class.java).setAction(PiEngineService.ACTION_STOP)
