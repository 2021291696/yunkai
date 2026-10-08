package com.zhuolin.yunkai.ui.settings

// 归档区（2026-10-07 用户定案）：已归档会话列表——可恢复，30 天后自动彻底删（启动清扫）。
// 入口=设置页「归档」行；条目显示标题+剩余保留天数，「恢复」钮回主列表。
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuolin.yunkai.YunkaiApp
import com.zhuolin.yunkai.model.Conv
import com.zhuolin.yunkai.store.ConversationRepo
import com.zhuolin.yunkai.ui.chat.glassBorder
import com.zhuolin.yunkai.ui.theme.GlassTokens
import com.zhuolin.yunkai.ui.theme.LocalGlassScheme
import com.zhuolin.yunkai.ui.theme.TextDark
import com.zhuolin.yunkai.ui.theme.TextFaint
import kotlinx.coroutines.launch

@Composable
fun ArchivedScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as YunkaiApp
    val glass = LocalGlassScheme.current
    val scope = rememberCoroutineScope()
    val archived = remember { mutableStateListOf<Conv>() }
    var tick by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }   // 首帧不闪「暂无归档」空态

    LaunchedEffect(tick) {
        archived.clear()
        archived.addAll(app.conversationRepo.listArchived())
        loaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(glass.glassBg)
                    .border(GlassTokens.BORDER_W.dp, glass.glassBorder, CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("‹", fontSize = 18.sp, color = glass.textHi) }
            Text("归档", fontSize = 26.sp, color = glass.textHi, fontWeight = FontWeight.Bold)
        }
        Text(
            "归档的会话保留 30 天，到期自动彻底删除；主列表长按会话可归档",
            fontSize = 11.sp, color = TextFaint,
        )

        if (loaded && archived.isEmpty()) {
            Text("暂无归档会话", fontSize = 14.sp, color = TextFaint, modifier = Modifier.padding(top = 24.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(archived, key = { it.id }) { c ->
                    // 向上取整：刚归档 5 秒也显示「剩 30 天」（整除截断会显示 29，与保留期文案矛盾）
                    val daysLeft = ((c.updatedAt + ConversationRepo.ARCHIVE_RETENTION_MS - System.currentTimeMillis() +
                        24 * 3600 * 1000L - 1) / (24 * 3600 * 1000L)).coerceIn(0, 30)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(glass.glassBg, RoundedCornerShape(12.dp))
                            .border(GlassTokens.BORDER_W.dp, glass.glassBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(c.title, fontSize = 15.sp, color = TextDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("剩 ${daysLeft} 天", fontSize = 11.sp, color = TextFaint)
                        }
                        Text(
                            "恢复",
                            fontSize = 13.sp,
                            color = glass.textHi,
                            modifier = Modifier
                                .glassBorder(RoundedCornerShape(50))
                                .clickable {
                                    scope.launch {
                                        app.conversationRepo.restore(c.id)
                                        tick++   // 触发列表刷新
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
