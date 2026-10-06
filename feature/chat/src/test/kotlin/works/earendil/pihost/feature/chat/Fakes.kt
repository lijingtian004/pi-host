package works.earendil.pihost.feature.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import works.earendil.pihost.core.engine.settings.AppSettings
import works.earendil.pihost.core.engine.settings.SettingsRepository
import works.earendil.pihost.core.environment.MemTier
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 设置面假体（P2E §4.3 的 maxParallelSessions 门要读它）。
 *
 * 为什么在 `:feature:chat` 测试源集里**重写**一份、而不是复用 `:feature:settings` 的
 * `FakeSettingsRepository`：Gradle 的 test source set 互不可见（要共享得再开一个
 * `testFixtures` 产物类型，那属于本里程碑白名单外的构建改动）。两边形状一致即可，
 * 各自只服务自己那套断言——chat 侧关心的是 `maxParallelSessions` 这一个数。
 */
class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {

    /** 一条写请求（与 settings 侧同形：chat 的用例只验「VM 不写设置」这条边界）。 */
    data class Write(val key: String, val value: Any?)

    private val _settings = MutableStateFlow(initial)
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val writes = CopyOnWriteArrayList<Write>()

    /** 改快照（门的用例把上限按到 1 档，无需碰磁盘）。 */
    fun setMaxParallel(count: Int) {
        _settings.value = _settings.value.copy(maxParallelSessions = count)
    }

    override suspend fun setMemTier(tier: MemTier) {
        writes.add(Write("mem_tier", tier.name))
        _settings.value = _settings.value.copy(memTier = tier)
    }

    override suspend fun setMaxParallelSessions(count: Int) {
        writes.add(Write("max_parallel_sessions", count))
        _settings.value = _settings.value.copy(maxParallelSessions = count)
    }

    override suspend fun setDefaultModel(model: String?) {
        writes.add(Write("default_model", model))
        _settings.value = _settings.value.copy(defaultModel = model)
    }
}
