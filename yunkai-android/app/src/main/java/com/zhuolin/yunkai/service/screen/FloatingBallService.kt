package com.zhuolin.yunkai.service.screen

// 悬浮球（M2b-T8 → 2026-10-02 苹果风格两态改版）：
// 展开态 = 白底云朵轮廓球（点球开面板）；贴边态 = 月牙胶囊（窗体位移半个身位藏进屏外，
// 点一下展开回球，向外拖自然拽出成球）。松手近边缘即吸附变月牙；左右两缘。
// 拖入「底部区域」（屏底 12%，=光带范围）松手 = 单次关闭（球雾化 + sessionHidden 内存标记，重绑/闪断不复活，
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
    private var cueFired = false   // 临界区暗示每轮手势只触发一次
    private var pointerId = -1     // 单指跟踪：第二指/换指事件不进状态机
    // 球窗父帧顶的屏幕绝对 y（=getLocationOnScreen.y - params.y，DOWN 时懒测一次）。
    // 松手判定用 params.y + 偏移换算绝对坐标：updateViewLayout 后立刻 getLocationOnScreen
    // 读到的是上一次 traversal 的帧（滞后 1-2 个触控采样），贴底边界会误判
    private var parentTop = Int.MIN_VALUE

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
            // 云散雾化（2026-10-07 定案：云散 · 轻快利落）：特效窗先同步落窗（首帧=球复刻，无闪烁），
            // 球窗随即撤下换场；sessionHidden 即刻生效，语义不变（重绑/闪断不复活，重启/重开开关恢复）
            val fxX = params.x
            val fxY = params.y
            val fxSize = params.width
            sessionHidden = true
            BottomGlow.release(true)   // 光带随雾化慢收
            CloudDissolve.show(host, fxX, fxY, fxSize)
            remove(fromDismiss = true)
        }

        ball.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    pointerId = ev.getPointerId(ev.actionIndex)
                    downRaw = ev.rawX; downRawY = ev.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    cueFired = false
                    anim?.cancel()
                    ball.animate().cancel()   // cue 呼吸动画与手写 alpha 单一持有者：先取消再写
                    if (parentTop == Int.MIN_VALUE) {
                        val loc = IntArray(2).also { ball.getLocationOnScreen(it) }
                        parentTop = loc[1] - params.y
                    }
                    ball.alpha = 0.8f
                    ball.scaleX = 1f; ball.scaleY = 1f
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // 跟踪指抬起（另一指仍按着）→ 手势终止但不算拖拽，光带收场
                    if (ev.getPointerId(ev.actionIndex) == pointerId) {
                        moved = false
                        ball.animate().cancel()
                        ball.alpha = 1f
                        BottomGlow.release(false)
                    }
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    val isUp = ev.action == MotionEvent.ACTION_UP
                    // 指针隔离：只认 DOWN 时那根指；换指后的 UP（raw 坐标已换人）不进状态机，
                    // 否则 dx=新指坐标-旧指按下点 → 球瞬移误入底部区误消失
                    val idx = ev.findPointerIndex(pointerId)
                    if (idx < 0) return@setOnTouchListener true
                    val rawX = ev.getRawX(idx); val rawY = ev.getRawY(idx)
                    // UP 也走完整位置处理：input swipe 的最后一个 MOVE 常到不了路径终点
                    //（终点坐标在 UP 事件里），不处理会让判定用过期位置
                    var dx = (rawX - downRaw).toInt()
                    var dy = (rawY - downRawY).toInt()
                    if (moved || abs(dx) + abs(dy) > 12) {
                        if (docked) {
                            // 脱月牙：位移基准整体切到当前位置（x/y/downRaw/downRawY 同步重置，
                            // 本事件 dx/dy 归零）——旧实现保留旧 dx 导致球先弹向旧目标再回跳
                            docked = false; ball.docked = false
                            startX = params.x
                            startY = params.y
                            downRaw = rawX
                            downRawY = rawY
                            dx = 0; dy = 0
                        }
                        moved = true
                        params.x = (startX + dx).coerceIn(0, screenW - params.width)
                        params.y = (startY + dy).coerceIn(0, screenH - params.height)
                        safeUpdate()
                        // 球底沿绝对 y = 父帧顶偏移 + 父帧相对位置：不读 getLocationOnScreen
                        //（updateViewLayout 后它滞后于本次写入，边界判定会用到上一帧位置）
                        val bottomAbs = parentTop + params.y + params.height
                        // 底缘光带（瞬态窗，全宽柔光带定案）：拖拽即亮、球越近越亮，松手收场——
                        // 回答「怎么知道该放底部」：底部自身要有存在感，但不画常驻目标物
                        BottomGlow.show(host)
                        BottomGlow.setLevel(bottomAbs / screenH.toFloat())
                        // 临界区暗示（一次性）：球底沿进入底部区域（=光带范围）→ 球身呼吸一次 + 轻震动，
                        // 语义=「现在松手就会关」；不画任何目标物（用户拍板口径）
                        if (!cueFired && bottomAbs >= screenH - (screenH * BottomGlow.ZONE_FRACTION).toInt()) {
                            cueFired = true
                            ball.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                            ball.animate().alpha(0.65f).setDuration(140L).withEndAction {
                                if (ballView === ball) ball.animate().alpha(1f).setDuration(160L).start()
                            }.start()
                        }
                    }
                    if (isUp) {
                        ball.animate().cancel()
                        ball.alpha = 1f
                        val bottomAbs = parentTop + params.y + params.height
                        when {
                            // 拖到「底部区域」内松手 → 消失（单次关闭）。判定界=光带范围=临界区，
                            // 三者一条底界（用户拍板：特效亮起的地方就得能关）；坐标用父帧偏移换算的
                            // 绝对值（params.y 是父帧相对值，直接比 screenH 会差出状态栏高度）
                            moved && bottomAbs >= screenH - (screenH * BottomGlow.ZONE_FRACTION).toInt() -> dismissDownward()
                            !moved && docked -> expand()
                            !moved -> onBallTap()
                            else -> { BottomGlow.release(false); dock(if (params.x + params.width / 2 < screenW / 2) -1 else 1) }
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    // 系统打断手势（来电等）：复位状态，光带快收
                    ball.animate().cancel()
                    ball.alpha = 1f
                    cueFired = false
                    BottomGlow.release(false)
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

    fun remove(fromDismiss: Boolean = false) {
        try { ballView?.let { wm?.removeView(it) } } catch (e: Exception) {}
        ballView = null
        wm = null
        docked = false
        dockSide = 0
        anim?.cancel()
        anim = null
        moved = false
        parentTop = Int.MIN_VALUE   // 重绑/旋转后父帧顶可能变化，下次 DOWN 重新懒测
        if (!fromDismiss) BottomGlow.remove()   // unbind/开关路径兜底拆光带；消失路径光带由 release 慢收
    }
}
