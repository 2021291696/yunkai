package com.zhuolin.yunkai.ui.skills

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.zhuolin.yunkai.model.AgentSkill
import com.zhuolin.yunkai.service.SkillImporter
import com.zhuolin.yunkai.ui.settings.GlassCard
import com.zhuolin.yunkai.ui.theme.CardBorder
import com.zhuolin.yunkai.ui.theme.CardGlass
import com.zhuolin.yunkai.ui.theme.TextDark
import com.zhuolin.yunkai.ui.theme.TextMuted
import com.zhuolin.yunkai.ui.theme.WarmOrange
import com.zhuolin.yunkai.ui.theme.WarmOrangeDeep
import kotlinx.coroutines.launch

// 技能库页状态：技能列表 + 新建表单 + 粘贴导入表单（两种表单互斥）+ 长按删除（非内置）
class SkillManageViewModel(private val app: YunkaiApp) : ViewModel() {
    val skills = mutableStateOf<List<AgentSkill>>(emptyList())
    // 表单模式：none=纯列表；create=新建；import=粘贴导入
    val mode = mutableStateOf("none")
    val formName = mutableStateOf("")
    val formDesc = mutableStateOf("")
    val formContent = mutableStateOf("")
    val importText = mutableStateOf("")

    fun refresh() {
        viewModelScope.launch { skills.value = app.skillRepo.list() }
    }

    fun closeForms() {
        mode.value = "none"
        formName.value = ""
        formDesc.value = ""
        formContent.value = ""
        importText.value = ""
    }

    fun openCreate() {
        formName.value = ""
        formDesc.value = ""
        formContent.value = ""
        mode.value = "create"
    }

    fun openImport() {
        importText.value = ""
        mode.value = "import"
    }

    fun saveNew(context: android.content.Context) {
        val name = formName.value.trim()
        if (name.isEmpty()) {
            toast(context, "请填写技能名")
            return
        }
        val content = formContent.value.trim()
        if (content.isEmpty()) {
            toast(context, "技能内容为空")
            return
        }
        viewModelScope.launch {
            val id = app.skillRepo.insert(name, formDesc.value.trim(), content, false)
            if (id < 0) {
                toast(context, "技能名已存在")
                return@launch
            }
            closeForms()
            toast(context, "已保存")
            refresh()
        }
    }

    fun doImport(context: android.content.Context) {
        val skill = SkillImporter.parse(importText.value)
        if (skill == null) {
            toast(context, "无法识别的格式")
            return
        }
        if (skill.content.trim().isEmpty()) {
            toast(context, "技能内容为空")
            return
        }
        viewModelScope.launch {
            val id = app.skillRepo.insert(skill.name, skill.description, skill.content, false)
            if (id < 0) {
                toast(context, "技能名已存在")
                return@launch
            }
            closeForms()
            toast(context, "已导入")
            refresh()
        }
    }

    fun doDelete(id: Long, context: android.content.Context) {
        viewModelScope.launch {
            app.skillRepo.delete(id)
            toast(context, "已删除")
            refresh()
        }
    }

    private fun toast(context: android.content.Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}

// 技能库页：技能列表（内置标记）+ 新建表单 + 粘贴导入 + 长按删除（非内置）
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SkillManageScreen(onBack: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as YunkaiApp
    val vm: SkillManageViewModel = viewModel(factory = viewModelFactory { initializer { SkillManageViewModel(app) } })
    val context = LocalContext.current
    var deleteTarget by remember { mutableStateOf<AgentSkill?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }

    // 长按卡片确认删除：内置技能不响应
    deleteTarget?.let { skill ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除技能") },
            text = { Text("确定删除「${skill.name}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    vm.doDelete(skill.id, context)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        // ===== 顶部：返回 + 标题 =====
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
            Text("‹", fontSize = 28.sp, color = TextDark, modifier = Modifier
                .clickable(onClick = onBack)
                .padding(horizontal = 12.dp, vertical = 2.dp))
            Text("技能库", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextDark)
        }

        // ===== 列表区 =====
        if (vm.skills.value.isEmpty()) {
            Text(
                "还没有技能，点下方新建或粘贴导入",
                fontSize = 15.sp, color = TextMuted,
                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (skill in vm.skills.value) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardGlass, RoundedCornerShape(16.dp))
                            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { if (!skill.builtin) deleteTarget = skill },
                            )
                            .padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(skill.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextDark, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (skill.builtin) {
                                Text(
                                    "内置", fontSize = 11.sp, color = WarmOrangeDeep,
                                    modifier = Modifier
                                        .background(androidx.compose.ui.graphics.Color(0xFFFFE3B3), RoundedCornerShape(9.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                        Text(skill.description, fontSize = 13.sp, color = TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        // ===== 新建表单（与导入互斥）=====
        if (vm.mode.value == "create") {
            GlassCard {
                Text("新建技能", fontSize = 15.sp, color = TextMuted)
                FormLabel("技能名")
                OutlinedTextField(value = vm.formName.value, onValueChange = { vm.formName.value = it }, placeholder = { Text("英文或中文名，@名字 引用") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp))
                FormLabel("简介")
                OutlinedTextField(value = vm.formDesc.value, onValueChange = { vm.formDesc.value = it }, placeholder = { Text("一句话说明这个技能做什么") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp))
                FormLabel("内容")
                OutlinedTextField(value = vm.formContent.value, onValueChange = { vm.formContent.value = it }, placeholder = { Text("技能正文（多行）") }, modifier = Modifier.fillMaxWidth().height(110.dp), shape = RoundedCornerShape(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { vm.closeForms() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = CardGlass, contentColor = WarmOrangeDeep)) { Text("取消") }
                    Button(onClick = { vm.saveNew(context) }, modifier = Modifier.weight(1f)) { Text("保存") }
                }
            }
        }

        // ===== 粘贴导入表单（与新建互斥）=====
        if (vm.mode.value == "import") {
            GlassCard {
                Text("粘贴导入", fontSize = 15.sp, color = TextMuted)
                OutlinedTextField(value = vm.importText.value, onValueChange = { vm.importText.value = it }, placeholder = { Text("粘贴 SKILL.md 全文（frontmatter 或 # 标题均可识别）") }, modifier = Modifier.fillMaxWidth().height(150.dp), shape = RoundedCornerShape(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { vm.closeForms() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = CardGlass, contentColor = WarmOrangeDeep)) { Text("取消") }
                    Button(onClick = { vm.doImport(context) }, modifier = Modifier.weight(1f)) { Text("解析并导入") }
                }
            }
        }

        // ===== 底部两按钮 =====
        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { if (vm.mode.value == "create") vm.closeForms() else vm.openCreate() },
                modifier = Modifier.weight(1f).height(46.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (vm.mode.value == "create") CardGlass else WarmOrange,
                    contentColor = if (vm.mode.value == "create") WarmOrangeDeep else androidx.compose.ui.graphics.Color.White,
                ),
            ) { Text("＋ 新建", fontSize = 16.sp) }
            Button(
                onClick = { if (vm.mode.value == "import") vm.closeForms() else vm.openImport() },
                modifier = Modifier.weight(1f).height(46.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (vm.mode.value == "import") CardGlass else WarmOrange,
                    contentColor = if (vm.mode.value == "import") WarmOrangeDeep else androidx.compose.ui.graphics.Color.White,
                ),
            ) { Text("📋 粘贴导入", fontSize = 16.sp) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FormLabel(text: String) {
    Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
}
