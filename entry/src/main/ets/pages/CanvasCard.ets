// 画布卡：消息流内嵌 WebView（每个 html 消息一个实例，MVP 接受多实例开销）。
// 用 data:base64 URL 直接作 src：明文 loadData/src 会把 CSS 的 #/% 当 URI 语法截断，必须 base64；
// src 在组件构造期即为最终内容，天然规避 attach/init 双到达顺序问题
// （实测 List 内嵌场景 controller.loadData 在 onControllerAttached 里加载会被布局重建吞掉，
// data-URI src 是稳定路径；HtmlGuard.sanitize 先行剥 script/补 viewport）
import { webview } from '@kit.ArkWeb';
import { buffer } from '@kit.ArkTS';
import { HtmlGuard } from '../service/HtmlGuard';

@Component
export struct CanvasCard {
  html: string = '';
  private dataSrc: string = 'about:blank';
  private webCtrl: webview.WebviewController = new webview.WebviewController();

  aboutToAppear(): void {
    const safe: string | null = HtmlGuard.sanitize(this.html);
    if (safe !== null) {
      const b64: string = buffer.from(safe, 'utf-8').toString('base64');
      this.dataSrc = 'data:text/html;base64,' + b64;
    }
  }

  build() {
    Column() {
      Web({ src: this.dataSrc, controller: this.webCtrl })
        .darkMode(WebDarkMode.Auto)
        .width('100%')
        .height(420)
    }
    .width('100%')
    .borderRadius(16)
    .border({ width: 1, color: '#FFFFFFCC' })
    .backgroundColor('#FFFFFFE6')
  }
}
