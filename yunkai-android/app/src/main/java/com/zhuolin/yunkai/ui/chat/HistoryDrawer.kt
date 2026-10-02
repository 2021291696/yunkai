package com.zhuolin.yunkai.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuolin.yunkai.model.Conv
import com.zhuolin.yunkai.ui.theme.GlassTokens
import com.zhuolin.yunkai.ui.theme.LocalGlassScheme
import com.zhuolin.yunkai.ui.theme.TextDark
import com.zhuolin.yunkai.ui.theme.TextFaint
import com.zhuolin.yunkai.ui.theme.WarmOrange
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 会话侧抽屉：会话列表（点击进入/长按删除）。
// 纯展示组件：数据由 Chat 传入，动作经回调上抛，自身不持有路由/DB 逻辑。
// 开合为位移驱动（原 AnimatedVisibility 滑入滑出）：左滑跟手收起、松手按阈值回弹/收起，
// 状态动画从当前进度接管，全程无跳变；遮罩透明度跟随开合进度。
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
    val scope = rememberCoroutineScope()
    val panelW = LocalConfiguration.current.screenWidthDp.dp * 0.80f
    val panelWf = panelW.value // Float，单位 dp
    val hiddenXf = -panelWf

    // 开合位移（Float 存 dp）：状态变化补间到目标位；拖拽期间 snapTo 跟手，松手由状态动画接管
    val offsetX = remember { Animatable(hiddenXf) }
    LaunchedEffect(visible, panelWf) {
        val target = if (visible) 0f else hiddenXf
        if (offsetX.targetValue != target) {
            offsetX.animateTo(target, tween(GlassTokens.MS_EMPH, easing = GlassTokens.EASE))
        }
    }

    // 收起且无动画在途时不渲染（省一层常驻 overdraw）
    if (visible || offsetX.value > hiddenXf) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 遮罩：35% 压暗上限，透明度随开合进度连续变化；点外部即收起
            val progress = ((offsetX.value + panelWf) / panelWf).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f * progress))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose,
                    ),
            )

            // 面板：位移驱动，左滑跟手收起
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.80f) // 收窄给拉头留外部空间：展开时拉头停在抽屉右缘之外
                    .offset(x = offsetX.value.dp)
                    .background(glass.glassBgStrong) // 双层同色叠加：玻璃提实（遮罩+玻璃组合）
                    .background(glass.glassBgStrong)
                    .statusBarsPadding() // 抽屉头部避让状态栏
                    .navigationBarsPadding()
                    .padding(top = 16.dp)
                    .pointerInput(Unit) {
                        var dragTotal = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dragTotal = 0f },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                dragTotal += amount
                                val candidate = (offsetX.value + amount.toDp().value).coerceAtMost(0f)
                                scope.launch { offsetX.snapTo(candidate) }
                            },
                            onDragEnd = {
                                val dragged = dragTotal < 0f
                                if (dragged && offsetX.value < -panelWf * 0.25f) {
                                    onClose() // 状态动画从当前拖拽位置继续滑出，无跳变
                                } else if (dragged) {
                                    scope.launch {
                                        offsetX.animateTo(0f, tween(GlassTokens.MS_STD, easing = GlassTokens.EASE))
                                    }
                                }
                            },
                        )
                    },
            ) {
                // 标题行让位 58 给常驻拉头；右侧让位 50 给拉头（开态停抽屉外），✕ 已由拉头取代
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 58.dp, end = 50.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("会话", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextDark, modifier = Modifier.weight(1f))
                    // 设置入口：只留齿轮符号（无「设置」二字）；✕ 已删——拉头 ☰ 即开关
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(glass.glassBg)
                            .border(GlassTokens.BORDER_W.dp, glass.glassBorder, CircleShape)
                            .clickable(onClick = onOpenSettings),
                        contentAlignment = Alignment.Center,
                    ) { Text("⚙", fontSize = 16.sp, color = glass.accent) }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onNewConversation,
                        modifier = Modifier.weight(1f).height(34.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = WarmOrange, contentColor = Color.White),
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
