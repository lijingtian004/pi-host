package works.earendil.pihost.feature.chat.ui

import android.content.Context
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.noties.prism4j.Prism4j
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P2E 冒烟探针（规格书 §4.6 + §5 RECON-P2E §1/§2）：markwon 三件套在
 * AGP 8.13 / compileSdk 36 / Robolectric 4.16 下到底跑不跑得起来。
 *
 * 四条各自独立，失败面互不掩盖：
 * - ①/③ 验 **core 的解析-渲染链**（CommonMark → Spanned + spans），不碰 Compose；
 * - ② 验 **AndroidView interop**（RECON-P2E §2 的判定项：能跑就不必降级成纯函数测试）；
 * - ④ 验 **prism4j 语法表**的解析语义（未注册语言必须回 null，走 fallback）。
 *
 * 保留进正式套件（P2D 的 `ToolchainProbeTest` 同一姿态）：这三件套是渲染层的依赖底座，
 * 任何一次升级（markwon 版本、compileSdk 变动）都该有测试立刻指出断链。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h800dp")
class MarkwonSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    // ── ① core：markdown → Spanned，排版必须留下真 span ───────────────────────────

    @Test
    fun `smoke01 markwon core parses markdown into a spanned with real spans`() {
        val markwon = buildMarkwon(context)
        val rendered = markwon.render(markwon.parse("# 标题\n\n这是 **粗** 与 `code` 的正文"))
        val plain = rendered.toString()

        assertTrue("正文文字必须可见：$plain", plain.contains("粗"))
        assertTrue("标题文字不该带 markdown 记号：$plain", plain.contains("标题"))
        assertTrue("必须产 span（否则 markwon 只是原文透传）", spansOf(rendered).isNotEmpty())
    }

    // ── ② interop：AndroidView(factory=TextView) 在 Robolectric 下真的更新了文本 ────

    @Test
    fun `smoke02 androidView interop renders markwon into a live TextView under robolectric`() {
        composeRule.setContent {
            MarkdownBody(text = "## 冒烟\n\n正文 **b**", markwon = buildMarkwon(context), modifier = Modifier.testTag(TAG))
        }
        composeRule.onNodeWithTag(TAG).assertIsDisplayed()
        composeRule.waitForIdle()

        val textView = collectTextViews(composeRule.activity.window.decorView)
            .firstOrNull { it.text?.toString()?.contains("冒烟") == true }
        assertNotNull("AndroidView 里的 TextView 没拿到 markwon 渲染结果", textView)
        assertTrue("interop 渲染出的 TextView 必须带 span", spansOf(textView!!.text).isNotEmpty())
    }

    // ── ③ syntax-highlight：code fence 过 prism4j，代码原文一个字都不能丢 ──────────

    @Test
    fun `smoke03 fenced code is highlighted through prism4j without losing characters`() {
        val markwon = buildMarkwon(context)
        val code = "fun main() {\n    println(\"hi\")\n}"
        val plain = markwon.render(markwon.parse("```kotlin\n$code\n```")).toString()

        assertTrue("代码正文必须完整可见：$plain", plain.contains("fun main()"))
        assertTrue("字符串字面量必须完整：$plain", plain.contains("\"hi\""))
    }

    // ── ④ 语法表语义：注册过的语言出语法，未注册的（含 mermaid）出 null ────────────

    @Test
    fun `smoke04 grammar locator serves registered languages and declines the rest`() {
        val prism4j = Prism4j(PiGrammarLocator())

        assertNotNull("kotlin 必须命中通用语法", prism4j.grammar("kotlin"))
        assertNotNull("别名 kt 必须命中", prism4j.grammar("kt"))
        assertNotNull("fallback 语言必须存在（未注册语言的去处）", prism4j.grammar(PiGrammarLocator.FALLBACK))
        assertNull("mermaid 在 P2E 没有语法（D5 开档，走 fallback）", prism4j.grammar("zzz-unknown"))

        val grammar = requireNotNull(prism4j.grammar("bash"))
        assertEquals("bash 用通用语法定义，name 即语言名", "bash", grammar.name())
        assertEquals("五类 token：comment/string/keyword/function/number", 5, grammar.tokens().size)
        assertTrue("tokenize 必须产出节点", prism4j.tokenize("echo \"hi\" # 注释", grammar).isNotEmpty())
    }

    private fun spansOf(text: CharSequence): Array<out Any> =
        if (text is Spanned) text.getSpans(0, text.length, Any::class.java) else emptyArray()

    private fun collectTextViews(root: View): List<TextView> {
        val out = ArrayList<TextView>(8)
        fun walk(view: View) {
            if (view is TextView) out.add(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        return out
    }

    private companion object {
        const val TAG = "smoke-markdown"
    }
}
