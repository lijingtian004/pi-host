package works.earendil.pihost.feature.chat

import works.earendil.pihost.core.rpc.PiEvent

/**
 * 两列队列的可视面（P3B §3.2）。
 *
 * **为什么不能只有 `queueDepth: Int`**（P2C 起的那个字段）：pi 的 `queue_update` 带的是
 * **两个列表**（protocol.md §4.1：`steering:string[]` + `followUp:string[]`，「每次入队发，
 * 内容=当前队列」），而这两列的生效时机完全不同——`steering` 插进**当前这一轮的下一个工具边界**，
 * `followUp` 要等**整轮结束**才消费。合成一个数字就说不出「哪条马上生效、哪条还得等这轮跑完」，
 * 而 §2 红线 8 要的恰恰是用户随时看得清自己排了什么、并能一键清掉。
 *
 * 两条纪律：
 * 1. **内容原样**：[steering] / [followUp] 存的就是 `queue_update` 给的那两条字符串列表，
 *    不截断、不改写、不排序（pi 已经把内容放进事件里了，宿主没有理由加工它）；
 * 2. **只由 `queue_update` 驱动**：本地**不做乐观更新**——`steer`/`follow_up` 的 response
 *    只有 `{disposition:"queued"}`（protocol.md §3.2，不带内容），自己先塞一条就会与紧随其后的
 *    `queue_update` 撞车（队列里凭空多一条重复的）。所以入队之后到事件到达之前，
 *    徽标维持旧值，这是**正确**的：徽标说的是「pi 此刻排了什么」。
 */
data class QueueState(
    val steering: List<String> = emptyList(),
    val followUp: List<String> = emptyList(),
) {
    /** 两列合计（顶栏那个数字仍是它，只是不再是他唯一的真相）。 */
    val depth: Int get() = steering.size + followUp.size

    val isEmpty: Boolean get() = depth == 0

    /** 徽标文案（§3.2 原文点名的形状：「排队中: steer×N followUp×N」）。UI 与测试共用这一处措辞。 */
    fun badgeText(): String = "排队中: steer×${steering.size} followUp×${followUp.size}"

    companion object {
        /** 空队列：会话滚了、或 pi 说队列空了，都回到它。 */
        val EMPTY = QueueState()

        /** `queue_update` → 队列快照（整表替换，不做增量合并）。 */
        fun of(event: PiEvent.QueueUpdate): QueueState =
            QueueState(steering = event.steering.toList(), followUp = event.followUp.toList())
    }
}

/** `set_steering_mode` / `set_follow_up_mode` 的两个取值（protocol.md §3.2，字符串集合已冻结）。 */
internal const val MODE_ALL: String = "all"

/** 一次只喂一条（pi 的默认档）。 */
internal const val MODE_ONE_AT_A_TIME: String = "one-at-a-time"
