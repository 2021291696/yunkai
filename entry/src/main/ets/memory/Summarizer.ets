// 会话摘要（忆枢协议 §4.4）：纯函数部分集中于此（可单测），LLM 调用由调用方完成。
// ArkTS 侧同构移植，与 Kotlin Summarizer.kt 逐行对齐；Msg 复用既有 model/Types（字段同构）。
// 换出边界语义（协议 §4.1/4.4，M2 钉死）：
// - messages 表 user 行 turn_no 恒 0，无法按 turn_no 过滤窗口 → 按「行序」定义：
//   截断点 = turn_no ≤ untilTurn 的最后一条 assistant 行，其前导 user 行与该行一起属已摘要跨度；
// - 窗口 = 截断点之后的全部行（untilTurn=0 或找不到截断点 → 全量窗口）；
// - 触发（§2 常量）：窗口内 assistant 轮数 > 12 或窗口字符总量 > 16000；
// - 摘要跨度 = 窗口内最旧的一半轮次（向上取整），到该半块最后一条 assistant 行（含其前导 user 行）；
// - 追加：多次摘要以 \n 连接；单次摘要文本 ≤300 字。
import { Msg } from '../model/Types';

// 摘要跨度（对应 Kotlin Summarizer.Span）
export interface Span {
  rows: Msg[];
  endTurnNo: number;
}

export class Summarizer {
  static readonly SUMMARY_TURN_THRESHOLD: number = 12;
  static readonly SUMMARY_CHAR_THRESHOLD: number = 16000;
  static readonly SUMMARY_MAX_CHARS: number = 300;

  static readonly SUMMARY_PROMPT: string =
    '请把以下对话压缩成不超过300字的中文摘要，保留关键事实、用户偏好、结论与未尽事项，' +
    '用平铺陈述句，不要逐句罗列，不要添加对话中没有的内容。';

  // 窗口计算：见类头注释。untilTurn≤0 → 全量窗口
  static windowRows(rows: Msg[], untilTurn: number): Msg[] {
    if (untilTurn <= 0) { return rows; }
    let cut = -1;
    for (let i = 0; i < rows.length; i++) {
      const m = rows[i];
      if (m.role === 'assistant' && m.turnNo <= untilTurn) { cut = i; }
    }
    return cut < 0 ? rows : rows.slice(cut + 1);
  }

  // 触发判定（§4.4）：轮数超阈值或字符超阈值。轮数按 assistant 行计（一行=一轮）。
  static shouldTrigger(window: Msg[]): boolean {
    let turns = 0;
    for (const m of window) {
      if (m.role === 'assistant') { turns++; }
    }
    if (turns > Summarizer.SUMMARY_TURN_THRESHOLD) { return true; }
    let chars = 0;
    for (const m of window) {
      chars += m.role.length + 1 + m.content.length;
    }
    return chars > Summarizer.SUMMARY_CHAR_THRESHOLD;
  }

  // 待摘要跨度：窗口最旧一半轮次（向上取整）；不足 2 轮无摘要价值返回 null（防御，阈值前不会被调到）
  static spanToSummarize(window: Msg[]): Span | null {
    const turnIdx: number[] = [];
    for (let i = 0; i < window.length; i++) {
      if (window[i].role === 'assistant') { turnIdx.push(i); }
    }
    if (turnIdx.length < 2) { return null; }
    const half = Math.floor((turnIdx.length + 1) / 2);
    const endIdx = turnIdx[half - 1];
    const span: Span = { rows: window.slice(0, endIdx + 1), endTurnNo: window[endIdx].turnNo };
    return span;
  }

  // 摘要请求正文：assistant 行优先用 plain（防大 HTML 撑爆摘要请求）
  static buildPrompt(span: Msg[]): string {
    const lines: string[] = [];
    for (const m of span) {
      if (m.role === 'user') {
        lines.push(`用户：${m.content}`);
      } else {
        lines.push(`助手：${m.plain !== '' ? m.plain : m.content}`);
      }
    }
    return `${Summarizer.SUMMARY_PROMPT}\n\n${lines.join('\n')}`;
  }

  // 多次摘要拼接：旧摘要空 → 直接用新增；否则 \n 连接（§4.4）
  // 注：Kotlin isBlank 为 Java Character.isWhitespace 口径（NBSP 等 no-break 空白不算空白），
  // JS trim 覆盖 White_Space 全集；NBSP-only 字符串双端分叉，协议用例未涉及（见汇报）。
  static joinSummary(oldSummary: string, addition: string): string {
    if (oldSummary.trim() === '') { return addition; }
    return `${oldSummary}\n${addition}`;
  }
}
