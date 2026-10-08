package com.zhuolin.yunkai.service.screen

// 拖底消失特效（2026-10-07 设计定案：云散 · 轻快利落，~450ms）：
// 球本体复刻帧放大 1.12 倍淡出，原地绽出 3 个雾团就地微浮散开，即收即止、无额外残留元素。
// 独立 overlay 承载（球窗 44dp 装不下雾团），按需加窗模式同 ClickIndicator：
// 主线程落窗、FLAG_NOT_TOUCHABLE、播完自移除；特效失败只丢动画，不碰 sessionHidden 语义。
// 坐标与球窗 params 同一父帧相对空间，首帧与 GlassBallView 展开态逐像素对齐（无闪烁换场）。
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator

object CloudDissolve {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var view: DissolveView? = null
    private var wm: WindowManager? = null

    /**
     * 在球的位置播放雾化。ballX/ballY 传球窗 params（父帧相对空间），ballSize 传球窗边长。
     * 主线程调用时同步落窗——调用方随后再撤球窗，首帧即球、同帧换场无闪烁。
     */
    fun show(context: Context, ballX: Int, ballY: Int, ballSize: Int) {
        val run = {
            try {
                remove()
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val dp = context.resources.displayMetrics.density
                val padX = (22 * dp).toInt()
                val padTop = (34 * dp).toInt()   // 顶部留雾团上浮空间
                val padBottom = (8 * dp).toInt()
                val v = DissolveView(context, ballSize.toFloat())
                val lp = WindowManager.LayoutParams(
                    ballSize + 2 * padX, ballSize + padTop + padBottom,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = ballX - padX
                    y = ballY - padTop
                }
                view = v
                wm = windowManager
                windowManager.addView(v, lp)
                v.start { remove() }
            } catch (e: Exception) {
                android.util.Log.w("yunkai", "CloudDissolve show failed: ${e.message}")
                remove()
            }
        }
        if (Looper.myLooper() == mainHandler.looper) run() else mainHandler.post(run)
    }

    fun remove() {
        try { view?.let { wm?.removeView(it) } } catch (_: Exception) {}
        view = null
        wm = null
    }
}

private class DissolveView(context: Context, private val ballSize: Float) : View(context) {
    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cloud = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#7A7A80")
        strokeWidth = 1.6f * dp
    }
    private val puff = Paint(Paint.ANTI_ALIAS_FLAG)
    private var frac = 0f

    fun start(onEnd: () -> Unit) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450L
            interpolator = DecelerateInterpolator()
            addUpdateListener { frac = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) = onEnd()
            })
        }.start()
    }

    override fun onDraw(c: Canvas) {
        val f = frac.coerceIn(0f, 1f)
        val cx = width / 2f
        val cy = height - ballSize / 2f - 8 * dp   // 球心贴窗底（与球窗原位对齐）
        val r = ballSize / 2f - 1.2f * dp

        // ---- 雾团 ×3：错峰起步（0/55/110ms），快显慢隐，就地微浮上飘 + 横向散开 ----
        val delays = floatArrayOf(0f, 0.12f, 0.24f)
        val dxs = floatArrayOf(0f, 0.20f, -0.18f)
        val startR = floatArrayOf(0.16f, 0.13f, 0.12f)
        val endR = floatArrayOf(0.38f, 0.30f, 0.26f)
        for (i in 0..2) {
            val p = ((f - delays[i]) / (1f - delays[i])).coerceIn(0f, 1f)
            if (p <= 0f) continue
            val ease = 1f - (1f - p) * (1f - p)
            val pr = (startR[i] + (endR[i] - startR[i]) * ease) * ballSize
            val px = cx + dxs[i] * ballSize * 0.5f * ease
            val py = cy - ease * 12f * dp - (if (i == 1) 6f * dp else 0f)
            val pa = 0.85f * (1f - p) * (p * 5f).coerceAtMost(1f)
            puff.shader = RadialGradient(px, py, pr,
                Color.parseColor("#F2F4F8"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            puff.alpha = (pa * 255).toInt()
            c.drawCircle(px, py, pr, puff)
        }

        // ---- 球复刻帧（GlassBallView 展开态同款）：放大 + 淡出 ----
        val layer = c.saveLayerAlpha(cx - r - 2 * dp, cy - r - 2 * dp,
            cx + r + 2 * dp, cy + r + 2 * dp, ((1f - f) * 255).toInt())
        c.scale(1f + 0.12f * f, 1f + 0.12f * f, cx, cy)
        halo.color = 0x33000000
        c.drawCircle(cx, cy + r * 0.1f, r + 1.1f * dp, halo)
        fill.shader = LinearGradient(0f, cy - r, 0f, cy + r,
            Color.parseColor("#FEFEFF"), Color.parseColor("#E9EAF0"), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, fill)
        border.color = 0x26000000.toInt()
        border.strokeWidth = 0.8f * dp
        c.drawCircle(cx, cy, r - border.strokeWidth / 2f, border)
        drawCloud(c, cx, cy, r * 0.58f)
        c.restoreToCount(layer)
    }

    // 云朵轮廓线：与 GlassBallView.drawCloud 逐参数一致（换场帧才对得上）
    private fun drawCloud(c: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Path()
        val bottom = cy + r * 0.42f
        val left = cx - r * 0.72f
        val right = cx + r * 0.72f
        p.addArc(cx - r * 0.62f - r * 0.34f, bottom - r * 0.68f,
            cx - r * 0.62f + r * 0.34f, bottom + r * 0.10f, 90f, 180f)
        p.addArc(cx - r * 0.42f, cy - r * 0.78f, cx + r * 0.42f, cy + r * 0.24f, 180f, 180f)
        p.addArc(cx + r * 0.28f - r * 0.38f, bottom - r * 0.76f,
            cx + r * 0.28f + r * 0.38f, bottom + r * 0.02f, 90f, 180f)
        p.moveTo(left, bottom)
        p.lineTo(right, bottom)
        c.drawPath(p, cloud)
    }
}
