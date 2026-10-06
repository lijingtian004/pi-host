package works.earendil.pihost.feature.chat.ui

import android.content.Context
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import works.earendil.pihost.feature.chat.SWITCHING_NOTE_TEXT
import works.earendil.pihost.feature.chat.TranscriptItem
import works.earendil.pihost.feature.chat.ToolCallRecord

/**
 * §4.5 的「markwon 渲染 UI Test」（编译机 ≥2 条，这里实交 4 条）。
 *
 * 与 [MarkwonSmokeTest]（§4.6 冒烟件）的分工：冒烟验**依赖链能不能落地**，
 * 本件验**业务渲染体的形状**——`TranscriptList` 里的 assistant 正文走的是 markwon，
 * 于是判据必须落到「宿主视图树里那个 TextView 的真实文本」上：
 * Compose 的 `onNodeWithText` 只看语义树的 Text 节点，`AndroidView` 内部的 TextView
 * 不在语义树里（这正是 §7 UI-③ 那条用例把判据换成 tag 的原因），
 * 所以内容断言一律经 [collectTextViews] 走真实视图遍历。
 *
 * `createAndroidComposeRule<ComponentActivity>()` 而非 `createComposeRule()`：
 * 只有前者能拿到 `activity.window.decorView` 做遍历（RECON-P2D §4 配方的同一选择）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h2400dp")
class MarkwonRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** 与 Hilt 的 @Singleton 同一配方（同一装配 = 同一语法表与 fallback 语义）。 */
    private val markwon by lazy { buildMarkwon(context) }

    // ── ① 列表里的 assistant 正文 = markwon 渲染，markdown 记号不进文本 ────────────

    @Test
    fun `render01 assistant body in the transcript is rendered by markwon without markdown tokens`() {
        val items = listOf(
            TranscriptItem.AssistantItem(
                id = 2,
                text = "已在 **main** 分支\n\n```bash\ngit status\n```",
                toolCalls = listOf(ToolCallRecord("call-1", "bash", "{}")),
                settled = true,
            ),
        )
        composeRule.setContent {
            PiHostTheme { TranscriptList(items = items, markwon = markwon) }
        }
        composeRule.onNodeWithTag(ChatTags.markdown(2)).assertIsDisplayed()
        composeRule.waitForIdle()

        val view = collectTextViews(composeRule.activity.window.decorView)
            .firstOrNull { it.text?.toString()?.contains("main") == true }
        assertTrue("渲染体里必须出现正文文字", view != null)
        val text = requireNotNull(view).text.toString()
        assertTrue("粗体的星号不该留在文本里：$text", !text.contains("**"))
        assertTrue("围栏记号不该留在文本里：$text", !text.contains("```"))
        assertTrue("代码正文必须完整：$text", text.contains("git status"))
        assertTrue("markwon 必须真的下过 span（否则等于原文透传）", spansOf(requireNotNull(view).text).isNotEmpty())
    }

    // ── ② 纯函数路线（§4.5 预案的那一层）：processor 产出 Spanned ─────────────────

    @Test
    fun `render02 the markwon processor yields a spanned with real spans`() {
        val rendered = markwon.toMarkdown("# 标题\n\n这是 **粗** 与 `code` 的正文")
        val plain = rendered.toString()

        assertTrue("标题文字必须可见且不带 #：$plain", plain.contains("标题") && !plain.contains("#"))
        assertTrue("正文文字必须可见：$plain", plain.contains("粗"))
        assertTrue("必须有真 span", spansOf(rendered).isNotEmpty())
    }

    // ── ③ 流式追加时 update 块必须重写 TextView（appliedText 判等不许冻住内容）────

    @Test
    fun `render03 a changed text re-renders into the same live text view`() {
        val text = mutableStateOf("前半")
        composeRule.setContent {
            PiHostTheme {
                Column {
                    MarkdownBody(text = text.value, markwon = markwon, modifier = Modifier.testTag(TAG_BODY))
                }
            }
        }
        composeRule.waitForIdle()
        assertTrue("首帧必须渲染初始文本", liveBody().contains("前半"))

        text.value = "前半 + 后半 **粗**"
        composeRule.waitForIdle()
        val updated = liveBody()
        assertTrue("换文后 TextView 必须跟着走：$updated", updated.contains("后半"))
        assertTrue("新文本的记号同样不该外泄：$updated", !updated.contains("**"))
    }

    // ── ④ 会话切换占位行是 Compose Text，不是 markwon 节点（§4.3）─────────────────

    @Test
    fun `render04 the switch placeholder renders as a plain note row`() {
        val items = listOf(TranscriptItem.NoteItem(id = 1, text = SWITCHING_NOTE_TEXT))
        composeRule.setContent {
            PiHostTheme { TranscriptList(items = items, markwon = markwon) }
        }
        composeRule.onNodeWithTag(ChatTags.note(1)).assertIsDisplayed()
        composeRule.onNodeWithText(SWITCHING_NOTE_TEXT).assertIsDisplayed()
    }

    /** 宿主视图树里那条正文 TextView 的真实文本（渲染体的最终判据）。 */
    private fun liveBody(): String = collectTextViews(composeRule.activity.window.decorView)
        .joinToString(separator = "\n") { it.text?.toString().orEmpty() }

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
        const val TAG_BODY = "render-body"
    }
}
