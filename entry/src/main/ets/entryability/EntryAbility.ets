import { AbilityConstant, Configuration, UIAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window, font } from '@kit.ArkUI';
import { SkillRepo } from '../store/SkillRepo';
import { ConfigStore } from '../store/ConfigStore';
import { applyThemeMode, configIsDark, THEME_SYSTEM } from '../common/ThemeMode';

const DOMAIN = 0x0000;

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onCreate');
    // 首启播种内置 eli5 技能（幂等：list 非空即返回；失败不影响启动）
    SkillRepo.seedIfEmpty(this.context).catch((err: Error) => {
      hilog.error(DOMAIN, 'testTag', 'seedIfEmpty failed: %{public}s', err.message);
    });
  }

  onDestroy(): void {
    hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onDestroy');
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    // Main window is created, set main page for this ability
    hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onWindowStageCreate');

    // Sora 字体注册（文件缺失时静默回落系统字体）
    try {
      font.registerFont({ familyName: 'Sora', familySrc: $rawfile('fonts/Sora.ttf') });
    } catch (e) { /* 字体缺失不致命 */ }

    // 主题档位（system/light/dark）：同步读持久化配置后立即应用。
    // 不用 Environment.envProp —— 环境变量只反映系统明暗，会把手动档覆盖掉
    const themeMode: string = ConfigStore.getThemeModeSync(this.context);
    AppStorage.setOrCreate('themeMode', themeMode);
    applyThemeMode(this.context, themeMode);

    // 沉浸式：内容延伸到状态栏/导航条之下
    windowStage.getMainWindowSync().setWindowLayoutFullScreen(true);

    windowStage.loadContent('pages/Index', (err) => {
      if (err.code) {
        hilog.error(DOMAIN, 'testTag', 'Failed to load the content. Cause: %{public}s', JSON.stringify(err));
        return;
      }
      hilog.info(DOMAIN, 'testTag', 'Succeeded in loading the content.');
    });

    const win = windowStage.getMainWindowSync();
    const topPx = win.getWindowAvoidArea(window.AvoidAreaType.TYPE_SYSTEM).topRect.height;
    const navPx = win.getWindowAvoidArea(window.AvoidAreaType.TYPE_NAVIGATION_INDICATOR).bottomRect.height;
    AppStorage.setOrCreate('statusBarH', px2vp(topPx));
    AppStorage.setOrCreate('navBarH', px2vp(navPx));
  }

  onWindowStageDestroy(): void {
    // Main window is destroyed, release UI related resources
    hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onWindowStageDestroy');
  }

  // 系统明暗切换：仅「跟随系统」档同步给按明暗分支的组件（手动档不受影响）
  onConfigurationUpdate(newConfig: Configuration): void {
    const mode: string | undefined = AppStorage.get<string>('themeMode');
    if (mode === undefined || mode === THEME_SYSTEM) {
      AppStorage.setOrCreate('colorMode', configIsDark(newConfig.colorMode) ? ColorMode.DARK : ColorMode.LIGHT);
    }
  }

  onForeground(): void {
    // Ability has brought to foreground
    hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onForeground');
  }

  onBackground(): void {
    // Ability has back to background
    hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onBackground');
  }
}