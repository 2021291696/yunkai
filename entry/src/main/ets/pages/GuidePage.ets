// 引导态：无任何消息时的冷启动页（🌤️ + 快捷问题 chips；第三个 chip 演示 @eli5 强制技能语法）
// 纯展示组件：点 chips 经 onAsk(question) 上抛，由 Chat 页填输入并触发发送

// 引导页快捷问题
class Chip {
  static readonly QUESTIONS: string[] =
    ['为什么天空是蓝色的？', '黑洞里面是什么？', '@eli5 讲讲黑洞是怎么形成的'];
}

@Component
export struct GuidePage {
  onAsk: (question: string) => void = (question: string): void => {};

  build() {
    Stack() {
      // 引导态光斑：与列表/设置页同族的柔光玻璃底
      Column()
        .width(340)
        .height(340)
        .position({ x: -90, y: 180 })
        .borderRadius(240)
        .backgroundColor('#4DFFD75E')
        .blur(90)
      Column()
        .width(360)
        .height(360)
        .position({ x: 220, y: 620 })
        .borderRadius(260)
        .backgroundColor('#407CC4FF')
        .blur(95)

      Column({ space: 12 }) {
        Text('🌤️').fontSize(60)
        Text('问我任何问题')
          .fontSize(20)
          .fontWeight(FontWeight.Medium)
          .fontColor('#3D3325')
        Text('我会用大白话和图讲给你听')
          .fontSize(14)
          .fontColor('#8A7A5A')
        Flex({ wrap: FlexWrap.Wrap, justifyContent: FlexAlign.Center }) {
          ForEach(Chip.QUESTIONS, (q: string) => {
            Text(q)
              .fontSize(14)
              .fontColor('#7A5B1E')
              .backgroundColor('#FFFFFFA6')
              .border({ width: 1, color: '#FFFFFFCC' })
              .borderRadius(18)
              .padding({ left: 14, right: 14, top: 8, bottom: 8 })
              .margin(6)
              .onClick(() => {
                this.onAsk(q);
              })
          }, (q: string) => q)
        }
        .width('86%')
        .margin({ top: 10 })
      }
      .width('100%')
      .height('100%')
      .justifyContent(FlexAlign.Center)
    }
    .width('100%')
    .height('100%')
  }
}
