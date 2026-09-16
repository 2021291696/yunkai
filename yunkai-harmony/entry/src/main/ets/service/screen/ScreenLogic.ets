// 屏幕感知纯逻辑层（M3 P1，Task 1）：节点快照格式化 / 敏感页关键词 / 隐私黑名单。
// 行为与输出 schema 与 Android 同源（yunkai-android service/screen/ScreenLogic.kt），双端工具口径一致。
// 不 import 无障碍 API——纯数据变换，logicTest 直测（对应 Android ScreenLogicTest）。
// Android 侧 ScreenRouteTable（视觉路线白名单）不迁移：鸿蒙无视觉路线（调研报告 B1），贫瘠树只向用户披露。

export class ScreenNode {
  text: string = '';
  cls: string = '';
  cx: number = 0;
  cy: number = 0;
  clickable: boolean = false;
  // 鸿蒙节点树无 isPassword 等价属性（调研报告 B3）：恒 false，密码框识别是已知盲区，
  // 敏感页判定只靠 ScreenGuard 关键词——与 Android「isPassword 节点坐标动作直接拒绝」的双保险不同。
  isPassword: boolean = false;

  constructor(text: string, cls: string, cx: number, cy: number, clickable: boolean) {
    this.text = text;
    this.cls = cls;
    this.cx = cx;
    this.cy = cy;
    this.clickable = clickable;
  }
}

export class ScreenFormat {
  // 节点树贫瘠阈值：可读文本节点少于此数 → 覆盖不足提示（鸿蒙无视觉兜底，只能建议换页面或换应用）
  static readonly MIN_TEXT_NODES: number = 5;

  // 快照 → 控件清单文本（模型可读 + 坐标可锚定，坐标=屏幕系中心点，写操作 tap 的锚定口径）。
  // 输出格式与 Android format() 逐段一致，模型提示词双端通用。
  static format(pkg: string, nodes: ScreenNode[]): string {
    let sb: string = `当前应用: ${pkg}（可读文本节点 ${nodes.filter((n: ScreenNode): boolean => n.text !== '').length} 个）\n`;
    let i = 0;
    for (const n of nodes) {
      i++;
      const t: string = n.isPassword ? '[密码框]' : `"${n.text}"`;
      sb += `[${i}] `;
      if (n.cls !== '') {
        sb += `${n.cls} `;
      }
      sb += `${t} @(${n.cx},${n.cy})`;
      if (n.clickable) {
        sb += ' 可点击';
      }
      sb += '\n';
    }
    return sb.trimEnd();
  }
}

export class ScreenBlacklist {
  // 内置银行/支付类：命中即拒绝读屏（Q6 默认不信，显式放行制）。
  // Android 内置名单是 Android 包名字体系，鸿蒙 bundleName 不同不可直接搬——
  // 已知鸿蒙 bundle 先列支付宝，其余靠用户增补集（ConfigStore，Task 2 接线设置页）承担。
  static readonly DEFAULT_SET: Set<string> = new Set<string>(['com.alipay.alipayfinance']);

  static isBlocked(pkg: string, userAdded: Set<string>): boolean {
    return pkg !== '' && (ScreenBlacklist.DEFAULT_SET.has(pkg) || userAdded.has(pkg));
  }
}

export class ScreenGuard {
  // 敏感页关键词（与 Android 同表）：节点树文本命中任一即视为敏感页。
  // 敏感页语义（设计简报 §四）：agent 只有「看」的权利，没有「动」的权利——写操作执行前每步复检。
  static readonly SENSITIVE_KEYWORDS: string[] = ['支付', '付款', '转账', '收款', '银行卡', '验证码', '密码'];

  static hasSensitive(text: string): boolean {
    for (const k of ScreenGuard.SENSITIVE_KEYWORDS) {
      if (text.includes(k)) {
        return true;
      }
    }
    return false;
  }
}
