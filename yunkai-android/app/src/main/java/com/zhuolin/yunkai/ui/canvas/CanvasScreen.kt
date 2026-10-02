package com.zhuolin.yunkai.ui.canvas

import android.graphics.Color
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
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

// WebView 池（闪跳修复 2026-10-02）：LazyColumn 快速滚动会销毁/重建画布卡，旧实现
// 每次重建都新 WebView + loadUrl——白闪 + 重排即「快速滑动闪跳」（M2 待办清账）。
// 池按 html 缓存已加载实例：滚出进池、滚回复用（不重载不闪）。
// 门0 B1 修复（run-all 2026-10-02）：淘汰必须 entry.remove()——只 destroy 不 remove
// 会死循环持锁 ANR；acquire 与 recycle 两侧都封顶（recycle 侧无界膨胀是 B1 放大器）。
// 门0 W1：宿主 Activity 销毁经 YunkaiApp.ActivityLifecycleCallbacks 调 clear() 清池防泄漏。
object WebViewPool {
    private const val CAP = 3
    private val pool = LinkedHashMap<String, WebView>()

    private fun destroyOldest() {
        val it = pool.entries.iterator()
        val first = it.next()
        (first.value.parent as? android.view.ViewGroup)?.removeView(first.value)
        first.value.destroy()
        it.remove()
    }

    @Synchronized
    fun acquire(context: android.content.Context, html: String): WebView {
        pool.remove(html)?.let { return it }
        while (pool.size >= CAP) destroyOldest()
        val b64 = Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return WebView(context).apply {
            settings.javaScriptEnabled = false
            // 安全审计（run-1 NV-3 配套）：禁止页面级跳转（meta refresh/链接/表单提交）
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true
            }
            // 底色透明：首载期间透出卡片底色，替代白闪
            setBackgroundColor(Color.TRANSPARENT)
            loadUrl("data:text/html;base64,$b64")
        }
    }

    @Synchronized
    fun recycle(html: String, wv: WebView) {
        // 门0 W2：同键覆盖时被顶者必须销毁（防孤儿实例泄漏）
        pool.put(html, wv)?.let { old ->
            (old.parent as? android.view.ViewGroup)?.removeView(old)
            old.destroy()
        }
        while (pool.size > CAP) destroyOldest()
    }

    @Synchronized
    fun clear() {
        for (wv in pool.values) {
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        pool.clear()
    }
}

// WebView 封装：JS 禁用（讲解页无需 JS，与 sanitize 剥 script 双保险）；
// 池化复用；滚出组合进池而非销毁（快速滚动不再反复重建闪跳）
@Composable
fun HtmlCanvas(html: String, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val webView = remember(html) { WebViewPool.acquire(context, html) }
    DisposableEffect(html) {
        onDispose { WebViewPool.recycle(html, webView) }
    }
    AndroidView(
        factory = {
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView
        },
        modifier = modifier,
    )
}
