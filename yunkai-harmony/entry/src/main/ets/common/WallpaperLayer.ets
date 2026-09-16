// 全局背景层：按皮肤分叉（皮肤 token 见 theme/Schemes.ets）——
// clear：照片壁纸 + 亮度滤镜 + 纵横渐变遮罩（现役实现，值走 scheme.mask*）
// aurora：实底画布 + 四团流动辉光 + 上下可读性纱。radialGradient 自带向透明的
// falloff，不再叠 CSS 式 blur（性能红线）；漂移用 translate 属性动画
// iterations:-1 + Alternate 从初始位往返，不逐帧重建渐变。
// 自定义壁纸仅 clear 皮肤显示（设计简报 aurora-brief.md §4.1）。
import { common } from '@kit.AbilityKit';
import { ConfigStore } from '../store/ConfigStore';
import { SkinScheme, CLEAR_LIGHT, GlowSpot } from '../theme/Schemes';

@Component
export struct WallpaperLayer {
  @StorageProp('colorMode') colorMode: number = ColorMode.LIGHT;
  @StorageProp('skinId') skinId: string = 'clear';
  @StorageProp('scheme') scheme: SkinScheme = CLEAR_LIGHT;
  // 门0 审查：customUri 走 AppStorage 订阅——aboutToAppear 只读一次会让
  // 「设置页换壁纸→返回对话页」不刷新（pushUrl 返回不重建本组件）
  @StorageProp('wallpaperUri') customUri: string = '';
  @State drift: boolean = false;

  aboutToAppear(): void {
    // 首帧兜底：AppStorage 尚无值时读一次持久化配置并回填（此后变更由写入方推送）
    if (AppStorage.get<string>('wallpaperUri') === undefined) {
      ConfigStore.getWallpaper(getContext(this) as common.UIAbilityContext)
        .then((p: string) => { AppStorage.setOrCreate('wallpaperUri', p); });
    }
    // 延迟一拍置位：.animation 只动画「属性变化」，首帧就置 true 会当成初始值而静止
    setTimeout((): void => { this.drift = true; }, 120);
  }

  // 一团辉光：radialGradient 从中心色衰减到全透明，自带柔边
  @Builder
  glowBlob(spot: GlowSpot) {
    Column()
      .width(spot.radius * 2)
      .height(spot.radius * 2)
      .position({ x: spot.x - spot.radius, y: spot.y - spot.radius })
      .radialGradient({
        center: ['50%', '50%'],
        radius: spot.radius,
        colors: [[spot.color, 0], ['#00000000', 1]]
      })
  }

  // aurora 背景：实底 + 辉光群（整体 14s 往返漂移）+ 上下纱
  @Builder
  auroraBg() {
    Stack() {
      Column()
        .width('100%')
        .height('100%')
        .backgroundColor(this.scheme.paperBase)
      Stack() {
        ForEach(this.scheme.glows, (spot: GlowSpot) => {
          this.glowBlob(spot)
        }, (spot: GlowSpot) => `${spot.x}_${spot.y}`)
      }
      .width('100%')
      .height('100%')
      .translate({ x: this.drift ? -10 : 0, y: this.drift ? 8 : 0 })
      .animation({
        duration: 14000,
        curve: Curve.EaseInOut,
        iterations: -1,
        playMode: PlayMode.Alternate
      })
      // 纱：顶部轻提亮、底部压暗，保证问候区与输入坞的可读性
      Column()
        .width('100%')
        .height('100%')
        .linearGradient({
          angle: 180,
          colors: [
            [this.scheme.auroraVeilTop, 0.0],
            ['#00000000', 0.28],
            ['#00000000', 0.62],
            [this.scheme.auroraVeilBot, 1.0]
          ]
        })
    }
    .width('100%')
    .height('100%')
  }

  // clear 背景：照片壁纸（原实现，遮罩色改走 scheme）
  @Builder
  wallpaperBg() {
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
            [this.scheme.maskVTop, 0.0],
            [this.scheme.maskV35, 0.35],
            [this.scheme.maskV50, 0.5],
            [this.scheme.maskV70, 0.7],
            [this.scheme.maskVBot, 1.0]
          ]
        })
      // 横向收边
      Column()
        .width('100%').height('100%')
        .linearGradient({
          angle: 90,
          colors: [
            [this.scheme.maskHEdge, 0.0],
            ['#00000000', 0.3],
            ['#00000000', 0.7],
            [this.scheme.maskHEdge, 1.0]
          ]
        })
    }
    .width('100%')
    .height('100%')
  }

  build() {
    Stack() {
      if (this.skinId === 'aurora') {
        this.auroraBg()
      } else {
        this.wallpaperBg()
      }
    }
    .width('100%')
    .height('100%')
    .hitTestBehavior(HitTestMode.None) // 纯背景不吃点击
  }
}
