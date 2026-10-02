package com.zhuolin.yunkai.service.screen

// 悬浮球自绘（2026-10-02 设计定案：苹果 AssistiveTouch 风格）。
// 展开态：白底近实色圆 + 灰色云朵轮廓线 + 细描边 + 软落影（无文字，替代旧「云」字占位）。
// 贴边态（月牙胶囊）：同窗体位移半个身位藏进屏外，整体半透明、云朵缩小移向可见侧；
//   点月牙回球、拖月牙拽出、点球开面板——状态机在 FloatingBall。
// 左右两缘（顶部状态栏/底部手势条不做）。
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View

class GlassBallView(context: Context) : View(context) {
    /** 贴边态：true=月牙胶囊（配合 dockSide），false=展开球 */
    var docked = false
        set(value) {
            field = value
            invalidate()
        }

    /** 贴边侧：-1=左缘（藏左半），+1=右缘（藏右半） */
    var dockSide = 0

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cloud = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        typeface = Typeface.SANS_SERIF
    }

    override fun onDraw(c: Canvas) {
        val s = width.toFloat()
        val cx = s / 2f
        val cy = s / 2f
        val r = s / 2f - 1.2f * dp
        val alpha = if (docked) 0.62f else 1f
        // 白底渐变（近实色，左上微亮）
        fill.shader = LinearGradient(
            0f, cy - r, 0f, cy + r,
            Color.parseColor("#FEFEFF"), Color.parseColor("#E9EAF0"),
            Shader.TileMode.CLAMP)
        // 软落影（苹果质感的关键一半）
        halo.color = 0x33000000
        c.drawCircle(cx + dockSide * r * 0.06f, cy + r * 0.1f, r + 1.1f * dp, halo)
        val sc = c.saveLayerAlpha(cx - r - 2 * dp, cy - r - 2 * dp, cx + r + 2 * dp, cy + r + 2 * dp, (alpha * 255).toInt())
        c.drawCircle(cx, cy, r, fill)
        // 细描边
        border.color = 0x26000000.toInt(); border.strokeWidth = 0.8f * dp
        c.drawCircle(cx, cy, r - border.strokeWidth / 2f, border)
        // 云朵轮廓（贴边态缩小移向可见侧）
        val gr = if (docked) r * 0.42f else r * 0.58f
        val gcx = cx + dockSide * (if (docked) r * 0.34f else 0f)
        val gcy = cy
        drawCloud(c, gcx, gcy, gr)
        c.restoreToCount(sc)
    }

    // 云朵轮廓线：三段圆弧鼓包 + 底线，灰色描边
    private fun drawCloud(c: Canvas, cx: Float, cy: Float, r: Float) {
        cloud.color = Color.parseColor("#7A7A80")
        cloud.strokeWidth = 1.6f * dp
        val p = Path()
        val bottom = cy + r * 0.42f
        val left = cx - r * 0.72f
        val right = cx + r * 0.72f
        // 左鼓包（小圆）
        p.addArc(cx - r * 0.62f - r * 0.34f, bottom - r * 0.68f,
                 cx - r * 0.62f + r * 0.34f, bottom + r * 0.10f,
                 90f, 180f)
        // 中大鼓包
        p.addArc(cx - r * 0.42f, cy - r * 0.78f, cx + r * 0.42f, cy + r * 0.24f,
                 180f, 180f)
        // 右鼓包
        p.addArc(cx + r * 0.28f - r * 0.38f, bottom - r * 0.76f,
                 cx + r * 0.28f + r * 0.38f, bottom + r * 0.02f,
                 90f, 180f)
        // 底线闭合
        p.moveTo(left, bottom)
        p.lineTo(right, bottom)
        c.drawPath(p, cloud)
    }
}
