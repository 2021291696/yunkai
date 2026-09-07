// 画布卡：消息流内嵌 WebView（每个 html 消息一个实例，MVP 接受多实例开销）。
// 用 data:base64 URL 直接作 src：明文 loadData/src 会把 CSS 的 #/% 当 URI 语法截断，必须 base64；
// src 在组件构造期即为最终内容，天然规避 attach/init 双到达顺序问题
// （实测 List 内嵌场景 controller.loadData 在 onControllerAttached 里加载会被布局重建吞掉，
// data-URI src 是稳定路径；HtmlGuard.sanitize 先行剥 script/补 viewport）。
// 【M2 待办】List 滚动会销毁/重建 ListItem 导致画布卡（Web 实例）反复重建、滚动卡顿——
//   可改 LazyForEach + 缓存渲染快照，或进全屏画布页复用单实例。
import { webview } from '@kit.ArkWeb';
import { buffer } from '@kit.ArkTS';
import { HtmlGuard } from '../service/HtmlGuard';

@Component
export struct CanvasCard {
  html: string = '';
  // 点击卡头进入全屏画布页（由父组件路由；Web 区域手势被 WebView 吃掉，入口做在卡头条上）
  onOpen: () => void = (): void => {};
  private dataSrc: string = 'about:blank';
  private invalid: boolean = false;
  private webCtrl: webview.WebviewController = new webview.WebviewController();

  aboutToAppear(): void {
    const safe: string | null = HtmlGuard.sanitize(this.html);
    if (safe === null) {
      this.invalid = true;
      return;
    }
    const b64: string = buffer.from(safe, 'utf-8').toString('base64');
    this.dataSrc = 'data:text/html;base64,' + b64;
  }

  build() {
    Column() {
      // 卡头：全屏入口条
      Row({ space: 6 }) {
        Text('📘').fontSize(13)
        Text('画布 · 点此全屏查看')
          .fontSize(12)
          .fontColor('#8A7A5A')
          .layoutWeight(1)
        Text('⤢').fontSize(14).fontColor('#B25E00')
      }
      .width('100%')
      .padding({ left: 12, right: 12, top: 8, bottom: 8 })
      .onClick(() => { this.onOpen(); })

      if (this.invalid) {
        // sanitize 失败兜底：显示占位而非空白卡
        Column() {
          Text('⚠️ 内容无法渲染')
            .fontSize(14)
            .fontColor('#A08C66')
        }
        .width('100%')
        .height(420)
        .justifyContent(FlexAlign.Center)
      } else {
        Web({ src: this.dataSrc, controller: this.webCtrl })
          .darkMode(WebDarkMode.Auto)
          .width('100%')
          .height(420)
      }
    }
    .width('100%')
    .borderRadius(16)
    .border({ width: 1, color: '#FFFFFFCC' })
    .backgroundColor('#FFFFFFE6')
  }
}
