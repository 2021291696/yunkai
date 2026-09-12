package com.zhuolin.yunkai.ui.memory

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhuolin.yunkai.YunkaiApp
import com.zhuolin.yunkai.memory.ArchivalRow
import com.zhuolin.yunkai.memory.MemoryStore
import com.zhuolin.yunkai.service.AgentLoop
import com.zhuolin.yunkai.ui.settings.GlassCard
import com.zhuolin.yunkai.ui.theme.CardBorder
import com.zhuolin.yunkai.ui.theme.CardGlass
import com.zhuolin.yunkai.ui.theme.GlassTokens
import com.zhuolin.yunkai.ui.theme.LocalGlassScheme
import com.zhuolin.yunkai.ui.theme.TextDark
import com.zhuolin.yunkai.ui.theme.TextFaint
import com.zhuolin.yunkai.ui.theme.TextMuted
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 记忆管理页（忆枢 M1c）：human/persona 核心块卡片（预览/字数预算/编辑/persona 重置种子）
// + 归档列表（搜索/长按删除/清空，1 万条软告警提示条，协议 §2 只提示不拦截）。
class MemoryManageViewModel(private val app: YunkaiApp) : ViewModel() {
    val human = mutableStateOf("")
    val persona = mutableStateOf("")
    val archival = mutableStateOf<List<ArchivalRow>>(emptyList())
    val query = mutableStateOf("")

    // 编辑态：editingBlock = "human" | "persona" | ""（空=非编辑）
    val editingBlock = mutableStateOf("")
    val editText = mutableStateOf("")
    val editError = mutableStateOf<String?>(null)

    fun refresh() {
        viewModelScope.launch {
            human.value = app.memoryStore.getCoreBlock(MemoryStore.BLOCK_HUMAN)?.content ?: ""
            persona.value = app.memoryStore.getCoreBlock(MemoryStore.BLOCK_PERSONA)?.content ?: ""
            archival.value = app.memoryStore.allArchival()
        }
    }

    /** 展示列表：created_at 倒序；搜索词非空按 contains 过滤（不区分大小写）。 */
    fun displayRows(): List<ArchivalRow> {
        val sorted = archival.value.sortedByDescending { it.createdAt }
        val q = query.value.trim()
        if (q.isEmpty()) return sorted
        return sorted.filter { it.content.contains(q, ignoreCase = true) }
    }

    fun beginEdit(block: String) {
        editingBlock.value = block
        editText.value = if (block == MemoryStore.BLOCK_HUMAN) human.value else persona.value
        editError.value = null
    }

    fun cancelEdit() {
        editingBlock.value = ""
        editError.value = null
    }

    // 保存核心块：超预算前端预检拒绝（协议 §2 上限，含空白），通过则整体覆盖写入
    fun saveEdit(context: android.content.Context) {
        val block = editingBlock.value
        val limit = MemoryStore.coreLimitOf(block) ?: return
        val next = editText.value
        if (next.length > limit) {
            editError.value = "超出上限（${next.length}/$limit），请精简后再保存"
            return
        }
        viewModelScope.launch {
            app.memoryStore.putCoreBlock(block, next)
            editingBlock.value = ""
            editError.value = null
            toast(context, "已保存")
            refresh()
        }
    }

    // persona 重置种子：写回 AGENT_SYSTEM 原文（与 AgentLoop 播种同一常量，协议 §4.1）
    fun resetPersonaSeed(context: android.content.Context) {
        viewModelScope.launch {
            app.memoryStore.putCoreBlock(MemoryStore.BLOCK_PERSONA, AgentLoop.AGENT_SYSTEM)
            toast(context, "已重置为初始种子")
            refresh()
        }
    }

    fun deleteOne(id: Long, context: android.content.Context) {
        viewModelScope.launch {
            app.memoryStore.deleteArchival(id)
            toast(context, "已删除")
            refresh()
        }
    }

    fun clearAll(context: android.content.Context) {
        viewModelScope.launch {
            val n = app.memoryStore.clearArchival()
            toast(context, "已清空 $n 条归档")
            refresh()
        }
    }

    private fun toast(context: android.content.Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MemoryManageScreen(onBack: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as YunkaiApp
    val vm: MemoryManageViewModel = viewModel(factory = viewModelFactory { initializer { MemoryManageViewModel(app) } })
    val context = LocalContext.current
    val glass = LocalGlassScheme.current
    var deleteTarget by remember { mutableStateOf<ArchivalRow?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }
    var resetConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refresh() }

    // 确认对话框三件：单条删除 / 清空归档 / persona 重置种子
    deleteTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除归档记忆") },
            text = { Text("确定删除该条归档记忆吗？") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    vm.deleteOne(row.id, context)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text("清空归档记忆") },
            text = { Text("确定清空全部 ${vm.archival.value.size} 条归档记忆吗？操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    clearConfirm = false
                    vm.clearAll(context)
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("取消") } },
        )
    }
    if (resetConfirm) {
        AlertDialog(
            onDismissRequest = { resetConfirm = false },
            title = { Text("重置 persona") },
            text = { Text("将 persona 块恢复为初始人格种子？当前内容会被覆盖。") },
            confirmButton = {
                TextButton(onClick = {
                    resetConfirm = false
                    vm.resetPersonaSeed(context)
                }) { Text("重置", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { resetConfirm = false }) { Text("取消") } },
        )
    }

    val rows = remember(vm.archival.value, vm.query.value) { vm.displayRows() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ===== 顶部：玻璃圆返回钮 + 标题 =====
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(glass.glassBg)
                    .border(GlassTokens.BORDER_W.dp, glass.glassBorder, CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("‹", fontSize = 18.sp, color = glass.textHi) }
            Text("记忆", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextDark, modifier = Modifier.padding(start = 12.dp))
        }

        // ===== 1 万条软告警提示条（协议 §2 ARCHIVAL_WARN_COUNT，只提示不拦截）=====
        if (vm.archival.value.size > MemoryStore.ARCHIVAL_WARN_COUNT) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardGlass, RoundedCornerShape(12.dp))
                    .border(GlassTokens.BORDER_W.dp, CardBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    "归档记忆已超过 ${MemoryStore.ARCHIVAL_WARN_COUNT} 条，建议适时清理",
                    fontSize = 12.sp, color = TextMuted,
                )
            }
        }

        // ===== 核心块：human =====
        CoreBlockCard(
            block = MemoryStore.BLOCK_HUMAN,
            title = "human · 用户档案",
            content = vm.human.value,
            editing = vm.editingBlock.value == MemoryStore.BLOCK_HUMAN,
            editError = vm.editError.value.takeIf { vm.editingBlock.value == MemoryStore.BLOCK_HUMAN },
            onEdit = { vm.beginEdit(MemoryStore.BLOCK_HUMAN) },
        ) {
            // 编辑态（editContent 尾 lambda 在卡内展开）
            BlockEditor(
                vm = vm,
                block = MemoryStore.BLOCK_HUMAN,
                onCancel = { vm.cancelEdit() },
                onSave = { vm.saveEdit(context) },
            )
        }

        // ===== 核心块：persona（另有重置种子）=====
        CoreBlockCard(
            block = MemoryStore.BLOCK_PERSONA,
            title = "persona · 自我定义",
            content = vm.persona.value,
            editing = vm.editingBlock.value == MemoryStore.BLOCK_PERSONA,
            editError = vm.editError.value.takeIf { vm.editingBlock.value == MemoryStore.BLOCK_PERSONA },
            onEdit = { vm.beginEdit(MemoryStore.BLOCK_PERSONA) },
            onReset = { resetConfirm = true },
        ) {
            BlockEditor(
                vm = vm,
                block = MemoryStore.BLOCK_PERSONA,
                onCancel = { vm.cancelEdit() },
                onSave = { vm.saveEdit(context) },
            )
        }

        // ===== 归档区：标题 + 搜索框 =====
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("归档记忆", fontSize = 15.sp, color = TextMuted, modifier = Modifier.weight(1f))
            Text("共 ${vm.archival.value.size} 条", fontSize = 12.sp, color = TextFaint)
        }
        OutlinedTextField(
            value = vm.query.value,
            onValueChange = { vm.query.value = it },
            placeholder = { Text("搜索归档记忆") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )

        // ===== 归档列表（LazyColumn；空态/无结果态各一句克制文案）=====
        if (rows.isEmpty()) {
            Text(
                if (vm.query.value.isBlank()) "暂无归档记忆" else "没有匹配「${vm.query.value.trim()}」的归档",
                fontSize = 15.sp, color = TextMuted,
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(rows, key = { it.id }) { row ->
                    ArchivalRowCard(row, onLongClick = { deleteTarget = row })
                }
            }
        }

        // ===== 清空归档（红色危险操作，确认对话框；无归档时禁用）=====
        Button(
            onClick = { clearConfirm = true },
            enabled = vm.archival.value.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(46.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = androidx.compose.ui.graphics.Color.White,
                disabledContainerColor = CardGlass,
                disabledContentColor = TextFaint,
            ),
        ) { Text("清空归档", fontSize = 16.sp) }
        Spacer(Modifier.height(8.dp))
    }
}

// 核心块卡：标题 + 字数/预算 + 编辑钮（persona 另有重置种子）；非编辑态给内容预览
@Composable
private fun CoreBlockCard(
    block: String,
    title: String,
    content: String,
    editing: Boolean,
    editError: String?,
    onEdit: () -> Unit,
    onReset: (() -> Unit)? = null,
    editContent: @Composable () -> Unit,
) {
    val glass = LocalGlassScheme.current
    val limit = MemoryStore.coreLimitOf(block) ?: MemoryStore.CORE_HUMAN_LIMIT
    val over = content.length > limit
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 15.sp, color = TextDark, modifier = Modifier.weight(1f))
            Text(
                "${content.length}/$limit",
                fontSize = 12.sp,
                color = if (over) MaterialTheme.colorScheme.error else TextFaint,
            )
            if (onReset != null && !editing) {
                TextButton(onClick = onReset) { Text("重置种子", color = TextMuted) }
            }
            if (!editing) {
                TextButton(onClick = onEdit) { Text("编辑", color = glass.accent) }
            }
        }
        if (editing) {
            editContent()
            if (editError != null) {
                Text(editError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        } else {
            Text(
                content.ifBlank { "（空）" },
                fontSize = 13.sp,
                color = if (content.isBlank()) TextFaint else TextMuted,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// 编辑态：多行文本框 + 实时字数 + 保存/取消（取消/保存按钮样式沿技能库表单）
@Composable
private fun BlockEditor(vm: MemoryManageViewModel, block: String, onCancel: () -> Unit, onSave: () -> Unit) {
    val limit = MemoryStore.coreLimitOf(block) ?: return
    val over = vm.editText.value.length > limit
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = vm.editText.value,
            onValueChange = {
                vm.editText.value = it
                vm.editError.value = null
            },
            modifier = Modifier.fillMaxWidth().height(150.dp),
            shape = RoundedCornerShape(12.dp),
        )
        Text(
            "${vm.editText.value.length}/$limit",
            fontSize = 12.sp,
            color = if (over) MaterialTheme.colorScheme.error else TextFaint,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = CardGlass, contentColor = TextDark),
            ) { Text("取消") }
            Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("保存") }
        }
    }
}

// 归档行卡：内容（2 行截断）+ 类型标签 + 日期；长按删除（沿技能库长按删除惯例）
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchivalRowCard(row: ArchivalRow, onLongClick: () -> Unit) {
    val glass = LocalGlassScheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardGlass, RoundedCornerShape(GlassTokens.R_CARD.dp))
            .border(GlassTokens.BORDER_W.dp, CardBorder, RoundedCornerShape(GlassTokens.R_CARD.dp))
            .combinedClickable(onClick = {}, onLongClick = onLongClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(row.content, fontSize = 14.sp, color = TextDark, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                typeLabel(row.type),
                fontSize = 11.sp,
                color = glass.accent,
                modifier = Modifier
                    .background(glass.accentSoft, RoundedCornerShape(9.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Text(formatDate(row.createdAt), fontSize = 11.sp, color = TextFaint)
            Spacer(Modifier.weight(1f))
            if (row.hitCount > 0) {
                Text("命中 ${row.hitCount}", fontSize = 11.sp, color = TextFaint)
            }
        }
    }
}

// 类型 wire → 中文标签（协议 §1.2 fact|episode|preference|identity；未知原样显示）
private fun typeLabel(type: String): String = when (type) {
    "fact" -> "事实"
    "episode" -> "情节"
    "preference" -> "偏好"
    "identity" -> "身份"
    else -> type
}

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epochMs))
