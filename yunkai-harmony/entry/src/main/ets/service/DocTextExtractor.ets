// 二期文档文本抽取（鸿蒙，纯端上）：docx/xlsx 用 @ohos.zlib 解压 zip 后抽 XML 文本，
// 与 Android DocTextExtractor 语义同构（PDF 为 Android 专属，鸿蒙 PDF 挂 backlog）。
// 流程：picker uri → 复制到沙箱 zip → zlib.decompressFile 解到临时目录 → 读 XML → 正则抽文本 → 清理。
import { zlib } from '@kit.BasicServicesKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { common } from '@kit.AbilityKit';
import { AttachUtil } from './AttachUtil';

export class DocTextExtractor {
  // 统一入口：按后缀分派；txt/md 等按 UTF-8 文本读
  static async extract(ctx: common.Context, name: string, uri: string): Promise<string> {
    const lower: string = name.toLowerCase();
    if (lower.endsWith('.docx')) {
      return await DocTextExtractor.extractOfficeZip(ctx, uri, true);
    }
    if (lower.endsWith('.xlsx')) {
      return await DocTextExtractor.extractOfficeZip(ctx, uri, false);
    }
    return AttachUtil.readTextFile(uri);
  }

  private static readText(path: string): string {
    const f = fs.openSync(path, fs.OpenMode.READ_ONLY);
    try {
      const stat = fs.statSync(f.fd);
      const buf = new ArrayBuffer(stat.size);
      fs.readSync(f.fd, buf, { offset: 0, length: stat.size });
      return util.TextDecoder.create('utf-8').decodeToString(new Uint8Array(buf));
    } finally {
      fs.closeSync(f);
    }
  }

  private static unescape(s: string): string {
    return s.replace(/&lt;/g, '<').replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"').replace(/&apos;/g, "'")
      .replace(/&amp;/g, '&');
  }

  // 解压 → 抽取 → 清理临时目录
  private static async extractOfficeZip(ctx: common.Context, uri: string, isDocx: boolean): Promise<string> {
    const zipPath: string = ctx.filesDir + '/tmp_office.zip';
    const destDir: string = ctx.filesDir + '/tmp_office';
    try {
      // picker uri → 沙箱 zip（zlib 只吃文件路径）
      const src = fs.openSync(uri, fs.OpenMode.READ_ONLY);
      const dst = fs.openSync(zipPath, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      fs.copyFileSync(src.fd, dst.fd);
      fs.closeSync(src);
      fs.closeSync(dst);
      if (fs.accessSync(destDir)) { fs.rmdirSync(destDir); }
      fs.mkdirSync(destDir, true);   // zlib.decompressFile 要求目标目录已存在（900002：destination path is not an existing directory）
      await zlib.decompressFile(zipPath, destDir);
      return isDocx
        ? DocTextExtractor.extractDocxXml(destDir + '/word/document.xml')
        : DocTextExtractor.extractXlsxDir(destDir);
    } finally {
      if (fs.accessSync(zipPath)) { fs.unlinkSync(zipPath); }
      if (fs.accessSync(destDir)) { fs.rmdirSync(destDir); }
    }
  }

  // docx：按段落 <w:p> 切，抽段内全部 <w:t>
  static extractDocxXml(xmlPath: string): string {
    if (!fs.accessSync(xmlPath)) { throw new Error('docx 结构异常：缺少 word/document.xml'); }
    const xml: string = DocTextExtractor.readText(xmlPath);
    const lines: string[] = [];
    const pRe = /<w:p(?:\s[^>]*)?>([\s\S]*?)<\/w:p>/g;
    let m: RegExpExecArray | null = pRe.exec(xml);
    while (m !== null) {
      const body: string = m[1];
      const tRe = /<w:t(?:\s[^>]*)?>([\s\S]*?)<\/w:t>/g;
      let line: string = '';
      let tm: RegExpExecArray | null = tRe.exec(body);
      while (tm !== null) {
        line += DocTextExtractor.unescape(tm[1]);
        tm = tRe.exec(body);
      }
      lines.push(line);
      m = pRe.exec(xml);
    }
    return lines.join('\n').trim();
  }

  // xlsx：sharedStrings 建表 → 各 sheet 单元格按行拼（t="s" 查表；inlineStr 取 <is>）
  static extractXlsxDir(destDir: string): string {
    const shared: string[] = [];
    const ssPath: string = destDir + '/xl/sharedStrings.xml';
    if (fs.accessSync(ssPath)) {
      const ssXml: string = DocTextExtractor.readText(ssPath);
      const siRe = /<si>([\s\S]*?)<\/si>/g;
      let sm: RegExpExecArray | null = siRe.exec(ssXml);
      while (sm !== null) {
        const tRe = /<t(?:\s[^>]*)?>([\s\S]*?)<\/t>/g;
        let val: string = '';
        let tm: RegExpExecArray | null = tRe.exec(sm[1]);
        while (tm !== null) {
          val += DocTextExtractor.unescape(tm[1]);
          tm = tRe.exec(sm[1]);
        }
        shared.push(val);
        sm = siRe.exec(ssXml);
      }
    }
    const wsDir: string = destDir + '/xl/worksheets';
    if (!fs.accessSync(wsDir)) { throw new Error('xlsx 结构异常：缺少工作表'); }
    const sheets: string[] = fs.listFileSync(wsDir)
      .filter((n: string): boolean => n.startsWith('sheet') && n.endsWith('.xml'))
      .sort();
    const out: string[] = [];
    for (let i = 0; i < sheets.length; i++) {
      const sheetXml: string = DocTextExtractor.readText(wsDir + '/' + sheets[i]);
      out.push(`【工作表${i + 1}】`);
      const rowRe = /<row(?:\s[^>]*)?>([\s\S]*?)<\/row>/g;
      let rm: RegExpExecArray | null = rowRe.exec(sheetXml);
      while (rm !== null) {
        const cells: string[] = [];
        const cRe = /<c(\s[^>]*)?>([\s\S]*?)<\/c>/g;
        let cm: RegExpExecArray | null = cRe.exec(rm[1]);
        while (cm !== null) {
          const attrs: string = cm[1] !== undefined ? cm[1] : '';
          const inner: string = cm[2];
          const tMatch: RegExpMatchArray | null = attrs.match(/t="([^"]+)"/);
          const t: string = tMatch !== null ? tMatch[1] : '';
          const vMatch: RegExpMatchArray | null = inner.match(/<v>([\s\S]*?)<\/v>/);
          const v: string = vMatch !== null ? vMatch[1].trim() : '';
          let text: string = '';
          if (t === 's' && v !== '') {
            const idx: number = parseInt(v, 10);
            if (!isNaN(idx) && idx >= 0 && idx < shared.length) { text = shared[idx]; }
          } else if (t === 'inlineStr') {
            const isMatch: RegExpMatchArray | null = inner.match(/<is>([\s\S]*?)<\/is>/);
            const seg: string = isMatch !== null ? isMatch[1] : inner;
            const itRe = /<t(?:\s[^>]*)?>([\s\S]*?)<\/t>/g;
            let im: RegExpExecArray | null = itRe.exec(seg);
            while (im !== null) { text += DocTextExtractor.unescape(im[1]); im = itRe.exec(seg); }
          } else if (v !== '') {
            text = v;
          }
          if (text !== '') { cells.push(text); }
          cm = cRe.exec(rm[1]);
        }
        if (cells.length > 0) { out.push(cells.join('\t')); }
        rm = rowRe.exec(sheetXml);
      }
      out.push('');
    }
    return out.join('\n').trim();
  }
}
