// 皮肤 token 层（双端同构，对齐 Android ui/theme/Glass.kt 的 GlassScheme）：
// 设计维度 skin(clear|aurora) × 明暗(themeMode) → 一套完整颜色 token。
// 颜色一律 #AARRGGBB 字符串，页面经 @StorageProp('scheme') 读取；
// 换肤/换明暗由 common/ThemeMode.applySkinAndTheme 单点重算并整体替换 AppStorage 里的实例。
// clear 的取值 = 原 resources/{base,dark}/element/color.json 逐字迁移（start_window_background 等系统能力仍走 $r）。

// 四团极光的一团：组件中心坐标 + 半径（vp）+ 色（#AARRGGBB）
export class GlowSpot {
  public x: number = 0;
  public y: number = 0;
  public radius: number = 0;
  public color: string = '#00000000';

  constructor(x: number, y: number, radius: number, color: string) {
    this.x = x;
    this.y = y;
    this.radius = radius;
    this.color = color;
  }
}

export const SKIN_CLEAR: string = 'clear';
export const SKIN_AURORA: string = 'aurora';

export class SkinScheme {
  // 注意：字段默认值不能用 SKIN_CLEAR 常量——常量声明在类之后时，模块初始化
  // 执行 CLEAR_LIGHT = SkinScheme.clear(false) 时尚未绑定（TDZ，真机 ReferenceError 亲历）；
  // 常量已上移到类之前，方法体内引用安全
  public skinId: string = 'clear';
  public isDark: boolean = false;
  // 玻璃件
  public glassBg: string = '#8CFFFFFF';
  public glassBgStrong: string = '#ADFFFFFF';
  public glassBorder: string = '#E6FFFFFF';
  // 文字三级
  public textHi: string = '#16181D';
  public textMid: string = '#9E16181D';
  public textLow: string = '#5C16181D';
  // 强调
  public accent: string = '#0071E3';
  public accentSoft: string = '#1F0071E3';
  // 用户气泡：top→base 竖向渐变（clear 两值相同=实色；aurora 用品牌渐变）
  public bubbleUserTop: string = '#290071E3';
  public bubbleUser: string = '#290071E3';
  // 实底（极光画布基色 / clear 通用实底）
  public paperBase: string = '#F9F8F5';
  // 遮罩/纱：clear=壁纸遮罩；aurora=极光层的上下可读性纱
  public maskVTop: string = '#8CF9F8F5';
  public maskV35: string = '#1FF9F8F5';
  public maskV50: string = '#0FF9F8F5';
  public maskV70: string = '#33F9F8F5';
  public maskVBot: string = '#99F9F8F5';
  public maskHEdge: string = '#4DF9F8F5';
  // 极光四团（仅 aurora；clear 为空数组）
  public glows: GlowSpot[] = [];
  // aurora 上下可读性纱（clear 不用）
  public auroraVeilTop: string = '#33FFFFFF';
  public auroraVeilBot: string = '#33FFFFFF';

  // 通透系：现役设计，值=原 color.json 逐字迁移
  static clear(isDark: boolean): SkinScheme {
    const s = new SkinScheme();
    s.skinId = SKIN_CLEAR;
    s.isDark = isDark;
    if (isDark) {
      s.glassBg = '#19FFFFFF';
      s.glassBgStrong = '#24FFFFFF';
      s.glassBorder = '#47FFFFFF';
      s.textHi = '#F4F4F6';
      s.textMid = '#9EF4F4F6';
      s.textLow = '#61F4F4F6';
      s.accent = '#0A84FF';
      s.accentSoft = '#380A84FF';
      s.bubbleUserTop = '#4D0A84FF';
      s.bubbleUser = '#4D0A84FF';
      s.paperBase = '#1A1820';
      s.maskVTop = '#991A1820';
      s.maskV35 = '#1A1A1820';
      s.maskV50 = '#0D1A1820';
      s.maskV70 = '#261A1820';
      s.maskVBot = '#B31A1820';
      s.maskHEdge = '#4D1A1820';
    }
    return s;
  }

  // 极光系：设计简报 aurora-brief.md §2（探针 direction-g-aurora 精修版）
  static aurora(isDark: boolean): SkinScheme {
    const s = new SkinScheme();
    s.skinId = SKIN_AURORA;
    s.isDark = isDark;
    if (isDark) {
      s.glassBg = '#851C212E';
      s.glassBgStrong = '#9E222838';
      s.glassBorder = '#1AFFFFFF';
      s.textHi = '#ECEFF5';
      s.textMid = '#94ECEFF5';
      s.textLow = '#57ECEFF5';
      s.accent = '#6D96FF';
      s.accentSoft = '#1F6D96FF';
      s.bubbleUserTop = '#5A79DE';
      s.bubbleUser = '#4860C8';
      s.paperBase = '#0C0E14';
      s.auroraVeilTop = '#1AFFFFFF';
      s.auroraVeilBot = '#4D0C0E14';
      s.glows = [
        new GlowSpot(300, 60, 260, '#5C6092F0'),
        new GlowSpot(46, 210, 280, '#52987EF0'),
        new GlowSpot(215, 370, 240, '#38EEA47C'),
        new GlowSpot(117, 520, 200, '#2E78C8E6'),
      ];
    } else {
      s.glassBg = '#99FFFFFF';
      s.glassBgStrong = '#BDFFFFFF';
      s.glassBorder = '#D9FFFFFF';
      s.textHi = '#1B1F27';
      s.textMid = '#941B1F27';
      s.textLow = '#5C1B1F27';
      s.accent = '#4B7BF5';
      s.accentSoft = '#1F4B7BF5';
      s.bubbleUserTop = '#5F8EFF';
      s.bubbleUser = '#4468E8';
      s.paperBase = '#F4F5F8';
      s.auroraVeilTop = '#4DFFFFFF';
      s.auroraVeilBot = '#40FFFFFF';
      s.glows = [
        new GlowSpot(300, 60, 260, '#EB70B2FF'),
        new GlowSpot(46, 210, 280, '#CCBCA8FF'),
        new GlowSpot(215, 370, 240, '#B8FFD0AC'),
        new GlowSpot(117, 520, 200, '#4DFFBA9E'),
      ];
    }
    return s;
  }
}

export const CLEAR_LIGHT: SkinScheme = SkinScheme.clear(false);
export const CLEAR_DARK: SkinScheme = SkinScheme.clear(true);
export const AURORA_LIGHT: SkinScheme = SkinScheme.aurora(false);
export const AURORA_DARK: SkinScheme = SkinScheme.aurora(true);

// skinId × 生效明暗 → scheme 实例（未知 skinId 回退 clear）
export function schemeFor(skinId: string, isDark: boolean): SkinScheme {
  if (skinId === SKIN_AURORA) { return isDark ? AURORA_DARK : AURORA_LIGHT; }
  return isDark ? CLEAR_DARK : CLEAR_LIGHT;
}
