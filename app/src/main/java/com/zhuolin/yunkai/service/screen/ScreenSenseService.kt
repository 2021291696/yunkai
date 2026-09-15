package com.zhuolin.yunkai.service.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.coroutines.resume

// 无障碍读屏服务：按需读「当前前台窗口」节点树（只读；不订阅事件流、不做任何注入）。
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
