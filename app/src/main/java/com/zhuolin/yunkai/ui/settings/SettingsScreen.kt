package com.zhuolin.yunkai.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhuolin.yunkai.YunkaiApp
import com.zhuolin.yunkai.store.ConfigStore
import com.zhuolin.yunkai.ui.theme.GlassTokens
import com.zhuolin.yunkai.ui.theme.LocalGlassScheme
import com.zhuolin.yunkai.ui.theme.TextFaint
import com.zhuolin.yunkai.ui.theme.TextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 设置页：OpenAI 兼容三项配置 + 获取模型列表 + 技能自动路由开关 + 独立搜索配置。
// （联网方式三选已下线：agent 自主决定何时搜索，searchMode 字段保留不迁移）
// M1c：记忆隐私三选（strict/standard/free，选中即写 ConfigStore）+ 记忆管理页入口。
@Composable
fun SettingsScreen(onOpenSkills: () -> Unit = {}, onOpenMemory: () -> Unit = {}, onBack: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as YunkaiApp
    val vm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(app) } })
    val context = LocalContext.current
    val glass = LocalGlassScheme.current
    val scope = rememberCoroutineScope()
    var modelMenuExpanded by remember { mutableStateOf(false) }
    // 主题模式：写入即生效（不随「保存」），明暗由 MainActivity 订阅 ConfigStore.themeModeFlow 决定
    var themeMode by remember { mutableStateOf(ConfigStore.THEME_SYSTEM) }
    val pickTheme: (String) -> Unit = { mode ->
        themeMode = mode
        scope.launch(Dispatchers.IO) { app.configStore.setThemeMode(mode) }
    }
    LaunchedEffect(Unit) { themeMode = app.configStore.getThemeMode() }

    // 皮肤：clear 通透（默认）/ aurora 极光。写入即生效（MainActivity 订阅 ConfigStore.skinFlow 换 scheme）
    var skin by remember { mutableStateOf(ConfigStore.SKIN_CLEAR) }
    val pickSkin: (String) -> Unit = { s ->
        skin = s
        scope.launch(Dispatchers.IO) { app.configStore.setSkin(s) }
    }
    LaunchedEffect(Unit) { skin = app.configStore.getSkin() }

    // M3 任务步数三档：写入即生效（下一轮 send 走 cfg.maxSteps），不随「保存」
    var maxSteps by remember { mutableStateOf(ConfigStore.MAX_STEPS_DEFAULT) }
    val pickSteps: (Int) -> Unit = { v ->
        maxSteps = v
        scope.launch(Dispatchers.IO) { app.configStore.setMaxSteps(v) }
    }
    LaunchedEffect(Unit) { maxSteps = app.configStore.getMaxSteps() }

    // 壁纸选择：系统相册选图 → 拷入沙箱 filesDir → 写 ConfigStore（WallpaperLayer 订阅即时生效）
    val pickWallpaper = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val ok = runCatching {
                    val dst = java.io.File(context.filesDir, "wallpaper_custom.jpg")
                    context.contentResolver.openInputStream(uri)?.use { inp ->
                        dst.outputStream().use { inp.copyTo(it) }
                    } ?: error("读不到所选图片")
                    app.configStore.setWallpaper(dst.absolutePath)
                }.isSuccess
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, if (ok) "壁纸已更换" else "壁纸更换失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding() // 边缘到边缘后避让状态栏
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // 玻璃圆返回钮（对齐鸿蒙设置页）
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(glass.glassBg)
                    .border(GlassTokens.BORDER_W.dp, glass.glassBorder, CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("‹", fontSize = 18.sp, color = glass.textHi) }
            Text("设置", fontSize = 26.sp, color = MaterialTheme.colorScheme.onBackground)
        }

        // 门2 交互发现：返回钮原先在滚动容器内，页面滚到底后返回钮滚出视口，只能靠系统返回键退出——
        // 顶栏固定化（仅卡片列表滚动）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
        // ===== 外观卡片：设计（皮肤）+ 主题模式 =====
        GlassCard {
            Text("外观", fontSize = 16.sp, color = TextMuted)
            FieldLabel("设计")
            Row(verticalAlignment = Alignment.CenterVertically) {
                ThemeOption(ConfigStore.SKIN_CLEAR, "通透", skin, pickSkin)
                ThemeOption(ConfigStore.SKIN_AURORA, "极光", skin, pickSkin)
            }
            FieldLabel("主题")
            Row(verticalAlignment = Alignment.CenterVertically) {
                ThemeOption("system", "跟随系统", themeMode, pickTheme)
                ThemeOption("light", "浅色", themeMode, pickTheme)
                ThemeOption("dark", "深色", themeMode, pickTheme)
            }
            if (skin == ConfigStore.SKIN_AURORA) {
                Text("极光皮肤自带背景光，壁纸仅在「通透」皮肤显示", fontSize = 10.sp, color = TextFaint)
            }
        }

        // ===== 任务卡片：步数三档（M3，忆枢协议 §2 MAX_STEPS_OPTIONS） =====
        GlassCard {
            Text("任务", fontSize = 16.sp, color = TextMuted)
            FieldLabel("任务步数")
            Row(verticalAlignment = Alignment.CenterVertically) {
                ThemeOption("10", "省流 10", maxSteps.toString()) { v -> pickSteps(v.toInt()) }
                ThemeOption("25", "标准 25", maxSteps.toString()) { v -> pickSteps(v.toInt()) }
                ThemeOption("50", "深度 50", maxSteps.toString()) { v -> pickSteps(v.toInt()) }
            }
        }

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

        // ===== 壁纸卡片 =====
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("壁纸", fontSize = 15.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    pickWallpaper.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }) { Text("选择图片", color = glass.accent) }
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        app.configStore.setWallpaper("")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "已恢复默认壁纸", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("恢复默认", color = TextMuted) }
            }
            Text("壁纸铺在全局背景层，玻璃卡片会透出它", fontSize = 12.sp, color = TextFaint)
        }

        // ===== 屏幕感知卡片（豆包对齐 M1）：总开关默认关 + 两项系统授权引导；写入即生效 =====
        var screenSense by remember { mutableStateOf(false) }
        var a11yReady by remember { mutableStateOf(false) }
        var projReady by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            screenSense = app.configStore.getScreenSense()
            // 轻量轮询：从系统设置授权回来后状态自动跟上（页面存活时 1.5s 一次，成本可忽略）
            while (true) {
                a11yReady = com.zhuolin.yunkai.service.screen.ScreenSenseService.ready
                projReady = com.zhuolin.yunkai.service.screen.ProjectionService.active
                kotlinx.coroutines.delay(1500)
            }
        }
        val projLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { res ->
            if (res.resultCode == android.app.Activity.RESULT_OK && res.data != null) {
                com.zhuolin.yunkai.service.screen.ProjectionService.start(context, res.resultCode, res.data!!)
                projReady = true
            } else {
                Toast.makeText(context, "未授予屏幕录制", Toast.LENGTH_SHORT).show()
            }
        }
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("屏幕感知", fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(checked = screenSense, onCheckedChange = { on ->
                    screenSense = on
                    scope.launch(Dispatchers.IO) { app.configStore.setScreenSense(on) }
                    if (!on) {
                        // 门0 I2：总开关关闭=彻底禁用，投屏会话一并停止（兑现通知里「停止请在设置页关闭」）
                        com.zhuolin.yunkai.service.screen.ProjectionService.stop(context)
                        projReady = false
                    }
                })
            }
            Text("开启后 agent 可列出/打开应用并读取屏幕：文字走无障碍节点树，图片与自绘应用走截图视觉", fontSize = 12.sp, color = TextFaint)
            Text("隐私：银行/支付类默认不读；密码框内容永不上传；截屏期间有常驻通知", fontSize = 12.sp, color = TextFaint)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "无障碍读屏", fontSize = 14.sp, modifier = Modifier.weight(1f),
                    color = if (a11yReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(if (a11yReady) "已开启" else "未开启", fontSize = 12.sp, color = TextFaint)
                if (!a11yReady) {
                    TextButton(onClick = {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }) { Text("去开启", color = glass.accent) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "屏幕录制", fontSize = 14.sp, modifier = Modifier.weight(1f),
                    color = if (projReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(if (projReady) "已授权" else "未授权", fontSize = 12.sp, color = TextFaint)
                if (!projReady) {
                    TextButton(onClick = {
                        val mpm = context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
                        if (mpm != null) projLauncher.launch(mpm.createScreenCaptureIntent())
                    }) { Text("授权", color = glass.accent) }
                }
            }
        }

        // ===== 记忆隐私卡片（M1c）：三挡写入即生效，不随「保存」；说明文案按协议 §5.2 挡位矩阵 =====
        GlassCard {
            Text("记忆隐私", fontSize = 16.sp, color = TextMuted)
            GearOption("strict", "严格", "拒存密码、证件号、卡号", vm) { gear ->
                scope.launch(Dispatchers.IO) { app.configStore.setMemoryGear(gear) }
            }
            GearOption("standard", "标准", "拒存证件号、卡号", vm) { gear ->
                scope.launch(Dispatchers.IO) { app.configStore.setMemoryGear(gear) }
            }
            GearOption("free", "自由", "不拦截", vm) { gear ->
                scope.launch(Dispatchers.IO) { app.configStore.setMemoryGear(gear) }
            }
        }

        // ===== 记忆管理页入口 =====
        GlassCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenMemory),
            ) {
                Text("记忆", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.secondary)
            }
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

        // ===== 关于区（对齐鸿蒙：纯文字居中块，不加玻璃卡）=====
        val versionName = remember {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "0.1.0"
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                androidx.compose.ui.res.stringResource(com.zhuolin.yunkai.R.string.app_name),
                fontSize = 18.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                color = glass.textHi,
            )
            Text("一个安静的 AI 对话空间", fontSize = 12.sp, color = glass.textMid)
            Text("版本 $versionName", fontSize = 10.5.sp, color = glass.textLow)
        }

        Spacer(Modifier.height(24.dp))
        }
    }
}

// 主题档位单选：写完立刻生效（不等「保存」）
@Composable
private fun ThemeOption(value: String, label: String, mode: String, onPick: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = mode == value, onClick = { onPick(value) })
        Text(label, fontSize = 15.sp)
    }
}

// 隐私挡位三选（M1c）：沿用主题三选的 RadioButton 样式，每项带一行小字说明；
// 选中即更新 VM 状态并立即写 ConfigStore（onWire 回调），不依赖「保存」按钮
@Composable
private fun GearOption(value: String, label: String, desc: String, vm: SettingsViewModel, onWire: (String) -> Unit) {
    val pick = {
        vm.memoryGear.value = value
        onWire(value)
        Unit
    }
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = pick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = vm.memoryGear.value == value, onClick = pick)
            Text(label, fontSize = 15.sp)
        }
        Text(desc, fontSize = 12.sp, color = TextFaint, modifier = Modifier.padding(start = 48.dp))
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

// 玻璃卡：玻璃底 + 0.5 细边框（方向 A 通透系，色板走 LocalGlassScheme 桥接）
@Composable
internal fun GlassCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(GlassTokens.R_CARD.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = androidx.compose.foundation.BorderStroke(GlassTokens.BORDER_W.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }
}
