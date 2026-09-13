// 设置页：外观（壁纸自定义）+ OpenAI 兼容三项配置 + 获取模型列表 + 技能自动路由开关 + 独立搜索配置 + 关于
// （联网方式三选已下线：agent 自主决定何时搜索，searchMode 字段保留不迁移）
import { router, promptAction } from '@kit.ArkUI';
import { bundleManager } from '@kit.AbilityKit';
import { common } from '@kit.AbilityKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { AppConfig } from '../model/Types';
import { ConfigStore } from '../store/ConfigStore';
import { LlmClient } from '../service/LlmClient';
import { ThemeTokens } from '../theme/ThemeTokens';
import { SkinScheme, CLEAR_LIGHT } from '../theme/Schemes';
import { WallpaperLayer } from '../common/WallpaperLayer';
import { applySkinAndTheme, THEME_DARK, THEME_LIGHT, THEME_SYSTEM } from '../common/ThemeMode';
import { SKIN_AURORA, SKIN_CLEAR } from '../theme/Schemes';

// TODO(task7): 新名字确定后全局替换
const APP_NAME: string = '云开';

interface ModelSelectOption {
  value: string;
}

// 玻璃卡片统一外壳（@Extend 无 this，皮肤 token 经参数传入）
@Extend(Column)
function glassCard(s: SkinScheme) {
  .alignItems(HorizontalAlign.Start)
  .padding(16)
  .borderRadius(ThemeTokens.R_CARD)
  .backgroundColor(s.glassBg)
  .backdropBlur(ThemeTokens.BLUR_CARD)
  .border({ width: ThemeTokens.BORDER_W, color: s.glassBorder })
  .shadow({ radius: 28, color: '#40000000', offsetY: 8 })
  .width('100%')
}

@Entry
@Component
struct Settings {
  @StorageProp('statusBarH') statusBarH: number = 0;
  @StorageProp('scheme') scheme: SkinScheme = CLEAR_LIGHT;
  @State baseUrl: string = '';
  @State apiKey: string = '';
  @State model: string = '';
  @State autoRoute: boolean = true;
  @State searchProvider: string = 'bing';
  @State searchApiKey: string = '';
  @State modelOptions: ModelSelectOption[] = [];
  @State modelSelected: string = '';
  @State fetchingModels: boolean = false;
  @State wallpaperLabel: string = '默认';
  @State themeMode: string = THEME_SYSTEM;
  @State skinId: string = SKIN_CLEAR;
  // 忆枢 M5b：隐私挡位 + 任务步数（选中即写，独立于「保存」）
  @State memoryGear: string = 'strict';
  @State maxSteps: number = 25;
  @State versionName: string = '';

  private ctx(): common.UIAbilityContext {
    return getContext(this) as common.UIAbilityContext;
  }

  aboutToAppear(): void {
    this.loadConfig();
    // 版本号读包信息（不再硬编码；与 app.json5 的 versionName 同源）
    this.versionName = bundleManager.getBundleInfoForSelfSync(
      bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT).versionName;

    this.themeMode = ConfigStore.getThemeModeSync(this.ctx());
    this.skinId = ConfigStore.getSkinIdSync(this.ctx());
    ConfigStore.getWallpaper(this.ctx())
      .then((p: string) => { this.wallpaperLabel = p === '' ? '默认' : '自定义'; });
    ConfigStore.getMemoryGear(this.ctx())
      .then((g: string) => { this.memoryGear = g; });
    ConfigStore.getMaxSteps(this.ctx())
      .then((n: number) => { this.maxSteps = n; });
  }

  // 主题档位/皮肤切换：持久化 + 立即生效（applySkinAndTheme 单点重算 token，同时刷 AppStorage）
  private pickTheme(mode: string): void {
    this.themeMode = mode;
    ConfigStore.setThemeMode(this.ctx(), mode).then(() => {
      AppStorage.setOrCreate('themeMode', mode);
      applySkinAndTheme(this.ctx(), mode, this.skinId);
    });
  }

  private pickSkin(skinId: string): void {
    this.skinId = skinId;
    ConfigStore.setSkinId(this.ctx(), skinId).then(() => {
      applySkinAndTheme(this.ctx(), this.themeMode, skinId);
    });
  }

  // 忆枢：隐私挡位（未知值由 PrivacyGate 回退 strict）与任务步数——选中即写
  private pickGear(gear: string): void {
    this.memoryGear = gear;
    ConfigStore.setMemoryGear(this.ctx(), gear);
  }

  private pickSteps(steps: number): void {
    this.maxSteps = steps;
    ConfigStore.setMaxSteps(this.ctx(), steps);
  }

  private async loadConfig(): Promise<void> {
    const cfg: AppConfig = await ConfigStore.load(this.ctx());
    this.baseUrl = cfg.baseUrl;
    this.apiKey = cfg.apiKey;
    this.model = cfg.model;
    this.autoRoute = cfg.autoRoute;
    this.searchProvider = cfg.searchProvider;
    this.searchApiKey = cfg.searchApiKey;
  }

  private collect(): AppConfig {
    const c = new AppConfig();
    c.baseUrl = this.baseUrl.trim();
    c.apiKey = this.apiKey.trim();
    c.model = this.model.trim();
    c.autoRoute = this.autoRoute;
    c.searchProvider = this.searchProvider;
    c.searchApiKey = this.searchApiKey.trim();
    return c;
  }

  private toast(msg: string): void {
    promptAction.showToast({ message: msg, duration: 2500 });
  }

  private async fetchModels(): Promise<void> {
    const cfg: AppConfig = this.collect();
    if (cfg.baseUrl === '' || cfg.apiKey === '') {
      this.toast('请先填写 API 地址和密钥');
      return;
    }
    this.fetchingModels = true;
    try {
      const llm = new LlmClient(cfg);
      const ids: string[] = await llm.listModels();
      if (ids.length === 0) {
        this.toast('模型列表为空');
        return;
      }
      const opts: ModelSelectOption[] = [];
      for (let i = 0; i < ids.length; i++) {
        opts.push({ value: ids[i] } as ModelSelectOption);
      }
      this.modelOptions = opts;
      this.toast(`获取到 ${ids.length} 个模型，请在下拉中选择`);
    } catch (e) {
      this.toast('获取失败：' + (e as Error).message);
    } finally {
      this.fetchingModels = false;
    }
  }

  private async save(): Promise<void> {
    const cfg: AppConfig = this.collect();
    if (cfg.baseUrl === '' || cfg.apiKey === '' || cfg.model === '') {
      this.toast('API 地址、密钥、模型名都不能为空');
      return;
    }
    await ConfigStore.save(this.ctx(), cfg);
    this.toast('已保存');
    router.back();
  }

  // 选图 → 复制进沙箱 → 写入 ConfigStore（返回对话页生效）
  private async pickWallpaper(): Promise<void> {
    try {
      const picker = new photoAccessHelper.PhotoViewPicker();
      const res: photoAccessHelper.PhotoSelectResult = await picker.select({
        MIMEType: photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE,
        maxSelectNumber: 1
      });
      if (res.photoUris.length === 0) { return; }
      const ctx = this.ctx();
      const dstPath: string = ctx.filesDir + '/wallpaper_custom.jpg';
      const srcFile: fs.File = fs.openSync(res.photoUris[0], fs.OpenMode.READ_ONLY);
      const dstFile: fs.File = fs.openSync(dstPath, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      fs.copyFileSync(srcFile.fd, dstFile.fd);
      fs.closeSync(srcFile);
      fs.closeSync(dstFile);
      await ConfigStore.setWallpaper(ctx, 'file://' + dstPath);
      this.wallpaperLabel = '自定义';
      this.toast('已更换，返回对话页生效');
    } catch (e) {
      this.toast('更换失败：' + (e as Error).message);
    }
  }

  private async resetWallpaper(): Promise<void> {
    await ConfigStore.setWallpaper(this.ctx(), '');
    this.wallpaperLabel = '默认';
    this.toast('已恢复默认壁纸');
  }

  build() {
    Stack() {
      WallpaperLayer()

      Scroll() {
      Column({ space: 18 }) {
        // ===== 顶栏：返回玻璃圆钮 + 标题 =====
        Row({ space: 12 }) {
          Button('‹')
            .width(34)
            .height(34)
            .fontSize(18)
            .fontColor(this.scheme.textHi)
            .backgroundColor(this.scheme.glassBg)
            .backdropBlur(20)
            .borderRadius(17)
            .border({ width: ThemeTokens.BORDER_W, color: this.scheme.glassBorder })
            .padding(0)
            .onClick(() => {
              router.back();
            })
          Text('设置')
            .fontSize(26)
            .fontWeight(FontWeight.Medium)
            .fontColor(this.scheme.textHi)
            .fontFamily('Sora, HarmonyOS Sans')
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
        .margin({ top: this.statusBarH + 12 })

        // ===== 外观卡片：壁纸自定义（点更换 / 长按恢复默认） =====
        Column({ space: 10 }) {
          Text('外观').fontSize(16).fontColor(this.scheme.textMid).width('100%')
          // 忆枢 M5b：记忆隐私三挡（协议 §5，选中即写）+ 记忆管理入口 + 任务步数三档（§2）
          Text('记忆隐私').fontSize(15).fontColor(this.scheme.textHi)
          Row({ space: 8 }) {
            Button('严格')
              .fontSize(12).height(30).padding({ left: 12, right: 12 })
              .fontColor(this.memoryGear === 'strict' ? this.scheme.accent : this.scheme.textHi)
              .backgroundColor(this.memoryGear === 'strict' ? this.scheme.glassBgStrong : this.scheme.glassBg)
              .borderRadius(15)
              .border({ width: ThemeTokens.BORDER_W, color: this.memoryGear === 'strict' ? this.scheme.accent : this.scheme.glassBorder })
              .onClick(() => { this.pickGear('strict'); })
            Button('标准')
              .fontSize(12).height(30).padding({ left: 12, right: 12 })
              .fontColor(this.memoryGear === 'standard' ? this.scheme.accent : this.scheme.textHi)
              .backgroundColor(this.memoryGear === 'standard' ? this.scheme.glassBgStrong : this.scheme.glassBg)
              .borderRadius(15)
              .border({ width: ThemeTokens.BORDER_W, color: this.memoryGear === 'standard' ? this.scheme.accent : this.scheme.glassBorder })
              .onClick(() => { this.pickGear('standard'); })
            Button('自由')
              .fontSize(12).height(30).padding({ left: 12, right: 12 })
              .fontColor(this.memoryGear === 'free' ? this.scheme.accent : this.scheme.textHi)
              .backgroundColor(this.memoryGear === 'free' ? this.scheme.glassBgStrong : this.scheme.glassBg)
              .borderRadius(15)
              .border({ width: ThemeTokens.BORDER_W, color: this.memoryGear === 'free' ? this.scheme.accent : this.scheme.glassBorder })
              .onClick(() => { this.pickGear('free'); })
            Blank()
            Text('拒证件·拒密码 / 拒证件 / 不拦').fontSize(10).fontColor(this.scheme.textLow)
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)
          Row({ space: 8 }) {
            Text('任务步数').fontSize(15).fontColor(this.scheme.textHi)
            Button('省流 10')
              .fontSize(12).height(30).padding({ left: 12, right: 12 })
              .fontColor(this.maxSteps === 10 ? this.scheme.accent : this.scheme.textHi)
              .backgroundColor(this.maxSteps === 10 ? this.scheme.glassBgStrong : this.scheme.glassBg)
              .borderRadius(15)
              .border({ width: ThemeTokens.BORDER_W, color: this.maxSteps === 10 ? this.scheme.accent : this.scheme.glassBorder })
              .onClick(() => { this.pickSteps(10); })
            Button('标准 25')
              .fontSize(12).height(30).padding({ left: 12, right: 12 })
              .fontColor(this.maxSteps === 25 ? this.scheme.accent : this.scheme.textHi)
              .backgroundColor(this.maxSteps === 25 ? this.scheme.glassBgStrong : this.scheme.glassBg)
              .borderRadius(15)
              .border({ width: ThemeTokens.BORDER_W, color: this.maxSteps === 25 ? this.scheme.accent : this.scheme.glassBorder })
              .onClick(() => { this.pickSteps(25); })
            Button('深度 50')
              .fontSize(12).height(30).padding({ left: 12, right: 12 })
              .fontColor(this.maxSteps === 50 ? this.scheme.accent : this.scheme.textHi)
              .backgroundColor(this.maxSteps === 50 ? this.scheme.glassBgStrong : this.scheme.glassBg)
              .borderRadius(15)
              .border({ width: ThemeTokens.BORDER_W, color: this.maxSteps === 50 ? this.scheme.accent : this.scheme.glassBorder })
              .onClick(() => { this.pickSteps(50); })
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)
          Row({ space: 8 }) {
            Text('记忆管理').fontSize(15).fontColor(this.scheme.textHi)
            Blank()
            Text('›')
              .fontSize(18)
              .fontColor(this.scheme.textHi)
              .padding({ left: 16, right: 4 })
              .onClick((): void => { router.pushUrl({ url: 'pages/MemoryManage' }); })
          }
          .width('100%')
          .onClick((): void => { router.pushUrl({ url: 'pages/MemoryManage' }); })
          Text('外观').fontSize(16).fontColor(this.scheme.textMid).width('100%')
          // 设计（皮肤）：通透=现役玻璃壁纸；极光=流动辉光替代壁纸。写入即生效
          Row({ space: 8 }) {
            Text('设计').fontSize(15).fontColor(this.scheme.textHi)
            Blank()
            Button('通透')
              .fontSize(12)
              .height(30)
              .padding({ left: 12, right: 12 })
              .fontColor(this.skinId === SKIN_CLEAR ? this.scheme.accent : this.scheme.textHi)
              .backgroundColor(this.skinId === SKIN_CLEAR ? this.scheme.glassBgStrong : this.scheme.glassBg)
              .borderRadius(15)
              .border({ width: ThemeTokens.BORDER_W, color: this.skinId === SKIN_CLEAR ? this.scheme.accent : this.scheme.glassBorder })
              .onClick(() => { this.pickSkin(SKIN_CLEAR); })
            Button('极光')
              .fontSize(12)
              .height(30)
              .padding({ left: 12, right: 12 })
              .fontColor(this.skinId === SKIN_AURORA ? this.scheme.accent : this.scheme.textHi)
              .backgroundColor(this.skinId === SKIN_AURORA ? this.scheme.glassBgStrong : this.scheme.glassBg)
              .borderRadius(15)
              .border({ width: ThemeTokens.BORDER_W, color: this.skinId === SKIN_AURORA ? this.scheme.accent : this.scheme.glassBorder })
              .onClick(() => { this.pickSkin(SKIN_AURORA); })
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)
          // 主题档位：写入即生效（手动档调 setColorMode，跟随系统档吃系统明暗）
          Row({ space: 8 }) {
            Text('主题').fontSize(15).fontColor(this.scheme.textHi)
            Blank()
            Button('跟随系统')
              .fontSize(12)
              .height(30)
              .padding({ left: 12, right: 12 })
              .fontColor(this.themeMode === THEME_SYSTEM ? this.scheme.accent : this.scheme.textHi)
              .backgroundColor(this.themeMode === THEME_SYSTEM ? this.scheme.glassBgStrong : this.scheme.glassBg)
              .borderRadius(15)
              .border({ width: ThemeTokens.BORDER_W, color: this.themeMode === THEME_SYSTEM ? this.scheme.accent : this.scheme.glassBorder })
              .onClick(() => { this.pickTheme(THEME_SYSTEM); })
            Button('浅色')
              .fontSize(12)
              .height(30)
              .padding({ left: 12, right: 12 })
              .fontColor(this.themeMode === THEME_LIGHT ? this.scheme.accent : this.scheme.textHi)
              .backgroundColor(this.themeMode === THEME_LIGHT ? this.scheme.glassBgStrong : this.scheme.glassBg)
              .borderRadius(15)
              .border({ width: ThemeTokens.BORDER_W, color: this.themeMode === THEME_LIGHT ? this.scheme.accent : this.scheme.glassBorder })
              .onClick(() => { this.pickTheme(THEME_LIGHT); })
            Button('深色')
              .fontSize(12)
              .height(30)
              .padding({ left: 12, right: 12 })
              .fontColor(this.themeMode === THEME_DARK ? this.scheme.accent : this.scheme.textHi)
              .backgroundColor(this.themeMode === THEME_DARK ? this.scheme.glassBgStrong : this.scheme.glassBg)
              .borderRadius(15)
              .border({ width: ThemeTokens.BORDER_W, color: this.themeMode === THEME_DARK ? this.scheme.accent : this.scheme.glassBorder })
              .onClick(() => { this.pickTheme(THEME_DARK); })
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)
          Row({ space: 10 }) {
            Text('壁纸').fontSize(15).fontColor(this.scheme.textHi)
            Text(this.wallpaperLabel).fontSize(13).fontColor(this.scheme.textLow)
            Blank()
            Button('恢复默认')
              .fontSize(13)
              .height(30)
              .fontColor(this.scheme.textMid)
              .backgroundColor(this.scheme.glassBgStrong)
              .borderRadius(15)
              .border({ width: ThemeTokens.BORDER_W, color: this.scheme.glassBorder })
              .onClick(() => { this.resetWallpaper(); })
            Button('选择图片')
              .fontSize(13)
              .height(30)
              .fontColor(this.scheme.textHi)
              .backgroundColor(this.scheme.glassBgStrong)
              .borderRadius(15)
              .border({ width: ThemeTokens.BORDER_W, color: this.scheme.glassBorder })
              .onClick(() => {
                this.pickWallpaper();
              })
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)
          if (this.skinId === SKIN_AURORA) {
            Text('极光皮肤自带背景光，壁纸仅在「通透」皮肤显示')
              .fontSize(10.5).fontColor(this.scheme.textLow).width('100%')
          }
        }
        .glassCard(this.scheme)

        // ===== API 配置卡片 =====
        Column({ space: 12 }) {
          Text('API 配置').fontSize(16).fontColor(this.scheme.textMid).width('100%')
          Text('API 地址').fontSize(14).fontColor(this.scheme.textHi).width('100%')
          TextInput({ text: this.baseUrl, placeholder: 'https://open.bigmodel.cn/api/paas/v4' })
            .type(InputType.Normal)
            .width('100%')
            .backgroundColor(this.scheme.glassBgStrong)
            .fontColor(this.scheme.textHi)
            .placeholderColor(this.scheme.textLow)
            .borderRadius(12)
            .onChange((v: string) => { this.baseUrl = v; })
          Text('API 密钥').fontSize(14).fontColor(this.scheme.textHi).width('100%')
          TextInput({ text: this.apiKey, placeholder: 'sk-...' })
            .type(InputType.Password)
            .width('100%')
            .backgroundColor(this.scheme.glassBgStrong)
            .fontColor(this.scheme.textHi)
            .placeholderColor(this.scheme.textLow)
            .borderRadius(12)
            .onChange((v: string) => { this.apiKey = v; })
          Text('模型名').fontSize(14).fontColor(this.scheme.textHi).width('100%')
          Row({ space: 10 }) {
            TextInput({ text: this.model, placeholder: '如 glm-4.7 / deepseek-chat' })
              .type(InputType.Normal)
              .layoutWeight(1)
              .backgroundColor(this.scheme.glassBgStrong)
              .fontColor(this.scheme.textHi)
              .placeholderColor(this.scheme.textLow)
              .borderRadius(12)
              .onChange((v: string) => { this.model = v; })
            Button(this.fetchingModels ? '获取中…' : '获取模型列表')
              .fontColor(this.scheme.textHi)
              .backgroundColor(this.scheme.glassBgStrong)
              .border({ width: ThemeTokens.BORDER_W, color: this.scheme.glassBorder })
              .enabled(!this.fetchingModels)
              .onClick(() => {
                this.fetchModels();
              })
          }
          .width('100%')

          if (this.modelOptions.length > 0) {
            Select(this.modelOptions as SelectOption[])
              .selected(this.modelSelected === '' ? -1 : 0)
              .value(this.modelSelected === '' ? '选择模型' : this.modelSelected)
              .width('100%')
              .onSelect((index: number) => {
                const picked: string = this.modelOptions[index].value;
                this.modelSelected = picked;
                this.model = picked;
              })
          }
        }
        .glassCard(this.scheme)

        // ===== 联网搜索卡片（搜索源/密钥；联网方式三选已下线，agent 自主决定何时搜索） =====
        Column({ space: 12 }) {
          Text('联网搜索').fontSize(16).fontColor(this.scheme.textMid).width('100%')

          Text('搜索源').fontSize(14).fontColor(this.scheme.textHi).width('100%')
          Row({ space: 14 }) {
            Radio({ value: 'bing', group: 'searchProvider' })
              .checked(this.searchProvider === 'bing')
              .radioStyle({ checkedBackgroundColor: this.scheme.accent })
              .onChange((on: boolean) => { if (on) { this.searchProvider = 'bing'; } })
            Text('必应(免key)').fontSize(15).fontColor(this.scheme.textHi)
            Radio({ value: 'bocha', group: 'searchProvider' })
              .checked(this.searchProvider === 'bocha')
              .radioStyle({ checkedBackgroundColor: this.scheme.accent })
              .onChange((on: boolean) => { if (on) { this.searchProvider = 'bocha'; } })
            Text('博查').fontSize(15).fontColor(this.scheme.textHi)
            Radio({ value: 'tavily', group: 'searchProvider' })
              .checked(this.searchProvider === 'tavily')
              .radioStyle({ checkedBackgroundColor: this.scheme.accent })
              .onChange((on: boolean) => { if (on) { this.searchProvider = 'tavily'; } })
            Text('Tavily').fontSize(15).fontColor(this.scheme.textHi)
          }
          .width('100%')

          Text('搜索密钥').fontSize(14).fontColor(this.scheme.textHi).width('100%')
          TextInput({ text: this.searchApiKey, placeholder: '博查或 Tavily 的 key' })
            .type(InputType.Password)
            .width('100%')
            .backgroundColor(this.scheme.glassBgStrong)
            .fontColor(this.scheme.textHi)
            .placeholderColor(this.scheme.textLow)
            .borderRadius(12)
            .onChange((v: string) => { this.searchApiKey = v; })

          Text('搜索源选博查/Tavily 时需填密钥；必应免密钥。是否搜索由 agent 在对话中自主决定')
            .fontSize(12).fontColor(this.scheme.textLow).width('100%')
        }
        .glassCard(this.scheme)

        // ===== 技能路由卡片：自动路由开关 =====
        Column({ space: 10 }) {
          Row({ space: 10 }) {
            Text('技能自动路由').fontSize(15).fontColor(this.scheme.textHi).layoutWeight(1)
            Toggle({ type: ToggleType.Switch, isOn: this.autoRoute })
              .selectedColor(this.scheme.accent)
              .onChange((on: boolean) => { this.autoRoute = on; })
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)

          Text('关=仅 @名字 手动触发技能')
            .fontSize(12).fontColor(this.scheme.textLow).width('100%')
        }
        .glassCard(this.scheme)

        // ===== 技能库入口 =====
        Row() {
          Text('技能库').fontSize(15).fontColor(this.scheme.textHi)
          Blank()
          Text('›').fontSize(20).fontColor(this.scheme.accent)
        }
        .padding({ left: 16, right: 16, top: 14, bottom: 14 })
        // Row 不能用 @Extend(Column) 的 glassCard()，内联相同样式
        .borderRadius(ThemeTokens.R_CARD)
        .backgroundColor(this.scheme.glassBg)
        .backdropBlur(ThemeTokens.BLUR_CARD)
        .border({ width: ThemeTokens.BORDER_W, color: this.scheme.glassBorder })
        .shadow({ radius: 28, color: '#40000000', offsetY: 8 })
        .width('100%')
        .onClick(() => {
          router.pushUrl({ url: 'pages/SkillManage' });
        })

        Button('保存', { type: ButtonType.Capsule })
          .width('100%')
          .height(46)
          .fontSize(17)
          .fontColor('#FFFFFF')
          .backgroundColor(this.scheme.accent)
          .onClick(() => {
            this.save();
          })

        // ===== 关于卡片 =====
        Column({ space: 6 }) {
          Text(APP_NAME)
            .fontSize(18)
            .fontWeight(FontWeight.Medium)
            .fontColor(this.scheme.textHi)
            .fontFamily('Sora, HarmonyOS Sans')
          Text('一个安静的 AI 对话空间')
            .fontSize(12)
            .fontColor(this.scheme.textMid)
          Text(`版本 ${this.versionName}`)
            .fontSize(10.5)
            .fontColor(this.scheme.textLow)
        }
        .alignItems(HorizontalAlign.Center)
        .padding(16)
        .width('100%')
      }
      .padding({ left: 18, right: 18, bottom: 40 })
      .width('100%')
    }
    .scrollable(ScrollDirection.Vertical)
    .width('100%')
    .height('100%')
    }
    .width('100%')
    .height('100%')
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
