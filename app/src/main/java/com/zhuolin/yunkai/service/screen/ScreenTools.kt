package com.zhuolin.yunkai.service.screen

import com.zhuolin.yunkai.YunkaiApp
import com.zhuolin.yunkai.model.ChatMsg
import com.zhuolin.yunkai.model.ContentImage
import com.zhuolin.yunkai.model.ContentPart
import com.zhuolin.yunkai.service.LlmClient
import com.zhuolin.yunkai.service.tools.AgentTool
import com.zhuolin.yunkai.service.tools.BuiltinTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 屏幕感知四工具（豆包对齐 M1，设计简报 §二/§五）：
// list_apps 查已装应用 / open_app 打开应用 / read_screen 节点树读屏 / capture_screen 截图视觉转述。
// 调度规则写进工具描述，模型自主执行：读文字先节点树、看画面才截图、自绘白名单直走视觉。
// 门槛：总开关 cfg.screenSense 关闭时 ChatViewModel 不接线（工具表根本不暴露给模型）。

// 视觉转述提示词：客观描述，控制篇幅（转述结果回填工具结果，不进对话历史）
private const val DESCRIBE_PROMPT =
    "请用中文客观描述这张手机屏幕截图：1) 当前页面/应用是什么；2) 可见的主要文字要点；" +
    "3) 图片、视频或图表等视觉内容。500 字以内，只描述所见，不要推测与建议。"

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
        val cur = svc.readForeground()
            ?: return BuiltinTools.err("读不到当前屏幕：请确认目标应用在前台")
        val pkg = cur.first
        val nodes = cur.second
        if (ScreenBlacklist.isBlocked(pkg, app.configStore.getUserBlacklist())) {
            return BuiltinTools.err("应用 " + pkg + " 在隐私黑名单中，默认不读取其内容（如需放行请在设置中调整）")
        }
        if (ScreenRouteTable.isVisionRoute(pkg, app.configStore.getVisionLearned())) {
            return "{\"route\":\"vision\",\"pkg\":\"" + pkg + "\",\"hint\":\"该应用为自绘界面，节点树无有效文本，请改用 capture_screen\"}"
        }
        val textCount = nodes.count { it.text.isNotEmpty() }
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
        "适用：图片/照片/视频等视觉内容；微信/抖音等自绘应用页面。需先在云开设置→屏幕感知中授权屏幕录制。" +
        "隐私黑名单应用会被拒绝。"
    override val parametersJson = """{"type":"object","properties":{}}"""

    override suspend fun execute(argsJson: String): String {
        // 黑名单判定借无障碍服务的包名读取；无障碍未开时截屏链路同样不成立，提示一致
        val svc = ScreenSenseService.instance
            ?: return BuiltinTools.err("无障碍服务未开启：请在云开设置→屏幕感知中开启无障碍读屏")
        val pkg = svc.readForeground()?.first ?: ""
        if (ScreenBlacklist.isBlocked(pkg, app.configStore.getUserBlacklist())) {
            return BuiltinTools.err("应用 " + pkg + " 在隐私黑名单中，默认不截取其内容")
        }
        if (!ProjectionService.active) {
            return BuiltinTools.err("截屏未授权：请先在云开设置→屏幕感知中授权屏幕录制")
        }
        val b64 = try {
            withContext(Dispatchers.IO) { ProjectionService.captureBase64() }
        } catch (e: Exception) {
            return BuiltinTools.err(e.message ?: "截屏失败")
        }
        return try {
            val llm = LlmClient(app.configStore.load())
            val resp = llm.chatMessage(
                listOf(ChatMsg(role = "user", contentParts = listOf(
                    ContentPart(type = "text", text = DESCRIBE_PROMPT),
                    ContentPart(type = "image_url", imageUrl = ContentImage("data:image/jpeg;base64," + b64)),
                ))),
                null,
            )
            resp.content.trim().ifEmpty { BuiltinTools.err("视觉模型返回空内容，请重试") }
        } catch (e: Exception) {
            BuiltinTools.err("视觉转述失败: " + (e.message ?: "未知错误"))
        }
    }
}

fun createScreenTools(app: YunkaiApp): List<AgentTool> = listOf(
    ListAppsTool(app),
    OpenAppTool(app),
    ReadScreenTool(app),
    CaptureScreenTool(app),
)
