// B2 轻量 markdown 渲染（与 Android MarkdownLite.kt 语义对齐）：
// 行内 **bold** / *italic* / `code`；块级 #/##/### 标题、- 与 * 列表。
// 只覆盖讲解页高频语法，不追求完整 GFM——复杂结构（表格/嵌套列表/围栏代码块）保持纯文本不丢内容。
// 用法：MarkdownText({ text: m.content }) 套用气泡 modifier 链（背景/圆角/边框照旧挂在组件上）。

// 行内片段：一段连续同风格文本
export class MdSpan {
  text: string = '';
  bold: boolean = false;
  italic: boolean = false;
  code: boolean = false;
}

// 块级行：kind 'h' 标题 / 'li' 列表 / 'p' 普通段 / 'blank' 空行
export class MdLine {
  kind: string = 'p';
  body: string = '';
}

// 行分类 + 剥前缀（trimEnd 后判定，尾随空格不影响）
export function mdLineKind(line: string): string {
  const t: string = line.trimEnd();
  if (t === '') { return 'blank'; }
  if (t.startsWith('### ') || t.startsWith('## ') || t.startsWith('# ')) { return 'h'; }
  if (t.startsWith('- ') || t.startsWith('* ')) { return 'li'; }
  return 'p';
}

export function mdStrip(line: string): string {
  const t: string = line.trimEnd();
  if (t.startsWith('### ')) { return t.substring(4); }
  if (t.startsWith('## ')) { return t.substring(3); }
  if (t.startsWith('# ')) { return t.substring(2); }
  if (t.startsWith('- ') || t.startsWith('* ')) { return t.substring(2); }
  return t;
}

// 行内解析：**bold**、`code`、*italic*（code 内不解析其他标记；未闭合标记按原字输出不丢内容）
export function mdInlineSpans(text: string): MdSpan[] {
  const out: MdSpan[] = [];
  let buf: string = '';
  let i: number = 0;
  const flush = (): void => {
    if (buf !== '') {
      const s: MdSpan = new MdSpan();
      s.text = buf;
      out.push(s);
      buf = '';
    }
  };
  while (i < text.length) {
    if (text.startsWith('**', i)) {
      const end: number = text.indexOf('**', i + 2);
      if (end > i + 1) {
        flush();
        const s: MdSpan = new MdSpan();
        s.text = text.substring(i + 2, end);
        s.bold = true;
        out.push(s);
        i = end + 2;
      } else { buf += text[i]; i++; }
    } else if (text[i] === '`') {
      const end: number = text.indexOf('`', i + 1);
      if (end > i + 1) {
        flush();
        const s: MdSpan = new MdSpan();
        s.text = text.substring(i + 1, end);
        s.code = true;
        out.push(s);
        i = end + 1;
      } else { buf += text[i]; i++; }
    } else if (text[i] === '*' && (i + 1 >= text.length || text[i + 1] !== '*')) {
      const end: number = text.indexOf('*', i + 1);
      if (end > i + 1) {
        flush();
        const s: MdSpan = new MdSpan();
        s.text = text.substring(i + 1, end);
        s.italic = true;
        out.push(s);
        i = end + 1;
      } else { buf += text[i]; i++; }
    } else {
      buf += text[i];
      i++;
    }
  }
  flush();
  return out;
}

// assistant 文本气泡/流式气泡的 markdown 渲染：逐行分块，行内 Span 着风格。
// 颜色与字体随使用处 Text 属性继承（气泡里是 text_hi + Sora）。
@Component
export struct MarkdownText {
  @Prop text: string = '';

  @Builder
  spansOf(body: string) {
    ForEach(mdInlineSpans(body), (s: MdSpan, i: number) => {
      if (s.bold && s.code) {
        Span(s.text).fontWeight(FontWeight.Bold).fontFamily('monospace')
      } else if (s.bold) {
        Span(s.text).fontWeight(FontWeight.Bold)
      } else if (s.italic) {
        Span(s.text).fontStyle(FontStyle.Italic)
      } else if (s.code) {
        Span(s.text).fontFamily('monospace')
      } else {
        Span(s.text)
      }
    }, (s: MdSpan, i: number) => `${i}_${s.text}`)
  }

  @Builder
  lineNode(ln: MdLine) {
    if (ln.kind === 'h') {
      Text(ln.body)
        .fontSize(16)
        .lineHeight(25)
        .fontWeight(FontWeight.Bold)
        .fontColor($r('app.color.text_hi'))
        .fontFamily('Sora, HarmonyOS Sans')
    } else if (ln.kind === 'li') {
      Text() {
        Span('• ')
        this.spansOf(ln.body)
      }
        .fontSize(14)
        .lineHeight(23)
        .fontColor($r('app.color.text_hi'))
        .fontFamily('Sora, HarmonyOS Sans')
    } else if (ln.kind === 'blank') {
      Text(' ')
        .fontSize(14)
        .lineHeight(14)
    } else {
      Text() {
        this.spansOf(ln.body)
      }
        .fontSize(14)
        .lineHeight(23)
        .fontColor($r('app.color.text_hi'))
        .fontFamily('Sora, HarmonyOS Sans')
    }
  }

  private lineList(): MdLine[] {
    const parts: string[] = this.text.split('\n');
    const out: MdLine[] = [];
    for (const line of parts) {
      const ln: MdLine = new MdLine();
      ln.kind = mdLineKind(line);
      ln.body = mdStrip(line);
      out.push(ln);
    }
    return out;
  }

  build() {
    Column() {
      ForEach(this.lineList(), (ln: MdLine, i: number) => {
        this.lineNode(ln)
      }, (ln: MdLine, i: number) => `ln_${i}`)
    }
    .alignItems(HorizontalAlign.Start)
  }
}
