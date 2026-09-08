package com.zhuolin.yunkai.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuolin.yunkai.ui.theme.CardBorder
import com.zhuolin.yunkai.ui.theme.TextDark
import com.zhuolin.yunkai.ui.theme.TextMuted
import com.zhuolin.yunkai.ui.theme.WarmOrangeDeep

// 画布卡：消息流内嵌 WebView（每个 html 消息一个实例，MVP 接受多实例开销）。
// data:base64 URL 直接加载：明文 loadData 会把 CSS 的 #/% 当 URI 语法截断，必须 base64；
// HtmlGuard.sanitize 先行剥 script/补 viewport。
// 【M2 待办】LazyColumn 滚动销毁/重建导致画布卡反复重建、滚动卡顿——
//   可改缓存渲染快照，或进全屏画布页复用单实例（OpenMinis 笔记第四节同结论）
@Composable
fun CanvasCard(html: String, onOpen: () -> Unit) {
    val safe = androidx.compose.runtime.remember(html) {
        com.zhuolin.yunkai.service.HtmlGuard.sanitize(html)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp)),
    ) {
        // 卡头：全屏入口条（Web 区域手势被 WebView 吃掉，入口做在卡头条上）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("📘", fontSize = 13.sp)
            Text("画布 · 点此全屏查看", fontSize = 12.sp, color = TextMuted, modifier = Modifier.padding(start = 6.dp, end = 6.dp).weight(1f))
            Text("⤢", fontSize = 14.sp, color = WarmOrangeDeep)
        }

        if (safe == null) {
            // sanitize 失败兜底：显示占位而非空白卡
            Column(
                modifier = Modifier.fillMaxWidth().height(420.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("⚠️ 内容无法渲染", fontSize = 14.sp, color = TextMuted, modifier = Modifier.padding(top = 190.dp))
            }
        } else {
            HtmlCanvas(html = safe, modifier = Modifier.fillMaxWidth().height(420.dp))
        }
    }
}
