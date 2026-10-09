package works.earendil.pihost.core.engine.audit

import kotlinx.serialization.Serializable

/**
 * 审计日志的一条记录（方案 §10「工具审计」/ 规格书 P3A §3.1）。
 *
 * 形状选择的三条理由：
 * 1. **一条 = jsonl 的一行**，所以字段全是扁平标量：[argsJson] 存**原样序列化后的 args 文本**
 *    而不是 `JsonElement`，为的是「追加写 + 逐行读回」不需要任何嵌套结构，损坏一行只丢一行
 *    （[FileAuditSink.readEntries] 的容忍粒度就是行）；
 * 2. **[isError] 可空**：`tool_execution_start` 阶段还没有成败概念，`PERMISSION` 也没有。
 *    用 `Boolean?` 而不是「默认 false」，是拒绝把「未知」伪装成「成功」——
 *    读回 null 就是 null（规格书 §3.1 测试第 ⑨ 条钉住这条）；
 * 3. **[resultPreview] 可空且截断**：工具结果可能是整份文件内容，留痕要的是「发生了什么」
 *    而不是「全部字节」；上限见 [FileAuditSink.RESULT_PREVIEW_MAX]。
 *
 * 不做 `@SerialName` 改名：字段名就是审计面上给运维看的键名，稳定比短重要。
 */
@Serializable
data class AuditEntry(
    /** 事件发生时刻（epoch millis），决定落在哪一天的文件。 */
    val ts: Long,
    val kind: AuditKind,
    /** pi 的 `toolCallId`；[AuditKind.PERMISSION] 没有工具调用，填权限请求 id。 */
    val toolCallId: String,
    val toolName: String,
    /** 工具入参的**原样** JSON 文本（审计目的即留痕，本地沙盒文件；见 §2 红线）。 */
    val argsJson: String,
    /** 仅 [AuditKind.TOOL_END] 有值；其余为 null（不是 false）。 */
    val isError: Boolean?,
    /** 结果文本的前 [FileAuditSink.RESULT_PREVIEW_MAX] 字符；无结果为 null。 */
    val resultPreview: String?,
    /** 会话工作目录；取不到时空串（规格书 §3.2 明确不许为此新造跨模块查询）。 */
    val cwd: String,
    /** host 侧会话 id（`ChatEngine.start()` 的返回值）；未就绪时 null。 */
    val sessionId: String?,
)

/**
 * 审计事件类别（规格书 §3.1 原文三类）。
 *
 * 只有这三类，是为了让「一次工具调用」在日志里天然成对：`TOOL_START` 带 args、
 * `TOOL_END` 带 isError + preview，同一 [AuditEntry.toolCallId] 可关联；
 * `PERMISSION` 是用户在弹框上的三选一（含超时），与工具调用不一定同一 id，
 * 所以它的 [AuditEntry.toolName] 固定为 `"permission"`（§3.2 原文）。
 */
@Serializable
enum class AuditKind { TOOL_START, TOOL_END, PERMISSION }
