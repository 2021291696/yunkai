// 数据类型与枚举：全 app 共用的最小类型集合
export interface ChatMsg {
  role: string;
  content: string;
  tool_calls?: ToolCall[];   // assistant 发起工具调用时携带
  tool_call_id?: string;     // role=tool 回传结果时对应调用 id
}

// OpenAI 兼容 function calling 协议
export interface FunctionCall {
  name: string;
  arguments: string; // JSON 字符串
}
export interface ToolCall {
  id: string;
  type: string; // 'function'
  function: FunctionCall;
}
// 工具定义（请求体 tools[] 元素）；web_search 字段兼容智谱内置联网工具（旧特性过渡）
export interface WebSearchEnable { enable: boolean }
export interface JsonSchema {
  type: string;
  properties?: Record<string, object>;
  required?: string[];
}
export interface FunctionDef {
  name: string;
  description: string;
  parameters?: JsonSchema;
}
export interface ToolDef {
  type: string;
  function?: FunctionDef;
  web_search?: WebSearchEnable;
}

export interface SearchHit { title: string; url: string; snippet: string }

export class Conv {
  id: number = 0;
  title: string = '';
  updatedAt: number = 0;
}

export class Msg {
  id: number = 0;
  convId: number = 0;
  role: string = '';
  content: string = '';   // user=问题原文; assistant=HTML 原文
  plain: string = '';     // assistant 剥标签纯文本
  turnNo: number = 0;     // assistant 从 1 递增
  createdAt: number = 0;
}

export class AppConfig {
  baseUrl: string = '';
  apiKey: string = '';
  model: string = '';
  searchMode: string = 'auto';        // auto | builtin | external
  searchProvider: string = 'bing';    // bing(免key爬取) | bocha | tavily
  searchApiKey: string = '';
}

// Agent 技能配方：skills 表一行（内置 eli5 与用户自建技能共用）
export class AgentSkill {
  id: number = 0;
  name: string = '';
  description: string = '';
  content: string = '';
  builtin: boolean = false;
}

export enum NetMode { BUILTIN, EXTERNAL, NONE }
