// 隐私闸门（忆枢协议 §5，写入前置）。ArkTS 侧同构移植，与 Kotlin PrivacyGate.kt 逐行对齐。
//
// 三类检测模式（§5.1，与 vectors/privacy_cases.json patterns 逐字一致）：
// - secret：`(密码|口令|passwd|password|pwd|api[\s_-]?key|secret|密钥)[^\n]{0,20}[:：=为是]\s*\S+`
//   （(?i) 在 ArkTS 非内联 flag，译为构造参数 'i'；折叠范围仅 ASCII 有保证，用例数据已限定）
// - idnum：`[1-9]\d{5}(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[\dXx]`
// - bankcard：`\b\d{16,19}\b` 经 Luhn 校验；跨语言纪律：ASCII 边界用环视
//   `(?<![0-9A-Za-z_])\d{16,19}(?![0-9A-Za-z_])` 等价模拟（JS \b 语义与 Kotlin 默认同为 ASCII 词边界）。
//
// 已知跨语言分歧（协议 §5.1 钉死用例层回避，不背）：
// - JS \s/\S 匹配 Unicode 空白全集，Kotlin 默认仅 ASCII 空白——用例数据仅含 ASCII 空白；
// - JS 'i' flag 为 Unicode 简单大小写折叠（如 K U+212A），Kotlin IGNORE_CASE 仅 ASCII——用例数据已限定。
//
// 挡位矩阵（§5.2）：strict 全拒 / standard 放 secret 拒 idnum·bankcard / free 全放；
// 未知挡位回退 strict（协议 §2 PRIVACY_GEAR_DEFAULT）。

export class PrivacyGate {
  static readonly SECRET: string = 'secret';
  static readonly IDNUM: string = 'idnum';
  static readonly BANKCARD: string = 'bankcard';

  private static readonly CATEGORIES: string[] = [PrivacyGate.SECRET, PrivacyGate.IDNUM, PrivacyGate.BANKCARD];

  private static readonly SECRET_PATTERN: RegExp =
    new RegExp('(密码|口令|passwd|password|pwd|api[\\s_-]?key|secret|密钥)[^\\n]{0,20}[:：=为是]\\s*\\S+', 'i');
  private static readonly IDNUM_PATTERN: RegExp =
    new RegExp('[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]');
  // g flag 供 String.match 取全部匹配（match 用 /g 时不消费 lastIndex，无状态残留）
  private static readonly BANKCARD_PATTERN: RegExp =
    new RegExp('(?<![0-9A-Za-z_])\\d{16,19}(?![0-9A-Za-z_])', 'g');

  // 标准银行卡 Luhn 校验（模 10 加权，自右起偶数位翻倍）；非数字字符或空串判假
  static luhnValid(digits: string): boolean {
    if (digits.length === 0) { return false; }
    let sum = 0;
    let alt = false;
    for (let i = digits.length - 1; i >= 0; i--) {
      const ch = digits.charAt(i);
      if (ch < '0' || ch > '9') { return false; }
      let d = ch.charCodeAt(0) - 0x30;
      if (alt) {
        d *= 2;
        if (d > 9) { d -= 9; }
      }
      sum += d;
      alt = !alt;
    }
    return sum % 10 === 0;
  }

  private static matches(category: string, text: string): boolean {
    if (category === PrivacyGate.SECRET) {
      return PrivacyGate.SECRET_PATTERN.test(text);
    }
    if (category === PrivacyGate.IDNUM) {
      return PrivacyGate.IDNUM_PATTERN.test(text);
    }
    // bankcard：所有 16-19 位数字段任一通过 Luhn 即命中
    const runs = text.match(PrivacyGate.BANKCARD_PATTERN);
    if (runs === null) { return false; }
    for (const run of runs) {
      if (PrivacyGate.luhnValid(run)) { return true; }
    }
    return false;
  }

  // 检测文本命中的类别，固定顺序 [secret, idnum, bankcard]（与对拍用例 expected_hits 一致）
  static detect(text: string): string[] {
    const hits: string[] = [];
    for (const category of PrivacyGate.CATEGORIES) {
      if (PrivacyGate.matches(category, text)) { hits.push(category); }
    }
    return hits;
  }

  // 三挡矩阵判定：命中且该挡位拒绝 → 返回首个被拒类别；放行返回 null。
  // 未知挡位字符串按默认 strict 处理（协议 §2 PRIVACY_GEAR_DEFAULT）。
  static check(content: string, gear: string): string | null {
    let rejected: string[];
    if (gear === 'standard') {
      rejected = [PrivacyGate.IDNUM, PrivacyGate.BANKCARD];  // secret 放行
    } else if (gear === 'free') {
      rejected = [];  // 全放行
    } else {
      rejected = PrivacyGate.CATEGORIES;  // strict 与未知挡位：全拒
    }
    for (const category of rejected) {
      if (PrivacyGate.matches(category, content)) { return category; }
    }
    return null;
  }
}
