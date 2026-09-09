// 引导态：无任何消息时的冷启动页（时段问候 + 玻璃快捷 chips；第三个 chip 演示 @eli5 强制技能语法）
// 纯展示组件：点 chips 经 onAsk(question) 上抛，由 Chat 页填输入并触发发送
// 背景由 Chat 页根 Stack 的 WallpaperLayer 负责，本组件自身透明
import { ThemeTokens } from '../theme/ThemeTokens';

// 引导页快捷问题
class Chip {
  static readonly QUESTIONS: string[] =
    ['继续昨天的对话', '帮我写周报', '@eli5 讲讲黑洞'];
}

@Component
export struct GuidePage {
  onAsk: (question: string) => void = (question: string): void => {};
  convCount: number = 0;

  // 时段问候：5-11 早上好 / 11-18 下午好 / 其余 晚上好
  private greeting(): string {
    const h: number = new Date().getHours();
    if (h >= 5 && h < 11) { return '早上好'; }
    if (h >= 11 && h < 18) { return '下午好'; }
    return '晚上好';
  }

  private subtitle(): string {
    const d = new Date();
    const weeks: string[] = ['日', '一', '二', '三', '四', '五', '六'];
    return `${d.getMonth() + 1}月${d.getDate()}日 · 周${weeks[d.getDay()]} · 已有 ${this.convCount} 次对话`;
  }

  build() {
    Column() {
      // 顶部留白：问候区从 24% 高度处起
      Column().width('100%').height('24%')

      Column({ space: 4 }) {
        Text(`${this.greeting()}，`)
          .fontSize(30)
          .fontWeight(FontWeight.Lighter)
          .fontColor($r('app.color.text_hi'))
          .fontFamily('Sora, HarmonyOS Sans')
          .width('100%')
        Text('想聊点什么？')
          .fontSize(30)
          .fontWeight(FontWeight.Medium)
          .fontColor($r('app.color.accent'))
          .fontFamily('Sora, HarmonyOS Sans')
          .width('100%')
        Text(this.subtitle())
          .fontSize(12)
          .fontColor($r('app.color.text_mid'))
          .width('100%')
          .margin({ top: 12 })
      }
      .width('100%')
      .padding({ left: 28, right: 28 })
      .alignItems(HorizontalAlign.Start)

      Flex({ wrap: FlexWrap.Wrap }) {
        ForEach(Chip.QUESTIONS, (q: string) => {
          Text(q)
            .fontSize(12)
            .fontColor($r('app.color.text_mid'))
            .backgroundColor($r('app.color.glass_bg'))
            .backdropBlur(20)
            .border({ width: ThemeTokens.BORDER_W, color: $r('app.color.glass_border') })
            .borderRadius(999)
            .padding({ left: 14, right: 14, top: 8, bottom: 8 })
            .margin({ right: 10, top: 6, bottom: 6 })
            .onClick(() => {
              this.onAsk(q);
            })
        }, (q: string) => q)
      }
      .width('100%')
      .padding({ left: 28, right: 28 })
      .margin({ top: 24 })
    }
    .width('100%')
    .height('100%')
    .alignItems(HorizontalAlign.Start)
  }
}
