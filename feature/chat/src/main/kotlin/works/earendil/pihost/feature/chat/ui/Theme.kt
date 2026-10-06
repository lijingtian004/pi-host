package works.earendil.pihost.feature.chat.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 应用级 Material3 主题封装（规格书 §6）。
 *
 * **动态取色关掉**（`dynamicColor` 参数保留但恒为 false 生效路径）：minSdk 26 上
 * `dynamicLightColorScheme` 只在 API 31+ 有意义，且真机色板会影响截图比对的稳定性；
 * P2D 用固定色板，深浅随系统。
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF3F51B5),
    onPrimary = Color.White,
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF1B1B1B),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9FA8DA),
    onPrimary = Color(0xFF1B1B1B),
    surface = Color(0xFF1B1B1B),
    onSurface = Color(0xFFE6E1E5),
)

@Composable
fun PiHostTheme(
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
