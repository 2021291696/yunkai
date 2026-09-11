package com.zhuolin.yunkai.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuolin.yunkai.model.Conv
import com.zhuolin.yunkai.ui.theme.GlassTokens
import com.zhuolin.yunkai.ui.theme.LocalGlassScheme
import com.zhuolin.yunkai.ui.theme.TextDark
import com.zhuolin.yunkai.ui.theme.TextFaint
import com.zhuolin.yunkai.ui.theme.WarmOrange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 会话侧抽屉：会话列表（点击进入/长按删除）。
// 纯展示组件：数据由 Chat 传入，动作经回调上抛，自身不持有路由/DB 逻辑
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryDrawer(
    visible: Boolean,
    convs: List<Conv>,
    onClose: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConversation: (Long) -> Unit,
    onDeleteConversation: (Conv) -> Unit,
) {
    val glass = LocalGlassScheme.current

    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩：35% 压暗底层，抽屉轮廓立起来；淡入淡出；点外部即收起
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color(0x59000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose,
                    ),
            )
        }

        // 面板：从左侧滑出/滑回
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(tween(260)) { -it },
            exit = slideOutHorizontally(tween(240)) { -it },
        ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.86f)
                .background(glass.glassBgStrong) // 玻璃面板，透出壁纸
                .background(glass.glassBgStrong) // 双层同色叠加：玻璃提实（遮罩+玻璃组合）
                .statusBarsPadding() // 抽屉头部避让状态栏
                .navigationBarsPadding()
                .padding(top = 16.dp),
        ) {
            // 标题行让位 58 给常驻 ☰（☰ 不属于抽屉，全 app 只此一颗）
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 58.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("会话", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextDark, modifier = Modifier.weight(1f))
                // 设置入口：只留齿轮符号（无「设置」二字）
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(glass.glassBg)
                        .border(GlassTokens.BORDER_W.dp, glass.glassBorder, CircleShape)
                        .clickable(onClick = onOpenSettings),
                    contentAlignment = Alignment.Center,
                ) { Text("⚙", fontSize = 16.sp, color = glass.accent) }
                // 收起钮只留符号（无「收起」二字），圆玻璃钮对齐全 app 圆钮惯例
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(glass.glassBg)
                        .border(GlassTokens.BORDER_W.dp, glass.glassBorder, CircleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Text("✕", fontSize = 14.sp, color = glass.textHi) }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onNewConversation,
                    modifier = Modifier.weight(1f).height(34.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = WarmOrange, contentColor = androidx.compose.ui.graphics.Color.White),
                ) { Text("＋ 新对话", fontSize = 14.sp) }
            }

            if (convs.isEmpty()) {
                Text("暂无会话", fontSize = 14.sp, color = TextFaint, modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(convs, key = { "${it.id}_${it.updatedAt}" }) { c ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(glass.glassBg, RoundedCornerShape(12.dp))
                                .border(GlassTokens.BORDER_W.dp, glass.glassBorder, RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    onClick = { onOpenConversation(c.id) },
                                    onLongClick = { onDeleteConversation(c) },
                                )
                                .padding(12.dp),
                        ) {
                            Text(c.title, fontSize = 15.sp, color = TextDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(timeLabel(c.updatedAt), fontSize = 12.sp, color = TextFaint)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
        }
    }
}

// 会话时间标签：刚刚 / N分钟前 / N小时前 / 月-日（与鸿蒙版口径一致）
private fun timeLabel(ts: Long): String {
    val diffMin = (System.currentTimeMillis() - ts) / 60000
    if (diffMin < 1) return "刚刚"
    if (diffMin < 60) return "${diffMin}分钟前"
    if (diffMin < 60 * 24) return "${diffMin / 60}小时前"
    return SimpleDateFormat("M-d", Locale.getDefault()).format(Date(ts))
}


