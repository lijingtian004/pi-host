package works.earendil.pihost.core.engine

import javax.inject.Qualifier

/**
 * 引擎级协程 scope 的限定符（规格书 §4.2.2）。
 *
 * 这条 scope 就是 P2C 的 `EngineRuntime.scope`：`SupervisorJob + Dispatchers.Default`，
 * 与进程同寿——会话协程**不挂在 Service 上**（`PiEngineService` 被系统停掉不等于
 * 子进程被杀），也不挂在 ViewModel 上（ViewModel 重建不该杀引擎）。
 *
 * 必须带限定符：`CoroutineScope` 是无界类型，Hilt 里裸 `@Provides CoroutineScope`
 * 会和别的绑定撞成重复。
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.FIELD,
)
annotation class EngineScope
