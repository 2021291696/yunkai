package com.zhuolin.yunkai.ui.settings

// 屏幕感知卡片（自 SettingsScreen 拆出，门0 W-B4 单文件 500 行收口）：
// 总开关 + 无障碍状态行（1.5s 轮询）+ 屏蔽应用管理入口 + 悬浮球长期开关。
// 写入全部挂 appScope（W-B3 同族：页面退出不丢写入）。
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuolin.yunkai.YunkaiApp
import com.zhuolin.yunkai.service.screen.ScreenSenseService
import com.zhuolin.yunkai.ui.theme.LocalGlassScheme
import com.zhuolin.yunkai.ui.theme.TextFaint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun ScreenSenseCard() {
    val app = LocalContext.current.applicationContext as YunkaiApp
    val context = LocalContext.current
    val glass = LocalGlassScheme.current
    var screenSense by remember { mutableStateOf(false) }
    var a11yReady by remember { mutableStateOf(false) }
    var privacyOpen by remember { mutableStateOf(false) } // 屏蔽应用管理弹窗（M2b 收尾）
    LaunchedEffect(Unit) {
        screenSense = app.configStore.getScreenSense()
        // 轻量轮询：从系统设置授权回来后状态自动跟上（页面存活时 1.5s 一次，成本可忽略）
        while (true) {
            a11yReady = ScreenSenseService.ready
            delay(1500)
        }
    }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("屏幕感知", fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(checked = screenSense, onCheckedChange = { on ->
                screenSense = on
                // 写入挂 appScope（app 级存活）：rememberCoroutineScope 随页面退出取消，
                // 未落盘的写入会静默丢失（真 bug：拨开关后立刻退出=设置回退）
                app.appScope.launch { app.configStore.setScreenSense(on) }
                if (on) {
                    // 悬浮球随总开关出现（主战场入口，M2b）；点按唤起闪问面板（T9b：拉起透明 FlashActivity）
                    // 显式重开 = 用户要球回来：清掉「拖底删除圈」的单次隐藏标记（2026-10-02）
                    com.zhuolin.yunkai.service.screen.FloatingBall.clearSessionHidden()
                    com.zhuolin.yunkai.service.screen.FloatingBall.show(context) {
                        com.zhuolin.yunkai.ui.flash.FlashPanelLauncher.launch(context)
                    }
                } else {
                    // 开关关闭即移除悬浮球（原 ProjectionService.stop 联动已随该服务下线，此处只留悬浮球）
                    com.zhuolin.yunkai.service.screen.FloatingBall.remove()
                }
            })
        }
        Text("开启后 agent 可列出/打开应用并读取屏幕：文字走无障碍节点树，图片与自绘应用走截图视觉", fontSize = 12.sp, color = TextFaint)
        Text("隐私：银行/支付类默认不读；密码框内容永不上传；截屏随无障碍自动可用", fontSize = 12.sp, color = TextFaint)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { privacyOpen = true },
        ) {
            Text("屏蔽应用管理", fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.secondary)
        }
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
        // ── 悬浮球长期开关（2026-10-02）：持久化 DataStore，与拖底删除圈的"单次隐藏"分层——
        // 这里管"球存不存在"（重启仍在），拖底关闭管"本次先不见"（重启回来）。
        // 前置：屏幕感知总开关关闭时球无从谈起（行隐藏）。
        if (screenSense) {
            var ballEnabled by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) { ballEnabled = app.configStore.getBallEnabled() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text("悬浮球", fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = ballEnabled,
                    onCheckedChange = { on ->
                        ballEnabled = on
                        // 写入挂 appScope：NonCancellable（ConfigStore.editStore）保落盘
                        app.appScope.launch { app.configStore.setBallEnabled(on) }
                        if (on) {
                            // 显式开 = 用户要球：清单次隐藏标记
                            com.zhuolin.yunkai.service.screen.FloatingBall.clearSessionHidden()
                            com.zhuolin.yunkai.service.screen.FloatingBall.show(context) {
                                com.zhuolin.yunkai.ui.flash.FlashPanelLauncher.launch(context)
                            }
                        } else {
                            com.zhuolin.yunkai.service.screen.FloatingBall.remove()
                        }
                    },
                )
            }
        }
    }

    if (privacyOpen) ScreenPrivacyDialog(glass = glass, onClose = { privacyOpen = false })
}
