package com.zhuolin.yunkai.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zhuolin.yunkai.service.screen.A11yWatchdog
import com.zhuolin.yunkai.ui.canvas.CanvasScreen
import com.zhuolin.yunkai.ui.chat.ChatScreen
import com.zhuolin.yunkai.ui.memory.MemoryManageScreen
import com.zhuolin.yunkai.ui.settings.SettingsScreen
import com.zhuolin.yunkai.ui.skills.SkillManageScreen
import com.zhuolin.yunkai.ui.theme.GlassTokens

// 单 Activity + NavHost：chat 为家（打开即对话），settings/skills/canvas/memory 为二级页
// 转场：共享轴 X——前进新页自右滑入 1/4 屏 + 淡入，旧页反向滑出；返回镜像。
// 时长/曲线取动效词汇表（对齐鸿蒙系统级页面滑动转场的方向语义）
@Composable
fun NavRoot() {
    val navController = rememberNavController()
    val context = LocalContext.current
    // 无障碍运行时看护（2026-10-07 用户定案）：服务断开确认后（后台已发通知）回到 app 弹修复引导。
    // 忽略=本断开期内不再弹；重连（onServiceConnected/onRebind）自动复位，下次断开重新弹
    var a11yDialogDismissed by remember { mutableStateOf(false) }
    val a11yDown = A11yWatchdog.disconnected.value
    LaunchedEffect(a11yDown) {
        if (a11yDown) a11yDialogDismissed = false   // 新断开期：重新允许弹窗
    }
    if (a11yDown && !a11yDialogDismissed) {
        AlertDialog(
            onDismissRequest = { a11yDialogDismissed = true },
            title = { Text("无障碍服务已断开") },
            text = { Text("悬浮球、读屏、写操作已失效。可在系统设置中重新开启，或到「设置 → 必要权限」处理。") },
            confirmButton = {
                TextButton(onClick = {
                    a11yDialogDismissed = true
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("去修复") }
            },
            dismissButton = {
                TextButton(onClick = { a11yDialogDismissed = true }) { Text("忽略") }
            },
        )
    }
    // 共享轴 X：前进=新页自右入/旧页向左出；返回=镜像（旧页向右出/下页自左回）
    fun enterFromRight() = slideInHorizontally(
        tween(GlassTokens.MS_EMPH, easing = GlassTokens.EASE),
    ) { it / 4 } + fadeIn(tween(GlassTokens.MS_EMPH, easing = GlassTokens.EASE))
    fun exitToLeft() = slideOutHorizontally(
        tween(GlassTokens.MS_EMPH, easing = GlassTokens.EASE),
    ) { -it / 4 } + fadeOut(tween(GlassTokens.MS_EMPH, easing = GlassTokens.EASE))
    fun enterFromLeft() = slideInHorizontally(
        tween(GlassTokens.MS_EMPH, easing = GlassTokens.EASE),
    ) { -it / 4 } + fadeIn(tween(GlassTokens.MS_EMPH, easing = GlassTokens.EASE))
    fun exitToRight() = slideOutHorizontally(
        tween(GlassTokens.MS_EMPH, easing = GlassTokens.EASE),
    ) { it / 4 } + fadeOut(tween(GlassTokens.MS_EMPH, easing = GlassTokens.EASE))

    NavHost(
        navController = navController,
        startDestination = "chat",
        enterTransition = { enterFromRight() },
        exitTransition = { exitToLeft() },
        popEnterTransition = { enterFromLeft() },
        popExitTransition = { exitToRight() },
    ) {
        composable("chat") {
            ChatScreen(
                onOpenSettings = { navController.navigate("settings") },
                onOpenCanvas = { navController.navigate("canvas") },
            )
        }
        composable("settings") {
            SettingsScreen(
                onOpenSkills = { navController.navigate("skills") },
                onOpenMemory = { navController.navigate("memory") },
                onOpenArchived = { navController.navigate("archived") },
                onBack = { navController.popBackStack() },
            )
        }
        composable("archived") {
            com.zhuolin.yunkai.ui.settings.ArchivedScreen(onBack = { navController.popBackStack() })
        }
        composable("skills") {
            SkillManageScreen(onBack = { navController.popBackStack() })
        }
        composable("canvas") {
            CanvasScreen(onBack = { navController.popBackStack() })
        }
        composable("memory") {
            MemoryManageScreen(onBack = { navController.popBackStack() })
        }
    }
}
