// 渲染形态判定：一条回答该渲染成画布卡还是文本气泡的唯一入口。
// 前缀 <!DOCTYPE（大小写不敏感）或 <html → 'html'，否则 'text'；
// 画布/气泡双形态、存量旧 eli5 HTML 轮的兼容渲染都走这里。
export class ReplyKind {
  static readonly HTML: string = 'html';
  static readonly TEXT: string = 'text';

  static detect(content: string): string {
    const t: string = content.trim().toLowerCase();
    if (t.startsWith('<!doctype') || t.startsWith('<html')) { return ReplyKind.HTML; }
    return ReplyKind.TEXT;
  }
}

// HTML 兜底：剥 markdown 围栏、校验、补 viewport、长度限制、READ_HTML 标记识别
export class HtmlGuard {
  // 剥```围栏→校验<!DOCTYPE html或<html(不区分大小写)→剥<script>块→缺viewport则注入→超300000字返回null→非法返回null
  // script 剥离是安全硬化：eli5 页面无需 JS，模型幻觉/被注入内容夹带的脚本不得在 WebView 执行
  static sanitize(raw: string): string | null {
    let t = raw.trim();
    if (t.startsWith('```')) {
      t = t.replace(/^```[a-zA-Z]*\s*/, '').replace(/```\s*$/, '').trim();
    }
    const lower = t.toLowerCase();
    if (!lower.startsWith('<!doctype html') && !lower.includes('<html')) { return null; }
    t = t.replace(/<script[\s\S]*?<\/script\s*>/gi, '');
    t = t.replace(/<script[^>]*\/?>/gi, '');
    if (!lower.includes('name="viewport"')) {
      t = t.replace(/<head([^>]*)>/i, '<head$1><meta name="viewport" content="width=device-width, initial-scale=1.0">');
      if (!t.toLowerCase().includes('<head')) {
        t = t.replace(/<html([^>]*)>/i, '<html$1><head><meta name="viewport" content="width=device-width, initial-scale=1.0"></head>');
      }
    }
    if (t.length > 300000) { return null; }
    return t;
  }

  // 整行匹配 ^\s*\[\[READ_HTML:(\d+)\]\]\s*$ 返回轮次号，否则 null
  static detectReadMarker(text: string): number | null {
    const m = text.trim().match(/^\[\[READ_HTML:(\d+)\]\]$/);
    if (m === null || m.length < 2) { return null; }
    return parseInt(m[1], 10);
  }
}
