package works.earendil.pihost.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 探针用假接口与假模块（RECON-P2D §3/§4 只验工具链形状，不验业务语义）。 */
interface ProbeApi {
    fun ping(): String
}

@Module
@InstallIn(SingletonComponent::class)
object ProbeModule {
    @Provides
    @Singleton
    fun probe(): ProbeApi = object : ProbeApi {
        override fun ping(): String = "pong"
    }
}

/**
 * 工具链探针 1（规格书 §8.4）：`createComposeRule()` 在 Robolectric 4.16 + ui-test-junit4 +
 * ui-test-manifest + includeAndroidResources 下的可用面。三条用例把 §6/§7 真正要用到的
 * 交互姿势（文本选择 / TextField 输入 / 点击 / LazyColumn 滚动）一次验完。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposeProbeTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * 实测坑（RECON-P2D §4）：`assertTextEquals` 只看**本节点**的 Text 语义、不聚合子节点，
     * 在容器（Box/Column）上断言会失败——容器用 `testTag` + `assertIsDisplayed`，
     * 文本用 `onNodeWithText` 选择。
     */
    @Test
    fun composeRuleRendersUnderRobolectric() {
        composeRule.setContent { ProbeScreen() }
        composeRule.onNodeWithTag(TAG).assertIsDisplayed()
        composeRule.onNodeWithText(TEXT).assertIsDisplayed()
    }

    /** OutlinedTextField 输入 + 按钮点击 + 状态回显（§6 InputBar 的测试姿势）。 */
    @Test
    fun textFieldAndClickAreDrivenUnderRobolectric() {
        var value by mutableStateOf("")
        var clicks by mutableIntStateOf(0)
        composeRule.setContent {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.testTag(TAG_INPUT),
                )
                TextButton(onClick = { clicks++ }) { Text("发送") }
                Text("clicks=$clicks", modifier = Modifier.testTag(TAG_COUNTER))
            }
        }
        composeRule.onNodeWithTag(TAG_INPUT).performTextInput("hello")
        assertEquals("hello", value)
        composeRule.onNodeWithText("发送").performClick()
        composeRule.onNodeWithTag(TAG_COUNTER).assertTextEquals("clicks=1")
    }

    /** LazyColumn 滚动到指定条目（§6 transcript 列表 + 自动滚底的测试姿势）。 */
    @Test
    fun lazyColumnScrollsUnderRobolectric() {
        composeRule.setContent {
            LazyColumn(modifier = Modifier.testTag(TAG_LIST)) {
                items((1..80).map { "item-$it" }) { label -> Text(label) }
            }
        }
        composeRule.onNodeWithText("item-1").assertIsDisplayed()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(79)
        composeRule.onNodeWithText("item-80").assertIsDisplayed()
    }

    companion object {
        const val TAG: String = "probe-screen"
        const val TEXT: String = "probe-ok"
        const val TAG_INPUT = "probe-input"
        const val TAG_COUNTER = "probe-counter"
        const val TAG_LIST = "probe-list"
    }
}

@Composable
private fun ProbeScreen() {
    Column(modifier = Modifier.testTag(ComposeProbeTest.TAG)) {
        Text(ComposeProbeTest.TEXT)
    }
}

/**
 * 工具链探针 2（规格书 §8.3）：Hilt 测试图在 Robolectric 下能否建起来并注入。
 *
 * **实测配方**（RECON-P2D §3）：`hilt-android-testing:2.57.1` 的 aar 里
 * **没有 `HiltTestRunner` 这个类**（aar class 清单实测只有 HiltAndroidRule / HiltAndroidTest /
 * HiltTestApplication / BindValue / BindValueIntoSet / UninstallModules / CustomTestApplication /
 * SkipTestInjection / OnComponentReadyRunner / MarkThatRulesRanRule），
 * 所以 Robolectric 路径只能用 `RobolectricTestRunner` +
 * `@Config(application = HiltTestApplication::class)` + `@HiltAndroidTest` + [HiltAndroidRule]。
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = HiltTestApplication::class)
class HiltProbeTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var probe: ProbeApi

    @Test
    fun hiltGraphInjectsUnderRobolectric() {
        hiltRule.inject()
        assertEquals("pong", probe.ping())
    }
}

/**
 * 工具链探针 3（§8.3 + §8.4 交叉）：同一用例里 **Hilt 注入 + Compose rule** 共存，
 * 且规则顺序正确（Hilt 先建图、Compose 后建宿主）。app 侧 §7 的用例就是这个形状。
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = HiltTestApplication::class)
class HiltComposeProbeTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    @Inject
    lateinit var probe: ProbeApi

    @Test
    fun injectedValueReachesComposeTree() {
        hiltRule.inject()
        composeRule.setContent { Text(probe.ping(), modifier = Modifier.testTag("probe-value")) }
        composeRule.onNodeWithText("pong").assertIsDisplayed()
    }
}
