package works.earendil.pihost.core.engine

import android.content.Context
import kotlinx.serialization.Serializable
import works.earendil.pihost.core.environment.PathLayout
import java.io.File

/** `filesDir/runtime/.installed.json` 的落盘结构。 */
@Serializable
internal data class InstalledMarker(val runtimeVersion: String)

/**
 * 已安装 runtime 版本的读写口：版本比对（方案 §5.1 b）与安装完成标记（§5.1 g）都走这里。
 */
object InstalledRuntime {

    /** 未安装（marker 不存在）或 marker 损坏 → null。 */
    fun version(ctx: Context): String? = versionOf(PathLayout.installedMarker(ctx))

    /** 写入安装标记；调用方保证 runtime 目录已经切换到位。 */
    internal fun write(ctx: Context, version: String) {
        val marker = PathLayout.installedMarker(ctx)
        marker.parentFile?.mkdirs()
        marker.writeText(PiAssetJson.encodeToString(InstalledMarker(version)))
    }

    internal fun versionOf(marker: File): String? {
        if (!marker.isFile) return null
        return runCatching {
            PiAssetJson.decodeFromString<InstalledMarker>(marker.readText()).runtimeVersion
        }.getOrNull()?.ifBlank { null }
    }
}
