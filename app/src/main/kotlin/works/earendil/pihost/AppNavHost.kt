package works.earendil.pihost

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import works.earendil.pihost.feature.chat.ChatViewModel
import works.earendil.pihost.feature.chat.ui.ChatScreen
import works.earendil.pihost.feature.sessions.SessionsViewModel
import works.earendil.pihost.feature.sessions.ui.SessionsScreen
import works.earendil.pihost.feature.settings.SettingsViewModel
import works.earendil.pihost.feature.settings.ui.SettingsScreen

/**
 * 导航四路由（规格书 §4.4「NavHost 四路由全实装」）。
 *
 * **§9 偏差 1 照办：chat 恒为 startDestination**，sessions/settings 从顶栏进入。
 * P2D 的形状一点没动（顶栏 + NavHost 两层），只是把两条占位屏换成真屏，
 * 并给 chat 加了一条**带 sessionId 参数**的同构路由——
 * 「四路由」= `chat` / `chat/{sessionId}` / `sessions` / `settings`。
 *
 * 为什么参数化 chat 而不是让 sessions 屏只跳回 `chat`：
 * `:feature:sessions` 的 [works.earendil.pihost.feature.sessions.SessionNavRequest] 带回了
 * 引擎侧活会话 id，路由把它写进 URL 才是「目的地自述」——回退栈恢复、未来深链、
 * 以及「从列表进的是哪一条」的可观测面都靠它；测试据此断言（`MainActivityTest` ④/⑤）。
 *
 * 顶栏的两个入口是 **tab 语义**（[navigateToTab]），sessions 点行的跳转是**列表在下方**
 * （[openChatFromSessions]）：两条规则合起来保证回退栈永不长过 3 层、
 * 且「会话列表 → 聊天 → 返回」回到列表。
 */
object Nav {
    /** 参数名先声明：[ROUTE_CHAT_SESSION] 的拼装要用它做**唯一真相**。 */
    const val ARG_SESSION_ID: String = "sessionId"

    const val ROUTE_CHAT: String = "chat"

    /** chat 的带参同构路由（§4.4 原文的 `chat/{sessionId}`）。 */
    const val ROUTE_CHAT_SESSION: String = "chat/{$ARG_SESSION_ID}"

    const val ROUTE_SESSIONS: String = "sessions"
    const val ROUTE_SETTINGS: String = "settings"

    const val TITLE: String = "pi host"
    const val ENTRY_SESSIONS: String = "会话"
    const val ENTRY_SETTINGS: String = "设置"

    /**
     * 顶栏入口的测试标签。
     *
     * P2D 的测试按文本点（`onNodeWithText(Nav.ENTRY_SESSIONS)`）就够用，
     * P2E 之后不够了：sessions 屏自己的标题也叫「会话」、它顶栏还有一颗「设置」按钮，
     * 进入真屏后 `onNodeWithText` 会命中两个节点。入口本身一律按 tag 定位（文案照旧可改）。
     */
    const val TAG_ENTRY_SESSIONS: String = "nav-entry-sessions"
    const val TAG_ENTRY_SETTINGS: String = "nav-entry-settings"

    /** 落地路由：有活会话 id 就带参进 `chat/{sessionId}`，没有就回默认 chat。 */
    fun chatRoute(sessionId: String?): String =
        if (sessionId.isNullOrBlank()) ROUTE_CHAT else "$ROUTE_CHAT/$sessionId"
}

/**
 * 顶栏 + NavHost。
 *
 * `chatOwner` 的取法是本文件最关键的一行纪律：`LocalViewModelStoreOwner.current`
 * 必须在 **NavHost 之外**读——进了 `composable{}` 作用域，Navigation 会把它换成
 * 那条路由自己的 `NavBackStackEntry`，于是每次进 chat 都 new 一个 ChatViewModel，
 * 而 ChatViewModel 的 `init` 会走 `installAndStart()` → `engine.start()`：
 * **从列表页回一次聊天页就多 spawn 一条会话**（还会去撞 §13 偏差 6 的并行门）。
 * 拿到 Activity 的 owner 后，两条 chat 路由共用同一个 VM 实例，
 * 「切换会话」才是 sessions 屏发命令、chat 屏只做清场（[ChatViewModel.onExternalSessionRoll]）。
 */
@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    // 宿主必然提供该 owner：AppNavHost 只在 MainActivity.setContent 里组合（Compose 1.9 +
    // activity-compose 1.11 的 ComponentActivity 一定设了 LocalViewModelStoreOwner）。
    // 不做「取不到就退回 navController」的兜底：实测那条 elvis 在本依赖组合下推不出
    // ViewModelStoreOwner（编译器给公共父类型 Any，即 nav 的 controller 在这套版本里不满足上界），
    // 而任何「静默降级成条目作用域 VM」的写法恰好会带回多 spawn 那条回归——宁可当场崩。
    val chatOwner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "AppNavHost 必须在带 ViewModelStoreOwner 的宿主（Activity/Fragment）里组合"
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = Nav.TITLE, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { navController.navigateToTab(Nav.ROUTE_SESSIONS) },
                    modifier = Modifier.testTag(Nav.TAG_ENTRY_SESSIONS),
                ) {
                    Text(Nav.ENTRY_SESSIONS)
                }
                TextButton(
                    onClick = { navController.navigateToTab(Nav.ROUTE_SETTINGS) },
                    modifier = Modifier.testTag(Nav.TAG_ENTRY_SETTINGS),
                ) {
                    Text(Nav.ENTRY_SETTINGS)
                }
            }
        }
        NavHost(
            navController = navController,
            startDestination = Nav.ROUTE_CHAT,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Nav.ROUTE_CHAT) {
                ChatScreenRoute(chatOwner = chatOwner)
            }
            composable(
                route = Nav.ROUTE_CHAT_SESSION,
                arguments = listOf(navArgument(Nav.ARG_SESSION_ID) { type = NavType.StringType }),
            ) { entry: NavBackStackEntry ->
                ChatScreenRoute(
                    chatOwner = chatOwner,
                    sessionId = entry.arguments?.getString(Nav.ARG_SESSION_ID),
                )
            }
            composable(Nav.ROUTE_SESSIONS) {
                SessionsScreenRoute(
                    onOpenChat = { sessionId -> navController.openChatFromSessions(sessionId) },
                    onOpenSettings = { navController.navigateToTab(Nav.ROUTE_SETTINGS) },
                )
            }
            composable(Nav.ROUTE_SETTINGS) {
                SettingsScreenRoute(onBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * 顶栏入口的跳转纪律：**tab 去重，但不洗别的页**。
 *
 * `popUpTo(route)` 的目标是**要去的这条路由自己**（不是 startDestination）：
 * - 目标已在栈里 → 弹掉它之上的条目，配合 `launchSingleTop` 直接复用那一条，
 *   连点两次「会话」不会压出两条列表；
 * - 目标不在栈里 → popUpTo 落空，什么都不弹，于是 `[chat, sessions]` 点「设置」得到
 *   `[chat, sessions, settings]`，返回依次回到列表、回到聊天页——**保留 P2D 测试 ④ 的回退形状**。
 *
 * 反过来用 `popUpTo(ROUTE_CHAT)`（官方底部导航的写法）会让「列表 → 设置 → 返回」
 * 直接跳回聊天页，与 §4.4 那条「P2D 形状不破坏」读起来不一致，故弃用。
 * 交替点两个入口的最深栈是 `[chat, sessions, settings]`，有界。
 */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        // 显式走 PopUpToBuilder 形态：`popUpTo(route, inclusive = …)` 那两参重载在 nav 2.9 已废弃
        popUpTo(route) { inclusive = false }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * sessions 屏点行/FAB 的落地跳转：`popUpTo(ROUTE_SESSIONS)` 让列表留在 chat 之下，
 * 于是聊天页按返回回到的是**会话列表**（用户的直觉），而不是直接退出 App。
 *
 * `sessionId == null` 时落到不带参的 `chat`（[Nav.chatRoute]），
 * 由聊天页的安装门/启动流程接手——这正是 `SessionsViewModel` 纪律 1「没有活会话时只导航、不命令」
 * 的另一半：它把提示写进 notice，启动责任留在 chat。
 */
private fun NavHostController.openChatFromSessions(sessionId: String?) {
    navigate(Nav.chatRoute(sessionId)) {
        popUpTo(Nav.ROUTE_SESSIONS)
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * chat 路由（§4.3 + §4.2.3 + §4.4 的带参形态）。
 *
 * VM 走 [chatOwner]（Activity 作用域）而不是本条路由的 backstack entry，理由见 [AppNavHost]；
 * `hiltViewModel(viewModelStoreOwner = …)` 在两条 chat 路由上取到的是**同一个实例**。
 *
 * `sessionId != null` 意味着「用户是在列表页点进来的，命令也已经由列表页发过了」，
 * 所以这里只调 [ChatViewModel.onExternalSessionRoll] 做清场，不发任何 RPC。
 * 用 `LaunchedEffect(sessionId)` 而不是 `remember(sessionId)`：清场改的是 StateFlow，
 * 在组合阶段直接写状态会触发「compose 运行期写快照」的崩与重组循环。
 */
@Composable
internal fun ChatScreenRoute(
    chatOwner: ViewModelStoreOwner,
    sessionId: String? = null,
    viewModel: ChatViewModel = hiltViewModel(chatOwner),
) {
    LaunchedEffect(sessionId) {
        if (sessionId != null) viewModel.onExternalSessionRoll()
    }
    ChatScreen(viewModel = viewModel)
}

/**
 * sessions 路由（§4.1 的真屏）。
 *
 * VM **不**共享 Activity 作用域，用默认的导航条目作用域就够，而且更对：
 * [works.earendil.pihost.feature.sessions.SessionsViewModel] 的 `init` 只扫盘、不 spawn，
 * 没有 [ChatScreenRoute] 那条「重建即多起一条会话」的风险；
 * 反过来，条目销毁时 `pendingDelete`（二步确认的第一步）跟着一起没，
 * 不会出现「离开列表页再回来，删除确认卡还挂着」这种状态泄漏。
 */
@Composable
internal fun SessionsScreenRoute(
    onOpenChat: (sessionId: String?) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    SessionsScreen(viewModel = viewModel, onOpenChat = onOpenChat, onOpenSettings = onOpenSettings)
}

/**
 * settings 路由（§4.2 的真屏）：同上，导航条目作用域。
 * `onBack` = 弹一层，回到用户进来的那一页（chat 或从 sessions 点进来的列表）。
 */
@Composable
internal fun SettingsScreenRoute(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    SettingsScreen(viewModel = viewModel, onBack = onBack)
}
