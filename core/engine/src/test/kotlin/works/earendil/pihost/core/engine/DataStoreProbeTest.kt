package works.earendil.pihost.core.engine

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RECON-P2E §3：DataStore Preferences 1.1.7 在 Robolectric 4.16 下的**可用测试配方**探针。
 *
 * 四条结论（全部实测，逐条落到 SettingsRepository 的实现里）：
 * 1. `PreferenceDataStoreFactory.create(scope = …, produceFile = …)` 在 Robolectric 里可用，
 *    但**必须显式给 scope**：默认 scope 是挂在 `Dispatchers.IO` 真线程上的，
 *    跑在 `runTest` 的虚拟时钟下会「测试先结束、写盘后完成」而偶发取到旧值；
 *    这里探针用 `runBlocking`（真实时间）把它钉死，正式实现同样显式传一条自有 scope
 *    （生产侧 = [EngineScope]，见 [works.earendil.pihost.core.engine.settings.DataStoreSettingsRepository]）。
 * 2. **同一个文件只允许一个活跃实例**。第一轮 probe03 实测原文：
 *    `java.lang.IllegalStateException: There are multiple DataStores active for the same file:
 *    /tmp/junit…/persisted.preferences_pb. You should either maintain your DataStore as a singleton
 *    or confirm that there is no two DataStore's active on the same file (by confirming that the
 *    scope is cancelled)` —— 抛自 `androidx.datastore.core.FileStorage.createConnection`。
 *    占用锁随**创建时那条 scope 的完成**释放，所以换实例重读同一份盘之前必须先 `cancel()`
 *    （[withStore] 的 finally）。生产侧对应纪律：SettingsRepository `@Singleton` + 只由一条
 *    scope 构造，绝不在 provider 里 new 第二个 store。
 * 3. 文件路径必须**每条用例独立**（`TemporaryFolder`），否则同 JVM 内跨用例复用路径就踩结论 2。
 * 4. 读默认值不需要磁盘上存在文件（首次读直接给空 `Preferences`，probe01 实测），
 *    所以 SettingsRepository 的默认值语义 = 「键缺失 → 取仓库里的默认」，不做落盘初始化。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataStoreProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keyTier = stringPreferencesKey("mem_tier")
    private val keyMax = intPreferencesKey("max_parallel_sessions")

    /**
     * 开一个「本用例独占」的 store：scope 由调用方之外的 helper 持有，
     * 结束时**一定**取消——结论 2 要求同一文件不能有两个活跃实例。
     */
    private suspend inline fun <R> withStore(file: File, block: (DataStore<Preferences>) -> R): R {
        val scope = CoroutineScope(Job())
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        return try {
            block(store)
        } finally {
            scope.cancel()
        }
    }

    /** 一次性读：开 store → 取首帧 Preferences → 立刻释放该文件的占用。 */
    private suspend fun prefsOf(file: File): Preferences = withStore(file) { it.data.first() }

    @Test
    fun `probe01 an absent file reads back empty preferences (defaults live in the repository)`() = runBlocking {
        val file = File(tmp.root, "missing.preferences_pb")
        assertNull("文件本不该预先存在", if (file.exists()) file else null)

        val prefs = prefsOf(file)
        assertNull("键缺失时 DataStore 给 null，而不是抛", prefs[keyTier])
        assertEquals("空 Preferences 可以正常迭代", 0, prefs.asMap().size)
    }

    @Test
    fun `probe02 write then read returns the same value on the same store`() = runBlocking {
        withStore(File(tmp.root, "roundtrip.preferences_pb")) { prefs ->
            prefs.edit { it[keyTier] = "HIGH" }
            prefs.edit { it[keyMax] = 3 }

            val read = prefs.data.first()
            assertEquals("HIGH", read[keyTier])
            assertEquals(3, read[keyMax])
        }
    }

    @Test
    fun `probe03 a second store over the same path sees the persisted bytes`() = runBlocking {
        val file = File(tmp.root, "persisted.preferences_pb")
        // 先写，再**释放那个 store**：不取消 scope 就重开同一文件会撞结论 2 的 IllegalStateException
        withStore(file) { it.edit { prefs -> prefs[keyTier] = "LOW" } }

        // 换一个 store 实例（同一路径）：验证的是「真的落盘了」而不是内存缓存
        val reopened = prefsOf(file)
        assertEquals("LOW", reopened[keyTier])
        assertEquals("preferencesOf 构造的对照值同形", "LOW", preferencesOf(keyTier to "LOW")[keyTier])
    }

    /**
     * 探针 4：scope 由外部持有（正式实现里它就是 [EngineScope]）——
     * 写完后取消 scope，读到的仍是已落盘的值。
     */
    @Test
    fun `probe04 an externally-owned scope can be cancelled without losing the write`() = runBlocking {
        val file = File(tmp.root, "scoped.preferences_pb")
        val scope = CoroutineScope(Job())
        val prefs = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        prefs.edit { it[keyMax] = 4 }
        assertEquals(4, prefs.data.first()[keyMax])

        scope.cancel()
        assertEquals(4, prefsOf(file)[keyMax])
    }
}
