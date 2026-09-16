package com.zhuolin.yunkai.ui.flash

// 闪问面板宿主（透明 Activity）：修复点球时主界面已退后台、Activity 窗口 token 失效导致的
// BadTokenException: Unable to add window -- token null is not valid。
// ComponentActivity 自带 Lifecycle/ViewModelStore，ComposeView 完整可用；窗口由系统正常分配 token，
// 面板与主界面彻底解耦（主界面退后台照样能拉起）。透明主题见 Theme.Yunkai.Flash。
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhuolin.yunkai.YunkaiApp
import com.zhuolin.yunkai.store.ConfigStore
import com.zhuolin.yunkai.ui.theme.YunkaiTheme

class FlashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 键盘弹起上推窗口，底部输入行不被盖住（对齐原 overlay 窗口的 SOFT_INPUT_ADJUST_RESIZE）
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val app = applicationContext as YunkaiApp
        // VM 挂本 Activity 的 ViewModelStore：配置变化不丢会话，Activity 结束即释放
        val vm: FlashViewModel = ViewModelProvider(
            this,
            viewModelFactory { initializer { FlashViewModel(app) } },
        )[FlashViewModel::class.java]
        setContent {
            YunkaiTheme(darkTheme = isSystemInDarkTheme(), skin = ConfigStore.SKIN_CLEAR) {
                // 面板外区域：点空白 = 关闭（无涟漪，避免透明背景上大片水波纹）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(interactionSource = null, indication = null) { finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(PANEL_HEIGHT_FRACTION)
                            // 空 handler：只吃掉面板内空白区的点击，防止误触关闭
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {},
                    ) {
                        FlashPanelContent(vm = vm, onClose = { finish() })
                    }
                }
            }
        }
    }

    private companion object {
        // 半屏高度：对齐原 overlay 窗口（屏幕显示高度 55%），底部对齐
        const val PANEL_HEIGHT_FRACTION = 0.55f
    }
}
