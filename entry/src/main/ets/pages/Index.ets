// 会话列表页：新建对话 / 进入对话 / 删除 / 设置入口
import { router, promptAction } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { Conv } from '../model/Types';
import { ConversationRepo } from '../store/ConversationRepo';

@Entry
@Component
struct Index {
  @State convs: Conv[] = [];

  private ctx(): common.UIAbilityContext {
    return getContext(this) as common.UIAbilityContext;
  }

  aboutToAppear(): void {
    this.refresh();
  }

  // 从 Chat 返回时刷新时间戳与标题
  onPageShow(): void {
    this.refresh();
  }

  private async refresh(): Promise<void> {
    this.convs = await ConversationRepo.list(this.ctx());
  }

  private timeLabel(ts: number): string {
    const d = new Date(ts);
    const now = Date.now();
    const diffMin = Math.floor((now - ts) / 60000);
    if (diffMin < 1) { return '刚刚'; }
    if (diffMin < 60) { return `${diffMin}分钟前`; }
    if (diffMin < 60 * 24) { return `${Math.floor(diffMin / 60)}小时前`; }
    return `${d.getMonth() + 1}-${d.getDate()}`;
  }

  private newConversation(): void {
    // 不预创建：convId=-1 表示未落库的草稿会话，Chat 页首次成功回答时才建记录
    const p: Record<string, number> = { 'convId': -1 };
    router.pushUrl({ url: 'pages/Chat', params: p });
  }

  private openConversation(id: number): void {
    const p: Record<string, number> = { 'convId': id };
    router.pushUrl({ url: 'pages/Chat', params: p });
  }

  private confirmDelete(conv: Conv): void {
    AlertDialog.show({
      title: '删除对话',
      message: `确定删除「${conv.title}」吗？`,
      primaryButton: {
        value: '取消',
        action: () => {
        }
      },
      secondaryButton: {
        value: '删除',
        fontColor: '#FF453A',
        action: () => {
          this.doDelete(conv.id);
        }
      }
    });
  }

  private async doDelete(id: number): Promise<void> {
    await ConversationRepo.remove(this.ctx(), id);
    promptAction.showToast({ message: '已删除', duration: 1500 });
    this.refresh();
  }

  build() {
    // 暖纸光斑背景层：玻璃卡后面要有东西可虚化
    Stack() {
      Column() {
        // 光斑：半透明大圆 + blur = 柔光（左上黄 / 右上蓝 / 底部珊瑚）
        Column()
          .width(360)
          .height(360)
          .position({ x: -80, y: 620 })
          .borderRadius(260)
          .backgroundColor('#4DFFD75E')
          .blur(90)

        Column()
          .width(380)
          .height(380)
          .position({ x: 820, y: 40 })
          .borderRadius(260)
          .backgroundColor('#407CC4FF')
          .blur(95)

        Column()
          .width(520)
          .height(520)
          .position({ x: 280, y: 1500 })
          .borderRadius(320)
          .backgroundColor('#47FF9D8A')
          .blur(110)

        // ===== 前景内容 =====
        Column() {
          // ===== 顶栏（半透明玻璃条） =====
          Row() {
            Text('云开')
              .fontSize(30)
              .fontWeight(FontWeight.Bold)
              .fontColor('#3D3325')
              .layoutWeight(1)
            Button('＋ 新对话')
              .fontSize(15)
              .height(38)
              .backgroundColor('#E8930C')
              .onClick(() => {
                this.newConversation();
              })
            Button('设置')
              .fontSize(15)
              .height(38)
              .margin({ left: 10 })
              .backgroundColor('#FFE9C7')
              .fontColor('#7A5B1E')
              .onClick(() => {
                router.pushUrl({ url: 'pages/Settings' });
              })
          }
          .width('100%')
          .padding({ left: 18, right: 18, top: 16, bottom: 10 })

          // ===== 会话列表 =====
          if (this.convs.length === 0) {
            Column({ space: 10 }) {
              Text('🌤️').fontSize(64)
              Text('有任何想懂的事，问我就好')
                .fontSize(17)
                .fontColor('#8A7A5A')
              Text('点右上角「＋ 新对话」开始')
                .fontSize(14)
                .fontColor('#B09A72')
            }
            .width('100%')
            .layoutWeight(1)
            .justifyContent(FlexAlign.Center)
          } else {
            List({ space: 12 }) {
              ForEach(this.convs, (c: Conv) => {
                ListItem() {
                  Column({ space: 4 }) {
                    Text(c.title)
                      .fontSize(17)
                      .fontColor('#3D3325')
                      .maxLines(1)
                      .textOverflow({ overflow: TextOverflow.Ellipsis })
                      .width('100%')
                    Text(this.timeLabel(c.updatedAt))
                      .fontSize(12)
                      .fontColor('#A08C66')
                      .width('100%')
                  }
                  .padding({ left: 16, right: 16, top: 13, bottom: 13 })
                  .borderRadius(16)
                  .backgroundColor('#FFFFFFA6')
                  .backgroundBlurStyle(BlurStyle.Thin)
                  .border({ width: 1, color: '#FFFFFFB8' })
                  .shadow({
                    radius: 22,
                    color: '#42342540',
                    offsetY: 10
                  })
                  .width('100%')
                  .onClick(() => {
                    this.openConversation(c.id);
                  })
                  .gesture(
                    LongPressGesture()
                      .onAction(() => {
                        this.confirmDelete(c);
                      })
                  )
                }
              }, (c: Conv) => `${c.id}_${c.updatedAt}`)
            }
            .layoutWeight(1)
            .width('100%')
            .padding({ left: 14, right: 14 })
          }
        }
        .width('100%')
        .height('100%')
      }
      .width('100%')
      .height('100%')
      .backgroundColor('#FAF6EF')
    }
    .width('100%')
    .height('100%')
  }
}
