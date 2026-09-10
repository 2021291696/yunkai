// 主题模式（跟随系统 / 浅色 / 深色）：手动档调 setColorMode 让 $r('app.color.*') 走
// base/dark 资源换档；同时把「生效明暗」写进 AppStorage 'colorMode'，供按明暗分支的组件
// （壁纸亮度滤镜、跳板页底色）读取。
//
// 注意两套枚举不同名，不能直接互赋：
//   ConfigurationConstant.ColorMode 是「设值」用（COLOR_MODE_DARK = 0）
//   ArkUI ColorMode 是「UI 读取」用（LIGHT = 0, DARK = 1）
import { common, ConfigurationConstant } from '@kit.AbilityKit';

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
    // 设置失败保持原样
  }
  AppStorage.setOrCreate('colorMode', effectiveDark(ctx, mode) ? ColorMode.DARK : ColorMode.LIGHT);
}
