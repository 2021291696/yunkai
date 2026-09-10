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

// HTML 兜底：剥 markdown 围栏、校验、补 viewport、长度限制
export class HtmlGuard {
  // 剥```围栏→校验<!DOCTYPE html或<html(不区分大小写)→剥<script>块/内联on*=/javascript:伪协议/<iframe>
  // →缺viewport则注入→超300000字返回null→非法返回null
  // 安全面：模型幻觉或 read_web 拓回的不可信内容夹带的脚本/事件处理器/伪协议 URL 不得在 WebView 生效
  static sanitize(raw: string): string | null {
    let t = raw.trim();
    if (t.startsWith('```')) {
      t = t.replace(/^```[a-zA-Z]*\s*/, '').replace(/```\s*$/, '').trim();
    }
    const lower = t.toLowerCase();
    if (!lower.startsWith('<!doctype html') && !lower.includes('<html')) { return null; }
    t = t.replace(/<script[\s\S]*?<\/script\s*>/gi, '');
    t = t.replace(/<script[^>]*\/?>/gi, '');
    // 内联事件属性：onerror= / onload= 等（引号包与裸值两种形态）
    t = t.replace(/\son[a-zA-Z]+\s*=\s*"[^"]*"/gi, '');
    t = t.replace(/\son[a-zA-Z]+\s*=\s*'[^']*'/gi, '');
    t = t.replace(/\son[a-zA-Z]+\s*=\s*[^\s>]+/gi, '');
    // 伪协议 URL（href/src/action 等属性值）；命中即整个属性值置空
    t = t.replace(/(\s(?:href|src|action|xlink:href)\s*=\s*)(?:"[^"]*(?:javascript|vbscript)[^"]*"|'[^']*(?:javascript|vbscript)[^']*'|(?:javascript|vbscript):[^\s>]+)/gi, '$1""');
    // iframe 整块剥（嵌套的外部页面不可控）
    t = t.replace(/<iframe[\s\S]*?<\/iframe\s*>/gi, '');
    t = t.replace(/<iframe[^>]*\/?>/gi, '');
    if (!lower.includes('name="viewport"')) {
      t = t.replace(/<head([^>]*)>/i, '<head$1><meta name="viewport" content="width=device-width, initial-scale=1.0">');
      if (!t.toLowerCase().includes('<head')) {
        t = t.replace(/<html([^>]*)>/i, '<html$1><head><meta name="viewport" content="width=device-width, initial-scale=1.0"></head>');
      }
    }
    if (t.length > 300000) { return null; }
    return t;
  }
}
