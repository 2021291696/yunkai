// LLM 客户端：OpenAI 兼容 /chat/completions 与 /models，支持智谱内置联网 tools
import { http } from '@kit.NetworkKit';
import { AppConfig, ChatMsg } from '../model/Types';

interface OpenAiMessage { content: string }
interface OpenAiChoice { message: OpenAiMessage }
interface OpenAiResp { choices: OpenAiChoice[] }
interface ModelsItem { id: string }
interface ModelsResp { data: ModelsItem[] }

// ponytail: 显式类型，ArkTS 对象字面量必须对应接口
interface WebSearchEnable { enable: boolean }
interface WebSearchTool { type: string; web_search: WebSearchEnable }
interface ChatRequestBody {
  model: string;
  messages: ChatMsg[];
  stream: boolean;
  temperature: number;
  tools?: WebSearchTool[];
}

export class LlmClient {
  private cfg: AppConfig;

  constructor(cfg: AppConfig) {
    this.cfg = cfg;
  }

  private base(): string {
    return this.cfg.baseUrl.replace(/\/+$/, '');
  }

  // 构造请求体：stream 固定 false；webSearchTool=true 时带智谱内置联网 tools
  static buildRequestBody(model: string, messages: ChatMsg[], webSearchTool: boolean): string {
    const body: ChatRequestBody = {
      model: model,
      messages: messages,
      stream: false,
      temperature: 0.6
    };
    if (webSearchTool) {
      const tools: WebSearchTool[] = [{ type: 'web_search', web_search: { enable: true } }];
      body.tools = tools;
    }
    return JSON.stringify(body);
  }

  static extractContent(jsonStr: string): string {
    const obj = JSON.parse(jsonStr) as OpenAiResp;
    if (obj.choices.length === 0) { throw new Error('LLM 响应无 choices'); }
    return obj.choices[0].message.content;
  }

  // 非流式对话；readTimeout 180s（整页 HTML 生成慢）
  async chat(messages: ChatMsg[], webSearchTool: boolean): Promise<string> {
    const hr = http.createHttp();
    try {
      const opt: http.HttpRequestOptions = {
        method: http.RequestMethod.POST,
        header: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${this.cfg.apiKey}` },
        extraData: LlmClient.buildRequestBody(this.cfg.model, messages, webSearchTool),
        readTimeout: 180000,
        connectTimeout: 15000,
        expectDataType: http.HttpDataType.STRING
      };
      const resp = await hr.request(`${this.base()}/chat/completions`, opt);
      if (resp.responseCode < 200 || resp.responseCode >= 300) {
        throw new Error(`LLM HTTP ${resp.responseCode}: ${String(resp.result).substring(0, 300)}`);
      }
      return LlmClient.extractContent(String(resp.result));
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
