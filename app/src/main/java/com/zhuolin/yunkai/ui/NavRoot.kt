package com.zhuolin.yunkai.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zhuolin.yunkai.ui.canvas.CanvasScreen
import com.zhuolin.yunkai.ui.chat.ChatScreen
import com.zhuolin.yunkai.ui.settings.SettingsScreen
import com.zhuolin.yunkai.ui.skills.SkillManageScreen

// 单 Activity + NavHost：chat 为家（打开即对话），settings/skills/canvas 为二级页
@Composable
fun NavRoot() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "chat") {
        composable("chat") {
            ChatScreen(
                onOpenSettings = { navController.navigate("settings") },
                onOpenCanvas = { navController.navigate("canvas") },
            )
        }
        composable("settings") {
            SettingsScreen(onOpenSkills = { navController.navigate("skills") })
        }
        composable("skills") {
            SkillManageScreen(onBack = { navController.popBackStack() })
        }
        composable("canvas") {
            CanvasScreen(onBack = { navController.popBackStack() })
        }
    }
}
