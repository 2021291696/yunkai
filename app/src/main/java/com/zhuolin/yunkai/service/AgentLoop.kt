package com.zhuolin.yunkai.service

import com.zhuolin.yunkai.model.AgentSkill
import com.zhuolin.yunkai.model.AppConfig
import com.zhuolin.yunkai.model.ChatMsg
import com.zhuolin.yunkai.model.JsonSchema
import com.zhuolin.yunkai.model.ToolCall
import com.zhuolin.yunkai.model.ToolDef
import com.zhuolin.yunkai.service.tools.AgentTool
import com.zhuolin.yunkai.service.tools.BuiltinTools
import com.zhuolin.yunkai.store.SkillSource
import kotlinx.serialization.json.Json
import android.util.Log

// AgentLoop 引擎：LLM function-calling 主循环。
// 职责：组装 system prompt（桌面 agent 人格 + 技能摘要块）与工具表，
// 驱动「模型 → 工具 → 模型」循环；工具失败以 '{"error":...}' 字符串回传给模型自行调整，
// 绝不让循环崩溃；步数到 MAX_STEPS 后强制一次无 tools 收尾作答。
// 每一步经 onEvent 上报 tool_start / tool_done / answer / limit，UI 层据此展示进度。
// 双模型分工：对话与工具调度用 cfg.model（主模型）；本轮 use_skill 成功拿到说明书后，
// 余下 LLM 调用切 cfg.longModel（长文模型，无思考、可出完整 eli5 页），选择收敛在 pickModel。
// 可测性：run 末位可选参 fakeChat 注入假 LLM（生产不传，走真 LlmClient.chatMessage）。
// 取消：run 末位可选参 isCancelled 轮询取消旗标，命中即抛 Error(CANCELLED_MSG)，
// 由调用方（Chat 页）静默吞掉；引擎层中断保证后台不再烧 token / 跑工具副作用。
// 【M2 待办】skill description 注入防御：技能 description 来自用户/导入内容，直接拼进 system prompt
//   可能夹带 prompt injection（「忽略以上指令」类）；上线前需转义/长度截断/敏感内容过滤。
// 【M2 待办】多工具并发与 tool 结果预算截断：同一轮 tool_calls 目前串行执行；且 tool 结果全文回填
//   可能撑爆上下文，需按预算截断/摘要（单条上限 + 本轮总预算）。
// 语义唯一依据：yunkai-harmony/entry/src/main/ets/service/AgentLoop.ets（AGENT_SYSTEM 文案逐字复制）

// 循环事件：tool_start/tool_done 携带工具名与「参数 / 结果前80字」摘要；answer/limit 无工具名
data class LoopEvent(val kind: String, val toolName: String = "", val detail: String = "")

// 循环结果：最终回答文本 + 实际步数。
// 输出形态（画布/气泡）不在引擎层判定：Chat 消费方用 HtmlGuard.sanitize+ReplyKind.detect
// 做唯一口径判定（防散文夹 <html 子串被 sanitize 的 includes 语义误判），引擎只给纯文本。
data class LoopResult(val answer: String, val steps: Int)

object AgentLoop {
    // 步数上限：防模型无限调用工具打转；到顶后强制一次无 tools 收尾
    const val MAX_STEPS: Int = 10

    // 取消专用错误消息：调用方按此静默吞（引擎层中断，结果直接丢弃）
    const val CANCELLED_MSG: String = "AGENT_LOOP_CANCELLED"

    private val json = Json { ignoreUnknownKeys = true }

    // system prompt 常量：桌面 agent 人格 + 无合适 skill 直接回答 + 工具守则（等工具结果再答）
    private const val AGENT_SYSTEM: String =
        "你是运行在用户桌面上的智能助手，擅长把复杂概念讲到普通人能听懂，回答用中文、简洁友好。\n" +
        "工作守则：\n" +
        "1. 涉及时效性信息（今天/最新/最近/新闻/热点等）、外部事实或你不确定的内容时，必须先调用 web_search 工具搜索再回答；搜索工具已配置且可用，不要声称无法搜索或未配置，拿到结果前不要编造。\n" +
        "2. 调用工具后必须等待工具结果（tool 消息）返回，再决定继续调用或给出最终回答。\n" +
        "3. 没有合适的工具或技能能帮上忙时，直接凭自身知识回答，不要硬凑工具调用。\n" +
        "4. 技能（skill）是写好的详细操作说明书；一旦调用 use_skill 拿到说明书，必须严格照它执行。"

    private fun checkCancel(isCancelled: (() -> Boolean)?) {
        if (isCancelled != null && isCancelled()) {
            throw Exception(CANCELLED_MSG)
        }
    }

    // 核心循环入口；fakeChat 仅测试注入，生产路径必须不传；isCancelled 为取消轮询旗标（末位可选参）
    suspend fun run(
        cfg: AppConfig,
        skillRepo: SkillSource,
        history: List<ChatMsg>,
        question: String,
        forcedSkill: AgentSkill?,
        onEvent: (LoopEvent) -> Unit,
        fakeChat: (suspend (List<ChatMsg>, List<ToolDef>?, model: String) -> OpenAiMessage)? = null,
        isCancelled: (() -> Boolean)? = null,
    ): LoopResult {
        val tools: List<AgentTool> = BuiltinTools.createAll(cfg, forcedSkill, skillRepo)
        val toolDefs: MutableList<ToolDef> = mutableListOf()
        for (t in tools) {
            toolDefs.add(ToolDef(
                type = "function",
                function = com.zhuolin.yunkai.model.FunctionDef(
                    name = t.name, description = t.description,
                    parameters = json.decodeFromString<JsonSchema>(t.parametersJson),
                ),
            ))
        }

        // skillBlock 注入条件化：autoRoute=false 且无 @指定时不注入技能清单（仅 @名字 手动触发；
        // forcedSkill 不受 autoRoute 影响——用户显式点名必须生效）
        val sysMsg = ChatMsg(role = "system", content = AGENT_SYSTEM + skillBlock(skillRepo, cfg.autoRoute, forcedSkill))
        val userMsg = ChatMsg(role = "user", content = question)
        val messages: MutableList<ChatMsg> = mutableListOf(sysMsg)
        messages.addAll(history)
        messages.add(userMsg)

        // 可测性注入：fakeChat 存在时完全替代真实 LLM 请求。
        // 双模型分工：longFormActive 置真（use_skill 成功拿到说明书）后，本轮余下的 LLM 调用
        // 切到 cfg.longModel（glm-4.7 无思考、可出完整长文）；模型选择收敛在 pickModel 单点。
        // （pickModel 在 fakeChat 之前调用：fakeChat 第三参接收本轮实际选用的模型名，测试据此断言切换行为）
        val llm = LlmClient(cfg)
        var longFormActive = false
        suspend fun doChat(ms: List<ChatMsg>, ts: List<ToolDef>?): OpenAiMessage {
            val model = pickModel(cfg, longFormActive)
            if (fakeChat != null) {
                return fakeChat(ms, ts, model)
            }
            return llm.chatMessage(ms, ts, model)
        }

        for (step in 1..MAX_STEPS) {
            checkCancel(isCancelled)
            val t0 = System.currentTimeMillis()
            Log.i("yunkai", "llm call step=$step model=${pickModel(cfg, longFormActive)} msgs=${messages.size}")
            val msg = doChat(messages, if (toolDefs.isNotEmpty()) toolDefs else null)
            Log.i("yunkai", "llm resp step=$step ${System.currentTimeMillis() - t0}ms toolCalls=${msg.toolCalls?.size ?: 0} contentLen=${msg.content.length}")
            if (!msg.toolCalls.isNullOrEmpty()) {
                messages.add(ChatMsg(role = "assistant", content = msg.content, toolCalls = msg.toolCalls))
                for (tc in msg.toolCalls!!) {
                    checkCancel(isCancelled)
                    onEvent(LoopEvent("tool_start", tc.function.name, tc.function.arguments))
                    val ts = System.currentTimeMillis()
                    val out = execTool(tools, tc)
                    Log.i("yunkai", "tool ${tc.function.name} ${System.currentTimeMillis() - ts}ms outLen=${out.length}")
                    // use_skill 成功返回说明书（非 '{"error"' 开头）→ 本轮余下调用切长文模型；
                    // 失败/error 回传不切换，模型仍用主模型自行调整策略
                    if (tc.function.name == "use_skill" && !out.startsWith("{\"error\"")) {
                        longFormActive = true
                    }
                    onEvent(LoopEvent("tool_done", tc.function.name, out.substring(0, minOf(80, out.length))))
                    messages.add(ChatMsg(role = "tool", content = out, toolCallId = tc.id))
                }
                continue
            }
            onEvent(LoopEvent("answer"))
            return LoopResult(msg.content, step)
        }
        // 步数到顶：kind='limit' 后追加收束指令并做一次无 tools 收尾，强制模型直接作答。
        // 取消准绳：收尾也是一次网络调用——最后一步 execTool await 期间取消旗标命中时，
        // 此处 checkCancel 先抛，保证取消后不再发起任何 LLM 请求（不烧 token）
        checkCancel(isCancelled)
        onEvent(LoopEvent("limit"))
        messages.add(ChatMsg(role = "user", content = "已达步数上限，请基于已有工具结果直接给出最终回答。"))
        val final = doChat(messages, null)
        var finalContent = final.content
        if (finalContent.isEmpty()) {
            finalContent = "已达到本轮工具步数上限，请稍后重试或换个问法"
        }
        return LoopResult(finalContent, MAX_STEPS + 1)
    }

    // 双模型分工的模型选择单点（纯函数）：本轮是否已由 use_skill 激活长文形态。
    // longFormActive=true 且 longModel 非空 → longModel；否则回退主模型 cfg.model
    // （longModel 空串=未配置，照旧主模型，绝不发出空 model 请求）。
    // 端点兼容守卫：longModel 需与主模型同端点兼容；非智谱系端点不切换
    // （默认值是智谱模型名，用户配 DeepSeek 等第三方端点时切 glm-4.7 会 404 整轮失败），
    // 仅 baseUrl 含 'bigmodel'/'zhipu'（不区分大小写）才允许切 longModel。
    fun pickModel(cfg: AppConfig, longFormActive: Boolean): String {
        val base = cfg.baseUrl.lowercase()
        val isZhipuEndpoint = base.contains("bigmodel") || base.contains("zhipu")
        return if (longFormActive && cfg.longModel.isNotEmpty() && isZhipuEndpoint) cfg.longModel else cfg.model
    }

    // 单工具执行：未知名/抛异常都收敛为 '{"error":...}' 字符串回传给模型，绝不让循环崩溃
    private suspend fun execTool(tools: List<AgentTool>, tc: ToolCall): String {
        val tool = tools.firstOrNull { it.name == tc.function.name }
            ?: return BuiltinTools.err("未知工具: ${tc.function.name}")
        return try {
            tool.execute(tc.function.arguments)
        } catch (err: Exception) {
            BuiltinTools.err(err.message ?: err.toString())
        }
    }

    // system prompt 的技能摘要块：
    // - forcedSkill 非 null（@手动指定）：只报该技能，autoRoute 开关不影响；
    // - autoRoute=false（用户关闭自动路由）：不注入技能清单，仅留一句守则提示；
    // - 其余：列库内全部技能供模型决定是否 use_skill
    private suspend fun skillBlock(skillRepo: SkillSource, autoRoute: Boolean, forcedSkill: AgentSkill?): String {
        if (forcedSkill != null) {
            return "\n\n[用户已指定技能] ${forcedSkill.name}：${forcedSkill.description}" +
                "\n需要时调用 use_skill 工具（name 填「${forcedSkill.name}」）获取完整说明书。"
        }
        if (!autoRoute) {
            return "\n\n（自动技能路由已关闭：仅当用户以「@技能名」显式指定技能时才可调用 use_skill，" +
                "否则不要自行调用技能，直接凭自身知识回答。）"
        }
        val block0 = "\n\n[可用技能清单]"
        val skills = skillRepo.list()
        if (skills.isEmpty()) {
            return block0 + "\n（当前无可用技能，无合适技能时直接凭自身知识回答）"
        }
        var block = block0
        for (s in skills) {
            block += "\n- ${s.name}：${s.description}"
        }
        block += "\n需要时调用 use_skill 工具（name 填技能名）获取完整说明书。"
        return block
    }
}
