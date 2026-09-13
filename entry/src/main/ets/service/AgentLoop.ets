// AgentLoop 引擎：LLM function-calling 主循环
// 职责：组装 system prompt（桌面 agent 人格 + 技能摘要块）与工具表，
// 驱动「模型 → 工具 → 模型」循环；工具失败以 '{"error":...}' 字符串回传给模型自行调整，
// 绝不让循环崩溃；步数到 MAX_STEPS 后强制一次无 tools 收尾作答。
// 每一步经 onEvent 上报 tool_start / tool_done / answer / limit，UI 层据此展示进度。
// 双模型分工：对话与工具调度用 cfg.model（主模型）；本轮 use_skill 成功拿到说明书后，
// 余下 LLM 调用切 cfg.longModel（长文模型，无思考、可出完整 eli5 页），选择收敛在 pickModel。
// 可测性：run 末位可选参 fakeChat 注入假 LLM（生产不传，走真 LlmClient.chatMessage）。
// 取消：run 末位可选参 isCancelled 轮询取消旗标，命中即抛 Error('AGENT_LOOP_CANCELLED')，
// 由调用方（Chat 页）静默吞掉；引擎层中断保证后台不再烧 token / 跑工具副作用。
// 【M2 待办】skill description 注入防御：技能 description 来自用户/导入内容，直接拼进 system prompt
//   可能夹带 prompt injection（「忽略以上指令」类）；上线前需转义/长度截断/敏感内容过滤。
// 【M2 待办】多工具并发与 tool 结果预算截断：同一轮 tool_calls 目前串行执行；且 tool 结果全文回填
//   可能撑爆上下文，需按预算截断/摘要（单条上限 + 本轮总预算）。
import { common } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { AppConfig, AgentSkill, ChatMsg, ContentPart, JsonSchema, ToolCall, ToolDef } from '../model/Types';
import { LlmClient, OpenAiMessage } from './LlmClient';
import { SkillRepo } from '../store/SkillRepo';
import { AgentTool } from './tools/AgentTool';
import { BuiltinTools } from './tools/BuiltinTools';
import { M2Tools } from './tools/M2Tools';
import { MemoryInjection } from '../memory/MemoryInjection';
import { MemoryTools } from '../memory/MemoryTools';
import { MemoryStore } from '../memory/MemoryStore';

// 循环事件：tool_start/tool_done 携带工具名与「参数 / 结果前80字」摘要；answer/limit 无工具名
export class LoopEvent {
  kind: string = '';      // 'tool_start' | 'tool_done' | 'answer' | 'limit'
  toolName: string = '';
  detail: string = '';
}

// 循环结果：最终回答文本 + 实际步数。M3：hitLimit=步数到顶收尾（trace 携带到顶前完整轨迹，
// 调用方持久化 task_state 供「继续」续跑）。
// 输出形态（画布/气泡）不在引擎层判定：Chat 消费方用 HtmlGuard.sanitize+ReplyKind.detect
// 做唯一口径判定（防散文夹 <html 子串被 sanitize 的 includes 语义误判），引擎只给纯文本。
export class LoopResult {
  answer: string = '';
  steps: number = 0;
  hitLimit: boolean = false;
  trace: ChatMsg[] | null = null;
}

// 工具执行异常兜底的统一 JSON 形态（与 BuiltinTools 契约一致）
interface ToolErrorPayload { error: string }

// 步数上限：防模型无限调用工具打转；到顶后强制一次无 tools 收尾。
// M3：生产走 cfg.maxSteps 三档（10/25/50，默认 25），本常量保留为缺省参数值
const MAX_STEPS: number = 10;

// M3 工具输出软预算（协议 §2 TOOL_OUTPUT_BUDGET）：单轮累计超限即截断并标记
export const TOOL_OUTPUT_BUDGET: number = 30000;

// M2 文件工具 + 忆枢记忆五件（长文形态下撤出工具表并拒绝执行，协议 §4.3）
const M2_TOOL_NAMES: string[] = ['read_file', 'write_file'].concat(MemoryTools.ALL_NAMES);

// system prompt 常量：桌面 agent 人格 + 无合适 skill 直接回答 + 工具守则（等工具结果再答）。
// export（M5b）：管理页 persona「重置种子」写回同一常量，保证种子文案单源（协议 §4.1）
export const AGENT_SYSTEM: string = '你是运行在用户桌面上的智能助手，擅长把复杂概念讲到普通人能听懂，回答用中文、简洁友好。\n' +
  '工作守则：\n' +
  '1. 涉及时效性信息（今天/最新/最近/新闻/热点等）、外部事实或你不确定的内容时，必须先调用 web_search 工具搜索再回答；搜索工具已配置且可用，不要声称无法搜索或未配置，拿到结果前不要编造。\n' +
  '2. 调用工具后必须等待工具结果（tool 消息）返回，再决定继续调用或给出最终回答。\n' +
  '3. 没有合适的工具或技能能帮上忙时，直接凭自身知识回答，不要硬凑工具调用。\n' +
  '4. 技能（skill）是写好的详细操作说明书；一旦调用 use_skill 拿到说明书，必须严格照它执行。';

// M3 到顶收尾指令：交代「已完成/未完成与继续方式」——继续按钮的语义基础。
// 注意：这条指令不进 trace（防续跑被「不要再调用工具」毒化）
const LIMIT_FINAL_PROMPT: string =
  '已达步数上限。请基于已有工具结果输出收尾说明：1) 已完成/已知的部分；2) 未完成的部分与建议的继续方式。不要再调用工具。';

export class AgentLoop {
  // 取消检查：isCancelled 命中即抛专用取消 Error，调用方静默吞（引擎层中断，结果直接丢弃）
  private static readonly CANCELLED_MSG: string = 'AGENT_LOOP_CANCELLED';

  // 核心循环入口；fakeChat 仅测试注入，生产路径必须不传；isCancelled 为取消轮询旗标（末位可选参）
  static async run(cfg: AppConfig, ctx: common.Context, history: ChatMsg[], question: string,
    forcedSkill: AgentSkill | null, onEvent: (e: LoopEvent) => void,
    fakeChat?: (messages: ChatMsg[], tools: ToolDef[] | null) => Promise<OpenAiMessage>,
    isCancelled?: () => boolean,
    onDelta?: (partial: string) => void,
    // 一期图片链路：带图提问时用户消息的 contentParts（文字仍走 question）
    extraUserParts?: ContentPart[],
    onThinking?: (partial: string) => void,
    // M3：任务步数三档（生产传 cfg.maxSteps；缺省保持旧 10 步语义）
    maxSteps?: number,
    // 忆枢 M5b：记忆存储（null=不接线，维持旧注入行为）
    memory?: MemoryStore | null,
    // M3 继续任务：到顶轨迹续跑（trace[0] 若为 system 则就地重建为当前人格+记忆段）
    seedMessages?: ChatMsg[] | null): Promise<LoopResult> {
    const steps_max = (maxSteps !== undefined && maxSteps > 0) ? maxSteps : MAX_STEPS;
    const checkCancel = (): void => {
      if (isCancelled !== undefined && isCancelled()) {
        throw new Error(AgentLoop.CANCELLED_MSG);
      }
    };
    const tools: AgentTool[] = BuiltinTools.createAll(ctx, cfg, forcedSkill);
    // M2 B3/B4：read_file/write_file（忆枢五件由调用方按需经 memory 参内建，见下）
    for (const t of M2Tools.createAll(ctx)) {
      tools.push(t);
    }
    if (memory !== undefined && memory !== null) {
      for (const t of MemoryTools.createMemoryTools(memory, cfg.memoryGear)) {
        tools.push(t);
      }
    }
    let toolDefs: ToolDef[] = [];
    for (const t of tools) {
      const def: ToolDef = {
        type: 'function',
        function: { name: t.name, description: t.description, parameters: JSON.parse(t.parametersJson) as JsonSchema }
      };
      toolDefs.push(def);
    }

    // 忆枢注入（协议 §4.1/§4.2）：首次运行播种 persona=AGENT_SYSTEM 原文、human=空串；
    // system = persona 块 + human 块 + 记忆说明块 + skillBlock；两块皆空 → 记忆段整段省略
    let baseSystem = AGENT_SYSTEM;
    let memorySection = '';
    if (memory !== undefined && memory !== null) {
      await AgentLoop.seedCoreBlocks(memory);
      const personaBlk = await memory.getCoreBlock(MemoryTools.BLOCK_PERSONA);
      const humanBlk = await memory.getCoreBlock(MemoryTools.BLOCK_HUMAN);
      const persona = personaBlk !== null ? personaBlk.content : '';
      const human = humanBlk !== null ? humanBlk.content : '';
      baseSystem = persona.trim() !== '' ? persona : AGENT_SYSTEM;
      const section = MemoryInjection.coreSection(persona, human);
      memorySection = section !== null ? '\n\n' + section : '';
    }

    // skillBlock 注入条件化：autoRoute=false 且无 @指定时不注入技能清单（仅 @名字 手动触发；
    // forcedSkill 不受 autoRoute 影响——用户显式点名必须生效）
    const sysMsg: ChatMsg = { role: 'system', content: baseSystem + memorySection + await AgentLoop.skillBlock(ctx, cfg.autoRoute, forcedSkill) };
    const userMsg: ChatMsg = { role: 'user', content: question, content_parts: extraUserParts };
    const messages: ChatMsg[] = [sysMsg];
    if (seedMessages !== undefined && seedMessages !== null) {
      // M3 继续任务：以到顶轨迹续跑，system 就地重建（防陈旧）
      const seed: ChatMsg[] = seedMessages.slice();
      if (seed.length > 0 && seed[0].role === 'system') {
        seed[0] = sysMsg;
      }
      for (const m of seed) { messages.push(m); }
    } else {
      for (const h of history) { messages.push(h); }
      messages.push(userMsg);
    }

    // 可测性注入：fakeChat 存在时完全替代真实 LLM 请求
    // 双模型分工：longFormActive 置真（use_skill 成功拿到说明书）后，本轮余下的 LLM 调用
    // 切到 cfg.longModel（glm-4.7 无思考、可出完整长文）；模型选择收敛在 pickModel 单点。
    const llm = new LlmClient(cfg);
    let longFormActive = false;
    let toolBudgetUsed = 0;   // M3 软预算：单轮累计工具输出字符数
    const doChat = async (ms: ChatMsg[], ts: ToolDef[] | null, onDelta?: (partial: string) => void): Promise<OpenAiMessage> => {
      const model = AgentLoop.pickModel(cfg, longFormActive);
      if (fakeChat !== undefined) {
        return fakeChat(ms, ts);
      }
      // B1 SSE：增量经 onDelta 上抛（UI 流式气泡）；取消由调用方在 onDelta 里检查
      return llm.chatStream(ms, ts, (partial: string) => {
        if (onDelta !== undefined && onDelta !== null) {
          onDelta(partial);
        }
      }, model, onThinking);
    };

    for (let step = 1; step <= steps_max; step++) {
      checkCancel();
      const t0 = Date.now();
      hilog.info(0x0000, 'yunkai', 'llm call step=%{public}d model=%{public}s msgs=%{public}d', step, AgentLoop.pickModel(cfg, longFormActive), messages.length);
      const msg = await doChat(messages, toolDefs.length > 0 ? toolDefs : null, onDelta);
      hilog.info(0x0000, 'yunkai', 'llm resp step=%{public}d %{public}dms toolCalls=%{public}d contentLen=%{public}d', step, Date.now() - t0, msg.tool_calls !== undefined ? msg.tool_calls.length : 0, msg.content.length);
      if (msg.tool_calls !== undefined && msg.tool_calls.length > 0) {
        const asst: ChatMsg = { role: 'assistant', content: msg.content, tool_calls: msg.tool_calls };
        messages.push(asst);
        const calls: ToolCall[] = msg.tool_calls;
        for (const tc of calls) {
          checkCancel();
          onEvent(AgentLoop.ev('tool_start', tc.function.name, tc.function.arguments));
          const ts = Date.now();
          let out = await AgentLoop.execTool(tools, tc, longFormActive);
          hilog.info(0x0000, 'yunkai', 'tool %{public}s %{public}dms outLen=%{public}d', tc.function.name, Date.now() - ts, out.length);
          // M3 软预算（协议 §2 TOOL_OUTPUT_BUDGET=30000）：累计超限即截断本条并标记；
          // 预算已被前序工具耗尽（keep==0）时以固定短文案替代（分支细节见 applyToolBudget）
          out = AgentLoop.applyToolBudget(out, toolBudgetUsed);
          toolBudgetUsed += out.length;
          // use_skill 成功返回说明书（非 '{"error"' 开头）→ 本轮余下调用切长文模型；
          // 失败/error 回传不切换，模型仍用主模型自行调整策略
          if (tc.function.name === 'use_skill' && !out.startsWith('{"error"')) {
            longFormActive = true;
            // M2 文件工具 + 忆枢记忆五件在长文形态下移出工具表：eli5 的产出契约是「整页 HTML 直接
            // 写在回答正文」，write_file 会把页面吸进沙箱文件让画布判定失效（门2 实测回归）
            const m2Names: string[] = M2_TOOL_NAMES;
            toolDefs = toolDefs.filter(d => d.function !== undefined && !m2Names.includes(d.function.name));
            // §4.3：长文形态本轮 system 撤下两块与记忆说明块（就地重建 system 消息）
            if (memorySection !== '') {
              memorySection = '';
              messages[0] = { role: 'system', content: baseSystem + await AgentLoop.skillBlock(ctx, cfg.autoRoute, forcedSkill) };
            }
          }
          onEvent(AgentLoop.ev('tool_done', tc.function.name, out.substring(0, 80)));
          const tm: ChatMsg = { role: 'tool', tool_call_id: tc.id, content: out };
          messages.push(tm);
        }
        continue;
      }
      onEvent(AgentLoop.ev('answer', '', ''));
      return AgentLoop.result(msg.content, step);
    }
    // 步数到顶：kind='limit' 后做一次无 tools 收尾——M3 语义升级为「交代进度」（继续按钮的语义基础）。
    // M3：轨迹在收尾指令入列**之前**截取（收尾指令含「不要再调用工具」，入轨会毒化续跑）
    // 取消准绳：收尾也是一次网络调用——最后一步 execTool await 期间取消旗标命中时，
    // 此处 checkCancel 先抛，保证取消后不再发起任何 LLM 请求（不烧 token）
    checkCancel();
    onEvent(AgentLoop.ev('limit', '', ''));
    const traceForContinue: ChatMsg[] = messages.slice();
    const finishMsg: ChatMsg = { role: 'user', content: LIMIT_FINAL_PROMPT };
    messages.push(finishMsg);
    const final = await doChat(messages, null);
    let finalContent = final.content;
    if (finalContent === '') {
      finalContent = '已达到本轮工具步数上限，可点「继续」接着跑，或稍后换个问法';
    }
    const r = new LoopResult();
    r.answer = finalContent;
    r.steps = steps_max + 1;
    r.hitLimit = true;
    r.trace = traceForContinue;
    return r;
  }

  // 忆枢核心记忆种子（协议 §4.1「种子=AGENT_SYSTEM 原文」）：幂等——以「行缺失」为首次运行判据
  private static async seedCoreBlocks(memory: MemoryStore): Promise<void> {
    if (await memory.getCoreBlock(MemoryTools.BLOCK_PERSONA) === null) {
      await memory.putCoreBlock(MemoryTools.BLOCK_PERSONA, AGENT_SYSTEM);
    }
    if (await memory.getCoreBlock(MemoryTools.BLOCK_HUMAN) === null) {
      await memory.putCoreBlock(MemoryTools.BLOCK_HUMAN, '');
    }
  }

  // 双模型分工的模型选择单点（纯函数）：本轮是否已由 use_skill 激活长文形态。
  // longFormActive=true 且 longModel 非空 → longModel；否则回退主模型 cfg.model
  // （longModel 空串=未配置，照旧主模型，绝不发出空 model 请求）。
  // 端点兼容守卫：longModel 需与主模型同端点兼容；非智谱系端点不切换
  // （默认值是智谱模型名，用户配 DeepSeek 等第三方端点时切 glm-4.7 会 404 整轮失败），
  // 仅 baseUrl 含 'bigmodel'/'zhipu'（不区分大小写）才允许切 longModel。
  // static 公开供单测直测（纯函数无副作用，与 LlmClient.buildRequestBody 同例）。
  static pickModel(cfg: AppConfig, longFormActive: boolean): string {
    const base = cfg.baseUrl.toLowerCase();
    const isZhipuEndpoint = base.includes('bigmodel') || base.includes('zhipu');
    if (longFormActive && cfg.longModel !== '' && isZhipuEndpoint) {
      return cfg.longModel;
    }
    return cfg.model;
  }

  // M3 软预算单条处理（纯函数，static 公开供单测直测——与 pickModel 同例）：
  // 未超限原样返回；keep>0 截断到预算余量并追加截断标记；keep==0（预算已被前序工具耗尽，
  // 再 substring(0,0) 只会产出空串+标记）→ 固定短文案，不再做截断拼接。
  static applyToolBudget(out: string, toolBudgetUsed: number): string {
    if (toolBudgetUsed + out.length <= TOOL_OUTPUT_BUDGET) { return out; }
    const keep = Math.max(0, TOOL_OUTPUT_BUDGET - toolBudgetUsed);
    if (keep === 0) {
      return '本轮工具输出预算已耗尽，请基于已有结果作答';
    }
    return out.substring(0, keep) + `\n…[已截断：本轮工具输出累计超 ${TOOL_OUTPUT_BUDGET} 字软预算]`;
  }

  // 单工具执行：未知名/抛异常都收敛为 '{"error":...}' 字符串回传给模型，绝不让循环崩溃。
  // m2Blocked=true（长文形态）时 M2 文件/记忆工具直接拒绝执行——工具表已撤下但模型仍可能
  // 幻觉调用（实测 glm-4.7 会），放行会让 eli5 的 HTML 流进沙箱使画布判定失效
  private static async execTool(tools: AgentTool[], tc: ToolCall, m2Blocked: boolean): Promise<string> {
    if (m2Blocked && M2_TOOL_NAMES.includes(tc.function.name)) {
      const e: ToolErrorPayload = { error: '当前为讲解长文模式，文件/记忆工具不可用；请把完整内容（含 HTML）直接写在回答正文中' };
      return JSON.stringify(e);
    }
    let tool: AgentTool | null = null;
    for (const t of tools) {
      if (t.name === tc.function.name) {
        tool = t;
        break;
      }
    }
    if (tool === null) {
      const e: ToolErrorPayload = { error: `未知工具: ${tc.function.name}` };
      return JSON.stringify(e);
    }
    try {
      return await tool.execute(tc.function.arguments);
    } catch (err) {
      const e: ToolErrorPayload = { error: (err as Error).message };
      return JSON.stringify(e);
    }
  }

  // system prompt 的技能摘要块：
  // - forcedSkill 非 null（@手动指定）：只报该技能，autoRoute 开关不影响；
  // - autoRoute=false（用户关闭自动路由）：不注入技能清单，仅留一句守则提示；
  // - 其余：列库内全部技能供模型决定是否 use_skill
  private static async skillBlock(ctx: common.Context, autoRoute: boolean, forcedSkill: AgentSkill | null): Promise<string> {
    if (forcedSkill !== null) {
      return `\n\n[用户已指定技能] ${forcedSkill.name}：${forcedSkill.description}` +
        `\n需要时调用 use_skill 工具（name 填「${forcedSkill.name}」）获取完整说明书。`;
    }
    if (!autoRoute) {
      return '\n\n（自动技能路由已关闭：仅当用户以「@技能名」显式指定技能时才可调用 use_skill，' +
        '否则不要自行调用技能，直接凭自身知识回答。）';
    }
    const block0 = '\n\n[可用技能清单]';
    const skills: AgentSkill[] = await SkillRepo.list(ctx);
    if (skills.length === 0) {
      return block0 + '\n（当前无可用技能，无合适技能时直接凭自身知识回答）';
    }
    let block = block0;
    for (const s of skills) {
      block += `\n- ${s.name}：${s.description}`;
    }
    block += '\n需要时调用 use_skill 工具（name 填技能名）获取完整说明书。';
    return block;
  }

  private static result(answer: string, steps: number): LoopResult {
    const r = new LoopResult();
    r.answer = answer;
    r.steps = steps;
    return r;
  }

  private static ev(kind: string, toolName: string, detail: string): LoopEvent {
    const e = new LoopEvent();
    e.kind = kind;
    e.toolName = toolName;
    e.detail = detail;
    return e;
  }

  // 【M1 占位·不实现】prompt 模拟协议降级：Task 1 Step 5 判定端点不支持原生 function calling 时启用——
  //   tools 不传参，改为把工具清单 JSON 与调用格式约定注入 AGENT_SYSTEM；模型纯文本输出单行
  //   {"tool":"名","args":{...}} 时，由本私有方法解析成 ToolCall 后走同一 execTool 分派，接口不变。
  //   已实测 glm-4.7 原生执行 function calling（finish_reason=tool_calls），M1 不启用此路径。
  // private static parsePromptSimulatedToolCall(text: string): ToolCall | null {
  //   // 解析层：按行扫描，行首行尾 trim 后 JSON.parse，校验含 string tool 与 object args；
  //   // args 无 id 可用，须以 `sim_${行号}` 合成 id。解析失败返回 null（视为普通文本回答）。
  //   return null;
  // }
}
