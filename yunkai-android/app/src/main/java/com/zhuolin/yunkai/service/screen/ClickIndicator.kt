package com.zhuolin.yunkai.service.screen

// 点击指示圈（M2c-T12）：无障碍 overlay 在动作坐标画圈 600ms，让用户看见 agent 正要点哪。
// 门0 B1：调用方（WritePlanExecutor）跑在 IO 线程，ViewRootImpl 要求 Looper 线程——
// addView 必须经主线程 handler 落地；失败记日志，不再静默吞。
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager

object ClickIndicator {
    @Volatile private var view: View? = null
    private var wm: WindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null

    fun show(context: Context, x: Int, y: Int) {
        mainHandler.post {
            try {
                hideRunnable?.let { mainHandler.removeCallbacks(it) }   // 连续动作不缩短上一圈存活（门0 M6）
                remove()
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm = windowManager
                val size = (56 * context.resources.displayMetrics.density).toInt()
                val v = View(context).apply {
                    background = object : Drawable() {
                        override fun draw(c: Canvas) {
                            val p = android.graphics.Paint().apply {
                                color = 0x6600FF88; style = android.graphics.Paint.Style.STROKE
                                strokeWidth = 6f; isAntiAlias = true
                            }
                            c.drawCircle(size / 2f, size / 2f, size / 2f - 8f, p)
                        }
                        override fun setAlpha(a: Int) {}
                        override fun setColorFilter(cf: ColorFilter?) {}
                        @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
                    }
                }
                val lp = WindowManager.LayoutParams(
                    size, size,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    this.x = x - size / 2; this.y = y - size / 2
                }
                windowManager.addView(v, lp)
                view = v
                val hide = Runnable { remove() }
                hideRunnable = hide
                mainHandler.postDelayed(hide, 600)
            } catch (e: Exception) {
                android.util.Log.w("yunkai", "ClickIndicator show failed: ${e.message}")
            }
        }
    }

    fun remove() {
        try { view?.let { wm?.removeView(it) } } catch (e: Exception) {}
        view = null
    }
}
