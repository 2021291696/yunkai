package com.zhuolin.yunkai.ui.chat

// 消息渲染部件（自 ChatScreen 拆出，门0 W-B4/P5 单文件 500 行收口）：
// MessageItem 分发 / 继续任务 chip / 思考折叠行 / 时间线卡 / 附件行 / 缩略图与文件名 /
// 流式画布探测 / 画布生成占位卡。全部 internal，ChatScreen 同包直用。

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuolin.yunkai.service.LoopEvent
import com.zhuolin.yunkai.service.ReplyKind
import com.zhuolin.yunkai.ui.canvas.CanvasCard
import com.zhuolin.yunkai.ui.theme.GlassTokens
import com.zhuolin.yunkai.ui.theme.LocalGlassScheme

// 0.5dp 玻璃描边（对齐鸿蒙 ThemeTokens.BORDER_W）；Composable 扩展以便读当前色板
@Composable
internal fun Modifier.glassBorder(shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.border(GlassTokens.BORDER_W.dp, LocalGlassScheme.current.glassBorder, shape)

private val BubbleShadow = Color(0x66000000)

// 消息渲染分发：user 气泡 / html 画布卡 / text 气泡
@Composable
internal fun MessageItem(m: RenderMsg, onOpenCanvas: () -> Unit, onEdit: () -> Unit = {}) {
    val glass = LocalGlassScheme.current
    val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.82f).dp
    Box(modifier = Modifier.fillMaxWidth()) {
        if (m.role == "user") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
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
internal fun ContinueChip(onContinue: () -> Unit) {
    val glass = LocalGlassScheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(glass.glassBg.copy(alpha = 0.5f), RoundedCornerShape(GlassTokens.R_TIGHT.dp))
            .border(1.dp, glass.glassBorder, RoundedCornerShape(GlassTokens.R_TIGHT.dp))
            .clickable { onContinue() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
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
internal fun ThinkingRow(m: RenderMsg) {
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
internal fun timelineLabel(e: LoopEvent): String {
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
internal fun TimelineCard(
    timeline: List<LoopEvent>,
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
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
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
internal fun AttachRow(label: String, onClick: () -> Unit) {
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
internal fun loadThumb(context: android.content.Context, uri: String): androidx.compose.ui.graphics.ImageBitmap? {
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
internal fun queryDisplayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

// 流式画布探测：与落库判定同口径（HtmlGuard.sanitize 剥```围栏后看 <!DOCTYPE/<html）。
// 命中 = 本轮输出是画布页 → 流式区显示占位卡，而非把整页 HTML 喂给 markdown 打碎成满屏碎片
internal fun looksLikeCanvasStream(raw: String): Boolean {
    var t = raw.trim()
    if (t.startsWith("```")) t = t.replace(Regex("^```[a-zA-Z]*\\s*"), "")
    val lower = t.lowercase()
    if (lower.startsWith("<!doctype") || lower.startsWith("<html")) return true
    return lower.take(256).contains("<html")
}

// 画布生成中占位卡：外观对齐 CanvasCard 卡头，完成后由落库的画布卡自然顶替
@Composable
internal fun CanvasGeneratingCard() {
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

// 附件 chips：缩略图（图片）或文件名（txt），点 ✕ 移除（自 ChatScreen 拆出，P5 收口）
@Composable
internal fun PickedChips(vm: ChatViewModel, context: android.content.Context) {
    val glass = LocalGlassScheme.current
    if (vm.picked.value.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
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
                        androidx.compose.foundation.Image(
                            bitmap = current,
                            contentDescription = item.name,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    } else { Text("图", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = glass.textHi) }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(16.dp)
                            .clip(RoundedCornerShape(10.dp))
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
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
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
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 110.dp),
                    )
                    Text("✕", fontSize = 12.sp, color = glass.textLow)
                }
            }
        }
    }
}
