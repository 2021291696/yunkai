// BM25 检索引擎（忆枢协议 §8，ArkTS 侧同构移植，与 Kotlin Bm25.kt 逐行对齐）。
//
// 切词（§8.1）：ASCII 转小写（仅 A-Z，禁用 String.toLowerCase——那是 Unicode 全集折叠）；
// CJK 段（U+4E00–U+9FFF）二元滑窗、段长 1 单字成 token；连续 ASCII 字母数字段整段一个 token；
// 其余字符仅作分隔。
// 打分（§8.2）：k1=1.2、b=0.75，idf=ln(1+(N-df+0.5)/(df+0.5))，查询 token 去重。
// 新近度（§8.3）：final = bm25 · (1 + 0.1 · recency)，recency = max(0, 1 - ageDays/30)，
// ageDays = max(0, (nowMs - createdAt) / 86400000)（浮点，不取整）。
// 排序（§8.4）：final 降序 → createdAt 新者前 → id 小者前；分数四舍五入到 4 位小数。
// 空查询（含纯标点切词后为空，§8.5）→ 返回最近 topK 条，score 0.0；0 分文档不过滤。

// 语料文档（对应 Kotlin Bm25.Doc）
export interface Doc {
  id: number;
  content: string;
  createdAt: number;
}

// 检索命中（对应 Kotlin Bm25.Hit）
export interface Hit {
  id: number;
  score: number;
}

// 内部打分中间态（对应 Kotlin 局部 data class Scored）
interface Scored {
  doc: Doc;
  score: number;
}

export class Bm25 {
  static readonly K1: number = 1.2;
  static readonly B: number = 0.75;
  static readonly RECENCY_WEIGHT: number = 0.1;

  private static readonly MS_PER_DAY: number = 86400000.0;
  private static readonly RECENCY_WINDOW_DAYS: number = 30.0;

  static isCjk(code: number): boolean {
    return code >= 0x4E00 && code <= 0x9FFF;
  }

  static isAsciiAlnum(code: number): boolean {
    return (code >= 0x61 && code <= 0x7A) || (code >= 0x41 && code <= 0x5A) || (code >= 0x30 && code <= 0x39);
  }

  // 整串转小写（仅 ASCII 范围，CJK 与非 ASCII 不受影响）
  private static lowerAscii(code: number): number {
    if (code >= 0x41 && code <= 0x5A) { return code + 32; }
    return code;
  }

  // 查询 token 去重（保首次出现序，与 Kotlin distinct() 一致）
  private static dedup(tokens: string[]): string[] {
    const out: string[] = [];
    const seen = new Set<string>();
    for (const t of tokens) {
      if (!seen.has(t)) {
        seen.add(t);
        out.push(t);
      }
    }
    return out;
  }

  static tokenize(text: string): string[] {
    const out: string[] = [];
    const n: number = text.length;
    let i = 0;
    while (i < n) {
      const c = Bm25.lowerAscii(text.charCodeAt(i));
      if (Bm25.isAsciiAlnum(c)) {
        // 连续 ASCII 字母数字段整段一个 token（累计小写后字符，与 Kotlin sb.append(lc) 一致）
        let sb = '';
        while (i < n) {
          const lc = Bm25.lowerAscii(text.charCodeAt(i));
          if (!Bm25.isAsciiAlnum(lc)) { break; }
          sb += String.fromCharCode(lc);
          i++;
        }
        out.push(sb);
      } else if (Bm25.isCjk(text.charCodeAt(i))) {
        // 连续 CJK 段：二元滑窗；段长 1 该单字即一个 token
        const start = i;
        while (i < n && Bm25.isCjk(text.charCodeAt(i))) { i++; }
        const run = text.substring(start, i);
        if (run.length === 1) {
          out.push(run);
        } else {
          for (let k = 0; k <= run.length - 2; k++) {
            out.push(run.substring(k, k + 2));
          }
        }
      } else {
        // 其余字符仅作分隔
        i++;
      }
    }
    return out;
  }

  static search(corpus: Doc[], query: string, topK: number, nowMs: number): Hit[] {
    const qTokens = Bm25.dedup(Bm25.tokenize(query));
    if (qTokens.length === 0) {
      // 空查询/纯标点：回退最近 topK 条（createdAt 倒序，并列 id 小者前），score 0.0
      const sorted = corpus.slice().sort((a: Doc, b: Doc): number => {
        if (a.createdAt !== b.createdAt) { return b.createdAt - a.createdAt; }
        return a.id - b.id;
      });
      const hits: Hit[] = [];
      for (let i = 0; i < sorted.length && i < topK; i++) {
        const h: Hit = { id: sorted[i].id, score: 0.0 };
        hits.push(h);
      }
      return hits;
    }
    if (corpus.length === 0) { return []; }

    const tokenized: string[][] = [];
    let totalLen = 0;
    for (const doc of corpus) {
      const toks = Bm25.tokenize(doc.content);
      tokenized.push(toks);
      totalLen += toks.length;
    }
    const n = tokenized.length;
    const avgdl: number = totalLen / n;
    // df(t)：含 t 的文档数（每篇内先去重再计数，与 Kotlin toks.distinct() 一致）
    const df = new Map<string, number>();
    for (const toks of tokenized) {
      for (const t of Bm25.dedup(toks)) {
        df.set(t, (df.get(t) ?? 0) + 1);
      }
    }

    const scored: Scored[] = [];
    for (let dIdx = 0; dIdx < corpus.length; dIdx++) {
      const doc = corpus[dIdx];
      const toks = tokenized[dIdx];
      const tf = new Map<string, number>();
      for (const t of toks) {
        tf.set(t, (tf.get(t) ?? 0) + 1);
      }
      const dl: number = toks.length;
      let bm25 = 0.0;
      for (const t of qTokens) {
        const f = tf.get(t);
        if (f === undefined) { continue; }
        const d = df.get(t) ?? 0;
        const idf = Math.log(1.0 + (n - d + 0.5) / (d + 0.5));
        bm25 += idf * (f * (Bm25.K1 + 1.0)) / (f + Bm25.K1 * (1.0 - Bm25.B + Bm25.B * dl / avgdl));
      }
      const ageDays = Math.max(0.0, (nowMs - doc.createdAt) / Bm25.MS_PER_DAY);
      const recency = Math.max(0.0, 1.0 - ageDays / Bm25.RECENCY_WINDOW_DAYS);
      const s: Scored = { doc: doc, score: bm25 * (1.0 + Bm25.RECENCY_WEIGHT * recency) };
      scored.push(s);
    }

    scored.sort((a: Scored, b: Scored): number => {
      if (a.score !== b.score) { return b.score - a.score; }
      if (a.doc.createdAt !== b.doc.createdAt) { return b.doc.createdAt - a.doc.createdAt; }
      return a.doc.id - b.doc.id;
    });

    const hits: Hit[] = [];
    for (let i = 0; i < scored.length && i < topK; i++) {
      const h: Hit = { id: scored[i].doc.id, score: Bm25.round4(scored[i].score) };
      hits.push(h);
    }
    return hits;
  }

  // 分数四舍五入到 4 位小数（HALF_UP 口径；对拍容差 1e-4，禁依赖 .xxxx5 边界）
  static round4(v: number): number {
    return Math.round(v * 10000.0) / 10000.0;
  }
}
