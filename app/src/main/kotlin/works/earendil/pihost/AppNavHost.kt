package works.earendil.pihost

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import works.earendil.pihost.feature.chat.ChatViewModel
import works.earendil.pihost.feature.chat.ui.ChatScreen

/**
 * 导航骨架（规格书 §4.3）：三条路由，chat 为默认；sessions/settings 只立占位（§13 偏差 2）。
 *
 * 入口形态 = 顶栏两个 [TextButton]（规格书允许「qoder 自定简单方案」）：
 * 不用 `Icons.*`，免得为两个图标拖进 `material-icons-core`（超出 §2 依赖白名单）。
 */
object Nav {
    const val ROUTE_CHAT: String = "chat"
    const val ROUTE_SESSIONS: String = "sessions"
    const val ROUTE_SETTINGS: String = "settings"

    const val TITLE: String = "pi host"
    const val ENTRY_SESSIONS: String = "会话"
    const val ENTRY_SETTINGS: String = "设置"

    /** 占位屏文案（规格书 §4.3 原文：各一个居中 Text「P2E」）。 */
    const val PLACEHOLDER: String = "P2E"

    const val TAG_SESSIONS = "route-sessions"
    const val TAG_SETTINGS = "route-settings"
}

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
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
                TextButton(onClick = { navController.navigateTo(Nav.ROUTE_SESSIONS) }) {
                    Text(Nav.ENTRY_SESSIONS)
                }
                TextButton(onClick = { navController.navigateTo(Nav.ROUTE_SETTINGS) }) {
                    Text(Nav.ENTRY_SETTINGS)
                }
            }
        }
        NavHost(
            navController = navController,
            startDestination = Nav.ROUTE_CHAT,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Nav.ROUTE_CHAT) { ChatScreenRoute() }
            composable(Nav.ROUTE_SESSIONS) { PlaceholderScreen(tag = Nav.TAG_SESSIONS, label = Nav.ENTRY_SESSIONS) }
            composable(Nav.ROUTE_SETTINGS) { PlaceholderScreen(tag = Nav.TAG_SETTINGS, label = Nav.ENTRY_SETTINGS) }
        }
    }
}

/** 顶栏入口的跳转纪律：单实例、不复活回退栈里的旧目标页。 */
private fun NavHostController.navigateTo(route: String) {
    navigate(route) {
        launchSingleTop = true
        restoreState = true
    }
}

/** chat 路由（§4.3 + §4.2.3）：真正的聊天主界面，VM 由 Hilt 按导航图作用域创建。 */
@Composable
internal fun ChatScreenRoute(viewModel: ChatViewModel = hiltViewModel()) {
    ChatScreen(viewModel = viewModel)
}

/** sessions/settings 占位（§13 偏差 2：SessionScanner 与 DataStore 留 P2E）。 */
@Composable
internal fun PlaceholderScreen(tag: String, label: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = label, style = MaterialTheme.typography.titleMedium)
            Text(text = Nav.PLACEHOLDER, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
