package com.zhuolin.yunkai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.zhuolin.yunkai.ui.NavRoot
import com.zhuolin.yunkai.ui.theme.YunkaiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            YunkaiTheme {
                NavRoot()
            }
        }
    }
}
