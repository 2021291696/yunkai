// 搜索客户端：博查/Tavily 双 provider，snippet 超 200 字截断
import { http } from '@kit.NetworkKit';
import { AppConfig, SearchHit } from '../model/Types';

interface BochaPage { name: string; url: string; summary?: string; snippet?: string }
interface BochaWebPagesInner { value?: BochaPage[] }
interface BochaWebPages { webPages?: BochaWebPagesInner }
interface BochaResp { code: number; data?: BochaWebPages }
interface TavilyResult { title: string; url: string; content: string }
interface TavilyResp { results: TavilyResult[] }
interface BochaReq { query: string; summary: boolean; count: number }
interface TavilyReq { query: string; max_results: number }

export class SearchClient {
  private cfg: AppConfig;

  constructor(cfg: AppConfig) {
    this.cfg = cfg;
  }

  // 按 cfg.searchProvider 分派
  async search(query: string): Promise<SearchHit[]> {
    if (this.cfg.searchProvider === 'tavily') {
      return SearchClient.searchTavily(this.cfg.searchApiKey, query);
    }
    return SearchClient.searchBocha(this.cfg.searchApiKey, query);
  }

  // 截断 helper：snippet 超 200 字截断
  private static truncate(s: string): string {
    return s.length > 200 ? s.substring(0, 200) : s;
  }

  // POST https://api.bochaai.com/v1/web-search
  // 头 Authorization: Bearer {key}；体 {"query":query,"summary":true,"count":5}
  // 解析 data.webPages.value[]：snippet 取 summary 非空否则 snippet 字段；HTTP≠2xx 抛 `搜索 HTTP ${code}: 前300字`
  static async searchBocha(key: string, query: string): Promise<SearchHit[]> {
    const hr = http.createHttp();
    try {
      const reqBody: BochaReq = { query: query, summary: true, count: 5 };
      const opt: http.HttpRequestOptions = {
        method: http.RequestMethod.POST,
        header: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${key}` },
        extraData: JSON.stringify(reqBody),
        readTimeout: 20000,
        connectTimeout: 15000,
        expectDataType: http.HttpDataType.STRING
      };
      const resp = await hr.request('https://api.bochaai.com/v1/web-search', opt);
      if (resp.responseCode < 200 || resp.responseCode >= 300) {
        throw new Error(`搜索 HTTP ${resp.responseCode}: ${String(resp.result).substring(0, 300)}`);
      }
      return SearchClient.parseBocha(String(resp.result));
    } finally {
      hr.destroy();
    }
  }

  // POST https://api.tavily.com/search
  // 头 Authorization: Bearer {key}；体 {"query":query,"max_results":5}
  // 解析 results[]：snippet 取 content 截断；错误处理同上
  static async searchTavily(key: string, query: string): Promise<SearchHit[]> {
    const hr = http.createHttp();
    try {
      const reqBody: TavilyReq = { query: query, max_results: 5 };
      const opt: http.HttpRequestOptions = {
        method: http.RequestMethod.POST,
        header: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${key}` },
        extraData: JSON.stringify(reqBody),
        readTimeout: 20000,
        connectTimeout: 15000,
        expectDataType: http.HttpDataType.STRING
      };
      const resp = await hr.request('https://api.tavily.com/search', opt);
      if (resp.responseCode < 200 || resp.responseCode >= 300) {
        throw new Error(`搜索 HTTP ${resp.responseCode}: ${String(resp.result).substring(0, 300)}`);
      }
      return SearchClient.parseTavily(String(resp.result));
    } finally {
      hr.destroy();
    }
  }

  // 纯函数：博查响应 → SearchHit[]
  static parseBocha(jsonStr: string): SearchHit[] {
    const obj = JSON.parse(jsonStr) as BochaResp;
    if (obj.data === undefined || obj.data.webPages === undefined || obj.data.webPages.value === undefined) {
      return [];
    }
    const items = obj.data.webPages.value;
    const out: SearchHit[] = [];
    for (let i = 0; i < items.length; i++) {
      const it = items[i];
      const raw = (it.summary !== undefined && it.summary !== '') ? it.summary : (it.snippet !== undefined ? it.snippet : '');
      const hit: SearchHit = {
        title: it.name,
        url: it.url,
        snippet: SearchClient.truncate(raw)
      };
      out.push(hit);
    }
    return out;
  }

  // 纯函数：Tavily 响应 → SearchHit[]
  static parseTavily(jsonStr: string): SearchHit[] {
    const obj = JSON.parse(jsonStr) as TavilyResp;
    const out: SearchHit[] = [];
    for (let i = 0; i < obj.results.length; i++) {
      const it = obj.results[i];
      const hit: SearchHit = { title: it.title, url: it.url, snippet: SearchClient.truncate(it.content) };
      out.push(hit);
    }
    return out;
  }
}
