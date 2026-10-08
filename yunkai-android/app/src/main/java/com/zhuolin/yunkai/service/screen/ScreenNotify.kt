package com.zhuolin.yunkai.service.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.zhuolin.yunkai.MainActivity

// 屏幕感知后台送达（设计简报 §六 / Q7 定案）：
// 进度通知=可更新单条（每步回显），结果通知=静默点入主界面；IMPORTANCE_LOW 不打横幅不响铃。
object ScreenNotify {
    private const val CH = "screen_sense"
    private const val CH_A11Y = "a11y_watchdog"
    private const val ID_PROGRESS = 4001
    private const val ID_RESULT = 4002
    private const val ID_A11Y = 4003

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH, "屏幕感知", NotificationManager.IMPORTANCE_LOW))
        // 无障碍断开提醒：DEFAULT 级别（打横幅）——服务挂了用户必须知道，与静默进度/结果区分
        nm.createNotificationChannel(NotificationChannel(CH_A11Y, "无障碍服务提醒", NotificationManager.IMPORTANCE_DEFAULT))
    }

    // 执行中进度回显（后台才发；每步覆盖同一条）
    fun notifyProgress(ctx: Context, text: String) {
        ensureChannels(ctx)
        val n = base(ctx)
            .setContentTitle("云开正在执行任务")
            .setContentText(text)
            .setOngoing(true)
            .build()
        notify(ctx, ID_PROGRESS, n)
    }

    // 完成结果：标题=会话标题，正文=回答首行；点击拉回云开看完整回答
    fun notifyResult(ctx: Context, title: String, summary: String) {
        ensureChannels(ctx)
        cancelProgress(ctx)
        val n = base(ctx)
            .setContentTitle(title.ifEmpty { "云开" })
            .setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .build()
        notify(ctx, ID_RESULT, n)
    }

    fun cancelProgress(ctx: Context) {
        cancel(ctx, ID_PROGRESS)
    }

    // 无障碍服务断开（运行时看护）：固定 id 不叠加，点击拉回云开（前台弹修复引导已就位）
    fun notifyA11yDisconnected(ctx: Context) {
        ensureChannels(ctx)
        val n = Notification.Builder(ctx, CH_A11Y)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("云开无障碍服务已断开")
            .setContentText("悬浮球与读屏已失效，点击进入修复")
            .setAutoCancel(true)
            .setContentIntent(launchPi(ctx))
            .build()
        notify(ctx, ID_A11Y, n)
    }

    fun cancelA11y(ctx: Context) {
        cancel(ctx, ID_A11Y)
    }

    // 点击通知拉回主界面（MainActivity singleTop，弹窗逻辑在 NavRoot）
    private fun launchPi(ctx: Context): PendingIntent {
        val it = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            ctx, 41, it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun base(ctx: Context): Notification.Builder {
        return Notification.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(launchPi(ctx))
    }

    private fun notify(ctx: Context, id: Int, n: Notification?) {
        try {
            ctx.getSystemService(NotificationManager::class.java)?.notify(id, n)
        } catch (e: Exception) {
            // POST_NOTIFICATIONS 未授等场景静默降级：通知是增益，绝不让主流程报错
        }
    }

    // 取消必须走 NotificationManager.cancel（notify(id, null) 不取消通知——真机验证抓出：
    // 重连后断开通知仍在，旧 cancelProgress 同款 bug，进度条通知清不掉也是它）
    private fun cancel(ctx: Context, id: Int) {
        try {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(id)
        } catch (_: Exception) {
        }
    }
}
