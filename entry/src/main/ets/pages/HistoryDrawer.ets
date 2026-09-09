// 会话与历史侧抽屉：上半会话列表（点击进入/长按删除）+ 下半当前会话历史轮次（点击滚动定位）。
// 纯展示组件：数据由 Chat 传入，动作经回调上抛（含 onClose），自身不持有路由/DB 逻辑
import { Conv, Msg } from '../model/Types';
import { ThemeTokens } from '../theme/ThemeTokens';

@Component
export struct HistoryDrawer {
  convs: Conv[] = [];
  turns: Msg[] = [];
  onClose: () => void = (): void => {};
  onNewConversation: () => void = (): void => {};
  onOpenSettings: () => void = (): void => {};
  onOpenConversation: (id: number) => void = (id: number): void => {};
  onDeleteConversation: (c: Conv) => void = (c: Conv): void => {};
  onOpenTurn: (turnNo: number) => void = (turnNo: number): void => {};

  // 从 turns 里找该轮对应的问题（轮次列表展示用）
  private questionOf(turnNo: number): string {
    let q = '';
    for (let i = 0; i < this.turns.length; i++) {
      const m: Msg = this.turns[i];
      if (m.role === 'user') { q = m.content; }
      if (m.role === 'assistant' && m.turnNo === turnNo) { break; }
    }
    return q === '' ? '（无问题记录）' : q;
  }

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
      Row() {
        Text('对话')
          .fontSize(18)
          .fontWeight(FontWeight.Medium)
          .fontColor($r('app.color.text_hi'))
          .fontFamily('Sora, HarmonyOS Sans')
          .layoutWeight(1)
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
      .padding(16)

      Row({ space: 10 }) {
        Button('＋ 新对话')
          .fontSize(14)
          .height(34)
          .fontColor('#FFFFFF')
          .backgroundColor($r('app.color.accent'))
          .borderRadius(999)
          .layoutWeight(1)
          .onClick(() => { this.onNewConversation(); })
        Button('设置')
          .fontSize(14)
          .height(34)
          .fontColor($r('app.color.text_hi'))
          .backgroundColor($r('app.color.glass_bg'))
          .backdropBlur(20)
          .borderRadius(999)
          .border({ width: ThemeTokens.BORDER_W, color: $r('app.color.glass_border') })
          .layoutWeight(1)
          .onClick(() => { this.onOpenSettings(); })
      }
      .width('100%')
      .padding({ left: 16, right: 16, bottom: 10 })

      Text('会话')
        .fontSize(11)
        .fontColor($r('app.color.text_low'))
        .letterSpacing(1.2)
        .width('100%')
        .padding({ left: 16, bottom: 6 })

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
        .height('32%')
        .width('100%')
        .padding({ left: 12, right: 12 })
      }

      Divider().color($r('app.color.glass_border')).margin({ left: 16, right: 16, top: 8, bottom: 8 })

      Text('历史轮次')
        .fontSize(11)
        .fontColor($r('app.color.text_low'))
        .letterSpacing(1.2)
        .width('100%')
        .padding({ left: 16, bottom: 6 })

      if (this.turns.length === 0) {
        Text('还没有讲解记录').fontSize(14).fontColor($r('app.color.text_low')).padding(16)
      } else {
        List({ space: 8 }) {
          ForEach(this.turns, (m: Msg) => {
            if (m.role === 'assistant') {
              ListItem() {
                Column({ space: 4 }) {
                  Text(`第${m.turnNo}轮`)
                    .fontSize(12).fontColor($r('app.color.accent'))
                  Text(this.questionOf(m.turnNo))
                    .fontSize(15)
                    .fontColor($r('app.color.text_hi'))
                    .maxLines(2)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                    .width('100%')
                }
                .alignItems(HorizontalAlign.Start)
                .padding(12)
                .borderRadius(16)
                .backgroundColor($r('app.color.glass_bg'))
                .backdropBlur(ThemeTokens.BLUR_CARD)
                .border({ width: ThemeTokens.BORDER_W, color: $r('app.color.glass_border') })
                .width('100%')
                .onClick(() => { this.onOpenTurn(m.turnNo); })
              }
            }
          }, (m: Msg) => `${m.id}`)
        }
        .layoutWeight(1)
        .width('100%')
        .padding({ left: 12, right: 12 })
      }
    }
    .width('86%')
    .height('100%')
    .backgroundColor($r('app.color.glass_bg_strong'))
    .backdropBlur(ThemeTokens.BLUR_DOCK)
    .border({ width: { left: ThemeTokens.BORDER_W }, color: $r('app.color.glass_border') })
    .alignItems(HorizontalAlign.Start)
  }
}
