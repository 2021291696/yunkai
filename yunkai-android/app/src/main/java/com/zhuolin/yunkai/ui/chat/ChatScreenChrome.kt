package com.zhuolin.yunkai.ui.chat

// 聊天页 chrome 部件（自 ChatScreen 拆出，门0 W-B4/P5 单文件 500 行收口）：
// 顶栏 / 回底按钮 / 抽屉拉头。全部 internal，ChatScreen 同包直用。

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.zhuolin.yunkai.ui.theme.GlassTokens
import com.zhuolin.yunkai.ui.theme.LocalGlassScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 顶栏：左上角侧边栏钮 + 居中标题，无整条栏背景（对齐鸿蒙）
@Composable
internal fun ChatTopBar(title: String, embedded: Boolean) {
    val glass = LocalGlassScheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (embedded) Modifier else Modifier.statusBarsPadding())
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.size(34.dp)) // 左侧占位：标题保持视觉居中（拉头常驻最上层）
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = glass.textHi,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Spacer(Modifier.size(34.dp)) // 右侧等宽占位：标题保持视觉居中
    }
}

// 回到底部按钮：离底>1/4 屏即现身（流式期间上滚回看也照常），点击动画回底。
// 按像素距离判：单条超长回答内部滚动时子项索引恒定，索引判据会永不出现（模拟器实测缺陷）
@Composable
internal fun JumpToBottomButton(listState: LazyListState, scope: CoroutineScope) {
    val glass = LocalGlassScheme.current
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(glass.glassBgStrong)
            .glassBorder(CircleShape)
            .clickable {
                scope.launch {
                    val last = listState.layoutInfo.totalItemsCount
                    if (last > 0) listState.animateScrollToItem(last - 1)
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text("↓", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = glass.textHi)
    }
}

// 拉头（上长下短双横线）：全 app 只此一颗，展开时停在抽屉右缘之外（收起时停在屏幕左缘）
@Composable
internal fun DrawerHandle(
    vm: ChatViewModel,
    embedded: Boolean,
    onCloseAttach: () -> Unit,
    scope: CoroutineScope,
) {
    val glass = LocalGlassScheme.current
    val drawerW = (LocalConfiguration.current.screenWidthDp * 0.80f).dp
    val handleX by animateDpAsState(
        targetValue = if (vm.showHistory.value) drawerW + 6.dp else 0.dp,
        animationSpec = tween(GlassTokens.MS_EMPH, easing = GlassTokens.EASE),
        label = "handleX",
    )
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .then(if (embedded) Modifier else Modifier.statusBarsPadding())
                .offset(x = handleX)
                .padding(top = 12.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(glass.glassBg)
                .glassBorder(CircleShape)
                .clickable {
                    if (vm.showHistory.value) {
                        vm.showHistory.value = false
                    } else {
                        onCloseAttach(); scope.launch { vm.openHistory() }
                    }
                }
                .pointerInput(Unit) {
                    // 右滑拉头开抽屉（与面板左滑收对称）；拖拽消费移动事件后 tap 自然不触发
                    var dragTotal = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragTotal = 0f },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            dragTotal += amount
                        },
                        onDragEnd = {
                            if (dragTotal > 40.dp.toPx() && !vm.showHistory.value) {
                                onCloseAttach()
                                scope.launch { vm.openHistory() }
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp)) {
                Box(Modifier.width(13.dp).height(1.8.dp).clip(RoundedCornerShape(1.dp)).background(glass.textHi))
                Box(Modifier.width(8.dp).height(1.8.dp).clip(RoundedCornerShape(1.dp)).background(glass.textHi))
            }
        }
    }
}
