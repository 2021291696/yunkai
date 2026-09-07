// AgentLoop 引擎：LLM function-calling 主循环
// 职责：组装 system prompt（桌面 agent 人格 + 技能摘要块）与工具表，
// 驱动「模型 → 工具 → 模型」循环；工具失败以 '{"error":...}' 字符串回传给模型自行调整，
// 绝不让循环崩溃；步数到 MAX_STEPS 后强制一次无 tools 收尾作答。
// 每一步经 onEvent 上报 tool_start / tool_done / answer / limit，UI 层据此展示进度。
// 可测性：run 末位可选参 fakeChat 注入假 LLM（生产不传，走真 LlmClient.chatMessage）。
// 取消：run 末位可选参 isCancelled 轮询取消旗标，命中即抛 Error('AGENT_LOOP_CANCELLED')，
// 由调用方（Chat 页）静默吞掉；引擎层中断保证后台不再烧 token / 跑工具副作用。
// 【M2 待办】skill description 注入防御：技能 description 来自用户/导入内容，直接拼进 system prompt
//   可能夹带 prompt injection（「忽略以上指令」类）；上线前需转义/长度截断/敏感内容过滤。
// 【M2 待办】多工具并发与 tool 结果预算截断：同一轮 tool_calls 目前串行执行；且 tool 结果全文回填
//   可能撑爆上下文，需按预算截断/摘要（单条上限 + 本轮总预算）。
import { common } from '@kit.AbilityKit';
import { AppConfig, AgentSkill, ChatMsg, JsonSchema, ToolCall, ToolDef } from '../model/Types';
import { LlmClient, OpenAiMessage } from './LlmClient';
import { HtmlGuard } from './HtmlGuard';
import { SkillRepo } from '../store/SkillRepo';
import { AgentTool } from './tools/AgentTool';
import { BuiltinTools } from './tools/BuiltinTools';

// 循环事件：tool_start/tool_done 携带工具名与「参数 / 结果前80字」摘要；answer/limit 无工具名
export class LoopEvent {
  kind: string = '';      // 'tool_start' | 'tool_done' | 'answer' | 'limit'
  toolName: string = '';
  detail: string = '';
}

// 循环结果：最终回答文本 + 实际步数 + 是否为 HTML 讲解页
export class LoopResult {
  answer: string = '';
  steps: number = 0;
  isHtml: boolean = false;
}

// 工具执行异常兜底的统一 JSON 形态（与 BuiltinTools 契约一致）
interface ToolErrorPayload { error: string }

// 步数上限：防模型无限调用工具打转；到顶后强制一次无 tools 收尾
const MAX_STEPS: number = 10;

// system prompt 常量：桌面 agent 人格 + 无合适 skill 直接回答 + 工具守则（等工具结果再答）
const AGENT_SYSTEM: string = '你是运行在用户桌面上的智能助手，擅长把复杂概念讲到普通人能听懂，回答用中文、简洁友好。\n' +
  '工作守则：\n' +
  '1. 涉及时效性信息、外部事实或你不确定的内容时，先调用工具取得结果再回答；拿到工具结果前不要编造。\n' +
  '2. 调用工具后必须等待工具结果（tool 消息）返回，再决定继续调用或给出最终回答。\n' +
  '3. 没有合适的工具或技能能帮上忙时，直接凭自身知识回答，不要硬凑工具调用。\n' +
  '4. 技能（skill）是写好的详细操作说明书；一旦调用 use_skill 拿到说明书，必须严格照它执行。';

export class AgentLoop {
  // 取消检查：isCancelled 命中即抛专用取消 Error，调用方静默吞（引擎层中断，结果直接丢弃）
  private static readonly CANCELLED_MSG: string = 'AGENT_LOOP_CANCELLED';

  // 核心循环入口；fakeChat 仅测试注入，生产路径必须不传；isCancelled 为取消轮询旗标（末位可选参）
  static async run(cfg: AppConfig, ctx: common.Context, history: ChatMsg[], question: string,
    forcedSkill: AgentSkill | null, onEvent: (e: LoopEvent) => void,
    fakeChat?: (messages: ChatMsg[], tools: ToolDef[] | null) => Promise<OpenAiMessage>,
    isCancelled?: () => boolean): Promise<LoopResult> {
    const checkCancel = (): void => {
      if (isCancelled !== undefined && isCancelled()) {
        throw new Error(AgentLoop.CANCELLED_MSG);
      }
    };
    const tools: AgentTool[] = BuiltinTools.createAll(ctx, cfg, forcedSkill);
    const toolDefs: ToolDef[] = [];
    for (const t of tools) {
      const def: ToolDef = {
        type: 'function',
        function: { name: t.name, description: t.description, parameters: JSON.parse(t.parametersJson) as JsonSchema }
      };
      toolDefs.push(def);
    }

    const sysMsg: ChatMsg = { role: 'system', content: AGENT_SYSTEM + await AgentLoop.skillBlock(ctx, forcedSkill) };
    const userMsg: ChatMsg = { role: 'user', content: question };
    const messages: ChatMsg[] = [sysMsg];
    for (const h of history) { messages.push(h); }
    messages.push(userMsg);

    // 可测性注入：fakeChat 存在时完全替代真实 LLM 请求
    const llm = new LlmClient(cfg);
    const doChat = async (ms: ChatMsg[], ts: ToolDef[] | null): Promise<OpenAiMessage> => {
      if (fakeChat !== undefined) {
        return fakeChat(ms, ts);
      }
      return llm.chatMessage(ms, ts);
    };

    for (let step = 1; step <= MAX_STEPS; step++) {
      checkCancel();
      const msg = await doChat(messages, toolDefs.length > 0 ? toolDefs : null);
      if (msg.tool_calls !== undefined && msg.tool_calls.length > 0) {
        const asst: ChatMsg = { role: 'assistant', content: msg.content, tool_calls: msg.tool_calls };
        messages.push(asst);
        const calls: ToolCall[] = msg.tool_calls;
        for (const tc of calls) {
          checkCancel();
          onEvent(AgentLoop.ev('tool_start', tc.function.name, tc.function.arguments));
          const out = await AgentLoop.execTool(tools, tc);
          onEvent(AgentLoop.ev('tool_done', tc.function.name, out.substring(0, 80)));
          const tm: ChatMsg = { role: 'tool', tool_call_id: tc.id, content: out };
          messages.push(tm);
        }
        continue;
      }
      onEvent(AgentLoop.ev('answer', '', ''));
      return AgentLoop.result(msg.content, step);
    }
    // 步数到顶：kind='limit' 后追加收束指令并做一次无 tools 收尾，强制模型直接作答
    onEvent(AgentLoop.ev('limit', '', ''));
    const finishMsg: ChatMsg = { role: 'user', content: '已达步数上限，请基于已有工具结果直接给出最终回答。' };
    messages.push(finishMsg);
    const final = await doChat(messages, null);
    let finalContent = final.content;
    if (finalContent === '') {
      finalContent = '已达到本轮工具步数上限，请稍后重试或换个问法';
    }
    return AgentLoop.result(finalContent, MAX_STEPS + 1);
  }

  // 单工具执行：未知名/抛异常都收敛为 '{"error":...}' 字符串回传给模型，绝不让循环崩溃
  private static async execTool(tools: AgentTool[], tc: ToolCall): Promise<string> {
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

  // system prompt 的技能摘要块：强制指定时只报该技能；否则列库内全部技能供模型决定是否 use_skill
  private static async skillBlock(ctx: common.Context, forcedSkill: AgentSkill | null): Promise<string> {
    if (forcedSkill !== null) {
      return `\n\n[用户已指定技能] ${forcedSkill.name}：${forcedSkill.description}` +
        `\n需要时调用 use_skill 工具（name 填「${forcedSkill.name}」）获取完整说明书。`;
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
    r.isHtml = HtmlGuard.sanitize(answer) !== null;
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
