package works.earendil.pihost.core.engine

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import works.earendil.pihost.core.rpc.ExtensionUiResponse
import works.earendil.pihost.core.rpc.PiEvent
import works.earendil.pihost.core.rpc.RpcCommand

/**
 * 聊天核心链看到的引擎面（规格书 §4.4）。
 *
 * 存在理由（§13 偏差 4，additive）：`:feature:chat` 的 ChatViewModel 只该拿到
 * 「会话 + 事件 + 命令 + 权限应答」这四件事，不该拿到 [SessionSupervisor] 的
 * 退避表、握手超时、`eventSubscriptions` 这些运行期细节；测试侧也因此有了一个
 * 可以直接脚本化的接口（[FakeChatEngine] 在 `:feature:chat` 测试源集），
 * 不用再像 P2C 那样靠 `open` + 继承来做假监督者（本里程碑同时移除那些 `open`）。
 *
 * 语义与 [SessionSupervisor] 逐条同构，没有新行为：方法签名就是 §4.4 的原文。
 *
 * **一条 suspend 差异**（RECON-P2D §5 末）：`sendUiResponse` 按规格书是**非 suspend** 的 `fun`，
 * 而冻结区 `PiSession.sendUiResponse` 是 suspend（写单 writer outbox）。
 * 实现里由引擎侧 `scope.launch` 投递；投递失败（会话已无进程）只记日志，不抛给 UI——
 * 权限应答本来就是「无回执」协议（protocol.md §3.7），UI 不等待它。
 */
interface ChatEngine {

    /** key = sessionId；每次状态转移换整张快照（引用透明，Compose 收敛）。 */
    val sessionStates: StateFlow<Map<String, SessionState>>

    /** 生成 UUID → spawn → `get_state` 握手；挂起到 Ready 或终态，返回 sessionId。 */
    suspend fun start(): String

    /** 停一条会话（幂等）。 */
    suspend fun stop(sessionId: String)

    /** 停全部会话。 */
    suspend fun shutdownAll()

    /** 该会话的事件总线；会话未知 → null。无 replay：**先订阅再发命令**。 */
    fun events(sessionId: String): SharedFlow<PiEvent>?

    /** 发命令，返回用于关联 [PiEvent.Response] 的 id；无活动进程 → [IllegalStateException]。 */
    suspend fun send(sessionId: String, command: RpcCommand, id: String? = null): String

    /** 权限应答写回（`extension_ui_response`，无回执）。会话未知 → 忽略。 */
    fun sendUiResponse(sessionId: String, response: ExtensionUiResponse)

    /** stderr 8KB 环快照（失败原因的唯一线索）；会话未知或没有进程 → null。 */
    fun stderrTail(sessionId: String): String?
}
