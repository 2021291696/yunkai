package com.zhuolin.yunkai.service.screen

// 悬浮球（M2b-T8 → 2026-10-02 苹果风格两态改版 → 拖底删除圈）：
// 展开态 = 白底云朵轮廓球（点球开面板）；贴边态 = 月牙胶囊（窗体位移半个身位藏进屏外，
// 点一下展开回球，向外拖自然拽出成球）。松手近边缘即吸附变月牙；左右两缘。
// 删除（2026-10-02 用户拍板）：拖拽期屏底中央出现删除圈，球心入圈高亮、圈内松手关闭——
// 关闭 = 单次隐藏（sessionHidden 内存标记）：屏幕感知保持开启，MIUI 闪断重绑不复活，
// 重启 app 或重新打开屏幕感知开关即回来。
// TYPE_ACCESSIBILITY_OVERLAY 由无障碍服务添加，免悬浮窗授权；随屏幕感知总开关生灭。
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.sqrt

@SuppressLint("ClickableViewAccessibility")
object FloatingBall {
    private var ballView: GlassBallView? = null
    private var wm: WindowManager? = null
    private var docked = false
    private var dockSide = 0   // -1 左缘 / +1 右缘
    private var anim: android.animation.ValueAnimator? = null
    private var downRaw = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false
    private var sessionHidden = false   // 单次关闭标记：内存态，重启/重开开关即清（restoreBallIfEnabled 检查）
    private var deleteView: View? = null

    val isSessionHidden: Boolean get() = sessionHidden
    fun clearSessionHidden() { sessionHidden = false }

    fun show(context: Context, onBallTap: () -> Unit) {
        if (ballView != null) return
        // TYPE_ACCESSIBILITY_OVERLAY 只能由无障碍服务上下文 addView（Activity/App 上下文会 BadTokenException），
        // 故实际宿主取服务实例；无障碍未授权（服务未连接）时无法显示，记日志后跳过，待授权后再开关一次即可。
        val host: Context = ScreenSenseService.instance ?: run {
            android.util.Log.w("yunkai", "FloatingBall.show: a11y 服务未连接，悬浮球不显示")
            return
        }
        val windowManager = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val ball = GlassBallView(host)
        val size = (44 * host.resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 400 }
        val screenW = host.resources.displayMetrics.widthPixels
        val screenH = host.resources.displayMetrics.heightPixels

        // 贴边位置：窗体位移半个身位藏进屏外，可见部分即月牙
        fun dockX(side: Int) = if (side < 0) -size / 2 else screenW - size / 2
        fun expandX(side: Int) = if (side < 0) 0 else screenW - size

        fun safeUpdate() {
            // 门0 W5：拖拽/动画路径与 remove()（闪断翻转）同在主线程，裸调 updateViewLayout
            // 对已移除 view 抛 IllegalArgumentException 直接崩溃——统一走这里吞掉
            try { windowManager.updateViewLayout(ball, params) } catch (e: Exception) {}
        }

        fun animateX(to: Int) {
            // 门0 W4：动画器存句柄，新动画/DOWN 先 cancel——双动画器逐帧互写会振荡，拖拽被在飞帧覆盖
            anim?.cancel()
            anim = android.animation.ValueAnimator.ofInt(params.x, to).apply {
                duration = 220
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    params.x = it.animatedValue as Int
                    safeUpdate()
                }
                start()
            }
        }

        fun dock(side: Int) {
            dockSide = side
            docked = true
            ball.dockSide = side
            ball.docked = true   // 视觉立刻切月牙，窗体滑入半藏位
            animateX(dockX(side))
        }

        fun expand() {
            val side = if (dockSide < 0) 0 else 1
            docked = false
            ball.docked = false
            animateX(expandX(side))
        }

        // ── 删除圈（拖底关闭，2026-10-02 用户拍板）──
        val zoneSize = (60 * host.resources.displayMetrics.density).toInt()
        var zoneCaptured = false
        fun showDeleteZone() {
            if (deleteView != null) return
            val v = DeleteZoneView(host)
            val zp = WindowManager.LayoutParams(
                zoneSize, zoneSize,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (screenW - zoneSize) / 2
                y = screenH - zoneSize - (36 * host.resources.displayMetrics.density).toInt()
            }
            try {
                windowManager.addView(v, zp)
                deleteView = v
            } catch (e: Exception) {
                android.util.Log.w("yunkai", "delete zone addView failed: ${e.message}")
            }
        }
        fun hideDeleteZone() {
            deleteView?.let {
                try { (it.parent as? WindowManager)?.removeView(it) } catch (e: Exception) {}
                try { windowManager.removeView(it) } catch (e: Exception) {}
            }
            deleteView = null
            zoneCaptured = false
        }
        fun closeBall() {
            // 球滑到圈心 + 缩小淡出 → 单次隐藏收场
            params.x = (screenW - size) / 2
            params.y = screenH - zoneSize / 2 - size / 2 - (36 * host.resources.displayMetrics.density).toInt()
            safeUpdate()
            ball.animate().alpha(0f).scaleX(0.25f).scaleY(0.25f).setDuration(180)
                .withEndAction {
                    sessionHidden = true
                    hideDeleteZone()
                    remove()
                }
                .start()
        }

        ball.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRaw = ev.rawX; downRawY = ev.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    anim?.cancel()   // 门0 W4：触摸优先，打断在飞动画
                    ball.alpha = 0.8f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downRaw).toInt()
                    val dy = (ev.rawY - downRawY).toInt()
                    if (moved || abs(dx) + abs(dy) > 12) {
                        if (docked) {
                            // 门0 W3：脱离贴边那一帧重置锚点——startX 记在半藏位（负值/超界），
                            // 直接钳制会瞬跳一整颗身位；重置到展开位并把 raw 锚对齐本帧防 dx 突变
                            docked = false; ball.docked = false
                            startX = if (dockSide < 0) 0 else screenW - params.width
                            downRaw = ev.rawX
                        }
                        moved = true
                        // P9/门0 M4：边界 clamp——杜绝 FLAG_LAYOUT_NO_LIMITS 下拖出屏外永久丢球
                        params.x = (startX + dx).coerceIn(0, screenW - params.width)
                        params.y = (startY + dy).coerceIn(0, screenH - params.height)
                        safeUpdate()
                        // 删除圈：拖拽期常驻屏底，球心进捕获半径即高亮
                        showDeleteZone()
                        val bcx = params.x + params.width / 2f
                        val bcy = params.y + params.height / 2f
                        val zcx = (screenW - zoneSize) / 2f + zoneSize / 2f
                        val zcy = screenH - zoneSize / 2f - (36 * host.resources.displayMetrics.density).toInt()
                        val captured = sqrt((bcx - zcx) * (bcx - zcx) + (bcy - zcy) * (bcy - zcy)) <
                            zoneSize / 2f + params.width * 0.4f
                        if (captured != zoneCaptured) {
                            zoneCaptured = captured
                            (deleteView as? DeleteZoneView)?.highlighted = captured
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    ball.alpha = 1f
                    when {
                        moved && zoneCaptured -> closeBall()          // 拖底删除（单次隐藏）
                        !moved && docked -> { hideDeleteZone(); expand() }  // 点月牙 → 展开回球（苹果语义）
                        !moved -> { hideDeleteZone(); onBallTap() }         // 点球 → 开面板
                        else -> { hideDeleteZone(); dock(if (params.x + params.width / 2 < screenW / 2) -1 else 1) }
                    }
                }
                MotionEvent.ACTION_CANCEL -> hideDeleteZone()
            }
            true
        }
        // 门0 W1：闪断竞态窗口内 accessibility 会话可能已失效，addView 抛 BadToken/DeadObject
        // 不接异常会从 mainHandler runnable 冒出即主线程崩溃
        try {
            windowManager.addView(ball, params)
            ballView = ball
            wm = windowManager
        } catch (e: Exception) {
            android.util.Log.w("yunkai", "FloatingBall.show addView failed: ${e.message}")
        }
    }

    fun remove() {
        // 删除圈先于 wm 置空清理（拖拽中走总开关关闭的场景会漏掉它）
        deleteView?.let {
            try { (it.parent as? android.view.ViewGroup)?.removeView(it) } catch (e: Exception) {}
            try { wm?.removeView(it) } catch (e: Exception) {}
        }
        deleteView = null
        try { ballView?.let { wm?.removeView(it) } } catch (e: Exception) {}
        ballView = null
        wm = null
        docked = false
        dockSide = 0
        anim?.cancel()
        anim = null
        moved = false
    }

    // 屏底删除圈：暗圈 + ×，被球瞄准时转红色高亮；不可触摸（纯视觉靶）
    private class DeleteZoneView(context: Context) : View(context) {
        var highlighted = false
            set(value) {
                field = value
                invalidate()
            }

        private val dp = resources.displayMetrics.density
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(c: Canvas) {
            val s = width.toFloat()
            val cx = s / 2f
            val cy = s / 2f
            val r = s / 2f - 1f * dp
            if (highlighted) {
                fill.color = 0x59FF453A.toInt()
                ring.color = 0xFFFF453A.toInt()
            } else {
                fill.color = 0x59000000.toInt()
                ring.color = 0x80FFFFFF.toInt()
            }
            ring.strokeWidth = 1.4f * dp
            c.drawCircle(cx, cy, r, fill)
            c.drawCircle(cx, cy, r, ring)
            cross.color = if (highlighted) 0xFFFF453A.toInt() else 0xB3FFFFFF.toInt()
            cross.strokeWidth = 2f * dp
            val k = r * 0.32f
            c.drawLine(cx - k, cy - k, cx + k, cy + k, cross)
            c.drawLine(cx - k, cy + k, cx + k, cy - k, cross)
        }
    }
}
