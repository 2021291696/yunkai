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
    private const val ID_PROGRESS = 4001
    private const val ID_RESULT = 4002

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH, "屏幕感知", NotificationManager.IMPORTANCE_LOW))
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
        notify(ctx, ID_PROGRESS, null)
    }

    private fun base(ctx: Context): Notification.Builder {
        val it = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            ctx, 41, it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pi)
    }

    private fun notify(ctx: Context, id: Int, n: Notification?) {
        try {
            ctx.getSystemService(NotificationManager::class.java)?.notify(id, n)
        } catch (e: Exception) {
            // POST_NOTIFICATIONS 未授等场景静默降级：通知是增益，绝不让主流程报错
        }
    }
}
