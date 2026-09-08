package com.zhuolin.yunkai.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.zhuolin.yunkai.ui.theme.CardGlass
import com.zhuolin.yunkai.ui.theme.TextDark
import com.zhuolin.yunkai.ui.theme.TopBarGlass
import com.zhuolin.yunkai.ui.theme.UserBubble
import com.zhuolin.yunkai.ui.theme.WarmOrange
import com.zhuolin.yunkai.ui.theme.WarmOrangeDeep
import kotlinx.coroutines.launch

// 对话页（打开即对话的家）：消息流 List（用户气泡/回答气泡/画布卡）+ AgentLoop 发送管线
// + 内联过程时间线卡 + 侧抽屉（会话列表 + 历史轮次）+ @提及解析 + 引导态
@Composable
fun ChatScreen(onOpenSettings: () -> Unit, onOpenCanvas: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as YunkaiApp
    val vm: ChatViewModel = viewModel(factory = viewModelFactory { initializer { ChatViewModel(app) } })
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<Conv?>(null) }

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
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding(),
    ) {
        // ===== 顶栏 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TopBarGlass)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                vm.title.value,
                fontSize = 19.sp,
                color = TextDark,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Button(
                onClick = { scope.launch { vm.openHistory() } },
                colors = ButtonDefaults.buttonColors(containerColor = CardGlass, contentColor = WarmOrangeDeep),
                modifier = Modifier.padding(start = 10.dp),
            ) { Text("历史") }
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
                GuidePage(onAsk = { q ->
                    vm.input.value = q
                    vm.send(context)
                })
            }
        }

        // ===== 底部输入区 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TopBarGlass)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = vm.input.value,
                onValueChange = { vm.input.value = it },
                placeholder = { Text("想弄懂什么？") },
                modifier = Modifier.weight(1f),
                enabled = !vm.loading.value,
                shape = RoundedCornerShape(21.dp),
                maxLines = 4,
            )
            Button(
                onClick = { if (vm.loading.value) vm.cancelLoading() else vm.send(context) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (vm.loading.value) CardGlass else WarmOrange,
                    contentColor = if (vm.loading.value) WarmOrangeDeep else Color.White,
                ),
            ) { Text(if (vm.loading.value) "停止" else "发送") }
        }
    }

    // ===== 侧抽屉：上半会话列表 + 下半历史轮次 =====
    HistoryDrawer(
        visible = vm.showHistory.value,
        convs = vm.convs.toList(),
        turns = vm.turns.toList(),
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
        onOpenTurn = { turnNo ->
            vm.showHistory.value = false
            // 轮次号 = 第 n 条 assistant 消息，滚动定位
            var seen = 0
            for ((idx, m) in vm.msgs.withIndex()) {
                if (m.role == "assistant") {
                    seen += 1
                    if (seen == turnNo) {
                        scope.launch { listState.animateScrollToItem(idx) }
                        break
                    }
                }
            }
        },
    )
}

// 消息渲染分发：user 气泡 / html 画布卡 / text 气泡
@Composable
private fun MessageItem(m: RenderMsg, onOpenCanvas: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        if (m.role == "user") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    m.content,
                    fontSize = 15.sp,
                    color = Color.White,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .background(UserBubble, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        } else if (m.kind == ReplyKind.HTML) {
            CanvasCard(html = m.content, onOpen = onOpenCanvas)
        } else {
            Text(
                m.content,
                fontSize = 15.sp,
                color = TextDark,
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .background(CardGlass, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardGlass, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("正在处理…", fontSize = 13.sp, color = com.zhuolin.yunkai.ui.theme.TextMuted, modifier = Modifier.weight(1f))
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(containerColor = CardGlass, contentColor = WarmOrangeDeep),
            ) { Text("取消", fontSize = 13.sp) }
        }
        for (e in timeline) {
            Text(
                timelineLabel(e),
                fontSize = 13.sp,
                color = if (e.kind == "tool_done") com.zhuolin.yunkai.ui.theme.TextDark else com.zhuolin.yunkai.ui.theme.TextMuted,
                maxLines = 1,
            )
        }
    }
}
