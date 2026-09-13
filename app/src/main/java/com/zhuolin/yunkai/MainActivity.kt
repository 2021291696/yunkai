package com.zhuolin.yunkai

import android.graphics.Color
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // 沉浸式：壁纸直通状态栏/导航栏，对齐鸿蒙版沉浸式窗口
        val store = ConfigStore(applicationContext)
        setContent {
            // 主题模式：跟随系统（默认）/ 浅色 / 深色——设置页写入 ConfigStore 即改主题
            val mode by store.themeModeFlow.collectAsState(initial = ConfigStore.THEME_SYSTEM)
            val dark = when (mode) {
                ConfigStore.THEME_DARK -> true
                ConfigStore.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            // 皮肤：clear 通透（默认）/ aurora 极光——设置页写入即生效
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
}
