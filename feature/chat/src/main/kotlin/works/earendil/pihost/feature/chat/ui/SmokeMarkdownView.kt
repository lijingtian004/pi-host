package works.earendil.pihost.feature.chat.ui

import android.content.Context
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.Prism4j

/**
 * P2E 冒烟（规格书 §4.6）：markwon 三件套经 `AndroidView` interop 的**最小可用渲染体**。
 *
 * 它存在的目的只有一个：在写任何业务渲染逻辑之前，证明依赖链真的能落地——
 * `io.noties.markwon:core` + `io.noties.markwon:syntax-highlight` + `io.noties:prism4j`
 * 在 AGP 8.13 / compileSdk 36 上可解析、可编译、可进 dex（RECON-P2E §1）。
 * 步骤 5 的正文渲染会在这个形状上补三件事：@Singleton Markwon（§9.5）、
 * delta → 80ms debounce（§3.3）、与 MaterialTheme 对齐的配色；
 * 冒烟阶段故意不做 debounce、也不把 Markwon 挂进 Hilt——一次只验一个变量。
 *
 * 线程纪律：`AndroidView` 的 factory/update 都在主线程执行，Markwon 实例因此天然
 * 只被主线程触碰（§9.5「渲染线程 confinement 靠 AndroidView update 语义」）。
 */
@Composable
fun SmokeMarkdownView(text: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val markwon = remember(context) { smokeMarkwon(context) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                textSize = 14f
                setPadding(PADDING_DP, PADDING_DP, PADDING_DP, PADDING_DP)
            }
        },
        update = { tv -> markwon.setMarkdown(tv, text) },
    )
}

/**
 * 冒烟装配：core（CommonMark → Spanned）+ 语法高亮（prism4j）。
 *
 * `SyntaxHighlightPlugin.create(prism4j, theme, fallbackLanguage)` 的第三条参数就是
 * §3.3 的落点：**未注册的语言（mermaid / 生僻 fence）走 [PiGrammarLocator.FALLBACK]**，
 * 也就是「按普通 code-block 展示」，D5 继续保持开档、不引 WebView。
 */
internal fun smokeMarkwon(context: Context): Markwon =
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
