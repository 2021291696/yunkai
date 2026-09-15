package com.zhuolin.yunkai.ui.flash

// 闪问悬浮面板（M2b-T9b）：半屏 Compose 对话面板，以 TYPE_ACCESSIBILITY_OVERLAY 窗口贴屏幕下半部
// （与悬浮球同权限通道，免悬浮窗授权）。宿主为前台 MainActivity：悬浮球点按只发 intent 把云开拉回前台，
// MainActivity.onResume/onNewIntent 见 open_flash 即 show(this)——规避 Service context 建 ComposeView 的坑。
// 生命周期：面板不随 Activity 销毁（窗口挂在 Activity 的 WindowManager 上，Activity 结束时进程通常仍在）——
// 关闭统一走 onClose/hide()；再次 show() 视为 toggle 关闭。
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhuolin.yunkai.YunkaiApp
import com.zhuolin.yunkai.MainActivity
import com.zhuolin.yunkai.ui.theme.ErrorRed
import com.zhuolin.yunkai.ui.theme.GlassTokens
import com.zhuolin.yunkai.ui.theme.LocalGlassScheme
import com.zhuolin.yunkai.ui.theme.TextFaint
import com.zhuolin.yunkai.ui.theme.YunkaiTheme

object FlashPanel {
    private var composeView: ComposeView? = null
    private var wm: WindowManager? = null

    fun show(activity: ComponentActivity) {
        if (composeView != null) { hide(); return }   // 已开则 toggle 关闭
        val windowManager = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val app = activity.applicationContext as YunkaiApp
        // VM 挂 Activity ViewModelStore：面板关了再开，会话状态（问答/时间线）不丢
        val vm: FlashViewModel = ViewModelProvider(
            activity,
            viewModelFactory { initializer { FlashViewModel(app) } },
        )[FlashViewModel::class.java]
        val view = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                YunkaiTheme(darkTheme = isSystemInDarkTheme(), skin = "clear") {
                    FlashPanelContent(vm = vm, onClose = { hide() })
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (activity.resources.displayMetrics.heightPixels * 0.55f).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE   // 底部输入行不被键盘盖住
        }
        try {
            windowManager.addView(view, params)
            composeView = view
            wm = windowManager
        } catch (e: Exception) {
            // 无障碍未连接/授权被撤时 2032 会被 WMS 拒（BadToken/Security）——记日志不崩主界面
            Log.e("yunkai", "FlashPanel.show failed: $e")
            vm.toast(activity, "闪问面板打开失败：${e.message}")
        }
    }

    fun hide() {
        try { composeView?.let { wm?.removeView(it) } } catch (e: Exception) {}
        composeView = null
        wm = null
    }
}

// 面板主体（简版对话）：顶部标题 + 关闭 / 中部回答区（出错、画布跳转、正文、步骤）/ 底部输入行
@Composable
internal fun FlashPanelContent(vm: FlashViewModel, onClose: () -> Unit) {
    val glass = LocalGlassScheme.current
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val shape = RoundedCornerShape(topStart = GlassTokens.R_CARD.dp, topEnd = GlassTokens.R_CARD.dp)
    // 流式/落稿/新步骤都追到底，用户不必手动滚
    LaunchedEffect(vm.streamText.value, vm.answer.value, vm.timeline.size) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(glass.glassBgStrong)
            .border(GlassTokens.BORDER_W.dp, glass.glassBorder, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ===== 顶栏 =====
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("闪问", fontSize = 19.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(glass.glassBg)
                    .border(GlassTokens.BORDER_W.dp, glass.glassBorder, CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Text("✕", fontSize = 14.sp, color = glass.textMid) }
        }

        // ===== 回答区 =====
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (vm.failed.value) Text("出错了", fontSize = 14.sp, color = ErrorRed)
            if (vm.canvasFile.value.isNotEmpty()) {
                Text(
                    "📄 画布已生成 · 点此在云开中打开",
                    fontSize = 14.sp,
                    color = glass.accent,
                    modifier = Modifier.fillMaxWidth().clickable { openApp(context) }.padding(vertical = 4.dp),
                )
            }
            val shown = vm.answer.value.ifEmpty { vm.streamText.value }
            if (shown.isNotEmpty()) {
                Text(shown, fontSize = 14.sp, lineHeight = 21.sp, color = glass.textHi)
            } else if (vm.loading.value) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("思考中…", fontSize = 13.sp, color = glass.textMid)
                }
            }
            // 步骤行：tool_start 计数编号，其余按事件类型给一行短文案
            var step = 0
            for (e in vm.timeline.toList()) {
                val label = when (e.kind) {
                    "tool_start" -> { step += 1; "步骤 $step：${e.toolName}" }
                    "tool_done" -> "✓ ${e.toolName}"
                    "answer" -> "整理回答…"
                    "limit" -> "已达步数上限"
                    else -> e.kind
                }
                Text(label, fontSize = 12.sp, color = TextFaint, maxLines = 1)
            }
        }

        // ===== 输入行 =====
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = vm.input.value,
                onValueChange = { vm.input.value = it },
                placeholder = { Text("闪问一句…", fontSize = 14.sp) },
                modifier = Modifier.weight(1f),
                maxLines = 3,
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.width(8.dp))
            if (vm.loading.value) {
                Button(onClick = { vm.cancel() }) { Text("停止") }
            } else {
                Button(
                    onClick = { vm.send(context) },
                    enabled = vm.input.value.isNotBlank(),
                ) { Text("发送") }
            }
        }
    }
}

// 「点此在云开中打开」：拉起主界面（SINGLE_TOP 复用现有实例，不叠新 MainActivity）
private fun openApp(context: Context) {
    context.startActivity(
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )
}
