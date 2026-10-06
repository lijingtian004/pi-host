package works.earendil.pihost.core.engine

import android.content.Context
import java.io.InputStream

/**
 * 运行时资产来源抽象（方案 §5.2）：P2 只有 APK 内置 assets，P3 OTA 换 OkHttp 实现，
 * 安装流程 [AssetInstaller] 不感知来源差异。
 */
interface AssetSource {
    /** `runtime/manifest.json` 字节流。调用方负责关闭。 */
    fun openManifest(): InputStream

    /** `runtime/pi-runtime.zip` 字节流。调用方负责关闭；版本一致时安装器不会打开它。 */
    fun openRuntime(): InputStream
}

/** APK assets 实现：`assets/runtime/{manifest.json,pi-runtime.zip}`。 */
class BundledAssets(private val ctx: Context) : AssetSource {

    override fun openManifest(): InputStream = ctx.assets.open(MANIFEST_ASSET_PATH)

    override fun openRuntime(): InputStream = ctx.assets.open(RUNTIME_ASSET_PATH)

    companion object {
        const val MANIFEST_ASSET_PATH: String = "runtime/manifest.json"
        const val RUNTIME_ASSET_PATH: String = "runtime/pi-runtime.zip"
    }
}
