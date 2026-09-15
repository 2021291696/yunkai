package com.zhuolin.yunkai.service.screen

import com.zhuolin.yunkai.YunkaiApp
import com.zhuolin.yunkai.service.tools.AgentTool
import com.zhuolin.yunkai.service.tools.BuiltinTools
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// propose_plan（M2a-T6）：把「一批写操作」整包交给用户批准，批准后在 UI 执行计划的间隙逐步执行。
// 工具只负责 提交 + 等终态：执行进度、敏感急停、外发二次确认全在 WritePlanExecutor 状态机与计划卡里，
// 工具消费同一单例的 state（非终态 = 计划卡在屏幕上等用户），终态才把结果回填给模型。
// 超时（用户长时间不理会）按「用户未确认」收敛：取消计划并返回，避免模型以为已终止而计划仍在执行。
class PlanTool(private val app: YunkaiApp) : AgentTool() {
    override val name = "propose_plan"
    override val description = "提交写操作计划等待用户批准后自动执行。参数 plan 为动作数组" +
        "（type: tap/swipe/input/back/home/finished；tap 带 x,y（用 read_screen 快照坐标）；" +
        "swipe 带 x1,y1,x2,y2,durMs；input 带 text（输入到用户批准后你指定的聚焦输入框，" +
        "若需先点输入框则在 plan 里前置一个 tap）；每项带 label 中文说明）。用户批准后逐步执行；" +
        "敏感页会自动急停等你确认。计划提交后不要重复提交，等待本工具结果。" +
        "计划提交时会自动回到云开展示计划卡，批准后自动打开目标应用再执行。"
    override val parametersJson =
        """{"type":"object","properties":{"summary":{"type":"string","description":"计划的一句话目的"},"target_pkg":{"type":"string","description":"计划将要操作的目标应用包名（从对话上下文判断）"},"plan":{"type":"array","items":{"type":"object"}}},"required":["summary","target_pkg","plan"]}"""

    override suspend fun execute(argsJson: String): String {
        val obj = parseObj(argsJson) ?: return BuiltinTools.err("参数不是合法 JSON 对象")
        val arr = obj["plan"] as? JsonArray ?: return BuiltinTools.err("缺少 plan 数组")
        if (arr.isEmpty()) return BuiltinTools.err("plan 为空：至少需要一个动作")

        val targetPkg = ((obj["target_pkg"] as? JsonPrimitive)?.content ?: "").trim()
        if (targetPkg.isEmpty()) return BuiltinTools.err("缺少 target_pkg：计划必须声明目标应用包名")

        // 逐项解析+校验：任一项非法即整体拒绝（不做「跳过坏项照跑」——计划是原子承诺）
        val dm = app.resources.displayMetrics
        val actions = ArrayList<WriteAction>(arr.size + 1)
        val labels = ArrayList<String>(arr.size + 1)
        // 计划第一步固定「打开目标应用」：agent 提交计划时目标 app 可能不在前台，
        // 不先切回来用户看不到执行、后序 tap 也会打偏。
        actions.add(WriteAction.OpenApp(targetPkg))
        labels.add("打开目标应用")
        for (i in arr.indices) {
            val item = arr[i] as? JsonObject ?: return BuiltinTools.err("plan 第 ${i + 1} 项不是对象")
            val action = WriteAction.fromObj(item)
                ?: return BuiltinTools.err("plan 第 ${i + 1} 项非法（type 或字段有误）")
            action.validate(dm.widthPixels, dm.heightPixels)?.let {
                return BuiltinTools.err("plan 第 ${i + 1} 项非法：$it")
            }
            actions.add(action)
            val label = ((item["label"] as? JsonPrimitive)?.content ?: "").trim()
            labels.add(label.ifEmpty { "步骤 ${i + 1}" })
        }

        val exec = app.writePlanExecutor
        val planId = "p" + System.currentTimeMillis()
        if (!exec.submit(planId, actions, labels, sensitiveHint = false)) {
            return BuiltinTools.err("已有写操作计划在进行中，请等它结束（或等用户取消）后再提交")
        }

        // 提交成功立刻把云开拉回前台：计划卡必须出现在用户眼前，
        // 否则用户停在目标 app 里看不到卡，只能等 180s 超时。
        runCatching {
            app.packageManager.getLaunchIntentForPackage(app.packageName)?.let { up ->
                up.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(up)
            }
        }

        // 挂起等终态：非终态期间计划卡在屏幕上，用户点「执行/继续/确认发送/取消」推进状态机
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        var last = exec.state.value
        while (true) {
            val s = exec.state.value
            if (s != null) last = s
            if (s is WritePlanExecutor.PlanState.Done || s is WritePlanExecutor.PlanState.Stopped) break
            if (System.currentTimeMillis() >= deadline) {
                // 超时未获批准：取消计划，"已终止"才不是空话（否则模型以为结束、计划仍在等批准）
                exec.cancel(planId)
                return STOPPED_TIMEOUT
            }
            delay(POLL_MS)
        }
        return when (val s = last) {
            is WritePlanExecutor.PlanState.Done -> "{\"plan_done\":true,\"executed\":${s.executed}}"
            is WritePlanExecutor.PlanState.Stopped -> "{\"plan_stopped\":true}"
            // 理论上不可达（循环只在 Done/Stopped/超时退出），兜底按「未走完」收敛
            else -> STOPPED_TIMEOUT
        }
    }

    private fun parseObj(raw: String): JsonObject? = try {
        Json.parseToJsonElement(raw) as? JsonObject
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val POLL_MS = 500L
        const val TIMEOUT_MS = 180_000L
        const val STOPPED_TIMEOUT = "{\"sensitive_paused\":true,\"hint\":\"用户未确认，计划已终止\"}"
    }
}
