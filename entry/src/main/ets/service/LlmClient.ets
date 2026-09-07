// LLM 客户端：OpenAI 兼容 /chat/completions 与 /models
// 请求体显式 max_tokens（16384，长 HTML 讲解不截断）；tools 走 OpenAI function calling 协议
import { http } from '@kit.NetworkKit';
import { AppConfig, ChatMsg, ToolCall, ToolDef } from '../model/Types';

interface OpenAiMessage {
  content: string;
  tool_calls?: ToolCall[];
  tool_call_id?: string;
}
interface OpenAiChoice { message: OpenAiMessage }
interface OpenAiResp { choices: OpenAiChoice[] }
interface ModelsItem { id: string }
interface ModelsResp { data: ModelsItem[] }

// ponytail: 显式类型，ArkTS 对象字面量必须对应接口
interface ChatRequestBody {
  model: string;
  messages: ChatMsg[];
  stream: boolean;
  temperature: number;
  max_tokens: number;
  tools?: ToolDef[];
}

export class LlmClient {
  private cfg: AppConfig;

  // 单次响应 token 上限：常量化显式下发，防端点默认值截断长讲解
  static readonly MAX_TOKENS: number = 16384;

  // 智谱内置联网工具（旧特性：netOn + 内置模式时随请求携带）
  static readonly BUILTIN_WEB_SEARCH: ToolDef[] = [{ type: 'web_search', web_search: { enable: true } }];

  constructor(cfg: AppConfig) {
    this.cfg = cfg;
  }

  private base(): string {
    return this.cfg.baseUrl.replace(/\/+$/, '');
  }

  // 构造请求体：stream 固定 false；max_tokens 恒写；tools 非 null 才写（OpenAI 兼容 function calling）
  static buildRequestBody(model: string, messages: ChatMsg[], tools: ToolDef[] | null): string {
    const body: ChatRequestBody = {
      model: model,
      messages: messages,
      stream: false,
      temperature: 0.6,
      max_tokens: LlmClient.MAX_TOKENS
    };
    if (tools !== null) {
      body.tools = tools;
    }
    return JSON.stringify(body);
  }

  // 解析完整 message（含 tool_calls / tool_call_id），供 agent 循环按调用分派
  static extractMessage(jsonStr: string): OpenAiMessage {
    const obj = JSON.parse(jsonStr) as OpenAiResp;
    if (obj.choices.length === 0) { throw new Error('LLM 响应无 choices'); }
    return obj.choices[0].message;
  }

  // 旧名薄包装：只取纯文本 content，供现有调用点编译过渡（后续任务删除）
  static extractContent(jsonStr: string): string {
    return LlmClient.extractMessage(jsonStr).content;
  }

  // 非流式对话；readTimeout 180s（整页 HTML 生成慢）
  // messages 元素可带 tool_calls/tool_call_id（ChatMsg 可选字段，JSON.stringify 天然序列化）
  async chat(messages: ChatMsg[], webSearchTool: boolean): Promise<string> {
    const hr = http.createHttp();
    try {
      const tools: ToolDef[] | null = webSearchTool ? LlmClient.BUILTIN_WEB_SEARCH : null;
      const opt: http.HttpRequestOptions = {
        method: http.RequestMethod.POST,
        header: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${this.cfg.apiKey}` },
        extraData: LlmClient.buildRequestBody(this.cfg.model, messages, tools),
        readTimeout: 180000,
        connectTimeout: 15000,
        expectDataType: http.HttpDataType.STRING
      };
      const resp = await hr.request(`${this.base()}/chat/completions`, opt);
      if (resp.responseCode < 200 || resp.responseCode >= 300) {
        throw new Error(`LLM HTTP ${resp.responseCode}: ${String(resp.result).substring(0, 300)}`);
      }
      return LlmClient.extractMessage(String(resp.result)).content;
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
}
