package works.earendil.pihost.core.engine.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import works.earendil.pihost.core.environment.MemTier

/**
 * `SettingsRepository` 的持久化与宽松解析（规格书 §4.5「SettingsRepositoryTest（Robolectric + TempDir
 * DataStore）：默认值、写读回、memTier 枚举映射 ≥3 条」，实交 6 条）。
 *
 * **测试配方直接抄 `DataStoreProbeTest`（RECON-P2E §3 实测结论）**，三条硬约束逐条落到 helper 里：
 * 1. `PreferenceDataStoreFactory.create` **必须显式给 scope**，且这条 scope 与仓库的收集 scope
 *    同源（[withRepository]）；不给的话默认挂在 `Dispatchers.IO` 真线程上，测试先结束、写盘后完成。
 * 2. **一个文件同时只允许一个活跃实例**（结论 2，抛自 `FileStorage.createConnection`），
 *    所以每条用例独占一个 `TemporaryFolder` 路径，且要「换实例重读同一份盘」之前
 *    **一定**先 `cancel()` 前一条 scope（[withRepository] 的 finally）。
 * 3. 全部走 `runBlocking`（真实时间）而不是 `runTest`：这里要验的是真落盘，虚拟时钟只会把
 *    「写完但还没落盘」这一档伪装成通过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * 开一条「本用例独占」的仓库：store 与仓库收集器共用 [scope]，退出时一起取消，
     * 于是同一份文件在下一条用例里可以被重新打开（结论 2）。
     */
    private suspend inline fun <R> withRepository(
        file: File,
        block: (DataStore<Preferences>, DataStoreSettingsRepository) -> R,
    ): R {
        val scope = CoroutineScope(Job())
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val repository = DataStoreSettingsRepository(store, scope)
        return try {
            block(store, repository)
        } finally {
            scope.release()
        }
    }

    /** 一次性读盘（必须先释放上一条 store 的占用，所以只在 `withRepository` 之外调）。 */
    private suspend fun prefsOf(file: File): Preferences {
        val scope = CoroutineScope(Job())
        return try {
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }).data.first()
        } finally {
            scope.release()
        }
    }

    /**
     * 放掉这条 scope 对文件的占用。
     *
     * **`cancel()` 之后必须 `join()`**：取消是异步的，锁要等那条 scope 的协程真正结束才释放。
     * 编译机实测——只 cancel 不 join 就重开同一份文件，直接撞 §3 结论 2：
     * `IllegalStateException: There are multiple DataStores active for the same file: /tmp/junit…/clamp.preferences_pb`
     * （抛自 `FileStorage.createConnection`）。这条纪律在仓库的 `@Singleton` 理由里同样成立。
     */
    private suspend fun CoroutineScope.release() {
        cancel()
        coroutineContext[Job]!!.join()
    }

    /** 等仓库的镜像收集器把磁盘值搬进快照（搬不动就是测试失败，而不是永远转圈）。 */
    private suspend fun awaitSnapshot(
        repository: DataStoreSettingsRepository,
        until: (AppSettings) -> Boolean,
    ): AppSettings = withTimeout(5_000) { repository.settings.first(until) }

    // ── r01 默认值：键缺失就是仓库的默认，不写盘、不抛 ────────────────────────────

    @Test
    fun `r01 missing keys fall back to the repository defaults`() {
        val empty = preferencesOf()
        val settings = empty.toAppSettings()
        assertEquals("§3.4：默认 MID", MemTier.DEFAULT, settings.memTier)
        assertEquals("§3.4：默认并行 2", SettingsRepository.DEFAULT_MAX_PARALLEL, settings.maxParallelSessions)
        assertNull("§3.4：空 = 用 pi 内置默认", settings.defaultModel)

        // 空表映射不抛、也不给「未定义」这种中间态
        assertEquals(0, empty.asMap().size)
        assertEquals(MemTier.MID, settings.memTier)
    }

    // ── r02 写读回：setter → 快照 → 磁盘（换实例重开才算真落盘）──────────────────

    @Test
    fun `r02 every setter lands in the snapshot and on disk`() = runBlocking {
        val file = File(tmp.root, "roundtrip.preferences_pb")
        withRepository(file) { _, repository ->
            repository.setMemTier(MemTier.HIGH)
            repository.setMaxParallelSessions(3)
            repository.setDefaultModel("anthropic/claude-sonnet-4")

            // 乐观写：edit 一返回就该读到新值（envFor 与 VM 门靠的就是这一条）
            assertEquals(MemTier.HIGH, repository.settings.value.memTier)
            assertEquals(3, repository.settings.value.maxParallelSessions)
            assertEquals("anthropic/claude-sonnet-4", repository.settings.value.defaultModel)
        }

        val persisted = prefsOf(file)
        assertEquals("HIGH", persisted[SettingsRepository.KEY_MEM_TIER])
        assertEquals(3, persisted[SettingsRepository.KEY_MAX_PARALLEL_SESSIONS])
        assertEquals("anthropic/claude-sonnet-4", persisted[SettingsRepository.KEY_DEFAULT_MODEL])
    }

    // ── r03 memTier 枚举映射：大小写/空白宽容，认不出回落默认 ─────────────────────

    @Test
    fun `r03 the mem tier string maps case and space tolerantly and never throws`() {
        MemTier.entries.forEach { tier ->
            assertEquals(tier, memTierOf(tier.name))
            assertEquals(tier, memTierOf(tier.name.lowercase()))
            assertEquals("磁盘内容按不可信输入对待：前后空白允许", tier, memTierOf("  ${tier.name}  "))
        }
        assertEquals("脏值回落默认档而不是抛", MemTier.DEFAULT, memTierOf("BOGUS"))
        assertEquals(MemTier.DEFAULT, memTierOf(""))
        assertEquals(MemTier.DEFAULT, memTierOf(null))
        assertEquals(MemTier.DEFAULT, memTierOf("  "))
    }

    // ── r04 并行上限：纯函数与 setter 是同一道门（UI stepper 之外再夹一次）─────────

    @Test
    fun `r04 the parallel limit is clamped both on read and on write`() = runBlocking {
        assertEquals(SettingsRepository.DEFAULT_MAX_PARALLEL, maxParallelOf(null))
        assertEquals(1, maxParallelOf(0))
        assertEquals(1, maxParallelOf(-7))
        assertEquals(4, maxParallelOf(99))
        assertEquals(2, maxParallelOf(2))

        val file = File(tmp.root, "clamp.preferences_pb")
        withRepository(file) { _, repository ->
            repository.setMaxParallelSessions(9)
            assertEquals(4, repository.settings.value.maxParallelSessions)
            repository.setMaxParallelSessions(0)
            assertEquals(1, repository.settings.value.maxParallelSessions)
        }
        assertEquals("夹紧后的值才是落盘的值", 1, prefsOf(file)[SettingsRepository.KEY_MAX_PARALLEL_SESSIONS])

        // 脏盘（直接写越界值）经映射层也进合法区间
        assertEquals(4, preferencesOf(SettingsRepository.KEY_MAX_PARALLEL_SESSIONS to 42).toAppSettings().maxParallelSessions)
    }

    // ── r05 defaultModel：空白 = 删键（回落 pi 内置默认）──────────────────────────

    @Test
    fun `r05 a blank default model removes the key rather than storing empty string`() = runBlocking {
        val file = File(tmp.root, "model.preferences_pb")
        withRepository(file) { _, repository ->
            repository.setDefaultModel("openai/gpt-4o")
            assertEquals("openai/gpt-4o", repository.settings.value.defaultModel)

            repository.setDefaultModel("   ")
            assertNull(repository.settings.value.defaultModel)
        }
        val persisted = prefsOf(file)
        assertNull("空白写入必须 remove，否则下次读回一个空串键", persisted[SettingsRepository.KEY_DEFAULT_MODEL])

        // 传 null 与传空白同义
        val other = File(tmp.root, "model-null.preferences_pb")
        withRepository(other) { _, repository ->
            repository.setDefaultModel("x/y")
            repository.setDefaultModel(null)
        }
        assertNull(prefsOf(other)[SettingsRepository.KEY_DEFAULT_MODEL])
    }

    // ── r06 镜像收集器真的在跑 + 默认路径的拼法（DI 与测试的唯一出口）─────────────

    @Test
    fun `r06 the repository mirrors pre-existing disk values and the prefs path is fixed`() = runBlocking {
        val file = File(tmp.root, "mirror.preferences_pb")
        // 先裸写一份盘（模拟「上个进程留下的设置」）
        val writerScope = CoroutineScope(Job())
        PreferenceDataStoreFactory.create(scope = writerScope, produceFile = { file })
            .edit { prefs ->
                prefs[SettingsRepository.KEY_MEM_TIER] = "LOW"
                prefs[SettingsRepository.KEY_MAX_PARALLEL_SESSIONS] = 4
                prefs[SettingsRepository.KEY_DEFAULT_MODEL] = "mi/m"
            }
            .also { writerScope.release() }

        withRepository(file) { _, repository ->
            assertEquals("仓库初值是默认值（收集器还没来得及跑）", MemTier.DEFAULT, repository.settings.value.memTier)
            val mirrored = awaitSnapshot(repository) { it.memTier == MemTier.LOW }
            assertEquals(MemTier.LOW, mirrored.memTier)
            assertEquals(4, mirrored.maxParallelSessions)
            assertEquals("mi/m", mirrored.defaultModel)
        }

        val expected = File(File(RuntimeEnvironment.getApplication().filesDir, "settings"), "pihost.preferences_pb")
        assertEquals(expected, SettingsRepository.settingsFile(RuntimeEnvironment.getApplication()))
        assertFalse("settingsFile 只拼路径，不许顺手建目录", expected.parentFile!!.exists())
        assertEquals(
            "键名是磁盘格式的一部分，改动要带数据迁移：钉住 §3.4 原文的三个键",
            setOf("mem_tier", "max_parallel_sessions", "default_model"),
            setOf(
                SettingsRepository.KEY_MEM_TIER.name,
                SettingsRepository.KEY_MAX_PARALLEL_SESSIONS.name,
                SettingsRepository.KEY_DEFAULT_MODEL.name,
            ),
        )
        // 键的**类型**由 r02 的落盘读回证明：那里用 String/Int 两种类型分别取到了值，
        // 取错类型会拿到 null 而不是抛，所以那条用例已经把「mem_tier 是字符串键」钉死。
    }
}
