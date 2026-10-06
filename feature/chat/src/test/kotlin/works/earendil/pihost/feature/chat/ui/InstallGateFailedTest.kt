package works.earendil.pihost.feature.chat.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import works.earendil.pihost.core.engine.InstallProgress
import works.earendil.pihost.feature.chat.ChatUiState

/**
 * Failed 屏的全量诊断与复制（P2F §4.4 的 InstallGate 侧用例）。
 *
 * 立测的动机（P2F §1 成功判据②）：上一版把 `maxLines=6 + Ellipsis` 压在 cause 上，
 * 窄屏 + stderr 多行尾巴一拼接，**errno 正好被切在可见区之外**，真机故障无法定位。
 * 本文件因此不验「好不好看」，只验三件硬事：**全文在场、一键可复制、两键互不干扰**，
 * 外加一条「别误伤另外两档」。
 *
 * 配方沿用 `ChatScreenUiTest`（RECON-P2D §4）：`createComposeRule()` + Robolectric +
 * `qualifiers = "w420dp-h2400dp"`（视口够高，滚动容器里的 Text 才稳进语义树）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h2400dp")
class InstallGateFailedTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * 现场形状的 cause：libcore 的 IOException 消息（含 `error=2`）+ 多行 stderr 尾巴。
     * 三个必查点（errno、中间说明、最后一行）全部落在**旧版 6 行截断够不到的位置**。
     */
    private val stderrTail = listOf(
        "node:internal/modules/cjs/loader:1140",
        "  throw err;",
        "        ^",
        "Error: Cannot find module 'pi/dist/bundle/cli.js'",
        "    at Module._resolveFilename (node:internal/modules/cjs/loader:1137:34)",
        "    at node:internal/main/run_main_module:28:49",
        "    at Module.executeUserEntryPoint [as runMain] (node:internal/modules/run_main:127:5)",
        TAIL_LINE,
    ).joinToString("\n")

    private val longCause: String =
        "spawn 失败：Cannot run program \"/data/user/0/works.earendil.pihost/lib/libpi_node.so\": " +
            "error=2, No such file or directory；" +
            "退避额度用完（3 次重启全部失败）。以下 stderr 尾巴逐字保留：\n$stderrTail"

    private fun showFailed(cause: String = longCause, onInstall: () -> Unit = {}) {
        composeRule.setContent {
            PiHostTheme { InstallGate(state = ChatUiState.Failed(cause), onInstall = onInstall) }
        }
    }

    // ─① cause 全文进语义树，且没有任何省略号截断 ─────────────────────────────────

    @Test
    fun `01 a long multiline cause is rendered in full without ellipsis`() {
        assertTrue("用例前提：cause 必须够长（≥300 字符）才测得出截断，实际 ${longCause.length}", longCause.length >= 300)
        assertTrue("用例前提：必须含换行（stderr 尾巴是多行）", longCause.contains("\n"))

        showFailed()

        val node = composeRule.onNodeWithTag(ChatTags.INSTALL_CAUSE)
        // 用成员 assertExists（不是扩展）：cause 比 280.dp 的滚动视口高，
        // 「整块在屏内」这时是假命题；本用例要证的恰恰是**语义树里装着全文**（可见性由滚动负责）。
        node.assertExists()
        // ①语义层：assertTextEquals 取节点的**完整**语义文本（trim 只掐首尾空白，本 cause 首尾无空白）。
        node.assertTextEquals(longCause)
        // ②视觉层：这才是「撤掉 maxLines=6」的真护栏。**不能用文本断言证伪视觉截断**——
        // Compose 的 SemanticsProperties.Text 里本来就存全文，maxLines+Ellipsis 只裁画布不裁语义，
        // 所以 ① 在旧实现下同样绿（变异检查实测：把 maxLines=6/Ellipsis 加回去，① 依旧通过）。
        // 唯一可靠的指纹是节点高度，两个数都是本机跑出来的实测值（非估算）：
        //   恢复截断（maxLines=6） → "Actual height is 136.0.dp, expected at least 150.0.dp"
        //   当前实现（不截断）     → "Actual height is 196.0.dp"（阈值改成 9999.dp 探出来的）
        // 阈值取 180.dp：离截断档 44dp（抓得住 maxLines=7/8 这种"少截一点"的回退），
        // 离实测 196dp 留 16dp；Box 的 heightIn(max=280.dp) 不构成干扰——verticalScroll 让子节点
        // 以无限高度测量，196 < 280 本来也没被裁，且 assertHeightIsAtLeast 读的是 unclipped bounds。
        node.assertHeightIsAtLeast(180.dp)
    }

    // ─②「复制详情」存在、可点，剪贴板拿到的就是 cause 全文 ──────────────────────────

    @Test
    fun `02 copy button puts the exact full cause on the clipboard`() {
        showFailed()

        composeRule.onNodeWithTag(ChatTags.INSTALL_COPY).assertIsDisplayed()
        composeRule.onNodeWithText("复制详情").assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.INSTALL_COPY).performClick()
        composeRule.waitForIdle()

        val clipboard = RuntimeEnvironment.getApplication()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

        assertEquals("剪贴板必须与 state.cause 逐字相等（不截断、不加前缀）", longCause, copied)
        assertNotNullCopied(copied)
        assertTrue("errno 必须随复制出去，否则用户贴给助手还是定位不了", copied!!.contains("error=2"))
        assertTrue("最后一行也必须在", copied.endsWith(TAIL_LINE))
        // 旧版 Ellipsis 的残留指纹：一旦有人把截断挪回复制路径，这里会先红。
        assertFalse("复制内容不许带省略号", copied.contains("…"))
    }

    // ─③ 两键并存：复制不影响「重试」回调，重试也不碰剪贴板 ─────────────────────────

    @Test
    fun `03 retry and copy coexist without stealing each other`() {
        var clicks = 0
        showFailed(onInstall = { clicks++ })

        composeRule.onNodeWithTag(ChatTags.INSTALL_RETRY_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.INSTALL_COPY).assertIsDisplayed()

        composeRule.onNodeWithTag(ChatTags.INSTALL_COPY).performClick()
        assertEquals("点复制不该触发重试回调", 0, clicks)

        composeRule.onNodeWithTag(ChatTags.INSTALL_RETRY_BUTTON).performClick()
        assertEquals("点重试仍走 onInstall（P2C 立的老规矩不能被新键挤掉）", 1, clicks)
    }

    // ─④ 其余两档不受影响（Installing 的紧凑文案仍在，也没有多出复制键） ─────────────

    @Test
    fun `04 the installing branch keeps its own compact behaviour`() {
        var clicks = 0
        composeRule.setContent {
            PiHostTheme {
                InstallGate(
                    state = ChatUiState.Installing(InstallProgress.Extracting(7, 50)),
                    onInstall = { clicks++ },
                )
            }
        }

        composeRule.onNodeWithText("正在安装运行时").assertIsDisplayed()
        composeRule.onNodeWithText("解包 7/50").assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.INSTALL_CAUSE).assertDoesNotExist()
        composeRule.onNodeWithTag(ChatTags.INSTALL_COPY).assertDoesNotExist()
        assertEquals(0, clicks)
    }

    // ─⑤ NotInstalled 档也不该冒出复制键（三档各自独立的红线） ──────────────────────

    @Test
    fun `05 the not-installed branch stays free of diagnostic affordances`() {
        composeRule.setContent {
            PiHostTheme { InstallGate(state = ChatUiState.NotInstalled, onInstall = {}) }
        }
        composeRule.onNodeWithText("运行时未安装").assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.INSTALL_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatTags.INSTALL_COPY).assertDoesNotExist()
        composeRule.onNodeWithTag(ChatTags.INSTALL_CAUSE).assertDoesNotExist()
    }

    /** 剪贴板非空断言（Robolectric 的 ShadowClipboardManager 是否接住了 setPrimaryClip）。 */
    private fun assertNotNullCopied(copied: String?) {
        assertTrue("剪贴板读回为空——Robolectric 的 ShadowClipboardManager 没接住 setPrimaryClip", copied != null)
    }

    private companion object {
        /** 旧版 `maxLines=6` 必然看不见的那一行，放在最后当「全文到达」的探针。 */
        const val TAIL_LINE = "errno 收尾行：这一行在 maxLines=6 时代必然看不见"
    }
}
