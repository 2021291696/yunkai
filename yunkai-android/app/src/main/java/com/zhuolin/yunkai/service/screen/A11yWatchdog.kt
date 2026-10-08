package com.zhuolin.yunkai.service.screen

// 无障碍运行时看护（2026-10-07 用户定案）：服务断开不能只靠 app 启动时修一次——
// 运行中被系统杀绑也要让用户知道。语义=「通知+弹窗」，不是自动拉回：
// ① onUnbind 5s 未重绑（复用既有去抖，闪断不吵）→ 确认断开；
// ② 用户不在 app → 发一条通知（固定 id 不叠加，点击拉回云开）；
// ③ 用户在 app / 点通知回来 → NavRoot 弹修复引导弹窗（忽略后本断开期内不再弹，重连即复位）。
// 注意边界：进程整体被杀时无人能发通知——那部分由启动自愈+体检卡兜底，此处只覆盖「绑定断了进程还活着」
// 的高频场景（HyperOS 死绑定/闪断后未重绑）。
import android.content.Context
import androidx.compose.runtime.mutableStateOf

object A11yWatchdog {
    /** true=断开已确认（5s 未重绑）；NavRoot 观察此值弹修复引导 */
    val disconnected = mutableStateOf(false)

    fun onConfirmedDisconnected(ctx: Context) {
        if (disconnected.value) return   // 本断开期已提示过
        disconnected.value = true
        android.util.Log.i("yunkai", "a11y watchdog confirmed (fg=${com.zhuolin.yunkai.MainActivity.activityForeground})")
        if (!com.zhuolin.yunkai.MainActivity.activityForeground) {
            ScreenNotify.notifyA11yDisconnected(ctx)
        }
    }

    fun onReconnected(ctx: Context) {
        if (!disconnected.value) return
        disconnected.value = false
        android.util.Log.i("yunkai", "a11y watchdog cleared")
        ScreenNotify.cancelA11y(ctx)
    }
}
