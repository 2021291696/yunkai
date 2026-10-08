package com.zhuolin.yunkai.service.screen

// 拖底缘光带（2026-10-07 设计定案：全宽柔光带，拖底消失的位置暗示）：
// 拖拽即亮（基础亮度，教学性：第一次拖就看得见底部有出口）、球越近底缘越亮（确认性）、
// 松手随雾化慢收/收手快收。只在拖拽期间存在的瞬态 overlay，不碰「不画常驻目标物」口径。
// 冷灰蓝渐变（#8FA3C8）：深色壁纸上读作柔光、浅色 app 背景上读作雾面，两栖可见。
// 按需加窗模式同 ClickIndicator：FLAG_NOT_TOUCHABLE 不挡触控；FloatingBall.remove() 兜底清理。
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

object BottomGlow {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var view: GlowView? = null
    private var wm: WindowManager? = null
    private var fadeAnim: ValueAnimator? = null

    /** 「底部区域」唯一口径：屏高的占比。光带窗高/临界暗示/松手消失判定三处共用一条底界。 */
    const val ZONE_FRACTION = 0.12f

    // 亮度曲线参数：拖起 0.22 起步，贴底 0.85 峰值（二次方缓起）
    private const val BASE = 0.22f
    private const val PEAK = 0.85f

    /** 拖拽开始调用（幂等：已显示则只保活）。setLevel 须在主线程（拖拽触摸即主线程）。 */
    fun show(context: Context) {
        if (view != null) return   // 幂等前置：拖拽期每次 MOVE 都调 show，别反复 post 空跑 runnable
        val run = {
            try {
                if (view == null) {
                    fadeAnim?.cancel(); fadeAnim = null
                    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    val dm = context.resources.displayMetrics
                    val v = GlowView(context)
                    val lp = WindowManager.LayoutParams(
                        dm.widthPixels, (dm.heightPixels * ZONE_FRACTION).toInt(),
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT,
                    ).apply {
                        gravity = Gravity.BOTTOM or Gravity.START
                        x = 0; y = 0
                    }
                    view = v
                    wm = windowManager
                    windowManager.addView(v, lp)
                    v.alpha = 0f
                    v.animate().alpha(BASE).setDuration(180L).start()
                }
            } catch (e: Exception) {
                android.util.Log.w("yunkai", "BottomGlow show failed: ${e.message}")
                remove()
            }
        }
        if (Looper.myLooper() == mainHandler.looper) run() else mainHandler.post(run)
    }

    /** p = 球底沿绝对 y / 屏高。内部映射：起步 BASE，贴底 PEAK，二次缓起。 */
    fun setLevel(bottomOverScreen: Float) {
        val v = view ?: return
        fadeAnim?.cancel(); fadeAnim = null
        val t = ((bottomOverScreen - 0.22f) / 0.78f).coerceIn(0f, 1f)
        v.alpha = BASE + (PEAK - BASE) * t * t
    }

    /** 手势收场：dismiss=true 随雾化 320ms 慢收，否则 150ms 快收。 */
    fun release(dismiss: Boolean) {
        val v = view ?: return
        fadeAnim?.cancel()
        fadeAnim = ValueAnimator.ofFloat(v.alpha, 0f).apply {
            duration = if (dismiss) 320L else 150L
            addUpdateListener { a -> v.alpha = a.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) = remove()
            })
        }
        fadeAnim!!.start()
    }

    fun remove() {
        fadeAnim?.cancel()
        try { view?.let { wm?.removeView(it) } } catch (_: Exception) {}
        view = null
        wm = null
        fadeAnim = null
    }
}

private class GlowView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp = resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // 主体渐变：上透下浓的冷灰蓝雾面
        paint.shader = LinearGradient(
            0f, h * 0.35f, 0f, h,
            Color.TRANSPARENT, Color.parseColor("#8FA3C8"), Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.35f, w, h, paint)
        // 底缘亮线（1.5dp）：光带的"地平线"，深浅背景都可读
        paint.shader = null
        paint.color = Color.parseColor("#B9C7E2")
        c.drawRect(0f, h - 1.5f * dp, w, h, paint)
    }
}
