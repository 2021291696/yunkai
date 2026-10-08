package com.zhuolin.yunkai.service.screen

import com.zhuolin.yunkai.YunkaiApp
import com.zhuolin.yunkai.model.ChatMsg
import com.zhuolin.yunkai.model.ContentImage
import com.zhuolin.yunkai.model.ContentPart
import com.zhuolin.yunkai.service.LlmClient
import com.zhuolin.yunkai.service.tools.AgentTool
import com.zhuolin.yunkai.service.tools.BuiltinTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// 屏幕感知四工具（豆包对齐 M1，设计简报 §二/§五）：
// list_apps 查已装应用 / open_app 打开应用 / read_screen 节点树读屏 / capture_screen 截图视觉转述。
// 调度规则写进工具描述，模型自主执行：读文字先节点树、看画面才截图、自绘白名单直走视觉。
// 门槛：总开关 cfg.screenSense 关闭时 ChatViewModel 不接线（工具表根本不暴露给模型）。

// 视觉接地提示词（2026-10-08）：不再只要散文——要求输出可点击元素清单（缩放图坐标 JSON），
// 工具侧换算物理坐标后供 agent 的 tap 直接使用。$SW/$SH=缩放图尺寸，$PW/$PH=物理尺寸（供参考）。
private const val ELEMENTS_PROMPT =
    "这是手机屏幕截图。原图 \$PW x \$PH 物理像素，你看到的图像已缩放为 \$SW x \$SH——" +
    "下面要求的所有坐标一律用【缩放图像素】（相对你看到的这张图）。\n" +
    "任务：列出当前屏幕上所有可点击/可交互的元素（按钮、图标、列表项、输入框、开关、底部 tab 等），" +
    "输出一个 JSON 数组（不要包 markdown 代码块），每项格式：\n" +
    "{\"type\":\"元素类型(图标/按钮/列表项/输入框/tab/开关)\",\"text\":\"元素上可见的文字(无则留空)\",\"x\":中心x,\"y\":中心y,\"w\":宽,\"h\":高}\n" +
    "x,y 为元素中心的缩放图坐标，w,h 为元素尺寸。元素要全（含右上角搜索、底部 tab 这类小图标），最多 25 个，" +
    "按从上到下排序。数组前用一句话概述当前页面。只输出概述和 JSON，不要其他解释。"

// 长按粘贴菜单定位提示词（自绘输入框降级末级）
private const val PASTE_MENU_PROMPT =
    "这是手机屏幕截图（已缩放为 \$SW x \$SH）。刚才长按了一个输入框，屏幕上应该弹出了操作菜单" +
    "（通常含「粘贴」）。请输出弹出菜单里所有可点击项的 JSON 数组，每项：" +
    "{\"text\":\"菜单项文字\",\"x\":中心x,\"y\":中心y,\"w\":宽,\"h\":高}（坐标用缩放图像素）。" +
    "「粘贴」项必须在列表里。如果没有弹出任何菜单，只输出 []。只输出 JSON。"

class ListAppsTool(private val app: YunkaiApp) : AgentTool() {
    override val name = "list_apps"
    override val description = "列出手机上已安装、可打开的应用（名称+包名）。打开应用前可先查询确认包名。"
    override val parametersJson = """{"type":"object","properties":{}}"""

    override suspend fun execute(argsJson: String): String = withContext(Dispatchers.IO) {
        val pm = app.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val acts = pm.queryIntentActivities(intent, 0)
        if (acts.isEmpty()) return@withContext BuiltinTools.err("未查询到可打开的应用")
        val userBl = app.configStore.getUserBlacklist()
        val rows = acts.mapNotNull { ai ->
            val label = ai.loadLabel(pm)?.toString() ?: ""
            val pkg = ai.activityInfo?.packageName ?: ""
            if (label.isEmpty() || pkg.isEmpty()) null else label to pkg
        }.distinctBy { it.second }.sortedBy { it.first }
        val sb = StringBuilder("已安装可打开应用 ").append(rows.size).append(" 个：\n")
        var i = 0
        for ((label, pkg) in rows) {
            i++
            if (i > 200) {
                sb.append("…（其余略，可让用户说应用名称再确认）")
                break
            }
            sb.append("- ").append(label).append(" | ").append(pkg)
            if (ScreenBlacklist.isBlocked(pkg, userBl)) sb.append(" [隐私黑名单]")
            sb.append('\n')
        }
        sb.toString().trimEnd()
    }
}

class OpenAppTool(private val app: YunkaiApp) : AgentTool() {
    override val name = "open_app"
    override val description = "打开手机上的某个应用。参数 pkg 填应用包名（可先 list_apps 查询）。" +
        "打开成功后应用到前台，随后的 read_screen/capture_screen 读到的就是它的页面。"
    override val parametersJson =
        """{"type":"object","properties":{"pkg":{"type":"string","description":"应用包名，如 com.tencent.mm"}},"required":["pkg"]}"""

    override suspend fun execute(argsJson: String): String = withContext(Dispatchers.IO) {
        val arg = parseOpenAppArg(argsJson)
        if (arg.isEmpty()) return@withContext BuiltinTools.err("缺少 pkg 参数")
        val pm = app.packageManager
        val launch = pm.getLaunchIntentForPackage(arg)
            ?: return@withContext BuiltinTools.err("未找到可打开的应用: " + arg + "（可先 list_apps 查包名）")
        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return@withContext try {
            app.startActivity(launch)
            // 给目标应用一点起前台的时间，下一轮 read_screen 才能读到它
            kotlinx.coroutines.delay(1200)
            "{\"opened\":\"" + arg + "\",\"note\":\"已打开，请用 read_screen 或 capture_screen 读取其当前页面\"}"
        } catch (e: Exception) {
            BuiltinTools.err("打开失败: " + (e.message ?: "未知错误"))
        }
    }
}

class ReadScreenTool(private val app: YunkaiApp) : AgentTool() {
    override val name = "read_screen"
    override val description = "读取手机当前前台页面的控件文本清单（文本+坐标+是否可点击）。" +
        "规则：先 open_app 打开目标应用再调用，读到的是此刻前台页面；坐标可用于后续点击类操作。" +
        "微信/抖音等自绘应用会返回 route=vision 提示，此时改用 capture_screen；" +
        "返回节点过少的应用会被自动记入视觉路线，下次直接走视觉。"
    override val parametersJson = """{"type":"object","properties":{}}"""

    override suspend fun execute(argsJson: String): String {
        val svc = ScreenSenseService.instance
            ?: return BuiltinTools.err("无障碍服务未开启：请在云开设置→屏幕感知中开启无障碍读屏")
        // DFS 逐节点 binder IPC，600 节点预算在巨型页面耗时可观——必须离开主线程（门0 I1）
        val cur = withContext(Dispatchers.IO) { svc.readForeground() }
        var pkg = cur?.first ?: ""
        var nodes = cur?.second
        // 面板遮蔽修正（模拟器 2026-09-25 实测）：透明面板 Activity 会把底下 app 挤出
        // 无障碍窗口列表，此时前台判定回退到最近一次非自身窗口状态事件；节点树拿不到
        // 就走视觉路线（capture_screen 截的是真实屏幕，不受面板遮蔽影响）
        if (com.zhuolin.yunkai.ui.flash.FlashActivity.panelForeground &&
            (pkg.isEmpty() || pkg == app.packageName)
        ) {
            pkg = svc.panelTargetPkg() ?: ""
            nodes = null
        }
        // 安全审计（run-1 NV-2）fail-closed：包名读出为空 = 前台身份不可判定 = 拒绝
        // （isBlocked("") 恒为 false，不拦空串会让黑名单门失效）
        if (pkg.isEmpty()) return BuiltinTools.err("读不到当前前台应用，拒绝读取（隐私保护）")
        if (ScreenBlacklist.isBlocked(pkg, app.configStore.getUserBlacklist())) {
            return BuiltinTools.err("应用 " + pkg + " 在隐私黑名单中，默认不读取其内容（如需放行请在设置中调整）")
        }
        if (ScreenRouteTable.isVisionRoute(pkg, app.configStore.getVisionLearned())) {
            return "{\"route\":\"vision\",\"pkg\":\"" + pkg + "\",\"hint\":\"该应用为自绘界面，节点树无有效文本，请改用 capture_screen\"}"
        }
        val textCount = nodes?.count { it.text.isNotEmpty() } ?: 0
        if (nodes == null) {
            return "{\"route\":\"vision\",\"pkg\":\"" + pkg + "\",\"hint\":\"当前从面板发起读取，节点树不可得，请改用 capture_screen\"}"
        }
        if (textCount < ScreenFormat.MIN_TEXT_NODES) {
            app.configStore.addVisionLearnedPkg(pkg)
            return "{\"route\":\"vision\",\"pkg\":\"" + pkg + "\",\"hint\":\"节点树仅 " + textCount +
                " 个文本节点，判定为自绘界面并已记入视觉路线；请改用 capture_screen\"}"
        }
        return ScreenFormat.format(pkg, nodes)
    }
}

class CaptureScreenTool(private val app: YunkaiApp) : AgentTool() {
    override val name = "capture_screen"
    override val description = "截取当前手机屏幕并让视觉模型转述内容（每次消耗视觉 token，非必要时优先 read_screen）。" +
        "适用：图片/照片/视频等视觉内容；微信/抖音等自绘应用页面。需先在云开设置→屏幕感知中开启无障碍读屏。" +
        "隐私黑名单应用会被拒绝。"
    override val parametersJson = """{"type":"object","properties":{}}"""

    // 前台包名判定：面板(FlashActivity)在前台时透明面板窗口会把底下 app 挤出
    // 无障碍窗口列表，回退到最近一次非自身窗口状态事件记录的包名（面板遮蔽修正）
    private suspend fun foregroundPkg(svc: ScreenSenseService, app: YunkaiApp): String {
        val p = withContext(Dispatchers.IO) { svc.readForeground() }?.first ?: ""
        if (com.zhuolin.yunkai.ui.flash.FlashActivity.panelForeground &&
            (p.isEmpty() || p == app.packageName)
        ) {
            return svc.panelTargetPkg() ?: ""
        }
        return p
    }

    override suspend fun execute(argsJson: String): String {
        val svc = ScreenSenseService.instance
            ?: return BuiltinTools.err("无障碍服务未开启：请在云开设置→屏幕感知中开启无障碍读屏")
        // 黑名单判定 fail-closed（门0 I3）：读不到前台包名=不知道会截到什么=拒绝
        // 安全审计（run-1 NV-2）：空串包名同样视为「读不到」，与 null 同款拒绝
        val pkg = foregroundPkg(svc, app)
        if (pkg.isEmpty()) return BuiltinTools.err("读不到当前前台应用，拒绝截屏（隐私保护）")
        if (ScreenBlacklist.isBlocked(pkg, app.configStore.getUserBlacklist())) {
            return BuiltinTools.err("应用 " + pkg + " 在隐私黑名单中，默认不截取其内容")
        }
        val bmp = withContext(Dispatchers.IO) { svc.captureScreen() }
            ?: return BuiltinTools.err("截屏失败：设备或服务暂不支持（需 Android 11+ 且无障碍已开启）")
        val physW = bmp.width
        val physH = bmp.height
        val enc = ScreenCapture.encode(bmp)
        bmp.recycle()
        // TOCTOU 收口（门0 I3）：截的是 t2 帧而包名是 t1 快照——截完复读前台，
        // 切换过（或进了黑名单 app）即丢弃，绝不把黑名单画面回传外发
        val pkgAfter = foregroundPkg(svc, app)
        if (pkgAfter != pkg || ScreenBlacklist.isBlocked(pkgAfter, app.configStore.getUserBlacklist())) {
            return BuiltinTools.err("截屏期间前台应用发生切换，已丢弃本次截图，请重试")
        }
        return try {
            val llm = LlmClient(app.configStore.load())
            // 视觉接地（2026-10-08）：不再只要散文描述——告诉视觉模型缩放比，要它输出
            // 可点击元素清单（缩放图坐标），工具侧换算成物理坐标附在结果里，
            // agent 的 tap 直接抄清单坐标（旧版散文转述导致坐标全靠猜、tap 落空）
            val prompt = ELEMENTS_PROMPT
                .replace("\$SW", enc.scaledW.toString())
                .replace("\$SH", enc.scaledH.toString())
                .replace("\$PW", physW.toString())
                .replace("\$PH", physH.toString())
            val resp = llm.chatMessage(
                listOf(ChatMsg(role = "user", contentParts = listOf(
                    ContentPart(type = "text", text = prompt),
                    ContentPart(type = "image_url", imageUrl = ContentImage("data:image/jpeg;base64," + enc.b64)),
                ))),
                null,
            )
            val raw = resp.content.trim().ifEmpty { return BuiltinTools.err("视觉模型返回空内容，请重试") }
            android.util.Log.i("yunkai", "grounding raw(200): " + raw.take(200))
            val elements = extractElements(raw, physW.toFloat() / enc.scaledW, physH.toFloat() / enc.scaledH)
            if (elements != null) android.util.Log.i("yunkai", "grounded " + elements.items.size + " elems: " +
                elements.items.joinToString { it.type + "@" + it.x + "," + it.y })
            if (elements == null) raw   // 视觉模型没按 JSON 输出：退回散文转述（降级可用）
            else buildString {
                append("页面：").append(elements.pageSummary).append('\n')
                append("可点击元素（坐标已换算为屏幕物理像素，propose_plan 的 tap 直接使用）：\n")
                for (e in elements.items) {
                    append("- [${e.type}] ${e.text} → tap(${e.x}, ${e.y})\n")
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            BuiltinTools.err("视觉转述失败: " + (e.message ?: "未知错误"))
        }
    }
}

// 视觉接地解析：从视觉模型输出里抠 JSON 元素数组，坐标按 缩放图→物理 比例换算
//（sx = 物理宽/缩放宽，sy = 物理高/缩放高；元素给中心点 x+w/2, y+h/2）。
// 返回 null = 没抠到合法数组（调用方退回散文转述）。
private data class GroundedElement(val type: String, val text: String, val x: Int, val y: Int)
private data class GroundedPage(val pageSummary: String, val items: List<GroundedElement>)

private fun extractElements(raw: String, sx: Float, sy: Float): GroundedPage? {
    val arrStart = raw.indexOf('[')
    if (arrStart < 0) return null
    val arrEnd = raw.lastIndexOf(']')
    if (arrEnd <= arrStart) return null
    val arr = try {
        kotlinx.serialization.json.Json.parseToJsonElement(raw.substring(arrStart, arrEnd + 1))
            as? kotlinx.serialization.json.JsonArray ?: return null
    } catch (_: Exception) {
        return null
    }
    fun prim(v: kotlinx.serialization.json.JsonElement?): String? =
        (v as? kotlinx.serialization.json.JsonPrimitive)?.content
    fun num(v: kotlinx.serialization.json.JsonElement?): Double? =
        (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
    val items = arr.mapNotNull { el ->
        val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        val cx = num(o["x"]) ?: return@mapNotNull null
        val cy = num(o["y"]) ?: return@mapNotNull null
        val w = num(o["w"]) ?: 0.0
        val h = num(o["h"]) ?: 0.0
        GroundedElement(
            type = prim(o["type"]) ?: "元素",
            text = (prim(o["text"]) ?: "").take(40),
            x = ((cx + w / 2) * sx).toInt().coerceIn(0, 9999),
            y = ((cy + h / 2) * sy).toInt().coerceIn(0, 9999),
        )
    }
    if (items.isEmpty()) return null
    return GroundedPage(raw.substringBefore('[').trim().take(150), items)
}

// 自绘输入框降级末级（2026-10-08 微信搜索页实测：a11y 树只有 1 个节点，SET_TEXT/PASTE 无处可施）：
// 长按聚焦点呼出系统粘贴菜单 → 视觉定位「粘贴」项 → 点击。x,y = 计划里前置 tap 的坐标
//（Input 前总有聚焦 tap，服务侧 lastTap 已记录）。剪贴板由调用方预置。
internal suspend fun ScreenSenseService.longPressPasteInput(app: YunkaiApp, text: String): Boolean {
    if (lastTapX == Int.MIN_VALUE) return false
    runCatching {
        val cm = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("yk", text))
    }.onFailure { android.util.Log.w("yunkai", "clipboard set failed: ${it.message}") }
    if (!performLongPress(lastTapX.toFloat(), lastTapY.toFloat())) return false
    delay(900)   // 等粘贴菜单弹出
    val bmp = captureScreen() ?: return false
    val physW = bmp.width; val physH = bmp.height
    val enc = ScreenCapture.encode(bmp)
    bmp.recycle()
    val prompt = PASTE_MENU_PROMPT
        .replace("\$SW", enc.scaledW.toString())
        .replace("\$SH", enc.scaledH.toString())
    return try {
        val llm = LlmClient(app.configStore.load())
        val resp = llm.chatMessage(
            listOf(ChatMsg(role = "user", contentParts = listOf(
                ContentPart(type = "text", text = prompt),
                ContentPart(type = "image_url", imageUrl = ContentImage("data:image/jpeg;base64," + enc.b64)),
            ))),
            null,
        )
        val els = extractElements(resp.content.trim(), physW.toFloat() / enc.scaledW, physH.toFloat() / enc.scaledH)
            ?: return false
        val paste = els.items.firstOrNull { it.text.contains("粘贴") || it.text.contains("Paste", true) }
            ?: return false
        android.util.Log.i("yunkai", "paste menu found at ${paste.x},${paste.y}")
        performTap(paste.x.toFloat(), paste.y.toFloat())
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        android.util.Log.w("yunkai", "longPressPaste failed: ${e.message}")
        false
    }
}

fun createScreenTools(app: YunkaiApp): List<AgentTool> = listOf(
    ListAppsTool(app),
    OpenAppTool(app),
    ReadScreenTool(app),
    CaptureScreenTool(app),
    PlanTool(app),
)
