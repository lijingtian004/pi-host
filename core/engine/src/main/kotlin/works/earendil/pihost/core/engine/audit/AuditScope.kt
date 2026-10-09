package works.earendil.pihost.core.engine.audit

import javax.inject.Qualifier

/**
 * 审计写入侧协程 scope 的限定符（规格书 P3A §3.1「scope 由构造注入，默认外部提供」）。
 *
 * 与 [works.earendil.pihost.core.engine.EngineScope] 分成两条是职责之分：
 * 引擎 scope 跑 spawn 与 JSON 轮转，用 `Dispatchers.Default`；审计只做**追加写文件**，
 * 用 `Dispatchers.IO` 才不跟会话心跳抢线程。两条都是「与进程同寿 + SupervisorJob」——
 * 一条写协程失败不许把兄弟写协程带下水（§2 红线「审计失败绝不影响对话主流程」在装配面的落点）。
 *
 * 必须带限定符：`CoroutineScope` 是无界类型，裸 `@Provides CoroutineScope` 会与
 * [EngineScope] 那条撞成 `DuplicateBindings`。
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.FIELD,
)
annotation class AuditScope
