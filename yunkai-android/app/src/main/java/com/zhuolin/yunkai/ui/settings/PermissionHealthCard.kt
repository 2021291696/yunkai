package com.zhuolin.yunkai.ui.settings

// 必要权限体检卡（2026-10-07 用户拍板）：设置页顶部常驻三行实时状态——
// ①无障碍服务（悬浮球/读屏/写操作全链前提）三态：未开启→「去开启」跳系统页；
//   已开启未连接（HyperOS 死绑定，真机多次踩）→「重置」= PM 组件开关触发系统 rebind（对自有组件免权限），
//   仍不行再走系统开关；已连接 ✓。
// ②通知权限（Android 13+ 运行时授权）：后台执行的进度/结果通知（Q7）依赖。
// ③获取应用列表（HyperOS 权限闸，ledger P15）：未授时屏蔽应用选择列表只剩自己（app 可自检）。
//   深链组件名随 HyperOS 版本漂移（P15 教训：不硬跳）→ 落应用详情页+文字指引。
// 状态 1.5s 轮询自刷新（开销可忽略），从系统设置返回后无需手动刷新。
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.zhuolin.yunkai.service.screen.ScreenSenseService
import com.zhuolin.yunkai.ui.chat.glassBorder
import com.zhuolin.yunkai.ui.theme.GlassTokens
import com.zhuolin.yunkai.ui.theme.LocalGlassScheme
import com.zhuolin.yunkai.ui.theme.TextFaint
import com.zhuolin.yunkai.ui.theme.TextMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val A11Y_COMPONENT = "com.zhuolin.yunkai/com.zhuolin.yunkai.service.screen.ScreenSenseService"

private enum class A11yState { ON_CONNECTED, ON_NOT_BOUND, OFF, COMPONENT_DISABLED }

private fun a11yState(context: Context): A11yState {
    // 组件被禁用 = 系统无障碍列表里根本不出现本 app（「去开启」后找不到的那类成因），
    // 优先检测并给出一步修复——比 OFF（去系统页翻列表）更早拦住
    val comp = ComponentName(context, ScreenSenseService::class.java)
    val compState = context.packageManager.getComponentEnabledSetting(comp)
    if (compState == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
        return A11yState.COMPONENT_DISABLED
    }
    val enabled = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: ""
    val isEnabled = enabled.split(':').any {
        it.equals(A11Y_COMPONENT, true) || it.equals("com.zhuolin.yunkai/.service.screen.ScreenSenseService", true)
    }
    val bound = ScreenSenseService.instance != null
    return when {
        !isEnabled -> A11yState.OFF
        bound -> A11yState.ON_CONNECTED
        else -> A11yState.ON_NOT_BOUND
    }
}

// HyperOS 权限闸自检（P15）：未授权时 launcher 查询只回自己
private fun appListGranted(context: Context): Boolean {
    val n = context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
    ).size
    return n > 1
}

// PM 组件开关触发 AccessibilityManagerService 重绑（死绑定自愈；对自有组件免任何权限）。
// 两步必须同帧连发且调用方保证不受协程取消打断：卡在 DISABLED 的服务从系统无障碍列表
// 消失（「去开启找不到云开」的成因）；startupEnsureA11yComponent 在 app 启动兜底复位
private fun resetA11yService(context: Context) {
    val pm = context.packageManager
    val comp = ComponentName(context, ScreenSenseService::class.java)
    pm.setComponentEnabledSetting(comp, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
    pm.setComponentEnabledSetting(comp, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP)
}

// 组件禁用态的一步修复（卡在 DISABLED 时系统列表不可见，重置的 DISABLED 步反而无益）
private fun enableA11yComponent(context: Context) {
    val comp = ComponentName(context, ScreenSenseService::class.java)
    context.packageManager.setComponentEnabledSetting(
        comp, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        android.content.pm.PackageManager.DONT_KILL_APP,
    )
}

@Composable
fun PermissionHealthCard() {
    val context = LocalContext.current
    val glass = LocalGlassScheme.current
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()   // 轮询驱动重组：状态检测都是轻量查询
            delay(1500)
        }
    }

    // 三项状态读取都在 binder（Settings.Secure / NotificationManager / PM 查询）——必须挂 IO 线程，
    // 主线程直读会在滚动中途每 1.5s 卡一下帧（设置页滑动卡顿的元凶之一，2026-10-08 帧统计定位）
    val a11y by produceState(A11yState.OFF, tick) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) { a11yState(context) }
    }
    val notifyOk by produceState(false, tick) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
    val appListOk by produceState(false, tick) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) { appListGranted(context) }
    }

    GlassCard {
        Text("必要权限", fontSize = 16.sp, color = TextMuted)
        Text("核心能力的前置系统授权，缺了对应功能会静默失效", fontSize = 10.sp, color = TextFaint)

        PermissionRow(
            name = "无障碍服务",
            hint = when (a11y) {
                A11yState.COMPONENT_DISABLED -> "服务被禁用，系统列表不会显示本 app——点「修复」一键启用"
                else -> "悬浮球、读屏、写操作都依赖它；系统列表拉到「已下载的应用」分组找「云开」"
            },
            badge = when (a11y) {
                A11yState.ON_CONNECTED -> "✓ 运行中"
                A11yState.ON_NOT_BOUND -> "⚡ 已开启未连接"
                A11yState.OFF -> "✕ 未开启"
                A11yState.COMPONENT_DISABLED -> "✕ 服务已禁用"
            },
            badgeColor = when (a11y) {
                A11yState.ON_CONNECTED -> Color(0xFF6BCB77)
                A11yState.ON_NOT_BOUND -> Color(0xFFFFB74D)
                A11yState.OFF -> Color(0xFFFF8A80)
                A11yState.COMPONENT_DISABLED -> Color(0xFFFF8A80)
            },
            actions = {
                when (a11y) {
                    A11yState.OFF -> ActionChip("去开启") {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    A11yState.COMPONENT_DISABLED -> ActionChip("修复") {
                        enableA11yComponent(context)
                    }
                    A11yState.ON_NOT_BOUND -> {
                        ActionChip("重置") { resetA11yService(context) }
                        ActionChip("去设置") {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                    A11yState.ON_CONNECTED -> {}
                }
            },
        )
        PermissionRow(
            name = "通知权限",
            hint = "后台执行的进度与结果通知",
            badge = if (notifyOk) "✓ 就绪" else "✕ 未开启",
            badgeColor = if (notifyOk) Color(0xFF6BCB77) else Color(0xFFFF8A80),
            actions = {
                if (!notifyOk) ActionChip("去开启") {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
        PermissionRow(
            name = "获取应用列表",
            hint = "屏蔽应用选择列表；HyperOS 侧载默认拒（设置→应用管理→云开→权限）",
            badge = if (appListOk) "✓ 就绪" else "✕ 未授权",
            badgeColor = if (appListOk) Color(0xFF6BCB77) else Color(0xFFFF8A80),
            actions = {
                if (!appListOk) ActionChip("去授权") {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
    }
}

@Composable
private fun PermissionRow(
    name: String,
    hint: String,
    badge: String,
    badgeColor: Color,
    actions: @Composable () -> Unit,
) {
    val glass = LocalGlassScheme.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontSize = 14.sp, color = glass.textHi)
            Text(hint, fontSize = 10.sp, lineHeight = 13.sp, color = TextFaint)
        }
        actions()
        Text(
            badge,
            fontSize = 12.sp,
            color = badgeColor,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun ActionChip(text: String, onClick: () -> Unit) {
    val glass = LocalGlassScheme.current
    Text(
        text,
        fontSize = 12.sp,
        color = glass.textHi,
        modifier = Modifier
            .padding(start = 8.dp)
            .glassBorder(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
