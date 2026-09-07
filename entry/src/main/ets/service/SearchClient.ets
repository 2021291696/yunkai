// 搜索客户端：博查/Tavily/必应爬取 三 provider，snippet 超 200 字截断
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

  // 按 cfg.searchProvider 分派（bing 爬取无需 key）
  // 必应对整句自然语言查询易被疑问词带偏（实测「这周国际上有什么科技大事」搜出单字释义），
  // 只对 bing 做关键词提取；博查/Tavily 本身支持自然语言查询保留原句
  async search(query: string): Promise<SearchHit[]> {
    if (this.cfg.searchProvider === 'tavily') {
      return SearchClient.searchTavily(this.cfg.searchApiKey, query);
    }
    if (this.cfg.searchProvider === 'bing') {
      return SearchClient.searchBing(SearchClient.extractKeywords(query));
    }
    return SearchClient.searchBocha(this.cfg.searchApiKey, query);
  }

  // 疑问词/时间词/口语句式停用词：整句提问剥掉后剩核心名词，搜索引擎召回质量更高
  private static readonly STOPWORDS: string[] = [
    '为什么', '怎么回事', '怎么样', '怎样', '怎么办', '如何', '是什么', '什么是',
    '有什么', '有哪些', '有没有', '是不是', '哪一个', '哪些', '哪个', '哪里', '哪儿',
    '这周', '本周', '上周', '最近', '日前', '请问', '告诉我', '解释一下',
    '介绍一下', '想知道', '想弄懂', '想了解', '讲讲', '说说', '到底', '究竟', '一下',
    '我', '你', '他', '她', '它', '我们', '你们', '吗', '呢', '吧', '啊', '呀',
    '的', '了', '是', '有', '和', '与', '或', '都', '也', '很', '被', '把', '让', '对', '关于'
  ];

  // 纯函数：自然语言问题 → 空格分隔的关键词串；全被停用词吃掉则回退原句
  static extractKeywords(q: string): string {
    let t = q.trim();
    for (let i = 0; i < SearchClient.STOPWORDS.length; i++) {
      t = t.split(SearchClient.STOPWORDS[i]).join(' ');
    }
    // 剥中英文标点，避免「？」等混进搜索词
    t = t.replace(/[，。？！、：；""''（）《》【】,?!:;'"()[\]{}]/g, ' ');
    const parts: string[] = [];
    const raw = t.split(/\s+/);
    for (let i = 0; i < raw.length; i++) {
      if (raw[i] !== '' && parts.indexOf(raw[i]) < 0) {
        parts.push(raw[i]);
      }
    }
    if (parts.length === 0) { return q.trim(); }
    return parts.slice(0, 6).join(' ');
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

  // GET https://cn.bing.com/search?q={query}&mkt=zh-CN（本地爬取，免 key，国内直连稳）
  // 解析 li.b_algo 块：h2>a[href] 取标题与链接，p 取摘要；HTTP≠2xx 抛错同风格
  static async searchBing(query: string): Promise<SearchHit[]> {
    const hr = http.createHttp();
    try {
      const url = 'https://cn.bing.com/search?q=' + encodeURIComponent(query) + '&mkt=zh-CN';
      const opt: http.HttpRequestOptions = {
        method: http.RequestMethod.GET,
        header: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36',
          'Accept-Language': 'zh-CN,zh;q=0.9'
        },
        readTimeout: 20000,
        connectTimeout: 15000,
        expectDataType: http.HttpDataType.STRING
      };
      const resp = await hr.request(url, opt);
      if (resp.responseCode < 200 || resp.responseCode >= 300) {
        throw new Error(`搜索 HTTP ${resp.responseCode}: ${String(resp.result).substring(0, 300)}`);
      }
      return SearchClient.parseBing(String(resp.result));
    } finally {
      hr.destroy();
    }
  }

  // 实体解码：命名实体 + 数字实体
  static decodeEntities(s: string): string {
    let t = s
      .replace(/<[^>]+>/g, '')
      .replace(/&ensp;/g, ' ').replace(/&emsp;/g, ' ').replace(/&nbsp;/g, ' ')
      .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&middot;/g, '·')
      .replace(/&#0183;/g, '·').replace(/&#160;/g, ' ').replace(/&#183;/g, '·');
    // 剩余数字实体 &#NNNN;
    t = t.replace(/&#(\d+);/g, (m: string, p1: string): string => {
      return String.fromCharCode(parseInt(p1, 10));
    });
    return t.replace(/\s+/g, ' ').trim();
  }

  // 纯函数：必应结果页 HTML → SearchHit[]（前 5 条）
  static parseBing(html: string): SearchHit[] {
    const out: SearchHit[] = [];
    const blockRe = new RegExp('<li class="b_algo"[\\s\\S]*?<h2[^>]*><a[^>]*href="([^"]+)"[^>]*>([\\s\\S]*?)</a>[\\s\\S]*?</li>', 'gi');
    const snipRe = new RegExp('<p[^>]*>([\\s\\S]*?)</p>', 'i');
    let m: RegExpExecArray | null = blockRe.exec(html);
    while (m !== null && out.length < 5) {
      const url: string = m[1];
      const title: string = SearchClient.decodeEntities(m[2]);
      const snipMatch = snipRe.exec(m[0]);
      let snippet = '';
      if (snipMatch !== null) {
        snippet = SearchClient.decodeEntities(snipMatch[1]);
      }
      if (title !== '' && url !== '') {
        const hit: SearchHit = { title: title, url: url, snippet: SearchClient.truncate(snippet) };
        out.push(hit);
      }
      m = blockRe.exec(html);
    }
    return out;
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
