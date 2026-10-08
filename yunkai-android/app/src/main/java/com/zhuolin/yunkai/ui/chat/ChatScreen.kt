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
import androidx.compose.material3.OutlinedTextField
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
    // 会话管理（2026-10-07 定案）：重命名对话框目标（长按菜单触发）
    var renameTarget by remember { mutableStateOf<Conv?>(null) }
    var renameText by remember { mutableStateOf("") }
    // 消息操作条（2026-10-07 用户定案）：值=点按的那条消息 id，-1=无；点空白处/再点收起
    var activeActionMsg by remember { mutableStateOf(-1L) }
    // 消息删除确认：值=待删除的 msgs 下标
    var deleteMsgTarget by remember { mutableStateOf(-1) }
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

    // 重命名对话框（抽屉长按菜单 ✎ 触发）
    renameTarget?.let { conv ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = renameText
                    val c = conv
                    renameTarget = null
                    scope.launch { vm.renameConversation(c.id, t) }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }

    // 会话切换版本号观察（2026-10-07 审查 R5）：任何 load() 路径（含归档当前会话/悬浮球
    // ensureStarted）都递增 uiEpoch——瞬时 UI 态在此统一重置，操作条不再跨会话命中同 id 的
    // 另一条消息（RenderMsg.id 每个会话都从 1 重排）
    LaunchedEffect(vm.uiEpoch.value) {
        activeActionMsg = -1L
        deleteMsgTarget = -1
    }

    // 单条消息删除确认（操作条 🗑 触发）：删除该条+其后全部（与编辑同款截断语义，必须确认）
    if (deleteMsgTarget >= 0) {
        AlertDialog(
            onDismissRequest = { deleteMsgTarget = -1 },
            title = { Text("删除此消息") },
            text = { Text("此条之后的所有消息也将被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    val idx = deleteMsgTarget
                    deleteMsgTarget = -1
                    activeActionMsg = -1L
                    vm.deleteFrom(idx)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteMsgTarget = -1 }) { Text("取消") }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().imePadding(), // 透明底，透出 WallpaperLayer
        ) {
            // ===== 顶栏（已拆至 ChatScreenChrome.kt）=====
            ChatTopBar(vm.title.value, embedded)

            // ===== 消息流 =====
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(vm.msgs, key = { it.id }) { m ->
                        // 长按=复制某一部分（2026-10-07 用户定案）：SelectionContainer 提供系统选字
                        androidx.compose.foundation.text.selection.SelectionContainer {
                        Column {
                            if (m.role != "user" && m.thinking.isNotEmpty()) {
                                ThinkingRow(m)
                            }
                            // 失败轮重发（B 治理）：仅文本轮给重试钮（附件本体未持久化，回输入框人工补）
                            val canRetry = m.status == "failed" && m.dbId > 0L &&
                                !m.content.contains("[附件") && !m.content.contains("[图片×")
                            MessageItem(
                                m,
                                onOpenCanvas = {
                                    com.zhuolin.yunkai.ui.canvas.CanvasHolder.html = m.content
                                    onOpenCanvas()
                                },
                                actionsActive = activeActionMsg == m.id,
                                onActivateActions = {
                                    activeActionMsg = if (activeActionMsg == m.id) -1L else m.id
                                },
                                onEdit = {
                                    activeActionMsg = -1L
                                    vm.startEdit(vm.msgs.indexOfFirst { it.id == m.id })
                                },
                                onCopy = {
                                    activeActionMsg = -1L
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", m.content))
                                    android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                onDelete = {
                                    deleteMsgTarget = vm.msgs.indexOfFirst { it.id == m.id }
                                },
                                onRetry = if (canRetry) {
                                    { vm.retryFailed(context, m.dbId) }
                                } else null,
                            )
                        }
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
                // 回到底部：离底>1/4屏即现身（流式期间上滚回看也照常），点击动画回底（组件已拆至 ChatScreenChrome.kt）。
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
                    JumpToBottomButton(listState, scope)
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
                // 附件 chips（已拆至 ChatMessageParts.kt，P5 收口）：缩略图（图片）或文件名（txt），点 ✕ 移除
                PickedChips(vm, context)
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
                    // 编辑态标识条（2026-10-07 用户定案②③）：编辑中高亮提示+取消钮（取消=从快照恢复被截断消息）
                    if (vm.editing.value) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 8.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "✎ 正在编辑 — 此条之后的消息已暂存，发送后生效",
                                fontSize = 11.sp, lineHeight = 14.sp,
                                color = glass.accent,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "✕ 取消",
                                fontSize = 12.sp,
                                color = glass.textHi,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { vm.cancelEdit() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
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
                            placeholder = {
                    Text(
                        if (vm.editing.value) "编辑后发送即重新生成…" else "问我任何问题…",
                        color = glass.textLow, fontSize = 14.sp,
                    )
                },
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
        onRename = { c -> renameText = c.title; renameTarget = c },
        onTogglePin = { c -> scope.launch { vm.togglePinConversation(c.id) } },
        onArchive = { c -> scope.launch { vm.archiveConversation(c.id) } },
    )

    // 拉头（已拆至 ChatScreenChrome.kt）：全 app 只此一颗，展开时停在抽屉右缘之外（收起时停在屏幕左缘）
    DrawerHandle(vm, embedded, { showAttach = false }, scope)
}

// 消息渲染部件（MessageItem/ContinueChip/ThinkingRow/TimelineCard/AttachRow/缩略图/画布探测/
// CanvasGeneratingCard/glassBorder）已拆至 ChatMessageParts.kt——门0 W-B4/P5 单文件 500 行收口。

