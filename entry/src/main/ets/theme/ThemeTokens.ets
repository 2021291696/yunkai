// 方向 A 通透系：尺寸/材质常量（颜色走 $r 资源，不在这里）
import { curves, ICurve } from '@kit.ArkUI';

export class ThemeTokens {
  static readonly BLUR_CARD: number = 24;
  static readonly BLUR_DOCK: number = 28;
  static readonly R_CARD: number = 20;
  static readonly R_BUBBLE: number = 22;
  static readonly R_TIGHT: number = 4;
  static readonly BORDER_W: number = 0.5;
  static readonly RISE_MS: number = 700;
  // cubic-bezier(.16,1,.3,1)
  static readonly EASE: ICurve = curves.cubicBezierCurve(0.16, 1, 0.3, 1);
}
