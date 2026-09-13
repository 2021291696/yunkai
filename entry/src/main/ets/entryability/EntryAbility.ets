import { AbilityConstant, Configuration, UIAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window, font } from '@kit.ArkUI';
import { fileIo as fs } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { SkillRepo } from '../store/SkillRepo';
import { ConfigStore } from '../store/ConfigStore';
import { applySkinAndTheme, applySkinTokens, configIsDark, THEME_SYSTEM } from '../common/ThemeMode';
import { Migrator, LegacyRow } from '../memory/Migrator';
import { MemoryStore } from '../memory/MemoryStore';

const DOMAIN = 0x0000;

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(DOMAIN, 'testTag', '%{public}s', 'Ability onCreate');
    // 首启播种内置 eli5 技能（幂等：list 非空即返回；失败不影响启动）
    SkillRepo.seedIfEmpty(this.context).catch((err: Error) => {
      hilog.error(DOMAIN, 'testTag', 'seedIfEmpty failed: %{public}s', err.message);
    });
    // 忆枢旧数据迁移（协议 §1.5，一次性）：filesDir/agent_memory.json 存在且无 .bak →
    // KV 迁入 archival（source=legacy-m2）→ 原文件改名 .bak（损坏文件也改名，视为空库）。
    // 幂等：.bak 已存在即跳过；失败不影响启动（下轮再试）
    this.migrateLegacyMemory().catch((err: Error) => {
      hilog.error(DOMAIN, 'testTag', 'migrate legacy memory failed: %{public}s', err.message);
    });
  }

  private async migrateLegacyMemory(): Promise<void> {
    const legacyPath = this.context.filesDir + '/agent_memory.json';
    const bakPath = legacyPath + '.bak';
    if (!fs.accessSync(legacyPath) || fs.accessSync(bakPath)) {
      return;
    }
    const stat = fs.statSync(legacyPath);
    const file = fs.openSync(legacyPath, fs.OpenMode.READ_ONLY);
    let text = '';
    try {
      const buf = new ArrayBuffer(stat.size);
      fs.readSync(file.fd, buf, { offset: 0, length: stat.size });
      text = new util.TextDecoder('utf-8').decodeToString(new Uint8Array(buf));
    } finally {
      fs.closeSync(file);
    }
    const store = await MemoryStore.create(this.context);
    const rows: LegacyRow[] = Migrator.migrate(text);
    await store.migrationWrite(rows);
    fs.renameSync(legacyPath, bakPath);
    hilog.info(DOMAIN, 'testTag', 'legacy memory migrated');
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

    // 主题档位（system/light/dark）+ 皮肤（clear|aurora）：同步读持久化配置。不用
    // Environment.envProp —— 环境变量只反映系统明暗，会把手动档覆盖掉
    const themeMode: string = ConfigStore.getThemeModeSync(this.context);
    const skinId: string = ConfigStore.getSkinIdSync(this.context);
    AppStorage.setOrCreate('themeMode', themeMode);
    // loadContent 之前先试一次：手动档想让首帧就是对的；此时窗口可能尚未就绪而被忽略（无害）
    applySkinAndTheme(this.context, themeMode, skinId);

    // 沉浸式：内容延伸到状态栏/导航条之下
    windowStage.getMainWindowSync().setWindowLayoutFullScreen(true);

    windowStage.loadContent('pages/Index', (err) => {
      if (err.code) {
        hilog.error(DOMAIN, 'testTag', 'Failed to load the content. Cause: %{public}s', JSON.stringify(err));
        return;
      }
      hilog.info(DOMAIN, 'testTag', 'Succeeded in loading the content.');
    });

    // SDK 明示 setColorMode 的前置条件：窗口已创建且 UIAbility 页面已 loadContent ——
    // 故此处必须再应用一次。漏掉会退化成「$r 资源停在浅色档、AppStorage 已是 DARK」的半深色且零信号
    applySkinAndTheme(this.context, themeMode, skinId);

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

  // 系统明暗切换：仅「跟随系统」档重算（手动档不受影响）。这里绝不能再调 setColorMode——
  // 回调本身就是 setColorMode 触发的，再调即无限递归栈溢出（jscrash RangeError 亲历）
  onConfigurationUpdate(newConfig: Configuration): void {
    const mode: string | undefined = AppStorage.get<string>('themeMode');
    if (mode === undefined || mode === THEME_SYSTEM) {
      const skinId: string | undefined = AppStorage.get<string>('skinId');
      const dark: boolean = configIsDark(newConfig.colorMode);
      AppStorage.setOrCreate('colorMode', dark ? ColorMode.DARK : ColorMode.LIGHT);
      applySkinTokens(skinId === undefined ? 'clear' : skinId, dark);
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