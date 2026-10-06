package works.earendil.pihost.feature.chat

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * 工具链冒烟骨架（RECON-P2D §1）：只为验证 compose+ksp+hilt 的版本组合能编出 APK。
 * 业务实现（LazyColumn/ToolCard/InputBar/PermissionDialog）在规格书 §5/§6 落地时替换本文件。
 */
@Composable
fun ChatSkeleton() {
    Text(text = "chat skeleton")
}
