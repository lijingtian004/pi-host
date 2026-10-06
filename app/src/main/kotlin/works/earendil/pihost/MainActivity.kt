package works.earendil.pihost

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.widget.TextView
import works.earendil.pihost.core.engine.InstalledRuntime

/**
 * P2 壳：不用 appcompat/Compose，只把 runtime 安装状态摆出来（方案 §13 P2 出口判据之一）。
 * 真正的导航与首启进度 UI 在 P3 的 Compose 层落地。
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val version = InstalledRuntime.version(this) ?: "not installed"
        val margin = dp(16f)

        setContentView(
            TextView(this).apply {
                text = "pi-android host (P2 shell)\nruntime: $version"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(margin, margin, margin, margin)
            },
        )
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
}
