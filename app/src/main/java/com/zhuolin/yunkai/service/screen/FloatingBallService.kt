package com.zhuolin.yunkai.service.screen

// 悬浮球（M2b-T8）：TYPE_ACCESSIBILITY_OVERLAY 由无障碍服务添加，免悬浮窗授权。
// 拖动贴边 + 单击回调 onBallTap；随屏幕感知总开关生灭（开关关闭即 remove，服务 onUnbind 也 remove）。
// M2b-T9b：单击默认动作 = 唤起闪问面板（FlashPanelLauncher.launch → 透明 FlashActivity，不依赖主界面前台）。
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.abs

@SuppressLint("ClickableViewAccessibility")
object FloatingBall {
    private var ballView: ImageView? = null
    private var wm: WindowManager? = null

    fun show(context: Context, onBallTap: () -> Unit) {
        if (ballView != null) return
        // TYPE_ACCESSIBILITY_OVERLAY 只能由无障碍服务上下文 addView（Activity/App 上下文会 BadTokenException），
        // 故实际宿主取服务实例；无障碍未授权（服务未连接）时无法显示，记日志后跳过，待授权后再开关一次即可。
        val host: Context = ScreenSenseService.instance ?: run {
            android.util.Log.w("yunkai", "FloatingBall.show: a11y 服务未连接，悬浮球不显示")
            return
        }
        val windowManager = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = windowManager
        val ball = ImageView(host).apply {
            setImageResource(android.R.drawable.ic_menu_myplaces)
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
            alpha = 0.55f
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        val size = (44 * host.resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 400 }
        // 拖动基准：按下时的原始指针坐标 + 球当时的位置（两者坐标系不同，不能混算）
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        ball.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downRawX).toInt()
                    val dy = (ev.rawY - downRawY).toInt()
                    if (moved || abs(dx) + abs(dy) > 12) {
                        moved = true
                        params.x = startX + dx
                        params.y = startY + dy
                        windowManager.updateViewLayout(ball, params)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) onBallTap()
            }
            true
        }
        windowManager.addView(ball, params)
        ballView = ball
    }

    fun remove() {
        try { ballView?.let { wm?.removeView(it) } } catch (e: Exception) {}
        ballView = null
        wm = null
    }
}
