package com.zhuolin.yunkai.service

import com.zhuolin.yunkai.model.AgentSkill
import com.zhuolin.yunkai.model.AppConfig
import com.zhuolin.yunkai.model.ContentPart
import com.zhuolin.yunkai.model.ChatMsg
import com.zhuolin.yunkai.model.JsonSchema
import com.zhuolin.yunkai.model.ToolCall
import com.zhuolin.yunkai.model.ToolDef
import com.zhuolin.yunkai.memory.MemoryInjection
import com.zhuolin.yunkai.memory.MemoryStore
import com.zhuolin.yunkai.memory.MemoryTools
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
// B1 流式：run 末位可选参 onDelta 上抛当前步增量文本（累计），UI 据此渲染流式气泡；最终
// 落库仍走唯一成功路径（与增量渲染解耦）。
// 【M2 待办】skill description 注入防御：技能 description 来自用户/导入内容，直接拼进 system prompt
//   可能夹带 prompt injection（「忽略以上指令」类）；上线前需转义/长度截断/敏感内容过滤。
// 【M2 待办】多工具并发与 tool 结果预算截断：同一轮 tool_calls 目前串行执行；且 tool 结果全文回填
//   可能撑爆上下文，需按预算截断/摘要（单条上限 + 本轮总预算）。
// 语义唯一依据：yunkai-harmony/entry/src/main/ets/service/AgentLoop.ets（AGENT_SYSTEM 文案逐字复制）

// 循环事件：tool_start/tool_done 携带工具名与「参数 / 结果前80字」摘要；answer/limit 无工具名
data class LoopEvent(val kind: String, val toolName: String = "", val detail: String = "")

// 循环结果：最终回答文本 + 实际步数（到顶时 = maxSteps + 1，含收尾调用）。M3：hitLimit=步数到顶收尾（true 时 trace 携带到顶前
// 完整消息轨迹，调用方持久化 task_state 供「继续」续跑）；正常作答两字段为默认值。
// 输出形态（画布/气泡）不在引擎层判定：Chat 消费方用 HtmlGuard.sanitize+ReplyKind.detect
// 做唯一口径判定（防散文夹 <html 子串被 sanitize 的 includes 语义误判），引擎只给纯文本。
data class LoopResult(val answer: String, val steps: Int, val hitLimit: Boolean = false, val trace: List<ChatMsg>? = null)

object AgentLoop {
    // 步数上限：防模型无限调用工具打转；到顶后强制一次无 tools 收尾。
    // M3：生产走 cfg.maxSteps 三档（10/25/50，默认 25），本常量保留为缺省参数值（测试兼容）
    const val MAX_STEPS: Int = 10

    // M3 工具输出软预算（协议 §2 TOOL_OUTPUT_BUDGET）：单轮累计工具结果超限即截断，
    // 防巨型网页/文件结果撑爆上下文；截断带标记让模型知情
    const val TOOL_OUTPUT_BUDGET: Int = 30000

    // 取消专用错误消息：调用方按此静默吞（引擎层中断，结果直接丢弃）
    const val CANCELLED_MSG: String = "AGENT_LOOP_CANCELLED"

    // M3 到顶收尾指令：从「直接给最终回答」升级为「交代进度」——已完成/未完成两部分，
    // 这是「继续」按钮的语义基础（继续=以轨迹续跑，模型从交接说明接着干）
    private const val LIMIT_FINAL_PROMPT: String =
        "已达步数上限。请基于已有工具结果输出收尾说明：1) 已完成/已知的部分；" +
        "2) 未完成的部分与建议的继续方式。不要再调用工具。"

    // 长文形态（eli5）撤出工具表并拒绝执行：M2 文件两件 + 忆枢记忆五件（协议 §4.3）
    private val M2_TOOL_NAMES = setOf("read_file", "write_file") + MemoryTools.ALL_NAMES

    private val json = Json { ignoreUnknownKeys = true }

    // system prompt 常量：桌面 agent 人格 + 无合适 skill 直接回答 + 工具守则（等工具结果再答）
    // public（M1c）：管理页 persona「重置种子」写回同一常量，保证种子文案单源（协议 §4.1）
    const val AGENT_SYSTEM: String =
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
        onDelta: ((String) -> Unit)? = null,
        onThinking: ((String) -> Unit)? = null,
        extraTools: List<AgentTool> = emptyList(),
        // 一期图片链路：带图提问时用户消息的 contentParts 由调用方传入（文字仍走 question）
        extraUserParts: List<ContentPart>? = null,
        // 忆枢 M1b：记忆存储（null=不接线，维持旧注入行为；生产由 ChatViewModel 传 RoomMemoryStore）
        memory: MemoryStore? = null,
        // M3：任务步数三档（生产传 cfg.maxSteps；缺省保持旧 10 步语义，既有测试零改动）
        maxSteps: Int = MAX_STEPS,
        // M3 继续任务：到顶轨迹续跑——非 null 时以其为消息基底下沉 history/question 组装
        // （trace[0] 若为 system 则就地重建为当前人格+记忆段，防陈旧）
        seedMessages: List<ChatMsg>? = null,
    ): LoopResult {
        val tools: List<AgentTool> = BuiltinTools.createAll(cfg, forcedSkill, skillRepo) + extraTools
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

        // 忆枢注入（协议 §4.1/4.2）：首次运行播种 persona=AGENT_SYSTEM 原文、human=空串；
        // system = persona 块 + "\n\n" + human 块 + "\n\n" + 记忆说明块 + skillBlock。
        // 两块皆空 → 记忆段整段省略（MemoryInjection.coreSection 返回 null，避免裸标题）。
        // memory == null（旧测试/未接线）时保持 AGENT_SYSTEM 原样。
        var baseSystem = AGENT_SYSTEM
        var memorySection = ""
        if (memory != null) {
            seedCoreBlocks(memory)
            val persona = memory.getCoreBlock(MemoryStore.BLOCK_PERSONA)?.content ?: ""
            val human = memory.getCoreBlock(MemoryStore.BLOCK_HUMAN)?.content ?: ""
            baseSystem = persona.ifBlank { AGENT_SYSTEM }
            memorySection = MemoryInjection.coreSection(persona, human)?.let { "\n\n$it" } ?: ""
        }

        // skillBlock 注入条件化：autoRoute=false 且无 @指定时不注入技能清单（仅 @名字 手动触发；
        // forcedSkill 不受 autoRoute 影响——用户显式点名必须生效）
        // 记下来供 eli5 撤记忆段时重建 system（skillRepo 内容一轮内不变，直接复用）
        val skillBlk = skillBlock(skillRepo, cfg.autoRoute, forcedSkill)
        val sysMsg = ChatMsg(role = "system", content = baseSystem + memorySection + skillBlk)
        val userMsg = ChatMsg(role = "user", content = question, contentParts = extraUserParts)
        val messages: MutableList<ChatMsg> = mutableListOf(sysMsg)
        if (seedMessages != null) {
            val seed = seedMessages.toMutableList()
            if (seed.isNotEmpty() && seed[0].role == "system") seed[0] = sysMsg
            messages.addAll(seed)
        } else {
            messages.addAll(history)
            messages.add(userMsg)
        }

        // 可测性注入：fakeChat 存在时完全替代真实 LLM 请求。
        // 双模型分工：longFormActive 置真（use_skill 成功拿到说明书）后，本轮余下的 LLM 调用
        // 切到 cfg.longModel（glm-4.7 无思考、可出完整长文）；模型选择收敛在 pickModel 单点。
        // （pickModel 在 fakeChat 之前调用：fakeChat 第三参接收本轮实际选用的模型名，测试据此断言切换行为）
        val llm = LlmClient(cfg)
        var longFormActive = false
        var toolBudgetUsed = 0   // M3 软预算：单轮累计工具输出字符数
        suspend fun doChat(ms: List<ChatMsg>, ts: List<ToolDef>?): OpenAiMessage {
            val model = pickModel(cfg, longFormActive)
            if (fakeChat != null) {
                return fakeChat(ms, ts, model)
            }
            // B1 SSE：非流式保持兜底；流式增量经 onDelta 上抛（UI 流式气泡）。
            // 取消：轮询旗标在 onDelta 里检查，命中即抛（中断读流、断连省 token）。
            return llm.chatStream(ms, ts, model, onDelta = { partial ->
                checkCancel(isCancelled)
                onDelta?.invoke(partial)
            }, onThinking = onThinking)
        }

        for (step in 1..maxSteps) {
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
                    var out = execTool(tools, tc, longFormActive)
                    Log.i("yunkai", "tool ${tc.function.name} ${System.currentTimeMillis() - ts}ms outLen=${out.length}")
                    // M3 软预算（协议 §2 TOOL_OUTPUT_BUDGET=30000）：累计超限即截断本条并标记，
                    // 模型据此改用更小粒度的工具调用；预算逐轮重置（每轮 send 重新计）。
                    // 预算已被前序结果打满（keep==0）时本条不再截零加标记，直接替换为耗尽提示
                    if (toolBudgetUsed + out.length > TOOL_OUTPUT_BUDGET) {
                        val keep = (TOOL_OUTPUT_BUDGET - toolBudgetUsed).coerceAtLeast(0)
                        out = if (keep == 0) {
                            "本轮工具输出预算已耗尽，请基于已有结果作答"
                        } else {
                            out.take(keep) + "\n…[已截断：本轮工具输出累计超 ${TOOL_OUTPUT_BUDGET} 字软预算]"
                        }
                    }
                    toolBudgetUsed += out.length
                    // use_skill 成功返回说明书（非 '{"error"' 开头）→ 本轮余下调用切长文模型；
                    // 失败/error 回传不切换，模型仍用主模型自行调整策略
                    if (tc.function.name == "use_skill" && !out.startsWith("{\"error\"")) {
                        longFormActive = true
                        // M2 文件工具 + 忆枢记忆五件在长文形态下移出工具表：eli5 的产出契约是「整页
                        // HTML 直接写在回答正文」，write_file 会把页面吸进沙箱让画布判定失效
                        // （门2 实测回归）；记忆工具随 §4.3 一并撤下（技能契约纯净优先）
                        toolDefs.removeAll { it.function?.name in M2_TOOL_NAMES }
                        // §4.3：长文形态本轮 system 撤下两块与记忆说明块（就地重建 system 消息，
                        // 已发出的第 1 步请求不受影响，长文步起生效）
                        if (memorySection.isNotEmpty()) {
                            memorySection = ""
                            messages[0] = messages[0].copy(content = baseSystem + skillBlk)
                        }
                    }
                    onEvent(LoopEvent("tool_done", tc.function.name, out.substring(0, minOf(80, out.length))))
                    messages.add(ChatMsg(role = "tool", content = out, toolCallId = tc.id))
                }
                continue
            }
            onEvent(LoopEvent("answer"))
            return LoopResult(msg.content, step)
        }
        // 步数到顶：kind='limit' 后追加收束指令并做一次无 tools 收尾——M3 语义升级为「交代进度」：
        // 模型输出已完成/未完成两部分（UI 据此出「继续」按钮，轨迹已随 LoopResult.trace 回传）。
        // 取消准绳：收尾也是一次网络调用——最后一步 execTool await 期间取消旗标命中时，
        // 此处 checkCancel 先抛，保证取消后不再发起任何 LLM 请求（不烧 token）
        checkCancel(isCancelled)
        onEvent(LoopEvent("limit"))
        // M3：轨迹在收尾指令入列**之前**截取——收尾指令写着「不要再调用工具」，带着它续跑会毒化模型
        val traceForContinue = messages.toList()
        messages.add(ChatMsg(role = "user", content = LIMIT_FINAL_PROMPT))
        val final = doChat(messages, null)
        var finalContent = final.content
        if (finalContent.isEmpty()) {
            finalContent = "已达到本轮工具步数上限，可点「继续」接着跑，或稍后换个问法"
        }
        // M3：到顶轨迹全量回传，调用方持久化 task_state 供「继续」续跑
        return LoopResult(finalContent, maxSteps + 1, hitLimit = true, trace = traceForContinue)
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
    private suspend fun execTool(tools: List<AgentTool>, tc: ToolCall, m2Blocked: Boolean): String {
        // 长文形态拒绝 M2 工具：工具表已撤下但模型仍可能幻觉调用（实测 glm-4.7 会），
        // 放行会让 eli5 的 HTML 流进沙箱使画布判定失效
        if (m2Blocked && tc.function.name in M2_TOOL_NAMES) {
            return BuiltinTools.err("当前为讲解长文模式，文件/记忆工具不可用；请把完整内容（含 HTML）直接写在回答正文中")
        }
        val tool = tools.firstOrNull { it.name == tc.function.name }
            ?: return BuiltinTools.err("未知工具: ${tc.function.name}")
        return try {
            tool.execute(tc.function.arguments)
        } catch (err: Exception) {
            BuiltinTools.err(err.message ?: err.toString())
        }
    }

    // 核心记忆种子（协议 §4.1「种子=AGENT_SYSTEM 原文」）：幂等——以「行缺失」为首次运行判据，
    // 每轮请求前调用；persona 缺行补 AGENT_SYSTEM 原文，human 缺行补空串。用户后续用
    // core_memory_replace 清空 persona 不会被重新覆盖（行仍在，仅内容空）。
    private suspend fun seedCoreBlocks(memory: MemoryStore) {
        if (memory.getCoreBlock(MemoryStore.BLOCK_PERSONA) == null) {
            memory.putCoreBlock(MemoryStore.BLOCK_PERSONA, AGENT_SYSTEM)
        }
        if (memory.getCoreBlock(MemoryStore.BLOCK_HUMAN) == null) {
            memory.putCoreBlock(MemoryStore.BLOCK_HUMAN, "")
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
