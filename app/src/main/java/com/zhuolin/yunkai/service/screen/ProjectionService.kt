package com.zhuolin.yunkai.service.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.view.WindowManager
import java.io.ByteArrayOutputStream

// MediaProjection 截屏前台服务（type=mediaProjection）：授权一次持续可用，重启后需重授。
// 约束（API 34+ 硬性）：createMediaProjection 之前必须已 startForeground，否则 SecurityException。
// 系统强制常驻通知「云开正在投射屏幕」= 截屏能力的可见提示（设计简报 §五），不做静默截图。
class ProjectionService : Service() {
    companion object {
        private const val CHANNEL = "screen_projection"
        private const val NOTIF_ID = 4003

        @Volatile private var projection: MediaProjection? = null
        @Volatile private var reader: ImageReader? = null
        @Volatile private var mirrorDisplay: android.hardware.display.VirtualDisplay? = null

        val active: Boolean get() = projection != null

        // 授权回调（SettingsScreen 的 ActivityResult）→ 拉起前台服务
        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, ProjectionService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ProjectionService::class.java))
        }

        // 同步截一帧（IO 线程调用）：等帧 → RGBA buffer → Bitmap → 长边压 maxEdge → JPEG(85) → base64。
        // 轮询等帧：VirtualDisplay 建好后首帧有延迟，acquireLatestImage 空手时短暂退避重试。
        fun captureBase64(maxEdge: Int = 1280): String {
            val r = reader ?: throw IllegalStateException("截屏未授权：请先在设置页「屏幕感知」中授权屏幕录制")
            var img: Image? = null
            var tries = 0
            while (img == null && tries < 12) {
                img = r.acquireLatestImage()
                if (img == null) {
                    Thread.sleep(120)
                    tries++
                }
            }
            val frame = img ?: throw IllegalStateException("截屏超时：屏幕暂无可用帧，请稍后重试")
            try {
                val plane = frame.planes[0]
                val w = frame.width
                val h = frame.height
                val rowPix = plane.rowStride / plane.pixelStride
                val raw = Bitmap.createBitmap(rowPix, h, Bitmap.Config.ARGB_8888)
                raw.copyPixelsFromBuffer(plane.buffer)
                val bmp = if (rowPix == w) raw else Bitmap.createBitmap(raw, 0, 0, w, h)
                val scaled = scaleDown(bmp, maxEdge)
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                if (scaled !== bmp) scaled.recycle()
                if (bmp !== raw) bmp.recycle()
                raw.recycle()
                return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            } finally {
                frame.close()
            }
        }

        private fun scaleDown(bmp: Bitmap, maxEdge: Int): Bitmap {
            val longEdge = maxOf(bmp.width, bmp.height)
            if (longEdge <= maxEdge) return bmp
            val ratio = maxEdge.toFloat() / longEdge
            val nw = (bmp.width * ratio).toInt().coerceAtLeast(1)
            val nh = (bmp.height * ratio).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(bmp, nw, nh, true)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "屏幕投射", NotificationManager.IMPORTANCE_LOW),
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("云开正在投射屏幕")
            .setContentText("agent 可截取当前屏幕用于视觉理解；停止请在设置页关闭")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("code", Int.MIN_VALUE) ?: Int.MIN_VALUE
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (code == Int.MIN_VALUE || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        releaseSession()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            mpm.getMediaProjection(code, data) ?: throw IllegalStateException("获取屏幕投影失败")
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        val wm = getSystemService(WindowManager::class.java)
        val b = wm.maximumWindowMetrics.bounds
        val reader0 = ImageReader.newInstance(b.width(), b.height(), PixelFormat.RGBA_8888, 2)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { stopSelf() }
            }
        }, mainHandler)
        val vd = mp.createVirtualDisplay(
            "yunkai_capture", b.width(), b.height(),
            resources.displayMetrics.densityDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader0.surface, null, mainHandler,
        )
        projection = mp
        reader = reader0
        mirrorDisplay = vd
        return START_STICKY
    }

    override fun onDestroy() {
        releaseSession()
        super.onDestroy()
    }

    private fun releaseSession() {
        try { mirrorDisplay?.release() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        try { projection?.stop() } catch (e: Exception) {}
        mirrorDisplay = null
        reader = null
        projection = null
    }
}
