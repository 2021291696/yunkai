package com.zhuolin.yunkai.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhuolin.yunkai.YunkaiApp
import com.zhuolin.yunkai.ui.theme.TextFaint
import com.zhuolin.yunkai.ui.theme.TextMuted

// 设置页：OpenAI 兼容三项配置 + 获取模型列表 + 技能自动路由开关 + 独立搜索配置。
// （联网方式三选已下线：agent 自主决定何时搜索，searchMode 字段保留不迁移）
@Composable
fun SettingsScreen(onOpenSkills: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as YunkaiApp
    val vm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(app) } })
    val context = LocalContext.current
    var modelMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("设置", fontSize = 26.sp, color = MaterialTheme.colorScheme.onBackground)

        // ===== API 配置卡片 =====
        GlassCard {
            Text("API 配置", fontSize = 16.sp, color = TextMuted)
            FieldLabel("API 地址")
            OutlinedTextField(
                value = vm.baseUrl.value,
                onValueChange = { vm.baseUrl.value = it },
                placeholder = { Text("https://open.bigmodel.cn/api/paas/v4") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
            FieldLabel("API 密钥")
            OutlinedTextField(
                value = vm.apiKey.value,
                onValueChange = { vm.apiKey.value = it },
                placeholder = { Text("sk-...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            FieldLabel("模型名")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = vm.model.value,
                    onValueChange = { vm.model.value = it },
                    placeholder = { Text("如 glm-4.7 / deepseek-chat") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.width(10.dp))
                if (vm.fetchingModels.value) {
                    CircularProgressIndicator(modifier = Modifier.height(22.dp).width(22.dp))
                } else {
                    Button(onClick = {
                        vm.fetchModels { count, err ->
                            Toast.makeText(
                                context,
                                err ?: "获取到 ${count} 个模型，请在下拉中选择",
                                Toast.LENGTH_SHORT,
                            ).show()
                            if (err == null && count > 0) modelMenuExpanded = true
                        }
                    }) { Text("获取模型列表") }
                }
            }
            if (vm.modelOptions.value.isNotEmpty()) {
                Box {
                    Button(onClick = { modelMenuExpanded = true }) {
                        Text(if (vm.model.value.isEmpty()) "选择模型" else vm.model.value)
                    }
                    DropdownMenu(expanded = modelMenuExpanded, onDismissRequest = { modelMenuExpanded = false }) {
                        for (id in vm.modelOptions.value) {
                            DropdownMenuItem(text = { Text(id) }, onClick = {
                                vm.model.value = id
                                modelMenuExpanded = false
                            })
                        }
                    }
                }
            }
        }

        // ===== 联网搜索卡片 =====
        GlassCard {
            Text("联网搜索", fontSize = 16.sp, color = TextMuted)
            FieldLabel("搜索源")
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderRadio("bing", "必应(免key)", vm)
                ProviderRadio("bocha", "博查", vm)
                ProviderRadio("tavily", "Tavily", vm)
            }
            FieldLabel("搜索密钥")
            OutlinedTextField(
                value = vm.searchApiKey.value,
                onValueChange = { vm.searchApiKey.value = it },
                placeholder = { Text("博查或 Tavily 的 key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Text(
                "搜索源选博查/Tavily 时需填密钥；必应免密钥。是否搜索由 agent 在对话中自主决定",
                fontSize = 12.sp, color = TextFaint,
            )
        }

        // ===== 技能路由卡片 =====
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("技能自动路由", fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(checked = vm.autoRoute.value, onCheckedChange = { vm.autoRoute.value = it })
            }
            Text("关=仅 @名字 手动触发技能", fontSize = 12.sp, color = TextFaint)
        }

        // ===== 技能库入口 =====
        GlassCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSkills),
            ) {
                Text("技能库", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.secondary)
            }
        }

        Button(
            onClick = {
                vm.save { err ->
                    Toast.makeText(context, err ?: "已保存", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth().height(46.dp),
        ) { Text("保存", fontSize = 17.sp) }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProviderRadio(value: String, label: String, vm: SettingsViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = vm.searchProvider.value == value, onClick = { vm.searchProvider.value = value })
        Text(label, fontSize = 15.sp)
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
}

// 玻璃卡：白玻璃底 + 细边框（鸿蒙版同族视觉）
@Composable
internal fun GlassCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }
}
