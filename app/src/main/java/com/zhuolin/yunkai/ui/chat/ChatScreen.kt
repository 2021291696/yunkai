package com.zhuolin.yunkai.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhuolin.yunkai.YunkaiApp
import com.zhuolin.yunkai.model.Conv
import com.zhuolin.yunkai.service.ReplyKind
import com.zhuolin.yunkai.ui.canvas.CanvasCard
import com.zhuolin.yunkai.ui.guide.GuidePage
import com.zhuolin.yunkai.ui.theme.GlassTokens
import com.zhuolin.yunkai.ui.theme.LocalGlassScheme
import kotlinx.coroutines.launch

// 对话页（打开即对话的家）：消息流 List（用户气泡/回答气泡/画布卡）+ AgentLoop 发送管线
// + 内联过程时间线卡 + 侧抽屉（会话列表 + 历史轮次）+ @提及解析 + 引导态
// 视觉：方向 A 通透系玻璃——页面透明底透出壁纸层，悬浮玻璃圆钮 + 玻璃气泡 + 胶囊输入坞
@Composable
fun ChatScreen(onOpenSettings: () -> Unit, onOpenCanvas: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as YunkaiApp
    val vm: ChatViewModel = viewModel(factory = viewModelFactory { initializer { ChatViewModel(app) } })
    val context = LocalContext.current
    val glass = LocalGlassScheme.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<Conv?>(null) }

    // 抽屉打开时，系统返回键优先收起抽屉（而不是退出 app）
    BackHandler(enabled = vm.showHistory.value) { vm.showHistory.value = false }

    LaunchedEffect(Unit) { vm.initIfNeed(-1L) }
    // 新消息/时间线上屏自动滚底
    LaunchedEffect(vm.msgs.size, vm.timeline.size, vm.loading.value) {
        if (vm.msgs.isNotEmpty()) listState.animateScrollToItem(vm.msgs.size - 1)
    }

    // 删除会话确认弹窗（抽屉长按触发）
    deleteTarget?.let { conv ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除对话") },
            text = { Text("确定删除「${conv.title}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch { vm.doDelete(conv.id, context) }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().imePadding(), // 透明底，透出 WallpaperLayer
    ) {
        // ===== 顶栏：左上角侧边栏钮 + 居中标题，无整条栏背景（对齐鸿蒙）=====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(glass.glassBg)
                    .glassBorder(CircleShape)
                    .clickable { scope.launch { vm.openHistory() } },
                contentAlignment = Alignment.Center,
            ) { Text("☰", fontSize = 15.sp, color = glass.textHi) }
            Text(
                vm.title.value,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = glass.textHi,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Spacer(Modifier.size(34.dp)) // 右侧等宽占位：标题保持视觉居中
        }

        // ===== 消息流 =====
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(vm.msgs, key = { it.id }) { m ->
                    MessageItem(m, onOpenCanvas = {
                        com.zhuolin.yunkai.ui.canvas.CanvasHolder.html = m.content
                        onOpenCanvas()
                    })
                }
                // 时间线卡：只在 loading 时渲染于流末尾
                if (vm.loading.value) {
                    // B1 流式气泡：增量文本非空时渲染生长中的回答（打字机体验）；
                    // 净空语义不变——流式渲染仅为预览，落库仍走唯一成功路径
                    if (vm.streamText.value.isNotEmpty()) {
                        item(key = "stream") {
                            val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.82f).dp
                            val streamShape = RoundedCornerShape(
                                topStart = GlassTokens.R_BUBBLE.dp, topEnd = GlassTokens.R_BUBBLE.dp,
                                bottomEnd = GlassTokens.R_TIGHT.dp, bottomStart = GlassTokens.R_BUBBLE.dp,
                            )
                            Text(
                                renderMarkdownSingle(vm.streamText.value),
                                fontSize = 14.sp,
                                lineHeight = 23.sp,
                                color = glass.textHi,
                                modifier = Modifier
                                    .widthIn(max = maxBubble)
                                    .shadow(8.dp, streamShape, clip = false, ambientColor = BubbleShadow, spotColor = BubbleShadow)
                                    .background(glass.glassBg, streamShape)
                                    .border(GlassTokens.BORDER_W.dp, glass.glassBorder, streamShape)
                                    .padding(horizontal = 17.dp, vertical = 13.dp),
                            )
                        }
                    }
                    item(key = "timeline") {
                        TimelineCard(
                            timeline = vm.timeline.toList(),
                            onCancel = { vm.cancelLoading() },
                        )
                    }
                }
            }
            // 引导态：无任何消息时
            if (vm.msgs.isEmpty() && !vm.loading.value) {
                GuidePage(
                    onAsk = { q ->
                        vm.input.value = q
                        vm.send(context)
                    },
                    convCount = vm.convs.size,
                )
            }
        }

        // ===== 底部胶囊输入坞 =====
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 14.dp, end = 14.dp, bottom = 22.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(10.dp, CircleShape, clip = false, ambientColor = Color(0x44000000), spotColor = Color(0x44000000))
                    .clip(CircleShape)
                    .background(glass.glassBgStrong)
                    .glassBorder(CircleShape)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextField(
                    value = vm.input.value,
                    onValueChange = { vm.input.value = it },
                    placeholder = { Text("问我任何问题…", color = glass.textLow, fontSize = 14.sp) },
                    modifier = Modifier.weight(1f),
                    enabled = !vm.loading.value,
                    maxLines = 4,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = glass.textHi),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        cursorColor = glass.accent,
                    ),
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(glass.accent)
                        .clickable { if (vm.loading.value) vm.cancelLoading() else vm.send(context) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (vm.loading.value) "■" else "↑",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
            Text(
                "内容由 AI 生成，请甄别",
                fontSize = 10.sp,
                color = glass.textLow,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }

    // ===== 侧抽屉：会话列表 =====
    HistoryDrawer(
        visible = vm.showHistory.value,
        convs = vm.convs.toList(),
        onClose = { vm.showHistory.value = false },
        onNewConversation = {
            vm.startNewConversation()
        },
        onOpenSettings = {
            vm.showHistory.value = false
            onOpenSettings()
        },
        onOpenConversation = { id -> vm.openConversation(id) },
        onDeleteConversation = { c -> deleteTarget = c },
    )
}

// 0.5dp 玻璃描边（对齐鸿蒙 ThemeTokens.BORDER_W）；Composable 扩展以便读当前色板
@Composable
private fun Modifier.glassBorder(shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.border(GlassTokens.BORDER_W.dp, LocalGlassScheme.current.glassBorder, shape)

private val BubbleShadow = Color(0x66000000)

// 消息渲染分发：user 气泡 / html 画布卡 / text 气泡
@Composable
private fun MessageItem(m: RenderMsg, onOpenCanvas: () -> Unit) {
    val glass = LocalGlassScheme.current
    val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.82f).dp
    Box(modifier = Modifier.fillMaxWidth()) {
        if (m.role == "user") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    m.content,
                    fontSize = 14.sp,
                    lineHeight = 23.sp,
                    color = glass.textHi,
                    modifier = Modifier
                        .widthIn(max = maxBubble)
                        .shadow(8.dp, RoundedCornerShape(
                            topStart = GlassTokens.R_BUBBLE.dp, topEnd = GlassTokens.R_BUBBLE.dp,
                            bottomEnd = GlassTokens.R_TIGHT.dp, bottomStart = GlassTokens.R_BUBBLE.dp,
                        ), clip = false, ambientColor = BubbleShadow, spotColor = BubbleShadow)
                        .background(glass.bubbleUser, RoundedCornerShape(
                            topStart = GlassTokens.R_BUBBLE.dp, topEnd = GlassTokens.R_BUBBLE.dp,
                            bottomEnd = GlassTokens.R_TIGHT.dp, bottomStart = GlassTokens.R_BUBBLE.dp,
                        ))
                        .padding(horizontal = 17.dp, vertical = 13.dp),
                )
            }
        } else if (m.kind == ReplyKind.HTML) {
            CanvasCard(html = m.content, onOpen = onOpenCanvas)
        } else {
            val shape = RoundedCornerShape(
                topStart = GlassTokens.R_BUBBLE.dp, topEnd = GlassTokens.R_BUBBLE.dp,
                bottomEnd = GlassTokens.R_BUBBLE.dp, bottomStart = GlassTokens.R_TIGHT.dp,
            )
            Text(
                renderMarkdownSingle(m.content),
                fontSize = 14.sp,
                lineHeight = 23.sp,
                color = glass.textHi,
                modifier = Modifier
                    .widthIn(max = maxBubble)
                    .shadow(8.dp, shape, clip = false, ambientColor = BubbleShadow, spotColor = BubbleShadow)
                    .background(glass.glassBg, shape)
                    .border(GlassTokens.BORDER_W.dp, glass.glassBorder, shape)
                    .padding(horizontal = 17.dp, vertical = 13.dp),
            )
        }
    }
}

// 时间线事件 → 展示文案（tool_done 的 detail 由引擎截到 80 字，这里再截 40 字防换行刷屏）
private fun timelineLabel(e: com.zhuolin.yunkai.service.LoopEvent): String {
    if (e.kind == "tool_start") return "🔍 调用 ${e.toolName}…"
    if (e.kind == "tool_done") {
        val d = if (e.detail.isNotEmpty()) " " + e.detail.take(40) else ""
        return "✓ 完成$d"
    }
    if (e.kind == "answer") return "✍️ 整理回答…"
    if (e.kind == "limit") return "已达步数上限"
    return e.kind
}

@Composable
private fun TimelineCard(
    timeline: List<com.zhuolin.yunkai.service.LoopEvent>,
    onCancel: () -> Unit,
) {
    val glass = LocalGlassScheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GlassTokens.R_CARD.dp))
            .background(glass.glassBg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("正在处理…", fontSize = 13.sp, color = glass.textMid, modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(glass.glassBgStrong)
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) { Text("取消", fontSize = 13.sp, color = glass.accent) }
        }
        for (e in timeline) {
            Text(
                timelineLabel(e),
                fontSize = 13.sp,
                color = if (e.kind == "tool_done") glass.textHi else glass.textMid,
                maxLines = 1,
            )
        }
    }
}
