package com.zhuolin.yunkai.service.screen

// 悬浮球（M2b-T8 → 2026-10-02 苹果风格两态改版）：
// 展开态 = 白底云朵轮廓球（点球开面板）；贴边态 = 月牙胶囊（窗体位移半个身位藏进屏外，
// 点一下展开回球，向外拖自然拽出成球）。松手近边缘即吸附变月牙；左右两缘。
// 拖到屏幕最底缘松手 = 单次关闭（球滑出屏底 + sessionHidden 内存标记，重绑/闪断不复活，
// 重启 app 或重开屏幕感知开关即回来）。TYPE_ACCESSIBILITY_OVERLAY 免悬浮窗授权。
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

@SuppressLint("ClickableViewAccessibility")
object FloatingBall {
    private var ballView: GlassBallView? = null
    private var wm: WindowManager? = null
    private var docked = false
    private var dockSide = 0   // -1 左缘 / +1 右缘
    private var anim: android.animation.ValueAnimator? = null
    private var sessionHidden = false   // 拖底消失标记：内存态，重启/重开屏幕感知开关即清
    private var downRaw = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    val isSessionHidden: Boolean get() = sessionHidden
    fun clearSessionHidden() { sessionHidden = false }

    fun show(context: Context, onBallTap: () -> Unit) {
        if (ballView != null) return
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

        fun dockX(side: Int) = if (side < 0) -size / 2 else screenW - size / 2
        fun expandX(side: Int) = if (side < 0) 0 else screenW - size

        fun safeUpdate() {
            try { windowManager.updateViewLayout(ball, params) } catch (e: Exception) {}
        }

        fun animateX(to: Int) {
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
            ball.docked = true
            animateX(dockX(side))
        }

        fun expand() {
            val side = if (dockSide < 0) 0 else 1
            docked = false
            ball.docked = false
            animateX(expandX(side))
        }

        fun dismissDownward() {
            // 球滑出屏底 + 淡出 → 单次隐藏收场
            ball.animate().alpha(0f).translationY(screenH.toFloat())
                .setDuration(250L)
                .withEndAction {
                    sessionHidden = true
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
                    anim?.cancel()
                    ball.alpha = 0.8f
                    ball.scaleX = 1f; ball.scaleY = 1f
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    val isUp = ev.action == MotionEvent.ACTION_UP
                    // UP 也走完整位置处理：input swipe 的最后一个 MOVE 常到不了路径终点
                    //（终点坐标在 UP 事件里），不处理会让判定用过期位置
                    val dx = (ev.rawX - downRaw).toInt()
                    val dy = (ev.rawY - downRawY).toInt()
                    if (moved || abs(dx) + abs(dy) > 12) {
                        if (docked) {
                            docked = false; ball.docked = false
                            startX = if (dockSide < 0) 0 else screenW - params.width
                            downRaw = ev.rawX
                        }
                        moved = true
                        params.x = (startX + dx).coerceIn(0, screenW - params.width)
                        params.y = (startY + dy).coerceIn(0, screenH - params.height)
                        safeUpdate()
                    }
                    if (isUp) {
                        ball.alpha = 1f
                        when {
                            // 拖到屏幕最底缘（球底沿触及屏底）松手 → 滑出消失（单次关闭）
                            moved && params.y + params.height >= screenH - 8 -> dismissDownward()
                            !moved && docked -> expand()
                            !moved -> onBallTap()
                            else -> dock(if (params.x + params.width / 2 < screenW / 2) -1 else 1)
                        }
                    }
                }
            }
            true
        }

        try {
            windowManager.addView(ball, params)
            ballView = ball
            wm = windowManager
        } catch (e: Exception) {
            android.util.Log.w("yunkai", "FloatingBall.show addView failed: ${e.message}")
        }
    }

    fun remove() {
        try { ballView?.let { wm?.removeView(it) } } catch (e: Exception) {}
        ballView = null
        wm = null
        docked = false
        dockSide = 0
        anim?.cancel()
        anim = null
        moved = false
    }
}
