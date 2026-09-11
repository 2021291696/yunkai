// 一期附件工具：图片压缩→base64、文本读取（≤5MB/3万字截断）
// 抽到 service 层便于设备单测直接覆盖（Chat 页只调用，不含实现）
import { image } from '@kit.ImageKit';
import { util } from '@kit.ArkTS';
import { fileIo as fs } from '@kit.CoreFileKit';

export class AttachUtil {
  // 图片压缩：长边 ≤1280 → JPEG(85) → base64（与 Android 语义一致）。
  // 优先走 fd：实测 image.createImageSource(file:// uri) 在设备上返回 undefined（单测 attach_compress 命中），
  // fd 是官方推荐路径；picker 返回的 uri 若 fd 打不开再回退 uri 形式
  static async compressToB64(uri: string): Promise<string> {
    let file: fs.File | null = null;
    let src: image.ImageSource | null = null;
    try {
      file = fs.openSync(uri, fs.OpenMode.READ_ONLY);
      src = image.createImageSource(file.fd);
    } catch (e) {
      src = image.createImageSource(uri);
    }
    if (src === null || src === undefined) {
      if (file !== null) { fs.closeSync(file); }
      throw new Error('图片解码失败');
    }
    try {
      const info: image.ImageInfo = await src.getImageInfo();
      const maxSide: number = 1280;
      const longSide: number = Math.max(info.size.width, info.size.height);
      const scale: number = longSide > maxSide ? maxSide / longSide : 1;
      const dw: number = Math.max(1, Math.floor(info.size.width * scale));
      const dh: number = Math.max(1, Math.floor(info.size.height * scale));
      const pm: image.PixelMap = await src.createPixelMap({ desiredSize: { width: dw, height: dh } });
      const packer: image.ImagePacker = image.createImagePacker();
      const buf: ArrayBuffer = await packer.packing(pm, { format: 'image/jpeg', quality: 85 });
      await pm.release();
      await packer.release();
      return new util.Base64Helper().encodeToStringSync(new Uint8Array(buf));
    } finally {
      await src.release();
      if (file !== null) { fs.closeSync(file); }
    }
  }

  // txt 读取：≤5MB；正文 3 万字截断（超出加标注）
  static readTextFile(uri: string): string {
    const file = fs.openSync(uri, fs.OpenMode.READ_ONLY);
    try {
      const stat = fs.statSync(file.fd);
      if (stat.size > 5 * 1024 * 1024) { throw new Error('文件超过 5MB 上限'); }
      const buf = new ArrayBuffer(stat.size);
      fs.readSync(file.fd, buf, { offset: 0, length: stat.size });
      let text: string = util.TextDecoder.create('utf-8').decodeToString(new Uint8Array(buf));
      if (text.length > 30000) { text = text.substring(0, 30000) + '\n…（已截断）'; }
      return text;
    } finally {
      fs.closeSync(file);
    }
  }
}
