package com.zhuolin.yunkai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.zhuolin.yunkai.ui.NavRoot
import com.zhuolin.yunkai.ui.theme.WallpaperLayer
import com.zhuolin.yunkai.ui.theme.YunkaiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // 沉浸式：壁纸直通状态栏/导航栏，对齐鸿蒙版沉浸式窗口
        setContent {
            YunkaiTheme {
                Box(Modifier.fillMaxSize()) {
                    WallpaperLayer() // 全局壁纸垫底层（不吃点击由下层页面自己消费）
                    NavRoot()
                }
            }
        }
    }
}
