package com.zhuolin.yunkai.service.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import kotlin.coroutines.resume

// 无障碍读屏+写操作服务：按需读「当前前台窗口」节点树，M2a 起提供手势注入基元（不订阅事件流）。
// 授权：系统设置→无障碍→云开（设置页「屏幕感知」引导跳转）；用户在系统里关闭 = 实例置空，读屏即不可用。
// 隐私红线（设计简报 §五）：isPassword 节点永不取文本，只出 [密码框] 标记。
class ScreenSenseService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: ScreenSenseService? = null
            private set
        val ready: Boolean get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        android.util.Log.i("yunkai", "a11y onServiceConnected")
        instance = this
        // 悬浮球随服务存活（服务被系统回收球也随之消失，onUnbind 时 remove）
    }

    // 系统对同一 service 记录解绑后再绑走 onRebind（force-stop 后重授、无障碍列表翻转等场景），
    // 不会再走 onServiceConnected——漏了它 instance 永久为 null（门2 实测：「已开启」变「未开启」不恢复）
    override fun onRebind(intent: android.content.Intent?) {
        android.util.Log.i("yunkai", "a11y onRebind")
        instance = this
        super.onRebind(intent)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        android.util.Log.i("yunkai", "a11y onUnbind")
        instance = null
        FloatingBall.remove()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    // API 30+：无障碍自带截屏（免 MediaProjection/授权弹窗/前台服务）。
    // hardwareBuffer 必须 close，否则每次截屏泄漏一块 GraphicBuffer。
    suspend fun captureScreen(): Bitmap? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val bmp = android.graphics.Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer, screenshot.colorSpace
                        )?.copy(Bitmap.Config.ARGB_8888, false)
                        screenshot.hardwareBuffer.close()
                        cont.resume(bmp)
                    }

                    override fun onFailure(errorCode: Int) {
                        android.util.Log.w("yunkai", "takeScreenshot fail code=$errorCode")
                        cont.resume(null)
                    }
                },
            )
        }
    }

    // ── 手势基元（M2a）：全部 dispatchGesture 实现，坐标屏幕系 ──
    fun performTap(x: Float, y: Float): Boolean {
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val b = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 10L))
        return dispatchGesture(b.build(), null, null)
    }

    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durMs: Long): Boolean {
        val path = android.graphics.Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val b = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durMs))
        return dispatchGesture(b.build(), null, null)
    }

    // 输入：先聚焦目标框再 ACTION_SET_TEXT（不依赖 IME，规避输入法碎字）
    fun setText(x: Float, y: Float, text: String): Boolean {
        if (!performTap(x, y)) return false
        SystemClock.sleep(300)
        val root = rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var target: AccessibilityNodeInfo? = null
        var n = 0
        while (queue.isNotEmpty() && n < 600) {
            val cur = queue.removeFirst(); n++
            if (cur.isEditable && cur.isFocused) { target = cur; break }
            for (i in 0 until cur.childCount) cur.getChild(i)?.let { queue.add(it) }
        }
        val t = target ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return t.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // 无坐标输入：找当前 focused editable 注入文本（Input 动作执行用；聚焦由计划里的前置 tap 负责）
    fun setTextFocused(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var n = 0
        while (queue.isNotEmpty() && n < 600) {
            val cur = queue.removeFirst(); n++
            if (cur.isEditable && cur.isFocused) {
                val args = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                return cur.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            for (i in 0 until cur.childCount) cur.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    // 读当前前台窗口：返回 (包名, 节点快照)。无前台窗口/未授权返回 null。
    // rootInActiveWindow 在窗口切换动画/服务重绑等时机会瞬时返回 null（门2 ⑱ 实测），
    // 兜底取默认显示器上 focused/active 窗口的 root。
    fun readForeground(): Pair<String, List<ScreenNode>>? {
        val root = rootInActiveWindow
            ?: run {
                val w = windows.firstOrNull { it.isFocused }
                    ?: windows.firstOrNull { it.isActive }
                    ?: return null
                w.root ?: return null
            }
        val pkg = root.packageName?.toString() ?: ""
        val out = mutableListOf<ScreenNode>()
        dfs(root, 0, out)
        return pkg to out
    }

    // DFS 采集：节点预算 600 / 深度 24，防巨型页面拖垮工具调用。
    // 只收「有文本或可点击」的节点；文本上限 80 字防超长段落撑爆快照。
    private fun dfs(n: AccessibilityNodeInfo, depth: Int, out: MutableList<ScreenNode>) {
        if (out.size >= 600 || depth > 24) return
        val pwd = n.isPassword
        val txt = if (pwd) "" else {
            (n.text?.toString()?.trim()?.take(80))?.ifEmpty { null }
                ?: n.contentDescription?.toString()?.trim()?.take(80)?.ifEmpty { null }
                ?: ""
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        if (txt.isNotEmpty() || n.isClickable) {
            out.add(ScreenNode(
                text = txt,
                cls = n.className?.toString()?.substringAfterLast('.') ?: "",
                cx = r.centerX(),
                cy = r.centerY(),
                clickable = n.isClickable,
                isPassword = pwd,
            ))
        }
        for (i in 0 until n.childCount) {
            n.getChild(i)?.let { dfs(it, depth + 1, out) }
        }
    }
}
