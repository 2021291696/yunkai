// 数据类型与枚举：全 app 共用的最小类型集合
export interface ChatMsg { role: string; content: string }
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

export enum NetMode { BUILTIN, EXTERNAL, NONE }
