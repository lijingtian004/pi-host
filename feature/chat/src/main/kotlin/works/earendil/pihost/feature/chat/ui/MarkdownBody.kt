package works.earendil.pihost.feature.chat.ui

import android.content.Context
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.Prism4j

/**
 * assistant 正文的渲染体（规格书 §3.3 / §4.3：P2D 的「Text + ``` 围栏 monospace」占位换成 markwon）。
 *
 * **形状沿用 §4.6 冒烟已跑绿的 AndroidView interop**（RECON-P2E §2 判定：Robolectric 下可渲染，
 * 不必降级成纯函数测试）——冒烟件 [SmokeMarkdownView] 因此被本件取代，配方整体搬进 [buildMarkwon]。
 *
 * 两条渲染纪律：
 * 1. **只在文本真的变了时 re-parse**：`remember(text)` 算一次 Spanned，`update` 里再用
 *    [appliedText] 兜一层（AndroidView 的 update 在每次重组都跑，不做判等就等于每帧全量重排）；
 *    历史条目读的是不可变快照，Compose 侧不会重组，所以「只有末条走 re-parse」天然成立
 *    （§3.3 原文），VM 侧的 80ms debounce（[works.earendil.pihost.feature.chat.ChatViewModel]）
 *    控制的是**提交频率**，不是解析次数。
 * 2. **Markwon 实例由外部传入**（@Singleton，§9.5）：本 composable 绝不 `remember { buildMarkwon() }`
 *    自建实例——Prism4j 语法表注册一次是硬要求，且每次自建会把 plugin 装配成本摊到滚动上。
 *
 * `setMovementMethod(LinkMovementMethod)`：markwon 的链接 span 靠它才可点（P2E 不做自定义
 * URL handler，链接点击落到系统浏览器属于 P3 的 Intent 面）。
 */
@Composable
fun MarkdownBody(text: String, markwon: Markwon, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    val textSizeSp = 14f // TextView.textSize 的单位本来就是 sp（与冒烟件一致）
    val rendered = remember(text) { markwon.toMarkdown(text) }
    var appliedText by remember { mutableStateOf("") }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                this.textSize = textSizeSp
                setPadding(PADDING_DP, PADDING_DP, PADDING_DP, PADDING_DP)
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { tv ->
            if (appliedText != text) {
                markwon.setParsedMarkdown(tv, rendered)
                appliedText = text
            }
        },
    )
}

/**
 * 装配 @Singleton Markwon 的唯一配方（`di/MarkwonModule` 与本模块 UI 测试共用）。
 *
 * `SyntaxHighlightPlugin.create(prism4j, theme, fallbackLanguage)` 的第三条参数是 §3.3 的落点：
 * **未注册的语言（mermaid / 生僻 fence）走 [PiGrammarLocator.FALLBACK]**，
 * 也就是「按普通 code-block 展示」，D5 继续保持开档、不引 WebView（RECON-P2E §1.3）。
 *
 * 线程：只在主线程调用（Hilt 的 @Provides 由组件创建线程触发，App 侧组件在主线程创建；
 * 渲染 confinement 靠 AndroidView update 语义，§9.5）。
 */
internal fun buildMarkwon(context: Context): Markwon =
    Markwon.builder(context)
        .usePlugin(
            SyntaxHighlightPlugin.create(
                Prism4j(PiGrammarLocator()),
                Prism4jThemeDefault.create(),
                PiGrammarLocator.FALLBACK,
            ),
        )
        .build()

private const val PADDING_DP = 8
