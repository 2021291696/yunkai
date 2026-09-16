// 剥标签：HTML→纯文本（去style/script→去标签→解码实体→压空白→截4000字）
export class HtmlExtractor {
  static stripTags(html: string): string {
    let t = html
      .replace(/<style[\s\S]*?<\/style>/gi, ' ')
      .replace(/<script[\s\S]*?<\/script>/gi, ' ')
      .replace(/<[^>]+>/g, ' ');
    t = t.replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'");
    t = t.replace(/\s+/g, ' ').trim();
    return t.length > 4000 ? t.substring(0, 4000) : t;
  }
}
