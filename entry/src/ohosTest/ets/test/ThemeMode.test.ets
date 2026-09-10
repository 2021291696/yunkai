// ThemeMode 契约测试（鸿蒙侧）：锁「两套 ColorMode 枚举数值相反」+ 档位映射口径。
// ThemeMode.ets 的 configIsDark/effectiveDark 是纯逻辑，直接 import 测。
import { describe, it, expect } from '@ohos/hypium';
import { ConfigurationConstant } from '@kit.AbilityKit';
import { configIsDark, THEME_DARK, THEME_LIGHT, THEME_SYSTEM } from '../../../main/ets/common/ThemeMode';

export default function themeModeTest(): void {
  describe('themeModeTest', () => {
    it('theme_configisdark_两套枚举相反契约', 0, () => {
      // SDK 实证：ConfigurationConstant.ColorMode DARK=0 / LIGHT=1（与 ArkUI ColorMode LIGHT=0/DARK=1 相反）
      // 若未来 SDK 数值变化或有人把两套枚举混用，这里第一时间红
      expect(Number(ConfigurationConstant.ColorMode.COLOR_MODE_DARK) === 0).assertTrue();
      expect(Number(ConfigurationConstant.ColorMode.COLOR_MODE_LIGHT) === 1).assertTrue();
      expect(configIsDark(ConfigurationConstant.ColorMode.COLOR_MODE_DARK)).assertTrue();
      expect(configIsDark(ConfigurationConstant.ColorMode.COLOR_MODE_LIGHT)).assertFalse();
      // NOT_SET/undefined 都按「非深即浅」处理
      expect(configIsDark(ConfigurationConstant.ColorMode.COLOR_MODE_NOT_SET)).assertFalse();
      expect(configIsDark(undefined)).assertFalse();
    });
    it('theme_档位常量与manifest同源', 0, () => {
      expect(THEME_SYSTEM === 'system').assertTrue();
      expect(THEME_LIGHT === 'light').assertTrue();
      expect(THEME_DARK === 'dark').assertTrue();
    });
  });
}
