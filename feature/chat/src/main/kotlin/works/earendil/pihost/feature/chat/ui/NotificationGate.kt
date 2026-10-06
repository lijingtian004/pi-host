package works.earendil.pihost.feature.chat.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * POST_NOTIFICATIONS 请求（规格书 §6 末条）。
 *
 * **为什么做成可注入的 gate**（[ChatScreen] 的 `notificationGate` 参数）：
 * `rememberLauncherForActivityResult` 硬要求 `LocalContext.current` 同时是 `LifecycleOwner`，
 * 而 Robolectric 下 `createComposeRule()` 的宿主不是一个真 Activity（RECON-P2D §4 实测边界）。
 * 于是三条路各验各的：生产走这个真实现；`:feature:chat` 的 UI 测试注入空 lambda；
 * `:app` 的 Robolectric 测试（有真 Activity 生命周期）覆盖这一条分支。
 *
 * 拒绝不阻塞（§6 原文：「拒绝不阻塞，仅通知不可见」）：结果一概不回填状态机，
 * 且**一次进入只请求一次**（`requested` 闩 + 已授权短路），不刷权限弹窗。
 */
@Composable
fun PostNotificationsPermissionGate(active: Boolean) {
    val context = LocalContext.current
    var requested by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { requested = true }

    LaunchedEffect(active) {
        if (!active || requested) return@LaunchedEffect
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            requested = true
            return@LaunchedEffect
        }
        requested = true
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
