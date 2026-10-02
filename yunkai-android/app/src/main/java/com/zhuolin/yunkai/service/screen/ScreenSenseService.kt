package com.zhuolin.yunkai.service.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import kotlin.coroutines.resume
import com.zhuolin.yunkai.ui.flash.FlashActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

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

    // 断开提示去抖：MIUI 对侧载应用会高频闪断重绑（真机实测 80s 内 3 次），
    // 5s 后仍未重绑才提示，避免闪断期间「已断开」toast 骚扰
    private val mainHandler = Handler(Looper.getMainLooper())
    private val disconnectNotice = Runnable {
        if (instance == null) {
            android.widget.Toast.makeText(
                this,
                "云开屏幕感知已断开，如需继续请在系统设置中重新开启",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        android.util.Log.i("yunkai", "a11y onServiceConnected (fgPkg=${lastForegroundPkg ?: "null"})")
        instance = this
        mainHandler.removeCallbacks(disconnectNotice)
        restoreBallIfEnabled()
    }

    // 系统对同一 service 记录解绑后再绑走 onRebind（force-stop 后重授、无障碍列表翻转等场景），
    // 不会再走 onServiceConnected——漏了它 instance 永久为 null（门2 实测：「已开启」变「未开启」不恢复）
    override fun onRebind(intent: android.content.Intent?) {
        android.util.Log.i("yunkai", "a11y onRebind (fgPkg=${lastForegroundPkg ?: "null"})")
        instance = this
        super.onRebind(intent)
        mainHandler.removeCallbacks(disconnectNotice)
        restoreBallIfEnabled()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        android.util.Log.i("yunkai", "a11y onUnbind")
        instance = null
        FloatingBall.remove()
        // M2c-T13：服务被系统回收/用户关闭时给一次明确回执，避免「读屏突然失灵」无解释
        //（去抖：见 disconnectNotice，闪断重绑场景不弹）
        mainHandler.postDelayed(disconnectNotice, 5000L)
        return super.onUnbind(intent)
    }

    // 球的显隐原本只挂在设置页开关动作上：服务闪断重绑、app 重启后都不补显
    //（真机 2026-09-25 实测当 bug）。(重)绑定时按配置补显；screenSense 关着则不加，与开关 off 语义一致。
    // 配置读在 IO，addView 必须回主线程（ViewRootImpl 要主线程 Looper，IO 直调即崩）
    private fun restoreBallIfEnabled() {
        GlobalScope.launch(Dispatchers.IO) {
            // 悬浮球长期开关（ballEnabled，2026-10-02）+ 单次隐藏（拖底删除圈 sessionHidden）：
            // 两者任一不满足都不补显；屏幕感知本身保持开启
            val on = com.zhuolin.yunkai.store.ConfigStore(applicationContext).getScreenSense() &&
                com.zhuolin.yunkai.store.ConfigStore(applicationContext).getBallEnabled() &&
                !com.zhuolin.yunkai.service.screen.FloatingBall.isSessionHidden
            if (on) {
                mainHandler.post {
                    android.util.Log.i("yunkai", "floating ball restore")
                    FloatingBall.show(this@ScreenSenseService) {
                        com.zhuolin.yunkai.ui.flash.FlashPanelLauncher.launch(applicationContext)
                    }
                }
            }
        }
    }

    // 面板遮蔽期间的「面板底下 app」前台包名：透明 Activity 全屏窗口会把底下 app 从
    // 无障碍窗口列表里剔除（模拟器 2026-09-25 窗口转储实测），read_screen/capture_screen
    // 的前台判定回退用这个记录。来源=TYPE_WINDOW_STATE_CHANGED 事件里最近的非自身包名。
    private var lastForegroundPkg: String? = null

    /** 面板在前台时的目标 app 包名（非面板态恒 null，走原 rootInActiveWindow 路径）。 */
    fun panelTargetPkg(): String? = if (FlashActivity.panelForeground) lastForegroundPkg else null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return
        // 只记 Activity 级窗口状态：输入法/系统 UI 的窗口事件也带非自身包名，
        // 会把「面板底下 app」的记录冲掉（模拟器实测 ADBKeyboard 事件污染导致黑名单被绕过）。
        // P12：判定收敛到 ScreenLogic.isActivityWindow——contains("Activity") 在 MIUI 上
        // 漏采 Launcher/MiuiSettings（真机根因），允许名单式修复
        val cls = event.className?.toString() ?: return
        if (isActivityWindow(pkg, cls)) {
            lastForegroundPkg = pkg
            // P12 诊断日志（2026-10-01 拍板：先加日志找根因，不改行为）：
            // 记录每个 Activity 级事件的采集情况，供排查「服务重启后正向读屏失效」
            android.util.Log.i("yunkai", "fgPkg 更新: $pkg (cls=$cls)")
        } else {
            android.util.Log.d("yunkai", "fgPkg 忽略非 Activity 事件: pkg=$pkg cls=$cls")
        }
    }

    override fun onInterrupt() {}

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
        val self = packageName
        val root: AccessibilityNodeInfo
        if (FlashActivity.panelForeground) {
            // 面板(FlashActivity)在前台时 rootInActiveWindow 是面板自己：「浮在别的 app 上问当前页面」
            // 要读的是面板底下那个 app——黑名单/视觉路线都判在它身上（模拟器 2026-09-25 实测被面板遮蔽）。
            // 只认 TYPE_APPLICATION 应用窗口：状态栏/通知面板/小部件等系统窗口 layer 更高会截胡。
            val allDump = windows.map {
                "T" + it.type + ":" + (it.root?.packageName?.toString() ?: "nullroot") +
                    ":L" + it.layer + if (it.isFocused) ":F" else ""
            }
            android.util.Log.i("yunkai", "readForeground panel: windows=[" + allDump.joinToString(", ") + "]")
            val appWindows = windows.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .filter { it.root?.packageName?.toString() != self }
                .toList()
            val chosen = appWindows.maxByOrNull { it.layer }
            android.util.Log.i(
                "yunkai",
                "readForeground panel: apps=" +
                    appWindows.map { (it.root?.packageName?.toString() ?: "?") + ":L" + it.layer } +
                    " chose=" + (chosen?.root?.packageName?.toString() ?: "none") +
                    " 回退记录=" + (lastForegroundPkg ?: "null"),
            )
            root = chosen?.root
                ?: rootInActiveWindow
                ?: return null
        } else {
            root = rootInActiveWindow ?: run {
                val w = windows.firstOrNull { it.isFocused }
                    ?: windows.firstOrNull { it.isActive }
                    ?: return null
                w.root ?: return null
            }
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
