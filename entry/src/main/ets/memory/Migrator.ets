// 旧数据迁移（忆枢协议 §1.5，一次性，启动时）。ArkTS 侧同构移植，与 Kotlin Migrator.kt 逐行对齐。
//
// 合法 JSON 对象：每个 KV 迁为一行，content = `<key>：<value>`（全角冒号连接）；
// value 非字符串时以紧凑 JSON（分隔符 `,` `:` 无空格）字符串化；
// value 为字符串时取原文（保留 \n 等已解码字符）。
// 损坏 / 非对象 JSON → 空数组（零迁移行）。键序按原 JSON 出现顺序保留。
//
// 已知跨语言分歧（协议 §1.5 数值字面量纪律钉死，用例层回避，不背）：
// - kotlinx 数字 JsonPrimitive 保留源文本字面量（`2.50`/`1e10` 原样），JS JSON.parse 归一化 Number
//   再 String() 输出——协议禁用尾随零与科学计数法形态的用例；
// - JS 对整数样键（"2"）按数值升序枚举，kotlinx 按出现序——用例数据不含整数样键。

// 迁移行（对应 Kotlin Migrator.LegacyRow）
export interface LegacyRow {
  content: string;
  type: string;
  source: string;
}

export class Migrator {
  static readonly TYPE_FACT: string = 'fact';
  static readonly SOURCE_LEGACY_M2: string = 'legacy-m2';

  static migrate(jsonText: string): LegacyRow[] {
    let root: Object | null = null;
    try {
      root = JSON.parse(jsonText) as Object;
    } catch (e) {
      return [];  // 损坏 JSON → 空数组
    }
    // 非对象（数组 / 字符串 / 数字 / 布尔 / null 顶层）→ 空数组（Kotlin root !is JsonObject 同口径）
    if (root === null || Array.isArray(root) || typeof root !== 'object') { return []; }
    const obj = root as Record<string, Object>;
    const rows: LegacyRow[] = [];
    for (const key of Object.keys(obj)) {
      const row: LegacyRow = {
        content: `${key}：${Migrator.stringify(obj[key])}`,
        type: Migrator.TYPE_FACT,
        source: Migrator.SOURCE_LEGACY_M2,
      };
      rows.push(row);
    }
    return rows;
  }

  // 字符串取解码后原文；数字/布尔/null 取字面量（JSON.stringify(null/true/42) 即字面量）；
  // 对象/数组紧凑序列化——JSON.stringify 默认即 `,` `:` 无空格、不转义非 ASCII、保留键序，与 kotlinx 一致
  private static stringify(value: Object): string {
    if (typeof value === 'string') { return value as string; }
    return JSON.stringify(value);
  }
}
