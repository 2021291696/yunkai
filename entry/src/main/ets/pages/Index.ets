// 打开即对话：Index 仅作跳板，aboutToAppear 直接 replaceUrl 到对话页（草稿会话）。
// 保留页面文件与路由注册，避免 main_pages.json 删页风险；历史列表功能已迁入 Chat 侧抽屉。
import { router } from '@kit.ArkUI';

@Entry
@Component
struct Index {
  aboutToAppear(): void {
    const p: Record<string, number> = { 'convId': -1 };
    router.replaceUrl({ url: 'pages/Chat', params: p });
  }

  build() {
    // 跳板底色与全局暖纸一致，replaceUrl 瞬时完成，避免闪白
    Column()
      .width('100%')
      .height('100%')
      .backgroundColor('#FAF6EF')
  }
}
