// LLM 客户端：OpenAI 兼容 /chat/completions 与 /models
// 请求体显式 max_tokens（16384，长 HTML 讲解不截断）；tools 走 OpenAI function calling 协议
import { http } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';
import { AppConfig, ChatMsg, ContentPart, WireMessage, ToolCall, ToolDef } from '../model/Types';

// 完整 message（含 tool_calls / tool_call_id），供 agent 循环按调用分派；导出供测试 fake 构造脚本
export interface OpenAiMessage {
  content: string;
  tool_calls?: ToolCall[];
  tool_call_id?: string;
}
interface OpenAiChoice { message: OpenAiMessage }
interface OpenAiResp { choices: OpenAiChoice[] }
interface ModelsItem { id: string }
interface ModelsResp { data: ModelsItem[] }

// SSE 流式分片结构（delta 增量；tool_calls 分片带 index）
interface DeltaFunction { name?: string; arguments?: string }
interface DeltaToolCall { index?: number; id?: string; function?: DeltaFunction }
interface DeltaChoice { delta: DeltaBody }
interface DeltaBody {
  content?: string;
  reasoning_content?: string;
  reasoning?: string;
  tool_calls?: DeltaToolCall[];
}
interface SseChunk { choices?: DeltaChoice[] }

// ponytail: 显式类型，ArkTS 对象字面量必须对应接口
interface ChatRequestBody {
  model: string;
  messages: WireMessage[];
  stream: boolean;
  temperature: number;
  max_tokens: number;
  tools?: ToolDef[];
}

export class LlmClient {
  private cfg: AppConfig;

  // 单次响应 token 上限：常量化显式下发，防端点默认值截断长讲解
  static readonly MAX_TOKENS: number = 16384;

  constructor(cfg: AppConfig) {
    this.cfg = cfg;
  }

  private base(): string {
    return this.cfg.baseUrl.replace(/\/+$/, '');
  }

  // 内部消息 → 出网消息：content_parts 非空时 content 输出多模态数组（parts 为唯一权威，
  // 不再由 content 字段补 text，否则调用方已放 text part 时会重复）；纯文本消息保持字符串原样
  static toWireMessages(messages: ChatMsg[]): WireMessage[] {
    const out: WireMessage[] = [];
    for (const m of messages) {
      const parts: ContentPart[] | undefined = m.content_parts;
      if (parts !== undefined && parts.length > 0) {
        const w: WireMessage = { role: m.role, content: parts };
        if (m.tool_calls !== undefined) { w.tool_calls = m.tool_calls; }
        if (m.tool_call_id !== undefined) { w.tool_call_id = m.tool_call_id; }
        out.push(w);
      } else {
        const w: WireMessage = { role: m.role, content: m.content };
        if (m.tool_calls !== undefined) { w.tool_calls = m.tool_calls; }
        if (m.tool_call_id !== undefined) { w.tool_call_id = m.tool_call_id; }
        out.push(w);
      }
    }
    return out;
  }

  // 构造请求体：stream 默认 false（chatStream 显式传 true）；max_tokens 恒写；tools 非 null 才写
  static buildRequestBody(model: string, messages: ChatMsg[], tools: ToolDef[] | null, stream: boolean = false): string {
    const body: ChatRequestBody = {
      model: model,
      messages: LlmClient.toWireMessages(messages),
      stream: stream,
      temperature: 0.6,
      max_tokens: LlmClient.MAX_TOKENS
    };
    if (tools !== null) {
      body.tools = tools;
    }
    return JSON.stringify(body);
  }

  // 解析完整 message（含 tool_calls / tool_call_id），供 agent 循环按调用分派。
  // 解析边界归一：
  // - content:null → ''（tool_calls 轮普遍返回 content:null；null 扩散会打穿
  //   messages 回传序列化 / HtmlGuard.sanitize 的 trim / messages 表 NOT NULL）；
  // - tool_calls 做数组性守卫（非数组视为未携带），防畸形响应带病下行。
  static extractMessage(jsonStr: string): OpenAiMessage {
    const obj = JSON.parse(jsonStr) as OpenAiResp;
    if (obj.choices.length === 0) { throw new Error('LLM 响应无 choices'); }
    const raw = obj.choices[0].message;
    const msg: OpenAiMessage = { content: '' };
    if (raw !== undefined && raw !== null && typeof raw.content === 'string') {
      msg.content = raw.content;
    }
    const tcs = raw !== undefined && raw !== null ? raw.tool_calls : undefined;
    if (tcs !== undefined && tcs !== null && Array.isArray(tcs)) {
      msg.tool_calls = tcs;
    }
    return msg;
  }

  // 非流式对话：返回完整 message（含 tool_calls），tools 由调用方显式给（null 表示不带）
  // readTimeout 600s（glm-4.7 非流式长文实测 140s~600s+ 波动）；messages 元素可带 tool_calls/tool_call_id
  // （ChatMsg 可选字段，JSON.stringify 天然序列化）。agent 循环走本方法。
  // model 可选覆盖：双模型分工——agent 循环 use_skill 后传 longModel 生成长文；
  // 不传或空串回退 cfg.model（其他调用方零改动）。
  async chatMessage(messages: ChatMsg[], tools: ToolDef[] | null, model?: string): Promise<OpenAiMessage> {
    const hr = http.createHttp();
    const useModel = (model !== undefined && model !== '') ? model : this.cfg.model;
    try {
      const opt: http.HttpRequestOptions = {
        method: http.RequestMethod.POST,
        header: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${this.cfg.apiKey}` },
        extraData: LlmClient.buildRequestBody(useModel, messages, tools),
        readTimeout: 600000,
        connectTimeout: 15000,
        expectDataType: http.HttpDataType.STRING
      };
      const resp = await hr.request(`${this.base()}/chat/completions`, opt);
      if (resp.responseCode < 200 || resp.responseCode >= 300) {
        throw new Error(`LLM HTTP ${resp.responseCode}: ${String(resp.result).substring(0, 300)}`);
      }
      return LlmClient.extractMessage(String(resp.result));
    } finally {
      hr.destroy();
    }
  }

  // GET {base}/models → data[].id 数组
  async listModels(): Promise<string[]> {
    const hr = http.createHttp();
    try {
      const opt: http.HttpRequestOptions = {
        method: http.RequestMethod.GET,
        header: { 'Authorization': `Bearer ${this.cfg.apiKey}` },
        readTimeout: 15000,
        connectTimeout: 15000,
        expectDataType: http.HttpDataType.STRING
      };
      const resp = await hr.request(`${this.base()}/models`, opt);
      if (resp.responseCode < 200 || resp.responseCode >= 300) {
        throw new Error(`模型列表 HTTP ${resp.responseCode}: ${String(resp.result).substring(0, 300)}`);
      }
      const obj = JSON.parse(String(resp.result)) as ModelsResp;
      const ids: string[] = [];
      for (const m of obj.data) { ids.push(m.id); }
      return ids;
    } finally {
      hr.destroy();
    }
  }

  // ── B1 SSE 流式对话 ──
  // 与 chatMessage 同协议但 stream=true：requestInStream + dataReceive 增量回调。
  // delta.content 增量经 onDelta(累计文本) 上抛（UI 流式气泡）；delta.tool_calls 分片按
  // index 组装（id/name 取首片，arguments 顺序拼接）；dataEnd 时 resolve 完整 message
  // （与 chatMessage 同构，AgentLoop 分派逻辑零改动）。
  // SSE 分片会切断行——用 TextDecoder(stream 模式) 增量解码 + 手动按 \n 切行保留残行。
  // 取消：调用方 isCancelled 在 onDelta 里检查（本类不管取消，与 Android 端对称）。
  async chatStream(messages: ChatMsg[], tools: ToolDef[] | null, onDelta: (partial: string) => void, model?: string, onThinking?: (partial: string) => void): Promise<OpenAiMessage> {
    return new Promise<OpenAiMessage>((resolve: (v: OpenAiMessage) => void, reject: (e: Error) => void) => {
      const hr = http.createHttp();
      const useModel = (model !== undefined && model !== '') ? model : this.cfg.model;
      const decoder = util.TextDecoder.create('utf-8', { ignoreBOM: false });
      let pending = '';          // 跨分片的残行
      let content = '';          // 累计增量文本
      let think = '';            // reasoning_content 累计（思考流）
      let done = false;          // dataEnd 已处理（防双 resolve）
      let httpErr = '';
      // tool_calls 分片组装：index → (id/name/arguments)
      const tcIds: Map<number, string> = new Map();
      const tcNames: Map<number, string> = new Map();
      const tcArgs: Map<number, string> = new Map();
      const tcOrder: number[] = [];

      const finish = (): void => {
        if (done) { return; }
        done = true;
        hr.destroy();
        if (httpErr !== '') {
          reject(new Error(httpErr));
          return;
        }
        const toolCalls: ToolCall[] = [];
        for (const idx of tcOrder) {
          const id = tcIds.get(idx) ?? `call_${idx}`;
          const name = tcNames.get(idx);
          if (name === undefined) { continue; }
          const tc: ToolCall = { id: id, type: 'function', function: { name: name, arguments: tcArgs.get(idx) ?? '{}' } };
          toolCalls.push(tc);
        }
        const msg: OpenAiMessage = { content: content };
        if (toolCalls.length > 0) { msg.tool_calls = toolCalls; }
        resolve(msg);
      };

      const applyDelta = (deltaObj: DeltaBody): void => {
        if (typeof deltaObj.reasoning_content === 'string' && deltaObj.reasoning_content.length > 0) {
          think += deltaObj.reasoning_content;
          if (onThinking !== undefined && onThinking !== null) { onThinking(think); }
        }
        if (typeof deltaObj.reasoning === 'string' && deltaObj.reasoning.length > 0) {
          think += deltaObj.reasoning;
          if (onThinking !== undefined && onThinking !== null) { onThinking(think); }
        }
        if (typeof deltaObj.content === 'string' && deltaObj.content.length > 0) {
          content += deltaObj.content;
          onDelta(content);
        }
        const tcs = deltaObj.tool_calls;
        if (tcs !== undefined && tcs !== null && Array.isArray(tcs)) {
          for (const tc of tcs) {
            let idx = 0;
            if (typeof tc.index === 'number') { idx = tc.index; }
            if (!tcArgs.has(idx)) {
              tcArgs.set(idx, '');
              tcOrder.push(idx);
              if (typeof tc.id === 'string' && tc.id !== '') { tcIds.set(idx, tc.id); }
              if (tc.function !== undefined && typeof tc.function.name === 'string' && tc.function.name !== '') {
                tcNames.set(idx, tc.function.name);
              }
            } else {
              if (typeof tc.id === 'string' && tc.id !== '' && !tcIds.has(idx)) { tcIds.set(idx, tc.id); }
              if (tc.function !== undefined && typeof tc.function.name === 'string' && tc.function.name !== '' && !tcNames.has(idx)) {
                tcNames.set(idx, tc.function.name);
              }
            }
            if (tc.function !== undefined && typeof tc.function.arguments === 'string') {
              tcArgs.set(idx, (tcArgs.get(idx) ?? '') + tc.function.arguments);
            }
          }
        }
      };

      const feedLines = (chunk: string): void => {
        pending += chunk;
        // SSE 事件以 \n 分隔；\r 容错（部分端点用 \r\n）
        let nl = pending.indexOf('\n');
        while (nl >= 0) {
          let line = pending.substring(0, nl);
          if (line.endsWith('\r')) { line = line.substring(0, line.length - 1); }
          pending = pending.substring(nl + 1);
          if (line.startsWith('data:')) {
            const payload = line.substring(5).trim();
            if (payload === '[DONE]') { finish(); return; }
            if (payload !== '') {
              try {
                const obj = JSON.parse(payload) as SseChunk;
                if (obj.choices !== undefined && obj.choices !== null && obj.choices.length > 0) {
                  applyDelta(obj.choices[0].delta);
                }
              } catch (e) {
                // 单片解析失败不致命：跳过该行（分片边界已在行切分时保证）
              }
            }
          }
          nl = pending.indexOf('\n');
        }
      };

      hr.on('headersReceive', (header: Object) => {
        // 头部到达时无动作；错误码在 dataReceive/dataEnd 阶段由 respCode 判定
      });
      hr.on('dataReceive', (data: ArrayBuffer) => {
        if (done) { return; }
        try {
          // stream:true 必传——跨分片切开的多字节 UTF-8（中文必中）要靠解码器缓存残字节
          const chunk = decoder.decodeToString(new Uint8Array(data), { stream: true });
          feedLines(chunk);
        } catch (e) {
          httpErr = `流式解码失败: ${e instanceof Error ? e.message : String(e)}`;
          finish();
        }
      });
      hr.on('dataEnd', () => {
        // 冲刷残行（若服务端最后无换行）
        if (pending.startsWith('data:')) {
          const payload = pending.substring(5).trim();
          if (payload === '[DONE]') {
            // 结束标记
          } else if (payload !== '') {
            try {
              const obj = JSON.parse(payload) as SseChunk;
              if (obj.choices !== undefined && obj.choices !== null && obj.choices.length > 0) {
                applyDelta(obj.choices[0].delta);
              }
            } catch (e) {
              // 忽略
            }
          }
        }
        finish();
      });

      const opt: http.HttpRequestOptions = {
        method: http.RequestMethod.POST,
        header: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${this.cfg.apiKey}` },
        extraData: LlmClient.buildRequestBody(useModel, messages, tools, true),
        readTimeout: 600000,
        connectTimeout: 15000,
        expectDataType: http.HttpDataType.STRING
      };
      hr.requestInStream(`${this.base()}/chat/completions`, opt).then((respCode: number) => {
        if (respCode < 200 || respCode >= 300) {
          httpErr = `LLM HTTP ${respCode}`;
          finish();
        }
      }).catch((e: Error) => {
        httpErr = `LLM HTTP: ${e.message}`;
        finish();
      });
    });
  }

}
