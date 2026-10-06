package works.earendil.pihost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import works.earendil.pihost.feature.chat.ui.PiHostTheme

/**
 * 唯一 Activity（规格书 §4.2.3）：`@AndroidEntryPoint` + [setContent] + NavHost。
 *
 * P2C 的纯 View 探针链（LinearLayout + 500ms 轮询）整体退役：
 * 安装门 / RPC 探针 / 停机 三条能力改由 `:feature:chat` 的 ChatScreen 与
 * ChatViewModel 承担（规格书 §5/§6），测试覆盖面等价重写见 app 测试。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PiHostTheme {
                AppNavHost()
            }
        }
    }
}
