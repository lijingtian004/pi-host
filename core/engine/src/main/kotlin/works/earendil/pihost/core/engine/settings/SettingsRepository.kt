package works.earendil.pihost.core.engine.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import works.earendil.pihost.core.environment.MemTier

/**
 * 设置项的内存快照（规格书 §3.4 的三键）。
 *
 * 三个字段都有**默认值**：DataStore 的语义是「键缺失 → 读不到」，而 UI 与 `envFor`
 * 需要一个永不为 null 的快照（RECON-P2E §3 结论 3：磁盘上还没有 preferences 文件时
 * 首读直接给空 `Preferences`，所以默认值属于仓库、不属于磁盘）。
 *
 * @param memTier             node 堆内存三档，默认 [MemTier.DEFAULT]（MID=192MB）
 * @param maxParallelSessions 并行会话上限，默认 2，合法区间 [SettingsRepository.PARALLEL_RANGE]
 * @param defaultModel        默认模型显示串；null/空 = 用 pi 内置默认（P2E 不做模型切换，与 §13 偏差 7 同源）
 */
data class AppSettings(
    val memTier: MemTier = MemTier.DEFAULT,
    val maxParallelSessions: Int = SettingsRepository.DEFAULT_MAX_PARALLEL,
    val defaultModel: String? = null,
)

/**
 * 设置持久化（规格书 §4.2）：DataStore Preferences 三键 + StateFlow 暴露 + suspend 写。
 *
 * **为什么在 `:core:engine` 而不是 feature**：`EngineModule` 的 `envFor` lambda 是
 * `() -> Map<String,String>`（SessionSupervisor 每次 spawn 同步调用），它要读 memTier；
 * repository 落 feature 就形成 `core:engine → feature` 的反向依赖（§2 红线原话依据，RECON-P2E §7.6）。
 *
 * **两条从 RECON-P2E §3 实测来的硬纪律**，实现里逐条落：
 * 1. `PreferenceDataStoreFactory.create` 必须**显式给 scope**（结论 1）：默认 scope 是
 *    `Dispatchers.IO` 的真线程，测试里跑在虚拟时钟下会「测试先结束、写盘后完成」而取到旧值。
 *    生产侧由 [DataStoreSettingsRepository.create] 自建 `SupervisorJob + Dispatchers.IO`；
 *    测试侧把 store 与 scope 一起注进主构造函数。
 * 2. **一个文件只允许一个活着的 DataStore 实例**（结论 2）：DataStore 按路径持锁，第二条实例抛
 *    `IllegalStateException: There are multiple DataStores active for the same file: …`
 *    （实测栈顶 `androidx.datastore.core.FileStorage.createConnection(FileStorage.kt:52)`）。
 *    所以本仓库**必须** `@Singleton`，且 [DataStoreSettingsRepository.create] 只在 DI 里调一次。
 */
interface SettingsRepository {

    /** 全量快照流：UI 侧 `collectAsState()`；引擎侧经下面两个同步取值读。 */
    val settings: StateFlow<AppSettings>

    /**
     * 同步读 memTier —— 专给 [works.earendil.pihost.core.engine.SessionSupervisor] 的
     * `envFor` lambda（§13 偏差 4：热更新只影响**后续** spawn，不杀活动会话）。
     * 读内存快照，不挂起、不碰磁盘。
     */
    val memTier: MemTier
        get() = settings.value.memTier

    /** 同步读并行上限 —— 专给 ChatViewModel 的 maxParallelSessions 门（§13 偏差 6：门在 VM 层）。 */
    val maxParallelSessions: Int
        get() = settings.value.maxParallelSessions

    /** 写 memTier（下次 spawn 生效）。 */
    suspend fun setMemTier(tier: MemTier)

    /** 写并行上限；越界值由实现夹紧到 [PARALLEL_RANGE]，不抛。 */
    suspend fun setMaxParallelSessions(count: Int)

    /** 写默认模型显示串；传 null 或空白 = 删除该键（回落 pi 内置默认）。 */
    suspend fun setDefaultModel(model: String?)

    companion object {
        const val TAG: String = "PiHostSettings"

        /** §3.4 原文键名（磁盘上的真 key；改名要带 DataMigration，别动）。 */
        val KEY_MEM_TIER: Preferences.Key<String> = stringPreferencesKey("mem_tier")
        val KEY_MAX_PARALLEL_SESSIONS: Preferences.Key<Int> = intPreferencesKey("max_parallel_sessions")
        val KEY_DEFAULT_MODEL: Preferences.Key<String> = stringPreferencesKey("default_model")

        const val DEFAULT_MAX_PARALLEL: Int = 2

        /** 规格书 §4.2：stepper 的合法区间 1..4。 */
        val PARALLEL_RANGE: IntRange = 1..4

        /** 设置目录名（filesDir 私有区，避开 pi 的 runtime/home/projects 三个目录）。 */
        const val PREFS_DIR_NAME: String = "settings"

        /** 文件名照 AndroidX 官方 `.preferences_pb` 后缀习惯，adb/logcat 里一眼认得出来。 */
        const val PREFS_FILE_NAME: String = "pihost.preferences_pb"

        /** 设置文件的唯一出口（DI 与测试都走这里，避免两条路径拼法不一致）。 */
        fun settingsFile(ctx: Context): File = File(File(ctx.filesDir, PREFS_DIR_NAME), PREFS_FILE_NAME)
    }
}

/**
 * [SettingsRepository] 的 DataStore Preferences 实现。
 *
 * **快照与磁盘的关系**：磁盘是唯一事实源，[snapshot] 是它的镜像 + 乐观写。
 * - 镜像：构造时用传入的 [scope] 立刻开始收集 `data.data`，进程内任何一次落盘变更都会回流；
 * - 乐观写：三个 setter 在 `edit` **成功返回后**再改内存快照，于是 `envFor`/VM 门在写完的
 *   下一刻就读到新值，不必等 DataStore 的收集器被真线程调度
 *   （那一步正是 RECON-P2E §3 结论 1 测出来的不确定性来源）。
 * 两者最终一致：收集器随后会把同样的值再写进快照一次，`StateFlow` 去重 → 不多一次重组。
 *
 * **读失败**：`IOException`（磁盘抖动）→ 记日志 + 保持内存快照不动，绝不让 UI 或 `envFor`
 * 读到半成品；其它异常同样记日志并终止收集（装配级错误会在第一次 `edit` 时抛给调用方）。
 *
 * @param data  磁盘事实源。测试注入 `PreferenceDataStoreFactory.create(scope=, produceFile=)` 的临时实例
 * @param scope 收集器的宿主 scope；**与 store 同寿**——取消它即释放文件锁（RECON-P2E §3 结论 2 的另一半）
 */
class DataStoreSettingsRepository(
    private val data: DataStore<Preferences>,
    scope: CoroutineScope,
) : SettingsRepository {

    private val snapshot = MutableStateFlow(AppSettings())

    override val settings: StateFlow<AppSettings> = snapshot.asStateFlow()

    init {
        data.data
            .catch { t ->
                if (t is IOException) {
                    // 磁盘抖动/临时不可读：不动快照，UI 与 envFor 继续拿最后一次好值
                    Log.w(SettingsRepository.TAG, "设置流读取失败，沿用内存快照", t)
                } else {
                    // 非预期异常也吞掉而不是崩收集器：DataStore 的文件损坏在第一次
                    // edit 时会把同样的异常抛给调用方，那里才是该暴露它的地方
                    Log.e(SettingsRepository.TAG, "设置流异常，沿用内存快照", t)
                }
            }
            .onEach { prefs -> snapshot.value = prefs.toAppSettings() }
            .launchIn(scope)
    }

    override suspend fun setMemTier(tier: MemTier) {
        data.edit { it[SettingsRepository.KEY_MEM_TIER] = tier.name }
        snapshot.update { it.copy(memTier = tier) }
    }

    override suspend fun setMaxParallelSessions(count: Int) {
        val clamped = count.coerceIn(SettingsRepository.PARALLEL_RANGE)
        data.edit { it[SettingsRepository.KEY_MAX_PARALLEL_SESSIONS] = clamped }
        snapshot.update { it.copy(maxParallelSessions = clamped) }
    }

    override suspend fun setDefaultModel(model: String?) {
        val value = model?.trim()?.ifBlank { null }
        data.edit { prefs ->
            if (value == null) prefs.remove(SettingsRepository.KEY_DEFAULT_MODEL)
            else prefs[SettingsRepository.KEY_DEFAULT_MODEL] = value
        }
        snapshot.update { it.copy(defaultModel = value) }
    }

    companion object {

        /**
         * 生产装配入口（`EngineModule` 里唯一一次调用）。
         *
         * [scope] 默认自建 `SupervisorJob + Dispatchers.IO` 并与 store 同寿：
         * DataStore 的写单线程与读收集都挂在这条 scope 上，进程活着它就得活着
         * （取消它 = 主动释放文件锁，@Singleton 的生命周期里没有这种时刻，所以不暴露 cancel）。
         * 测试要自己掌控生命周期，故绕开本工厂、直接调主构造函数。
         */
        fun create(
            ctx: Context,
            file: File = SettingsRepository.settingsFile(ctx),
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ): DataStoreSettingsRepository {
            file.parentFile?.mkdirs()
            return DataStoreSettingsRepository(
                data = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
                scope = scope,
            )
        }
    }
}

/**
 * 磁盘 [Preferences] → 内存快照：三键全部**宽松解析**（磁盘内容按不可信输入对待）。
 * 脏值（`mem_tier="BOGUS"`、`max_parallel_sessions=99`）不抛，回落默认或夹紧。
 */
internal fun Preferences.toAppSettings(): AppSettings = AppSettings(
    memTier = memTierOf(this[SettingsRepository.KEY_MEM_TIER]),
    maxParallelSessions = maxParallelOf(this[SettingsRepository.KEY_MAX_PARALLEL_SESSIONS]),
    defaultModel = this[SettingsRepository.KEY_DEFAULT_MODEL]?.trim()?.ifBlank { null },
)

/** 枚举映射：大小写/空白宽容（`"mid"`、`" MID "`、`"MID"` 都算 MID）；认不出 → 默认档 MID。 */
internal fun memTierOf(raw: String?): MemTier {
    val key = raw?.trim()?.lowercase() ?: return MemTier.DEFAULT
    return MemTier.entries.firstOrNull { it.name.lowercase() == key } ?: MemTier.DEFAULT
}

/** 区间夹紧：null / 越界 / 非正数一律落回合法区间（UI stepper 之外的第二道门）。 */
internal fun maxParallelOf(raw: Int?): Int {
    val value = raw ?: return SettingsRepository.DEFAULT_MAX_PARALLEL
    return value.coerceIn(SettingsRepository.PARALLEL_RANGE)
}
