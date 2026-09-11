// 会话侧抽屉：会话列表（点击进入/长按删除）。
// 纯展示组件：数据由 Chat 传入，动作经回调上抛（含 onClose），自身不持有路由/DB 逻辑
import { Conv } from '../model/Types';
import { ThemeTokens } from '../theme/ThemeTokens';

@Component
export struct HistoryDrawer {
  @StorageProp('statusBarH') statusBarH: number = 0;
  convs: Conv[] = [];
  onClose: () => void = (): void => {};
  onNewConversation: () => void = (): void => {};
  onOpenSettings: () => void = (): void => {};
  onOpenConversation: (id: number) => void = (id: number): void => {};
  onDeleteConversation: (c: Conv) => void = (c: Conv): void => {};

  private timeLabel(ts: number): string {
    const d = new Date(ts);
    const now: number = Date.now();
    const diffMin: number = Math.floor((now - ts) / 60000);
    if (diffMin < 1) { return '刚刚'; }
    if (diffMin < 60) { return `${diffMin}分钟前`; }
    if (diffMin < 60 * 24) { return `${Math.floor(diffMin / 60)}小时前`; }
    return `${d.getMonth() + 1}-${d.getDate()}`;
  }

  build() {
    Column() {
      // 双层同色叠加：玻璃提实（遮罩+玻璃组合），保留一点透感
      Column() {
      // 左侧让位悬浮 ☰（14 + 34 + 10）；⚙ 设置钮挪顶栏与 ✕ 并排；删常驻「长按可删除」提示
      Row({ space: 10 }) {
        Text('会话')
          .fontSize(18)
          .fontWeight(FontWeight.Medium)
          .fontColor($r('app.color.text_hi'))
          .fontFamily('Sora, HarmonyOS Sans')
          .layoutWeight(1)
        Button('⚙')
          .width(34)
          .height(34)
          .fontSize(16)
          .fontColor($r('app.color.accent'))
          .backgroundColor($r('app.color.glass_bg'))
          .backdropBlur(20)
          .borderRadius(17)
          .border({ width: ThemeTokens.BORDER_W, color: $r('app.color.glass_border') })
          .padding(0)
          .onClick(() => { this.onOpenSettings(); })
        Button('✕')
          .width(34)
          .height(34)
          .fontSize(14)
          .fontColor($r('app.color.text_hi'))
          .backgroundColor($r('app.color.glass_bg'))
          .backdropBlur(20)
          .borderRadius(17)
          .border({ width: ThemeTokens.BORDER_W, color: $r('app.color.glass_border') })
          .padding(0)
          .onClick(() => { this.onClose(); })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .padding({ left: 58, right: 16, top: this.statusBarH + 16, bottom: 16 })

      Row({ space: 10 }) {
        Button('＋ 新对话')
          .fontSize(14)
          .height(34)
          .fontColor('#FFFFFF')
          .backgroundColor($r('app.color.accent'))
          .borderRadius(999)
          .layoutWeight(1)
          .onClick(() => { this.onNewConversation(); })
      }
      .width('100%')
      .padding({ left: 16, right: 16, bottom: 10 })

      if (this.convs.length === 0) {
        Text('暂无会话').fontSize(14).fontColor($r('app.color.text_low')).padding(16)
      } else {
        List({ space: 8 }) {
          ForEach(this.convs, (c: Conv) => {
            ListItem() {
              Column({ space: 4 }) {
                Text(c.title)
                  .fontSize(15)
                  .fontColor($r('app.color.text_hi'))
                  .maxLines(1)
                  .textOverflow({ overflow: TextOverflow.Ellipsis })
                  .width('100%')
                Text(this.timeLabel(c.updatedAt))
                  .fontSize(12)
                  .fontColor($r('app.color.text_low'))
                  .width('100%')
              }
              .alignItems(HorizontalAlign.Start)
              .padding(12)
              .borderRadius(16)
              .backgroundColor($r('app.color.glass_bg'))
              .backdropBlur(ThemeTokens.BLUR_CARD)
              .border({ width: ThemeTokens.BORDER_W, color: $r('app.color.glass_border') })
              .width('100%')
              .onClick(() => { this.onOpenConversation(c.id); })
              .gesture(
                LongPressGesture()
                  .onAction(() => { this.onDeleteConversation(c); })
              )
            }
          }, (c: Conv) => `${c.id}_${c.updatedAt}`)
        }
        .layoutWeight(1)
        .width('100%')
        .padding({ left: 12, right: 12 })
      }
      }
      .width('100%')
      .backgroundColor($r('app.color.glass_bg_strong'))
      .alignItems(HorizontalAlign.Start)
    }
    .width('86%')
    .height('100%')
    .backgroundColor($r('app.color.glass_bg_strong'))
    .backdropBlur(ThemeTokens.BLUR_DOCK)
    .border({ width: { left: ThemeTokens.BORDER_W }, color: $r('app.color.glass_border') })
    .alignItems(HorizontalAlign.Start)
  }
}
