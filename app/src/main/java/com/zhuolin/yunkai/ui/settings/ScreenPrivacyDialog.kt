package com.zhuolin.yunkai.ui.settings

// 屏幕感知隐私管理弹窗（M2b 收尾）：用户黑名单增删 + 视觉学习集只读清空。
// 独立文件原因：SettingsScreen 已逼近 500 行红线（AGENTS 硬约束），新增 UI 必须外置。
// 数据面只走 ConfigStore 既有接口；应用枚举按「有 launcher 入口」过滤，图标在 IO 线程转 Bitmap。

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuolin.yunkai.YunkaiApp
import com.zhuolin.yunkai.ui.theme.GlassScheme
import com.zhuolin.yunkai.ui.theme.GlassTokens
import com.zhuolin.yunkai.ui.theme.TextDark
import com.zhuolin.yunkai.ui.theme.TextFaint
import com.zhuolin.yunkai.ui.theme.WarmOrange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 已安装用户应用条目（弹窗内枚举一次）
private data class InstalledApp(val pkg: String, val label: String, val icon: android.graphics.Bitmap?)

@Composable
internal fun ScreenPrivacyDialog(glass: GlassScheme, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as? YunkaiApp
    val context = LocalContext.current
    var blacklist by remember { mutableStateOf<Set<String>>(emptySet()) }
    var learned by remember { mutableStateOf<Set<String>>(emptySet()) }
    var reload by remember { mutableStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) } // 学习集清空二段确认
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) {
        if (app == null) return@LaunchedEffect
        blacklist = app.configStore.getUserBlacklist()
        learned = app.configStore.getVisionLearned()
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("屏蔽应用管理", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextDark) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ── 区1：已屏蔽列表 ──
                Text("已屏蔽（不读屏、不截屏）", fontSize = 13.sp, color = TextFaint)
                if (blacklist.isEmpty()) {
                    Text("未屏蔽任何应用", fontSize = 13.sp, color = TextFaint)
                } else {
                    blacklist.sorted().forEach { pkg ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(appLabel(context, pkg), fontSize = 14.sp, color = TextDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(pkg, fontSize = 10.5.sp, color = TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("移除", fontSize = 12.sp, color = WarmOrange, modifier = Modifier.clickable {
                                scope.launch { app?.configStore?.removeUserBlacklist(pkg); reload++ }
                            })
                        }
                    }
                }
                // ── 区2：添加屏蔽 ──
                Text(
                    if (showAdd) "收起" else "＋ 添加屏蔽",
                    fontSize = 13.sp, color = glass.accent,
                    modifier = Modifier.clickable {
                        showAdd = !showAdd
                        if (showAdd && apps == null) {
                            scope.launch {
                                apps = withContext(Dispatchers.IO) { enumerateApps(context) }
                            }
                        }
                    },
                )
                if (showAdd) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("按应用名过滤", fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                    val list = apps
                    when {
                        list == null -> Text("加载应用列表…", fontSize = 13.sp, color = TextFaint)
                        else -> {
                            val shown = list
                                .filter { it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true) }
                                .filter { it.pkg !in blacklist }
                            if (shown.isEmpty()) {
                                Text("没有可添加的应用", fontSize = 13.sp, color = TextFaint)
                            } else {
                                shown.forEach { a ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                scope.launch { app?.configStore?.addUserBlacklist(a.pkg); reload++ }
                                            },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        a.icon?.let {
                                            Image(
                                                bitmap = it.asImageBitmap(),
                                                contentDescription = null,
                                                modifier = Modifier.size(30.dp).clip(CircleShape),
                                            )
                                        }
                                        Column(modifier = Modifier.padding(start = 8.dp)) {
                                            Text(a.label, fontSize = 14.sp, color = TextDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(a.pkg, fontSize = 10.5.sp, color = TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // ── 区3：视觉学习集只读 ──
                if (learned.isNotEmpty()) {
                    Text("视觉路线学习集（read_screen 贫瘠时自动学习，可清空）", fontSize = 13.sp, color = TextFaint)
                    Text(
                        learned.sorted().joinToString("、") { appLabel(context, it) },
                        fontSize = 12.sp, color = TextDark,
                    )
                    Text(
                        if (confirmClear) "再点一次确认清空" else "清空学习记录",
                        fontSize = 12.sp,
                        color = if (confirmClear) WarmOrange else glass.accent,
                        modifier = Modifier.clickable {
                            if (!confirmClear) {
                                confirmClear = true
                            } else {
                                scope.launch { app?.configStore?.clearVisionLearned(); confirmClear = false; reload++ }
                            }
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("完成") } },
    )
}

// 包名 → 应用名（查不到时回退包名本身）
private fun appLabel(context: android.content.Context, pkg: String): String = runCatching {
    context.packageManager.getApplicationLabel(
        context.packageManager.getApplicationInfo(pkg, 0),
    ).toString()
}.getOrDefault(pkg)

// 枚举有 launcher 入口的用户应用（系统应用不列），图标转 ARGB Bitmap 供 Compose 显示
private fun enumerateApps(context: android.content.Context): List<InstalledApp> {
    val pm = context.packageManager
    val launchables = mutableSetOf<String>()
    pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .forEach { launchables.add(it.activityInfo.packageName) }
    return pm.getInstalledApplications(0)
        .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName in launchables }
        .map { info ->
            val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
            val bmp = runCatching {
                val d = pm.getApplicationIcon(info.packageName)
                val size = 96
                val b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(b)
                d.setBounds(0, 0, size, size)
                d.draw(c)
                b
            }.getOrNull()
            InstalledApp(info.packageName, label, bmp)
        }
        .sortedBy { it.label.lowercase() }
}
