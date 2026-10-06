package works.earendil.pihost.core.environment

/**
 * node 堆内存三档（方案 §9.2）：通过 `NODE_OPTIONS --max-old-space-size` 传给 libpi_node.so。
 * 默认 MID=192MB，LOW=128MB 给小内存机，HIGH=256MB 给大上下文会话。
 */
enum class MemTier(val nodeOptions: String) {
    LOW("--max-old-space-size=128"),
    MID("--max-old-space-size=192"),
    HIGH("--max-old-space-size=256"),
    ;

    companion object {
        /** 方案 §9.2：默认 192。 */
        val DEFAULT: MemTier = MID
    }
}
