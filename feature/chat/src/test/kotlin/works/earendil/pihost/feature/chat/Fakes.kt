package works.earendil.pihost.feature.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import works.earendil.pihost.core.engine.audit.AuditEntry
import works.earendil.pihost.core.engine.audit.AuditKind
import works.earendil.pihost.core.engine.audit.AuditSink
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

/**
 * 审计假体（P3A §3.2）。
 *
 * 只做两件事：**收条目**与**按开关抛异常**。后者是给红线用例用的——`ChatViewModel` 承诺
 * 「sink 抛 RuntimeException 时对话不崩」，而那必须靠一个违约的假体才能测出来
 * （真实现 `FileAuditSink` 全量吞异常，§3.1 用例 05/06/12 已经把它的「不抛」钉住了）。
 *
 * 为什么不复用 `:core:engine` 的 `FileAuditSink` + 临时目录：那会把「写盘面」测第二遍
 * （归属 `:core:engine`），而 chat 侧要测的是**挂点**——条目形状、时机、以及对状态机零影响。
 */
class FakeAuditSink : AuditSink {
    val entries = CopyOnWriteArrayList<AuditEntry>()

    /** 非空即让 [record] 抛它：模拟「实现违反『必须不抛』契约」。 */
    var boom: Throwable? = null

    override fun record(entry: AuditEntry) {
        boom?.let { throw it }
        entries.add(entry)
    }

    /** 某一类条目的第一条（没有则 null），断言措辞用。 */
    fun first(kind: AuditKind): AuditEntry? = entries.firstOrNull { it.kind == kind }

    /** 某一类的全部条目，按写入序。 */
    fun all(kind: AuditKind): List<AuditEntry> = entries.filter { it.kind == kind }
}
