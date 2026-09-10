// 全屏画布页：整屏 Web 展示消息流画布卡对应的 HTML。
// html 经 AppStorage 暂存传入（可能 15-60KB，router params 传大字符串不可靠）；
// 复用与 CanvasCard 相同的 sanitize + data:base64 src 方案
import { router } from '@kit.ArkUI';
import { webview } from '@kit.ArkWeb';
import { buffer } from '@kit.ArkTS';
import { HtmlGuard } from '../service/HtmlGuard';

const CANVAS_HTML_KEY: string = 'canvasHtml';

@Entry
@Component
struct CanvasPage {
  @State src: string = 'about:blank';
  @State invalid: boolean = false;
  private webCtrl: webview.WebviewController = new webview.WebviewController();

  aboutToAppear(): void {
    const raw: string | undefined = AppStorage.get<string>(CANVAS_HTML_KEY);
    const safe: string | null = raw === undefined ? null : HtmlGuard.sanitize(raw);
    if (safe === null) {
      this.invalid = true;
      return;
    }
    const b64: string = buffer.from(safe, 'utf-8').toString('base64');
    this.src = 'data:text/html;base64,' + b64;
  }

  build() {
    Stack() {
      Column() {
        // ===== 顶栏 =====
        Row({ space: 10 }) {
          Text('‹ 返回')
            .fontSize(16)
            .fontColor('#B25E00')
            .onClick(() => {
              router.back();
            })
          Text('画布')
            .fontSize(19)
            .fontWeight(FontWeight.Medium)
            .fontColor('#3D3325')
            .layoutWeight(1)
        }
        .width('100%')
        .padding({ left: 14, right: 14, top: 12, bottom: 10 })
        .backgroundColor('#F2EADCB0')
        .backgroundBlurStyle(BlurStyle.Thin)
        .border({ width: { bottom: 1 }, color: '#FFFFFFCC' })

        // ===== 画布主体 =====
        if (this.invalid) {
          Column({ space: 10 }) {
            Text('内容无法渲染')
              .fontSize(16)
              .fontColor('#A08C66')
          }
          .width('100%')
          .layoutWeight(1)
          .justifyContent(FlexAlign.Center)
        } else {
          Web({ src: this.src, controller: this.webCtrl })
            .darkMode(WebDarkMode.Auto)
            .width('100%')
            .layoutWeight(1)
        }
      }
      .width('100%')
      .height('100%')
      .backgroundColor('#FAF6EF')
    }
    .width('100%')
    .height('100%')
  }
}
