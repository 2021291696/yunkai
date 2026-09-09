// 全局壁纸层：照片 + 亮度滤镜 + 纵向/横向渐变遮罩。置于页面 Stack 最底层。
// 换壁纸 = 设置页写入 ConfigStore.wallpaper（file:// 沙箱路径），这里读。
import { common } from '@kit.AbilityKit';
import { ConfigStore } from '../store/ConfigStore';

@Component
export struct WallpaperLayer {
  @StorageProp('colorMode') colorMode: number = ColorMode.LIGHT;
  @State customUri: string = '';

  aboutToAppear(): void {
    ConfigStore.getWallpaper(getContext(this) as common.UIAbilityContext)
      .then((p: string) => { this.customUri = p; });
  }

  build() {
    Stack() {
      if (this.customUri !== '') {
        Image(this.customUri)
          .width('100%').height('100%')
          .objectFit(ImageFit.Cover)
          .brightness(this.colorMode === ColorMode.DARK ? 0.45 : 1.06)
          .saturate(0.85)
      } else {
        Image($r('app.media.wallpaper_default'))
          .width('100%').height('100%')
          .objectFit(ImageFit.Cover)
          .brightness(this.colorMode === ColorMode.DARK ? 0.45 : 1.06)
          .saturate(0.85)
      }
      // 纵向暗角
      Column()
        .width('100%').height('100%')
        .linearGradient({
          angle: 180,
          colors: [
            [$r('app.color.mask_v_top'), 0.0],
            [$r('app.color.mask_v_35'), 0.35],
            [$r('app.color.mask_v_50'), 0.5],
            [$r('app.color.mask_v_70'), 0.7],
            [$r('app.color.mask_v_bot'), 1.0]
          ]
        })
      // 横向收边
      Column()
        .width('100%').height('100%')
        .linearGradient({
          angle: 90,
          colors: [
            [$r('app.color.mask_h_edge'), 0.0],
            ['#00000000', 0.3],
            ['#00000000', 0.7],
            [$r('app.color.mask_h_edge'), 1.0]
          ]
        })
    }
    .width('100%')
    .height('100%')
    .hitTestBehavior(HitTestMode.None) // 纯背景不吃点击
  }
}
