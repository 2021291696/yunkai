package com.zhuolin.yunkai.ui.flash

// 悬浮球/磁贴宿主（2026-09-19 重构：入口=主对话，见 design-explorations 重构计划）：
// 透明 Activity 上浮可拖高度玻璃面板，面板内复用主对话 ChatScreen——
// 共享 YunkaiApp.chatViewModel 全局大脑（同一会话流/记忆/技能/画布，无旁路）。
// 形态：默认半屏（60%），顶部拉头拖高，松手 ≥85% 切全屏、≤50% 收起面板（PANEL_FULL_ENTER/PANEL_COLLAPSE）。
// 画布：面板内嵌 CanvasScreen（全屏态体验最佳）。
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuolin.yunkai.YunkaiApp
import com.zhuolin.yunkai.service.screen.ScreenNotify
import com.zhuolin.yunkai.ui.canvas.CanvasScreen
import com.zhuolin.yunkai.ui.chat.ChatScreen
import com.zhuolin.yunkai.ui.theme.GlassTokens
import com.zhuolin.yunkai.ui.theme.LocalGlassScheme
import com.zhuolin.yunkai.ui.theme.YunkaiTheme
import com.zhuolin.yunkai.ui.theme.panelScrimAlpha
import com.zhuolin.yunkai.store.ConfigStore

// 面板高度形态常量（拖拽阈值与缺省，均按屏幕高度占比）
private const val PANEL_DEFAULT_FRACTION = 0.6f
private const val PANEL_MIN_FRACTION = 0.45f
private const val PANEL_FULL_ENTER = 0.85f   // 松手 ≥ 此值切全屏
private const val PANEL_COLLAPSE = 0.5f      // 松手 ≤ 此值收起面板
private const val PANEL_FULL = 1f

// 入口统一（悬浮球/磁贴等非 Activity 上下文）
object FlashPanelLauncher {
    fun launch(context: android.content.Context) {
        val up = Intent(context, FlashActivity::class.java)
        up.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(up)
    }
}

class FlashActivity : ComponentActivity() {
    companion object {
        /** 面板是否在前台：read_screen/capture_screen 据此改读面板底下的 app（面板窗口会占住 rootInActiveWindow） */
        @Volatile
        var panelForeground = false
            private set
    }

    override fun onResume() {
        super.onResume()
        panelForeground = true
    }

    override fun onPause() {
        super.onPause()
        panelForeground = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as YunkaiApp
        val vm = app.chatViewModel
        vm.ensureStarted()   // 共享大脑未初始化（主界面从未打开）→ 最近活跃会话；已初始化则呈现当前状态
        val store = ConfigStore(applicationContext)
        setContent {
            val mode by store.themeModeFlow.collectAsState(initial = ConfigStore.THEME_SYSTEM)
            val dark = when (mode) {
                ConfigStore.THEME_DARK -> true
                ConfigStore.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            val skin by store.skinFlow.collectAsState(initial = ConfigStore.SKIN_CLEAR)
            YunkaiTheme(darkTheme = dark, skin = skin) {
                FlashPanelHost(store, onFinish = { finish() })
            }
        }
    }
}

@Composable
private fun FlashPanelHost(store: ConfigStore, onFinish: () -> Unit) {
    val glass = LocalGlassScheme.current
    val context = LocalContext.current
    val screenH = LocalConfiguration.current.screenHeightDp.toFloat()
    val density = LocalDensity.current
    var showCanvas by remember { mutableStateOf(false) }
    var fraction by remember { mutableStateOf(PANEL_DEFAULT_FRACTION) }
    val cardShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    // 面板底色浓度（设置→外观→面板底色）：paperBase 实底遮罩叠在玻璃上，默认 soft
    // 保证浮在任意 app 上时可读（真机 2026-09-25 用户反馈）
    val panelOpacity by store.panelOpacityFlow.collectAsState(initial = ConfigStore.PANEL_OPACITY_DEFAULT)
    val panelScrim = glass.paperBase.copy(alpha = panelScrimAlpha(panelOpacity))

    // 回前台清后台进度通知（对齐 MainActivity.onResume）
    LaunchedEffect(Unit) {
        ScreenNotify.cancelProgress(context.applicationContext)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) {
                detectTapGestures { onFinish() }
            }
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(fraction)
                // 面板区域吞掉点击：否则面板内非按钮区（列表空白/拉头/标签）的 tap
                // 会透传到遮罩层 detectTapGestures 误关面板（真机 2026-09-25 用户实测）
                .pointerInput(Unit) { detectTapGestures { } }
                .clip(cardShape)
                .background(glass.glassBgStrong)
                .background(panelScrim)
                .border(GlassTokens.BORDER_W.dp, glass.glassBorder, cardShape)
        ) {
            // ===== 拉头：拖动调高（松手 ≥0.85 全屏，≤0.5 收起面板）=====
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (fraction >= PANEL_FULL) Modifier.statusBarsPadding() else Modifier)
                    .padding(top = 10.dp, bottom = 4.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                // amount 是 px、screenH 是 dp：先按密度换算再相除，否则高密度设备灵敏度被放大 density 倍、阈值状态机失真（门0 B2）
                                val amountDp = with(density) { amount.toDp().value }
                                fraction = (fraction - amountDp / screenH).coerceIn(PANEL_MIN_FRACTION, PANEL_FULL)
                            },
                            onDragEnd = {
                                if (fraction <= PANEL_COLLAPSE) onFinish()
                                else fraction = if (fraction >= PANEL_FULL_ENTER) PANEL_FULL else PANEL_DEFAULT_FRACTION
                            }
                        )
                    },
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier
                        .width(44.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(glass.glassBorder)
                )
            }
            // ===== 工具行：视图切换（对话/画布）+ 收起 =====
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (fraction >= PANEL_FULL) Modifier.statusBarsPadding() else Modifier)
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("对话", fontSize = 15.sp, color = glass.textHi, modifier = Modifier.weight(1f))
                TextButton(onClick = { showCanvas = !showCanvas }) {
                    Text(if (showCanvas) "对话" else "画布", fontSize = 13.sp)
                }
                TextButton(onClick = onFinish) { Text("收起", fontSize = 13.sp) }
            }
            // ===== 主体：主对话（共享大脑）/ 全屏画布，互斥 =====
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (showCanvas) {
                    CanvasScreen(onBack = { showCanvas = false })
                } else {
                    ChatScreen(
                        embedded = true,
                        onOpenSettings = { onFinish() },   // 设置页在主界面：收起面板回主界面进
                        onOpenCanvas = { showCanvas = true }
                    )
                }
            }
        }
    }
}
