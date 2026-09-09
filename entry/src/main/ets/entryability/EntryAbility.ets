import { AbilityConstant, ConfigurationConstant, UIAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window, font } from '@kit.ArkUI';
import { SkillRepo } from '../store/SkillRepo';

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

    // colorMode 注入 AppStorage 供组件读取
    Environment.envProp('colorMode', ColorMode.LIGHT);

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

    try {
      this.context.getApplicationContext().setColorMode(ConfigurationConstant.ColorMode.COLOR_MODE_NOT_SET);
    } catch (err) {
      hilog.error(DOMAIN, 'testTag', 'Failed to set colorMode. Cause: %{public}s', JSON.stringify(err));
    }
  }

  onWindowStageDestroy(): void {
    // Main window is destroyed, release UI related resources
    hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onWindowStageDestroy');
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