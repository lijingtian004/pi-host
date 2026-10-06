package works.earendil.pihost.feature.chat

/**
 * errno 人话化（P2F §4.2(c)）：把 libcore 的 `error=N` 翻译成一句能看懂的话，追加在 cause 尾部。
 *
 * 为什么放在 `:feature:chat` 而不是 `core/engine`（规格书红线「别动 core 契约」）：
 * `SessionSupervisor` 的 [works.earendil.pihost.core.engine.SessionState.Failed.cause] 是**跨模块契约**，
 * 文案属于 UI 层关切；core 侧只负责把完整的 IOException 消息送上来（P2F §3.1：`:258` 的
 * `t.message` 本来就是全文，丢字的是显示层）。
 *
 * 两条提示各对应一种根因与一种用户能做的动作：
 * - `error=2` ENOENT：**exe 或 cwd 不存在**。P2F §4.1(a) 已让 spawn 前 mkdirs，
 *   所以还出现 2 就只剩「exe 真不在」——提示保留两种可能，因为老包/别的调用方不一定有那层修复。
 * - `error=13` EACCES：权限拒绝。[works.earendil.pihost.core.engine.ExecFallbackStarter]
 *   此时会自动走一次 `/system/bin/linker64` 装载降级，提示如实说明「已自动尝试」。
 *
 * 其余情况**原样返回**（不猜、不包装、不吞）。安装失败/并行上限这类中文文案本身没有 `error=N`，
 * 正则不命中即直通，语义零变化。
 */
object ErrnoHint {

    /** libcore 消息里的 errno（P2F §3.3 的提取正则，与 core 侧同源形状）。 */
    private val ERRNO_REGEX: Regex = Regex("""error=(\d+)""")

    const val ENOENT_SUFFIX: String = "（errno 2 ENOENT：可执行文件或工作目录不存在）"
    const val EACCES_SUFFIX: String = "（errno 13 EACCES：权限拒绝，已自动尝试 linker64 降级）"

    /** 命中 `error=2`/`error=13` 时追加对应后缀，否则原样返回。 */
    fun humanize(cause: String): String = when (ERRNO_REGEX.find(cause)?.groupValues?.get(1)) {
        "2" -> cause + ENOENT_SUFFIX
        "13" -> cause + EACCES_SUFFIX
        else -> cause
    }

    /** 只提 errno，没有则 null（设置页/诊断复制用的轻量工具，UI 测试直接验形状）。 */
    fun errnoOf(cause: String): Int? = ERRNO_REGEX.find(cause)?.groupValues?.get(1)?.toIntOrNull()
}
