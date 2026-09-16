// 忆枢注入拼装（协议 §4.1/§4.2）：persona 由调用方作 baseSystem 注入一次——本函数【不含 persona】，
// 否则重复注入烧双倍 token（门0 审查实锤的双端共同缺陷）。
// 记忆段 = human 块（非空时）+ 标准记忆说明块；两块皆空（blank）→ 整段省略（返回 null）。

// 记忆说明块（协议 §4.2 标准文案，逐字双端同步，禁改一字）
export const MEMORY_GUIDE: string =
  '[记忆系统说明] 你拥有可持续的记忆。核心记忆常驻你的上下文：human 块是用户档案，\n' +
  'persona 块是你的自我定义，可用 core_memory_append 追加、core_memory_replace 修改\n' +
  '（块满时必须替换不再需要的旧内容）。用户的重要持久信息（身份、偏好、长期事实）\n' +
  '应主动存入核心记忆；一次性情节与背景资料用 archival_memory_insert 归档；\n' +
  '需要旧信息时用 archival_memory_search 检索；用户提及过往对话时用 conversation_search。\n' +
  '只记对用户有用的信息，存取要克制。';

export class MemoryInjection {
  // 拼「human 块 + 说明块」（不含 persona；两块皆空返回 null，调用方省略整段）
  static coreSection(persona: string, human: string): string | null {
    if (persona.trim() === '' && human.trim() === '') {
      return null;
    }
    const parts: string[] = [];
    if (human.trim() !== '') {
      parts.push(human);
    }
    parts.push(MEMORY_GUIDE);
    return parts.join('\n\n');
  }
}
