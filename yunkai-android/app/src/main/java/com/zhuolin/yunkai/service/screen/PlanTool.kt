package com.zhuolin.yunkai.service.screen

import com.zhuolin.yunkai.YunkaiApp
import com.zhuolin.yunkai.service.tools.AgentTool
import com.zhuolin.yunkai.service.tools.BuiltinTools
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// propose_plan（M2a-T6；2026-10-02 确认分级改造）：把「一批写操作」整包提交后自动执行。
// 工具只负责 提交 + 等终态：执行进度、分档确认、敏感急停全在 WritePlanExecutor 状态机与计划卡里，
// 工具消费同一单例的 state（非终态 = 计划卡在屏幕上），终态才把结果回填给模型。
// 确认模式（设置页三档）决定打扰程度：完全访问=全自动不拉前台不打扰；AI 自审/事事过问=
// 预测会暂停（或执行中真撞上暂停）才把云开拉到前台让用户看到确认按钮。
// 超时（用户长时间不理会）按「用户未确认」收敛：取消计划并返回，避免模型以为已终止而计划仍在执行。
// 失败自愈闭环：某步重试仍失败 → 计划带原因收敛 Stopped → 结果回传模型 → 模型调整后重提
// （低危新计划免确认直接执行）；实例内计数防同一轮无限重提（每次 send 重建工具表=实例即本轮）。
class PlanTool(private val app: YunkaiApp) : AgentTool() {
    override val name = "propose_plan"
    override val description = "提交写操作计划并自动执行。参数 plan 为动作数组" +
        "（type: tap/swipe/input/back/home/finished；tap 带 x,y（用 read_screen 快照坐标）；" +
        "swipe 带 x1,y1,x2,y2,durMs；input 带 text（输入到当前聚焦的输入框，" +
        "若需先点输入框则在 plan 里前置一个 tap）；每项带 label 中文说明；" +
        "可选 confirm:true 标记该动作需要用户确认——只标真正危险的动作" +
        "（支付/密码/把内容发送给他人等），普通操作不要标）。" +
        "打扰程度由用户设置决定：完全访问=全部自动执行；AI 自审（默认）=你标记的动作与敏感操作暂停等确认；" +
        "事事过问=所有输入动作都暂停。敏感页始终自动急停（完全访问除外）。" +
        "计划提交后不要重复提交，等待本工具结果；某步彻底失败时计划中止并在结果里给出原因，" +
        "此时可调整计划重新提交（同一轮最多重提 2 次，超限会被拒绝）。"
    override val parametersJson =
        """{"type":"object","properties":{"summary":{"type":"string","description":"计划的一句话目的"},"target_pkg":{"type":"string","description":"计划将要操作的目标应用包名（从对话上下文判断）"},"plan":{"type":"array","items":{"type":"object","properties":{"confirm":{"type":"boolean","description":"标记该动作需用户确认（仅危险动作）"}}}}},"required":["summary","target_pkg","plan"]}"""

    // 本轮已提交计划数：ChatViewModel 每次 send/resume 重建工具表 → 本工具实例即一个用户轮，
    // 实例内计数 = 本轮内初提交 + 失败重提次数，防失败自愈死循环烧 token
    private var submits = 0

    override suspend fun execute(argsJson: String): String {
        val obj = parseObj(argsJson) ?: return BuiltinTools.err("参数不是合法 JSON 对象")
        val arr = obj["plan"] as? JsonArray ?: return BuiltinTools.err("缺少 plan 数组")
        if (arr.isEmpty()) return BuiltinTools.err("plan 为空：至少需要一个动作")

        val targetPkg = ((obj["target_pkg"] as? JsonPrimitive)?.content ?: "").trim()
        if (targetPkg.isEmpty()) return BuiltinTools.err("缺少 target_pkg：计划必须声明目标应用包名")
        // 安全审计（run-1 F-3）：summary 供计划卡向用户展示计划目的——确认绑定的是参数而非仅标签
        val summary = ((obj["summary"] as? JsonPrimitive)?.content ?: "").trim()

        submits += 1
        if (submits > MAX_PLANS_PER_TURN) {
            return BuiltinTools.err("同一轮提交计划已达上限（$MAX_PLANS_PER_TURN 次）。" +
                "请基于已有工具结果总结进展、直接回答用户；确需继续操作请让用户发起新指令")
        }

        // 确认模式（设置页三档；未匹配值回退 SMART，与 ConfigStore.sanitizeConfirmMode 同口径）
        val mode = when (app.configStore.getConfirmMode()) {
            com.zhuolin.yunkai.store.ConfigStore.CONFIRM_FULL -> ConfirmMode.FULL_AUTO
            com.zhuolin.yunkai.store.ConfigStore.CONFIRM_STRICT -> ConfirmMode.STRICT
            else -> ConfirmMode.SMART
        }

        // 逐项解析+校验：任一项非法即整体拒绝（不做「跳过坏项照跑」——计划是原子承诺）
        val dm = app.resources.displayMetrics
        val modelConfirm = extractConfirmFlags(arr)
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
        if (!exec.submit(planId, actions, labels, sensitiveHint = false, summary = summary,
                targetPkg = targetPkg, mode = mode, modelConfirm = modelConfirm)) {
            return BuiltinTools.err("已有写操作计划在进行中，请等它结束（或等用户取消）后再提交")
        }

        // 预测会暂停才把云开拉回前台：用户要看到确认按钮，否则停在目标 app 里看不到卡只能等超时。
        // 完全访问档永不暂停也就永不拉前台——这是「别频繁跳回云开界面」的主开关。
        if (predictPause(mode, actions, modelConfirm)) {
            bringToForeground()
        }

        // 挂起等终态：计划卡在屏幕上推进状态机；执行中真撞上暂停（如中途进敏感页）
        // 同样要把云开带到前台，否则用户看不到暂停原因只能干等超时
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        var last = exec.state.value
        var wasPaused = false
        while (true) {
            val s = exec.state.value
            if (s != null) last = s
            if (s is WritePlanExecutor.PlanState.Done || s is WritePlanExecutor.PlanState.Stopped) break
            val paused = s is WritePlanExecutor.PlanState.SensitivePaused ||
                s is WritePlanExecutor.PlanState.SendConfirmPaused
            if (paused && !wasPaused) bringToForeground()
            wasPaused = paused
            if (System.currentTimeMillis() >= deadline) {
                // 超时取消计划，"已终止"才不是空话（否则模型以为结束、计划仍在执行）。
                // 归因看当时卡在哪：Paused 态才是「等确认超时」；否则是执行本身超时
                //（完全访问档无确认点，归因错了会把模型自愈带偏——审查 W2 收口）
                exec.cancel(planId)
                val waiting = s is WritePlanExecutor.PlanState.SensitivePaused ||
                    s is WritePlanExecutor.PlanState.SendConfirmPaused
                return "{\"plan_stopped\":true,\"reason\":\"" +
                    (if (waiting) "等待用户确认超时，计划已取消" else "计划执行超时，已取消") + "\"}"
            }
            delay(POLL_MS)
        }
        return when (val s = last) {
            is WritePlanExecutor.PlanState.Done -> "{\"plan_done\":true,\"executed\":${s.executed}}"
            is WritePlanExecutor.PlanState.Stopped ->
                if (s.reason.isNotEmpty()) stoppedJson(s.executed, s.reason)
                else "{\"plan_stopped\":true,\"executed\":${s.executed}}"
            // 理论上不可达（循环只在 Done/Stopped/超时退出），兜底按「未走完」收敛
            else -> "{\"plan_stopped\":true,\"reason\":\"计划未正常收敛，已取消\"}"
        }
    }

    private fun bringToForeground() {
        runCatching {
            app.packageManager.getLaunchIntentForPackage(app.packageName)?.let { up ->
                up.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(up)
            }
        }.onFailure {
            // 门0 W-A4：BAL 限制（后台启动意图）或无 launch intent 时失败不再静默——
            // 暂停确认仍可经悬浮窗/通知推进，但要有痕迹可查
            android.util.Log.w("yunkai", "bringToForeground failed: ${it.message}（BAL/launch intent；确认可走悬浮窗推进）")
        }
    }

    // reason 来自动作标签（模型生成文本），可能含引号等字符——用 buildJsonObject 序列化保证转义安全
    private fun stoppedJson(executed: Int, reason: String): String = buildJsonObject {
        put("plan_stopped", true)
        put("executed", executed)
        put("reason", reason)
    }.toString()

    private fun parseObj(raw: String): JsonObject? = try {
        Json.parseToJsonElement(raw) as? JsonObject
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val POLL_MS = 500L
        const val TIMEOUT_MS = 180_000L
        // 单轮计划数上限 = 初次提交 + 2 次失败重提（用户拍板：自动重提上限 2 次）
        const val MAX_PLANS_PER_TURN = 3
    }
}

// 提交时静态预测是否会出现确认暂停（决定要不要把云开拉到前台给用户看确认钮）。
// 判定与执行时闸门同源 ConfirmTiers（单一事实源，防预测/执行漂移——审查 W3 收口）；
// 敏感页急停是执行期动态事件，由等待循环观察状态补拉，不在本预测内。
// 文件顶层纯函数：不依赖 YunkaiApp，ConfirmTiersTest 直测（审查 W5 收口）。
fun predictPause(mode: ConfirmMode, actions: List<WriteAction>, modelConfirm: Set<Int>): Boolean =
    actions.indices.any { i -> ConfirmTiers.requiresConfirm(mode, actions[i], flagged = i in modelConfirm) }

// 模型标记的需确认动作：plan 数组下标 → 执行器 actions 下标（计划头部固定多一个
// 「打开目标应用」，整体 +1——审查 W5 的偏移风险点，表驱动直测）。
// confirm 接受 JSON boolean true 与字符串 "true"（部分端点会把布尔降级成字符串下发）。
fun extractConfirmFlags(plan: JsonArray): Set<Int> = buildSet {
    for (i in plan.indices) {
        val item = plan[i] as? JsonObject ?: continue
        if ((item["confirm"] as? JsonPrimitive)?.content == "true") add(i + 1)
    }
}
