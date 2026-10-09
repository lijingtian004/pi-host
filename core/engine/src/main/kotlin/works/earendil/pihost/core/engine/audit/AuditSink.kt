package works.earendil.pihost.core.engine.audit

/**
 * 审计事件的出口（规格书 P3A §3.1）。
 *
 * **实现必须不抛**（§2 红线的原文）：调用点是 [works.earendil.pihost.feature.chat.ChatViewModel]
 * 的事件分发主路径，一次 `record` 抛异常就等于把整轮对话带下水（collect 协程死掉 = 转录从此不再更新）。
 * 所以：
 * - 接口层承诺「不抛」，实现层（[FileAuditSink]）内部**全量** try-catch + `Log.w` 后吞；
 * - 调用层因此**不再包 try**（§3.2 原文），这条纪律靠 `ChatViewModelTest` 的
 *   「sink 抛 RuntimeException 时 ChatViewModel 不崩」用例反过来钉住——那条用例喂的是一个
 *   **故意违约**的假体，验证的是「即使实现违约，VM 的兜底也只在挂点处」；
 * - [NoopAuditSink] 是给「不需要审计」的调用方与单测的默认值（§3.2 的构造参数默认值）。
 *
 * 为什么是 `fun interface` 而不是 `interface`：只有一个方法，函数式形态让调用方
 * （含测试）可以直接 `AuditSink { entry -> … }` 建假体，不必写一个类。
 */
fun interface AuditSink {
    /**
     * 记录一条审计事件。实现**必须不抛**，且**必须不挂起**（这是同步调用点，
     * 任何「等磁盘」的语义都只能靠 fire-and-forget 的写入协程实现，见 [FileAuditSink]）。
     */
    fun record(entry: AuditEntry)
}

/**
 * 空实现：什么都不做，用于单测默认值与「审计关闭」的装配形态。
 *
 * 它是**对象单例**而不是 `AuditSink {}`：作为构造参数默认值时，
 * 身份可比（测试可以断言 `assertSame(NoopAuditSink, vm.auditSinkOfTestSurface)` 这类形状），
 * 也保证没有一次 lambda 分配发生在事件热路径上。
 */
object NoopAuditSink : AuditSink {
    override fun record(entry: AuditEntry) = Unit
}
