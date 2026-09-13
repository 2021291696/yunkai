// 主题模式（跟随系统 / 浅色 / 深色）：手动档调 setColorMode 让 $r('app.color.*') 走
// base/dark 资源换档；同时把「生效明暗」写进 AppStorage 'colorMode'，供按明暗分支的组件
// （壁纸亮度滤镜、跳板页底色）读取。
//
// 注意两套枚举不同名，不能直接互赋：
//   ConfigurationConstant.ColorMode 是「设值」用（COLOR_MODE_DARK = 0）
//   ArkUI ColorMode 是「UI 读取」用（LIGHT = 0, DARK = 1）
import { common, ConfigurationConstant } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { schemeFor } from '../theme/Schemes';

export const THEME_SYSTEM: string = 'system';
export const THEME_LIGHT: string = 'light';
export const THEME_DARK: string = 'dark';

// 配置里的明暗 → 布尔（避开两套枚举混用的坑）
// Configuration.colorMode 在 SDK 里是可选的（可能 undefined），按「非深即浅」处理
export function configIsDark(cm: ConfigurationConstant.ColorMode | undefined): boolean {
  return cm === ConfigurationConstant.ColorMode.COLOR_MODE_DARK;
}

// 生效明暗：手动档看档位；跟随系统档看当前生效配置（NOT_SET 时 config 报的就是系统值）
export function effectiveDark(ctx: common.UIAbilityContext, mode: string): boolean {
  if (mode === THEME_DARK) { return true; }
  if (mode === THEME_LIGHT) { return false; }
  return configIsDark(ctx.config.colorMode);
}

// 应用主题档位：设 colorMode + 同步 AppStorage。失败不致命（保持原样）
export function applyThemeMode(ctx: common.UIAbilityContext, mode: string): void {
  const cm: ConfigurationConstant.ColorMode =
    mode === THEME_DARK ? ConfigurationConstant.ColorMode.COLOR_MODE_DARK
      : mode === THEME_LIGHT ? ConfigurationConstant.ColorMode.COLOR_MODE_LIGHT
        : ConfigurationConstant.ColorMode.COLOR_MODE_NOT_SET;
  try {
    ctx.getApplicationContext().setColorMode(cm);
  } catch (e) {
    // 失败保持原样，但必须留痕：否则退化成「资源浅色 + AppStorage 深色」后无从发现
    hilog.error(0x0000, 'yunkai', 'setColorMode failed: %{public}s', JSON.stringify(e));
  }
  AppStorage.setOrCreate('colorMode', effectiveDark(ctx, mode) ? ColorMode.DARK : ColorMode.LIGHT);
}

// 只重算皮肤 token（绝不触碰 setColorMode）——系统明暗回调里用。
// 回调内若再调 setColorMode 会同步触发下一轮 onConfigurationUpdate → 无限递归栈溢出（真机亲历）
export function applySkinTokens(skinId: string, isDark: boolean): void {
  AppStorage.setOrCreate('skinId', skinId);
  AppStorage.setOrCreate('scheme', schemeFor(skinId, isDark));
}

// 应用皮肤+主题（唯一入口）：在 applyThemeMode 之上重算生效皮肤的完整 token 并整体写入
// AppStorage 'scheme'（页面 @StorageProp('scheme') 订阅即换装）。skinId 同步落 AppStorage 供
// 按 skinId 分支的组件（壁纸层、问候区）读取。必须单点调用——散落重算会复刻「半深色」退化。
// sysDark：跟随系统档在 onConfigurationUpdate 里拿到的系统明暗（ctx.config 此时可能未刷新）
export function applySkinAndTheme(ctx: common.UIAbilityContext, mode: string, skinId: string, sysDark?: boolean): void {
  applyThemeMode(ctx, mode);
  const dark: boolean = sysDark === undefined ? effectiveDark(ctx, mode) : sysDark;
  applySkinTokens(skinId, dark);
}
