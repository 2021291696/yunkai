package com.zhuolin.yunkai.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
                onBack = { navController.popBackStack() },
            )
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
