package works.earendil.pihost.core.engine

import kotlinx.serialization.Serializable

/** 资产清单（方案 §5.1）：CI job B 产出，随 APK assets 一起下发。 */
@Serializable
data class RuntimeManifest(
    val runtimeVersion: String,
    val files: List<RuntimeFile>,
    val symlinks: List<RuntimeSymlink> = emptyList(),
)

/** runtime 目录内的一个普通文件（相对 `filesDir/runtime`）。 */
@Serializable
data class RuntimeFile(
    val path: String,
    val sha256: String,
)

/** 安装完成阶段一次性创建的 symlink：`runtime/<path> -> nativeLibraryDir/<target>`。 */
@Serializable
data class RuntimeSymlink(
    val path: String,
    val target: String,
)
