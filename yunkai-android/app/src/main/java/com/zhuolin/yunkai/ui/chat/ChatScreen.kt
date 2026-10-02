package com.zhuolin.yunkai.ui.chat

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(embedded: Boolean = false, onOpenSettings: () -> Unit, onOpenCanvas: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as YunkaiApp
    val vm: ChatViewModel = app.chatViewModel   // 全局共享大脑（悬浮球面板与主界面同一实例）
    val context = LocalContext.current
    val glass = LocalGlassScheme.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<Conv?>(null) }
    // ===== 一期附件：+ 面板（拍照/相册/文件）+ 多图 chips =====
    var showAttach by remember { mutableStateOf(false) }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.take(5).forEachIndexed { i, u -> vm.addPicked(PickedItem(u.toString(), "图片${i + 1}", "image/*", true)) }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            val name = queryDisplayName(context, u) ?: "附件"
            if (vm.isSupportedFile(name)) {
                vm.addPicked(PickedItem(u.toString(), name, "text/plain", false))
            } else {
                vm.toast(context, "暂不支持该类型（支持 txt / md / csv / docx / xlsx / pdf）")
            }
        }
    }

    // 抽屉打开时，系统返回键优先收起抽屉（而不是退出 app）
    BackHandler(enabled = vm.showHistory.value) { vm.showHistory.value = false }
    // 附件面板展开时，返回键只收面板
    BackHandler(enabled = showAttach) { showAttach = false }

    LaunchedEffect(Unit) { vm.initIfNeed(-1L) }
    // 时间线快照：只在步数变化时重建实例——原来每 chunk toList 新分配，作为不稳定参数
    // 每次 SSM equals 都付全量比较（timeline 只追加，size 即正确 key）
    val timelineSnapshot = remember(vm.timeline.size) { vm.timeline.toList() }
    // 新消息/生成起止 → 动画滚底（低频事件，动画体验保留）
    LaunchedEffect(vm.msgs.size, vm.loading.value) {
        val last = listState.layoutInfo.totalItemsCount
        if (last > 0) listState.animateScrollToItem(last - 1)
    }
    // 流式增长 → 仅贴底时瞬时跟随：不再以 streamText/thinking 为 key 逐 chunk 重启
    // animateScrollToItem（动画互相打断是流式期间抖动主因之一）；用户上滚回看时不拽人
    val pinnedToBottom by remember { derivedStateOf { !listState.canScrollForward } }
    LaunchedEffect(listState) {
        snapshotFlow { vm.streamText.value.length to vm.thinking.value.length }
            .collect {
                if (pinnedToBottom && listState.layoutInfo.totalItemsCount > 0) {
                    listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
                }
            }
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

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().imePadding(), // 透明底，透出 WallpaperLayer
        ) {
            // ===== 顶栏：左上角侧边栏钮 + 居中标题，无整条栏背景（对齐鸿蒙）=====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (embedded) Modifier else Modifier.statusBarsPadding())
                    .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Spacer(Modifier.size(34.dp)) // 左侧占位：标题保持视觉居中（拉头常驻最上层）
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
                        Column {
                            if (m.role != "user" && m.thinking.isNotEmpty()) {
                                ThinkingRow(m)
                            }
                            MessageItem(m, onOpenCanvas = {
                                com.zhuolin.yunkai.ui.canvas.CanvasHolder.html = m.content
                                onOpenCanvas()
                            }, onEdit = {
                                vm.startEdit(vm.msgs.indexOfFirst { it.id == m.id })
                            })
                        }
                    }
                    // M3 继续任务：上一轮到顶时出现（符号优先），点击以轨迹续跑
                    if (vm.canContinue.value && !vm.loading.value) {
                        item(key = "continue") {
                            ContinueChip(onContinue = { vm.resumeTask(context) })
                        }
                    }
                    // 过程卡：loading 实时看；失败/取消保持展开（三态规则）
                    if (vm.loading.value || vm.failed.value) {
                        // B1 流式气泡：增量文本非空时渲染生长中的回答（打字机体验）；
                        // 净空语义不变——流式渲染仅为预览，落库仍走唯一成功路径
                        item(key = "timeline") {
                            TimelineCard(
                                timeline = timelineSnapshot,
                                thinking = vm.thinking.value,
                                steps = vm.steps.value,
                                failed = vm.failed.value,
                                onCancel = { vm.cancelLoading() },
                            )
                        }
                        if (vm.streamText.value.isNotEmpty()) {
                            item(key = "stream") {
                                if (looksLikeCanvasStream(vm.streamText.value)) {
                                    // 画布类输出：整页 HTML 喂给 markdown 会逐 chunk 打碎成满屏
                                    // 碎片（真机 /eli5 实测），改占位卡衔接最终画布卡
                                    CanvasGeneratingCard()
                                } else {
                                    // 按内容记忆化：timeline/thinking 等旁路状态重组时不再重跑解析
                                    val streamMarkdown = remember(vm.streamText.value) {
                                        renderMarkdownSingle(vm.streamText.value)
                                    }
                                    Text(
                                        streamMarkdown,
                                        fontSize = 14.sp,
                                        lineHeight = 23.sp,
                                        color = glass.textHi,
                                        modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                }
                            }
                        }

                    }
                    // 写操作计划卡（M2a-T6）：非终态显示（待批准/执行中/暂停中），终态自动消失
                    item(key = "plan") { PlanCardHost(app.writePlanExecutor) }
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
                // 回到底部：离底>1/4屏即现身（流式期间上滚回看也照常），点击动画回底。
                // 按像素距离判：单条超长回答内部滚动时子项索引恒定，索引判据会永不出现（模拟器实测缺陷）
                val showJumpToBottom by remember {
                    derivedStateOf {
                        val info = listState.layoutInfo
                        val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
                        if (info.totalItemsCount == 0) return@derivedStateOf false
                        val gapPx = last.offset + last.size - info.viewportEndOffset
                        val axisPx = info.viewportEndOffset - info.viewportStartOffset
                        (info.totalItemsCount - 1 - last.index) > 0 || gapPx > axisPx * 0.25f
                    }
                }
                // 全限定：外层 Column 的 ColumnScope.AnimatedVisibility 扩展会抢走隐式接收器解析
                androidx.compose.animation.AnimatedVisibility(
                    visible = showJumpToBottom,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                    enter = fadeIn(tween(GlassTokens.MS_STD, easing = GlassTokens.EASE)),
                    exit = fadeOut(tween(GlassTokens.MS_SUBTLE, easing = GlassTokens.EASE)),
                ) {
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
                // 附件面板展开时：点消息区任意处收起面板
                if (showAttach) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .pointerInput(showAttach) { detectTapGestures { showAttach = false } },
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
                // 附件 chips：缩略图（图片）或文件名（txt），点 ✕ 移除
                if (vm.picked.value.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (item in vm.picked.value) {
                            if (item.isImage) {
                                // 图片 chip：缩略图方块 + 角标 ✕
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(glass.glassBgStrong)
                                        .border(GlassTokens.BORDER_W.dp, glass.glassBorder, RoundedCornerShape(10.dp))
                                        .clickable { vm.removePicked(item.uri) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    // 解码移出组合线程：BitmapFactory 是文件 IO，remember 里跑会卡首帧
                                    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, item.uri) {
                                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            loadThumb(context, item.uri)
                                        }
                                    }
                                    // 委托属性不能 smart cast：先落局部变量再判空
                                    val current = bmp
                                    if (current != null) {
                                        Image(
                                            bitmap = current,
                                            contentDescription = item.name,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                        )
                                    } else { Text("图", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = glass.textHi) }
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xAA000000)),
                                        contentAlignment = Alignment.Center,
                                    ) { Text("✕", fontSize = 9.sp, color = Color.White) }
                                }
                            } else {
                                // 文件 chip：类型徽标 + 文件名（自解释），✕ 内联
                                Row(
                                    modifier = Modifier
                                        .height(52.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(glass.glassBgStrong)
                                        .border(GlassTokens.BORDER_W.dp, glass.glassBorder, RoundedCornerShape(10.dp))
                                        .clickable { vm.removePicked(item.uri) }
                                        .padding(horizontal = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(glass.glassBg),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            item.name.substringAfterLast('.', "件").take(4).uppercase(),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = glass.textHi,
                                        )
                                    }
                                    Text(
                                        item.name,
                                        fontSize = 12.sp,
                                        color = glass.textHi,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 110.dp),
                                    )
                                    Text("✕", fontSize = 12.sp, color = glass.textLow)
                                }
                            }
                        }
                    }
                }
                // 排队条：生成中点 ↑ 的下一问挂这里；立即=打断当前，✕=退回输入框
                if (vm.queued.value.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "已排队：${vm.queued.value}",
                            fontSize = 12.sp,
                            color = glass.textMid,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "立即",
                            fontSize = 13.sp,
                            color = glass.accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { vm.sendQueuedNow(context) }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                        Text(
                            "✕",
                            fontSize = 13.sp,
                            color = glass.textLow,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { vm.clearQueued() }
                                .padding(4.dp),
                        )
                    }
                }
                // ===== 输入坞合体：输入行与附件选项同属一块玻璃（四角全圆角、无内部线条）=====
                val dockCorner by animateDpAsState(
                    if (showAttach) 26.dp else 100.dp,
                    tween(GlassTokens.MS_STD, easing = GlassTokens.EASE),
                    label = "dockCorner",
                )
                val dockShape = RoundedCornerShape(dockCorner)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(10.dp, dockShape, clip = false, ambientColor = Color(0x44000000), spotColor = Color(0x44000000))
                        .clip(dockShape)
                        .background(glass.glassBgStrong)
                        .glassBorder(dockShape),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 多行增高平滑过渡（maxLines=4 逐行增高不再硬跳）
                            .animateContentSize(tween(GlassTokens.MS_STD, easing = GlassTokens.EASE))
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(glass.glassBg)
                                .glassBorder(CircleShape)
                                .clickable(enabled = !vm.loading.value) { showAttach = !showAttach },
                            contentAlignment = Alignment.Center,
                        ) { Text("＋", fontSize = 17.sp, color = glass.textHi) }
                        TextField(
                            value = vm.input.value,
                            onValueChange = { vm.input.value = it },
                            placeholder = { Text("问我任何问题…", color = glass.textLow, fontSize = 14.sp) },
                        modifier = Modifier.weight(1f),
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
                                .clickable {
                                    // 生成中且无输入 = ■ 暂停；有输入 = ↑ 排队发送
                                    if (vm.loading.value && vm.input.value.isEmpty()) vm.cancelLoading() else vm.send(context)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (vm.loading.value && vm.input.value.isEmpty()) "■" else "↑",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                    }
                    // 附件选项：与输入行同一块玻璃；关闭 = ＋ 切换 / 点面板外 / 返回键
                    AnimatedVisibility(
                        visible = showAttach,
                        enter = expandVertically(
                            expandFrom = Alignment.Top,
                            animationSpec = tween(GlassTokens.MS_STD, easing = GlassTokens.EASE),
                        ) + fadeIn(tween(GlassTokens.MS_STD, easing = GlassTokens.EASE)),
                        exit = shrinkVertically(
                            shrinkTowards = Alignment.Top,
                            animationSpec = tween(GlassTokens.MS_STD, easing = GlassTokens.EASE),
                        ) + fadeOut(tween(GlassTokens.MS_SUBTLE, easing = GlassTokens.EASE)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            AttachRow("相册") { showAttach = false; pickImages.launch("image/*") }
                            AttachRow("文件") { showAttach = false; pickFile.launch(arrayOf("*/*")) }
                        }
                    }
                }
            }
        }

    }
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

    // 拉头（上长下短双横线）：全 app 只此一颗，展开时停在抽屉右缘之外（收起时停在屏幕左缘）
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
                        showAttach = false; scope.launch { vm.openHistory() }
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
                                showAttach = false
                                scope.launch { vm.openHistory() }
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.width(13.dp).height(1.8.dp).clip(RoundedCornerShape(1.dp)).background(glass.textHi))
                Box(Modifier.width(8.dp).height(1.8.dp).clip(RoundedCornerShape(1.dp)).background(glass.textHi))
            }
        }
    }
}

// 0.5dp 玻璃描边（对齐鸿蒙 ThemeTokens.BORDER_W）；Composable 扩展以便读当前色板
@Composable
private fun Modifier.glassBorder(shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.border(GlassTokens.BORDER_W.dp, LocalGlassScheme.current.glassBorder, shape)

private val BubbleShadow = Color(0x66000000)

// 消息渲染分发：user 气泡 / html 画布卡 / text 气泡
@Composable
private fun MessageItem(m: RenderMsg, onOpenCanvas: () -> Unit, onEdit: () -> Unit = {}) {
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
                        // 皮肤气泡：clear=双色同值（实色半透明）；aurora=品牌渐变
                        .background(
                            Brush.verticalGradient(listOf(glass.bubbleUserTop, glass.bubbleUser)),
                            RoundedCornerShape(
                                topStart = GlassTokens.R_BUBBLE.dp, topEnd = GlassTokens.R_BUBBLE.dp,
                                bottomEnd = GlassTokens.R_TIGHT.dp, bottomStart = GlassTokens.R_BUBBLE.dp,
                            ),
                        )
                        .clickable { onEdit() }
                        .padding(horizontal = 17.dp, vertical = 13.dp),
                )
            }
        } else if (m.kind == ReplyKind.HTML) {
            CanvasCard(html = m.content, onOpen = onOpenCanvas)
        } else {
            // AI 正文无气泡：直接排版在壁纸上；代码块独立卡片（2026-10-02：json 等可复制）
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                val blocks = remember(m.id, m.content) { renderMarkdownBlocks(m.content) }
                for (b in blocks) {
                    if (b.isCode) {
                        CodeBlockCard(b.codeText)
                    } else {
                        Text(
                            b.an,
                            fontSize = 14.sp,
                            lineHeight = 23.sp,
                            color = glass.textHi,
                        )
                    }
                }
            }
        }
    }
}

// M3 继续任务 chip：上一轮到顶后出现；符号优先（用户偏好），玻璃系描边弱化不抢正文
@Composable
private fun ContinueChip(onContinue: () -> Unit) {
    val glass = LocalGlassScheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(glass.glassBg.copy(alpha = 0.5f), RoundedCornerShape(GlassTokens.R_TIGHT.dp))
            .border(1.dp, glass.glassBorder, RoundedCornerShape(GlassTokens.R_TIGHT.dp))
            .clickable { onContinue() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            "▶",
            fontSize = 13.sp,
            color = glass.textHi,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            "继续未完成任务",
            fontSize = 13.sp,
            color = glass.textHi,
        )
    }
}

// 思考过程折叠行：默认收起，点按展开回看（三态规则的成功态）
@Composable
private fun ThinkingRow(m: RenderMsg) {
    val glass = LocalGlassScheme.current
    var expanded by remember { mutableStateOf(!m.thinkingCollapsed) }
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(glass.glassBg)
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "✦ 思考过程 · ${m.steps} 步",
                fontSize = 12.sp,
                color = glass.textMid,
                modifier = Modifier.weight(1f),
            )
            Text(if (expanded) "▾" else "▸", fontSize = 11.sp, color = glass.textLow)
        }
        if (expanded) {
            Text(
                m.thinking,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = glass.textMid,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

// 时间线事件 → 展示文案（tool_done 的 detail 由引擎截到 80 字，这里再截 40 字防换行刷屏）
private fun timelineLabel(e: com.zhuolin.yunkai.service.LoopEvent): String {
    if (e.kind == "tool_start") return "调用 ${e.toolName}…"
    if (e.kind == "tool_done") {
        val d = if (e.detail.isNotEmpty()) " " + e.detail.take(40) else ""
        return "✓ 完成$d"
    }
    if (e.kind == "answer") return "整理回答…"
    if (e.kind == "limit") return "已达步数上限"
    return e.kind
}

@Composable
private fun TimelineCard(
    timeline: List<com.zhuolin.yunkai.service.LoopEvent>,
    thinking: String,
    steps: Int,
    failed: Boolean,
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
            Text(if (failed) "已停止（过程保留）" else "正在处理…", fontSize = 13.sp, color = glass.textMid, modifier = Modifier.weight(1f))
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
        if (thinking.isNotEmpty()) {
            // 尾部截取按内容记忆化：思考流高频增长时不重复切串
            val tail = remember(thinking) {
                if (thinking.length > 600) "…" + thinking.takeLast(600) else thinking
            }
            Text(
                tail,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = glass.textMid.copy(alpha = 0.75f),
            )
        }
    }
}

// 面板行：整行可点，左图标右文案
@Composable
private fun AttachRow(label: String, onClick: () -> Unit) {
    val glass = LocalGlassScheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = glass.textHi)
    }
}

// 本地 uri 缩略图（12MP 相册图必须降采样，否则 chips 会 OOM）
private fun loadThumb(context: android.content.Context, uri: String): androidx.compose.ui.graphics.ImageBitmap? {
    return try {
        val u = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 128) sample *= 2
        val bmp = context.contentResolver.openInputStream(u)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        bmp?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

// OpenDocument 返回的 uri → 显示名（DISPLAY_NAME 查询失败回落 null）
private fun queryDisplayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

// 流式画布探测：与落库判定同口径（HtmlGuard.sanitize 剥```围栏后看 <!DOCTYPE/<html）。
// 命中 = 本轮输出是画布页 → 流式区显示占位卡，而非把整页 HTML 喂给 markdown 打碎成满屏碎片
private fun looksLikeCanvasStream(raw: String): Boolean {
    var t = raw.trim()
    if (t.startsWith("```")) t = t.replace(Regex("^```[a-zA-Z]*\\s*"), "")
    val lower = t.lowercase()
    if (lower.startsWith("<!doctype") || lower.startsWith("<html")) return true
    return lower.take(256).contains("<html")
}

// 画布生成中占位卡：外观对齐 CanvasCard 卡头，完成后由落库的画布卡自然顶替
@Composable
private fun CanvasGeneratingCard() {
    val glass = LocalGlassScheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GlassTokens.R_CARD.dp))
            .background(glass.glassBg)
            .glassBorder(RoundedCornerShape(GlassTokens.R_CARD.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = glass.accent,
        )
        Spacer(Modifier.size(10.dp))
        Text("画布生成中…", fontSize = 13.sp, color = glass.textMid)
    }
}
