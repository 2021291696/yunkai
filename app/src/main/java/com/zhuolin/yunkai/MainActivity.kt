package com.zhuolin.yunkai

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.zhuolin.yunkai.store.ConfigStore
import com.zhuolin.yunkai.ui.NavRoot
import com.zhuolin.yunkai.ui.theme.WallpaperLayer
import com.zhuolin.yunkai.ui.theme.YunkaiTheme

class MainActivity : ComponentActivity() {
    companion object {
        // 前后台旗标（Q7）：后台时工具进度/结果走系统通知送达，前台不打扰
        @Volatile
        var activityForeground: Boolean = false

        // 闪问面板唤起旗标（M2b-T9b）：FloatingBall.openFlashIntent 写入，本页消费
        const val FLASH_EXTRA = "open_flash"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // 沉浸式：壁纸直通状态栏/导航栏，对齐鸿蒙版沉浸式窗口
        val store = ConfigStore(applicationContext)
        // POST_NOTIFICATIONS（API 33+）：屏幕感知后台进度/结果通知需要；拒绝则通知静默降级，不影响主流程
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41)
        }
        setContent {
            // 主题模式：跟随系统（默认）/ 浅色 / 深色——设置页写入 ConfigStore 即改主题
            val mode by store.themeModeFlow.collectAsState(initial = ConfigStore.THEME_SYSTEM)
            val dark = when (mode) {
                ConfigStore.THEME_DARK -> true
                ConfigStore.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            // 皮肤：clear 通透（默认）/ aurora 极光——设置页写入即生效（MainActivity 订阅 ConfigStore.skinFlow 换 scheme）
            val skin by store.skinFlow.collectAsState(initial = ConfigStore.SKIN_CLEAR)
            // 系统栏图标明暗随主题翻转（手动档时系统不知道，必须显式设，否则暗色下看不清时间/电量）
            LaunchedEffect(dark) {
                val bar = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                this@MainActivity.enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
            }
            YunkaiTheme(darkTheme = dark, skin = skin) {
                Box(Modifier.fillMaxSize()) {
                    WallpaperLayer() // 全局壁纸垫底层（不吃点击由下层页面自己消费）
                    NavRoot()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        activityForeground = true
        // 回前台即清后台进度通知（Q7：前台有完整时间线，通知残留是噪音）
        com.zhuolin.yunkai.service.screen.ScreenNotify.cancelProgress(applicationContext)
        // M2b-T9b：悬浮球点按唤起闪问面板——FloatingBall 侧只发 intent 把云开拉回前台并置 open_flash，
        // 面板窗口（ComposeView）始终由前台 Activity 承载
        maybeOpenFlashPanel()
    }

    // 已在前台时 launcher intent 走 onNewIntent（onResume 不会再触发），两条路都接；旗标消费即清，天然幂等
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeOpenFlashPanel()
    }

    private fun maybeOpenFlashPanel() {
        if (intent?.getBooleanExtra(FLASH_EXTRA, false) != true) return
        intent.removeExtra(FLASH_EXTRA)
        com.zhuolin.yunkai.ui.flash.FlashPanel.show(this)
    }

    override fun onPause() {
        super.onPause()
        activityForeground = false
    }
}
