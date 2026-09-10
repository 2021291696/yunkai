package com.zhuolin.yunkai.ui.canvas

import android.util.Base64
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zhuolin.yunkai.service.HtmlGuard
import com.zhuolin.yunkai.ui.theme.LocalGlassScheme
import com.zhuolin.yunkai.ui.theme.TextDark
import com.zhuolin.yunkai.ui.theme.TextMuted

// 全屏画布页：整屏 WebView 展示消息流画布卡对应的 HTML。
// html 经 CanvasHolder 暂存传入；sanitize + data:base64 URL 方案（明文 loadData 有中文/#/% 截断坑，禁用）
@Composable
fun CanvasScreen(onBack: () -> Unit) {
    val raw = CanvasHolder.html
    val safe = remember(raw) { HtmlGuard.sanitize(raw) }

    Column(modifier = Modifier.fillMaxSize()) { // 透明底，透出壁纸层
        val glass = LocalGlassScheme.current
        // ===== 顶栏：玻璃圆返回钮 + 标题（对齐鸿蒙）=====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(glass.glassBg)
                    .border(0.5.dp, glass.glassBorder, CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("‹", fontSize = 18.sp, color = glass.textHi) }
            Text("画布", fontSize = 19.sp, color = TextDark, modifier = Modifier.padding(start = 12.dp))
        }

        // ===== 画布主体 =====
        if (safe == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("内容无法渲染", fontSize = 16.sp, color = TextMuted)
            }
        } else {
            HtmlCanvas(html = safe, modifier = Modifier.fillMaxSize())
        }
    }
}

// WebView 封装：JS 禁用（讲解页无需 JS，与 sanitize 剥 script 双保险）；
// data:base64 URL 加载；离开组合时销毁实例
@Composable
fun HtmlCanvas(html: String, modifier: Modifier = Modifier) {
    val url = remember(html) {
        val b64 = Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        "data:text/html;base64,$b64"
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = false
        }
    }
    DisposableEffect(Unit) {
        onDispose { webView.destroy() }
    }
    AndroidView(
        factory = { webView },
        update = { wv -> if (wv.url != url) wv.loadUrl(url) },
        modifier = modifier,
    )
}
