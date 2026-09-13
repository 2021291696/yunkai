// 忆枢五工具（协议 §3，与 Android MemoryTools.kt 契约逐字对齐）：
// core_memory_append / core_memory_replace / archival_memory_insert / archival_memory_search / conversation_search
// 写入前置隐私闸门（§5）；错误一律 '{"error":...}' 字符串回传，绝不上抛。
import { AgentTool } from '../service/tools/AgentTool';
import { PrivacyGate } from './PrivacyGate';
import { Summarizer } from './Summarizer';
import { MemoryStore } from './MemoryStore';

// 失败收敛 helper（与 BuiltinTools/M2Tools 形态一致）
function memErr(msg: string): string {
  return JSON.stringify({ error: msg });
}

// args 解析 + string 字段提取（缺失/非 string → 空串）
function memField(argsJson: string, field: string): string {
  try {
    const obj = JSON.parse(argsJson) as Record<string, Object>;
    const v = obj[field];
    return typeof v === 'string' ? v as string : '';
  } catch (e) {
    return '';
  }
}

// args 提取 number 字段（缺失/非法 → fallback）
function memNumberOr(argsJson: string, field: string, fallback: number): number {
  try {
    const obj = JSON.parse(argsJson) as Record<string, Object>;
    const v = obj[field];
    return typeof v === 'number' ? v as number : fallback;
  } catch (e) {
    return fallback;
  }
}

// 检索结果的 wire 形状（JSON.stringify 直出，字段名与协议 §3.4/§3.5 一致）
interface SearchHitWire {
  id: number;
  content: string;
  type: string;
  created_at: number;
  score: number;
}

interface ConversationHitWire {
  conversation_id: number;
  turn_no: number;
  role: string;
  content: string;
  created_at: number;
}

export class MemoryTools {
  // 块名与预算常量（协议 §2 CORE_HUMAN_LIMIT / CORE_PERSONA_LIMIT）
  static readonly BLOCK_HUMAN: string = 'human';
  static readonly BLOCK_PERSONA: string = 'persona';
  static readonly CORE_HUMAN_LIMIT: number = 800;
  static readonly CORE_PERSONA_LIMIT: number = 600;
  static readonly ARCHIVAL_TOP_K: number = 5;
  static readonly ARCHIVAL_TOP_K_MAX: number = 20;
  static readonly CONVERSATION_SEARCH_LIMIT: number = 10;
  static readonly CONVERSATION_SEARCH_LIMIT_MAX: number = 50;

  static readonly ALL_NAMES: string[] = [
    'core_memory_append', 'core_memory_replace',
    'archival_memory_insert', 'archival_memory_search', 'conversation_search',
  ];

  // 工厂：五个记忆工具。gear=本轮隐私挡位 wire 串（Chat 每轮从 ConfigStore 取，未知回退 strict）
  static createMemoryTools(store: MemoryStore, gear: string): AgentTool[] {
    const out: AgentTool[] = [];
    out.push(MemoryTools.buildCoreAppend(store, gear));
    out.push(MemoryTools.buildCoreReplace(store, gear));
    out.push(MemoryTools.buildArchivalInsert(store, gear));
    out.push(MemoryTools.buildArchivalSearch(store));
    out.push(MemoryTools.buildConversationSearch(store));
    return out;
  }

  private static blockLimit(block: string): number {
    return block === MemoryTools.BLOCK_PERSONA
      ? MemoryTools.CORE_PERSONA_LIMIT : MemoryTools.CORE_HUMAN_LIMIT;
  }

  // 追加：目标块非空先补 \n（计入 chars）；超限整次拒绝（§3.1）
  private static joined(coreContent: string, addition: string): string {
    if (coreContent === '') {
      return addition;
    }
    return coreContent + '\n' + addition;
  }

  private static buildCoreAppend(store: MemoryStore, gear: string): AgentTool {
    const tool = new AgentTool();
    tool.name = 'core_memory_append';
    tool.description = '向核心记忆追加一条内容（human=用户档案，persona=你的自我定义）。参数：block、content。';
    tool.parametersJson = '{"type":"object","properties":{"block":{"type":"string","enum":["human","persona"]},"content":{"type":"string"}},"required":["block","content"]}';
    tool.execute = async (argsJson: string): Promise<string> => {
      try {
        const block = memField(argsJson, 'block');
        const content = memField(argsJson, 'content');
        if (block !== MemoryTools.BLOCK_HUMAN && block !== MemoryTools.BLOCK_PERSONA) {
          return memErr('block 必须为 human 或 persona');
        }
        if (content === '') {
          return memErr('缺少 content');
        }
        const rejected = PrivacyGate.check(content, gear);
        if (rejected !== null) {
          return memErr(`[隐私闸门-${gear}] 检测到疑似${rejected}，已拒存；该挡位可在设置中调整`);
        }
        const cur = await store.getCoreBlock(block);
        const existing = cur !== null ? cur.content : '';
        const merged = MemoryTools.joined(existing, content);
        const limit = MemoryTools.blockLimit(block);
        if (merged.length > limit) {
          return memErr(`核心记忆 ${block} 块已满(${merged.length}/${limit})，请用 core_memory_replace 替换不再需要的旧内容`);
        }
        await store.putCoreBlock(block, merged);
        return JSON.stringify({ appended: true, block: block, chars: merged.length, limit: limit });
      } catch (e) {
        return memErr(`核心记忆追加失败: ${(e as Error).message}`);
      }
    };
    return tool;
  }

  private static buildCoreReplace(store: MemoryStore, gear: string): AgentTool {
    const tool = new AgentTool();
    tool.name = 'core_memory_replace';
    tool.description = '替换核心记忆中的一段旧内容（改主意/订正时用）。参数：block、old_content（须在块内精确出现且唯一）、new_content。';
    tool.parametersJson = '{"type":"object","properties":{"block":{"type":"string","enum":["human","persona"]},"old_content":{"type":"string"},"new_content":{"type":"string"}},"required":["block","old_content","new_content"]}';
    tool.execute = async (argsJson: string): Promise<string> => {
      try {
        const block = memField(argsJson, 'block');
        const oldContent = memField(argsJson, 'old_content');
        const newContent = memField(argsJson, 'new_content');
        if (block !== MemoryTools.BLOCK_HUMAN && block !== MemoryTools.BLOCK_PERSONA) {
          return memErr('block 必须为 human 或 persona');
        }
        // 隐私闸门前置（协议 §3.3，与 Android 同序）且只检 new_content（old_content 是待删除方）
        const rejected = PrivacyGate.check(newContent, gear);
        if (rejected !== null) {
          return memErr(`[隐私闸门-${gear === 'strict' || gear === 'standard' || gear === 'free' ? gear : 'strict'}] 检测到疑似${rejected}，已拒存；该挡位可在设置中调整`);
        }
        const cur = await store.getCoreBlock(block);
        const existing = cur !== null ? cur.content : '';
        if (oldContent === '' || !existing.includes(oldContent)) {
          return memErr('old_content 未找到');
        }
        const hits: number = existing.split(oldContent).length - 1;
        if (hits > 1) {
          return memErr(`old_content 匹配到 ${hits} 处，请提供更长的片段`);
        }
        // 字面替换：split/join 不解释 new_content 里的 $&/$` 等替换模式（JS replace 会静默篡改）
        const merged = existing.split(oldContent).join(newContent);
        const limit = MemoryTools.blockLimit(block);
        if (merged.length > limit) {
          return memErr(`核心记忆 ${block} 块已满(${merged.length}/${limit})，请用 core_memory_replace 替换不再需要的旧内容`);
        }
        await store.putCoreBlock(block, merged);
        return JSON.stringify({ replaced: true, block: block, chars: merged.length, limit: limit });
      } catch (e) {
        return memErr(`核心记忆替换失败: ${(e as Error).message}`);
      }
    };
    return tool;
  }

  private static buildArchivalInsert(store: MemoryStore, gear: string): AgentTool {
    const tool = new AgentTool();
    tool.name = 'archival_memory_insert';
    tool.description = '把一条事实/情节存入归档记忆（无限量，可被 archival_memory_search 检索）。参数：content、type（fact|episode|preference|identity，默认 fact）。';
    tool.parametersJson = '{"type":"object","properties":{"content":{"type":"string"},"type":{"type":"string","enum":["fact","episode","preference","identity"]}},"required":["content"]}';
    tool.execute = async (argsJson: string): Promise<string> => {
      try {
        const content = memField(argsJson, 'content');
        if (content === '') {
          return memErr('缺少 content');
        }
        const type = memField(argsJson, 'type');
        if (type !== '' && type !== 'fact' && type !== 'episode' && type !== 'preference' && type !== 'identity') {
          return memErr('type 须为 fact|episode|preference|identity');
        }
        const rejected = PrivacyGate.check(content, gear);
        if (rejected !== null) {
          return memErr(`[隐私闸门-${gear}] 检测到疑似${rejected}，已拒存；该挡位可在设置中调整`);
        }
        const id = await store.insertArchival(content, type, 'agent');
        return JSON.stringify({ saved: true, id: id });
      } catch (e) {
        return memErr(`归档保存失败: ${(e as Error).message}`);
      }
    };
    return tool;
  }

  private static buildArchivalSearch(store: MemoryStore): AgentTool {
    const tool = new AgentTool();
    tool.name = 'archival_memory_search';
    tool.description = '检索归档记忆（BM25 排序）。参数：query（关键词/短句）、top_k（可选，默认 5，≤20）。';
    tool.parametersJson = '{"type":"object","properties":{"query":{"type":"string"},"top_k":{"type":"number"}},"required":["query"]}';
    tool.execute = async (argsJson: string): Promise<string> => {
      try {
        const query = memField(argsJson, 'query');
        let topK = Math.floor(memNumberOr(argsJson, 'top_k', MemoryTools.ARCHIVAL_TOP_K));
        if (topK < 1) { topK = 1; }
        if (topK > MemoryTools.ARCHIVAL_TOP_K_MAX) { topK = MemoryTools.ARCHIVAL_TOP_K_MAX; }
        const rows = await store.searchArchival(query, topK, Date.now());
        const results: SearchHitWire[] = [];
        for (const r of rows) {
          const w: SearchHitWire = { id: r.id, content: r.content, type: r.type, created_at: r.createdAt, score: r.score };
          results.push(w);
        }
        return JSON.stringify({ results: results, count: results.length });
      } catch (e) {
        return memErr(`归档检索失败: ${(e as Error).message}`);
      }
    };
    return tool;
  }

  private static buildConversationSearch(store: MemoryStore): AgentTool {
    const tool = new AgentTool();
    tool.name = 'conversation_search';
    tool.description = '检索过往对话原文（多关键词 OR 匹配，按时间倒序）。参数：query、limit（可选，默认 10，≤50）。';
    tool.parametersJson = '{"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"number"}},"required":["query"]}';
    tool.execute = async (argsJson: string): Promise<string> => {
      try {
        const query = memField(argsJson, 'query');
        let limit = Math.floor(memNumberOr(argsJson, 'limit', MemoryTools.CONVERSATION_SEARCH_LIMIT));
        if (limit < 1) { limit = 1; }
        if (limit > MemoryTools.CONVERSATION_SEARCH_LIMIT_MAX) { limit = MemoryTools.CONVERSATION_SEARCH_LIMIT_MAX; }
        const rows = await store.conversationSearch(query, limit);
        const results: ConversationHitWire[] = [];
        for (const r of rows) {
          const w: ConversationHitWire = { conversation_id: r.conversationId, turn_no: r.turnNo, role: r.role, content: r.content, created_at: r.createdAt };
          results.push(w);
        }
        return JSON.stringify({ results: results, count: results.length });
      } catch (e) {
        return memErr(`对话检索失败: ${(e as Error).message}`);
      }
    };
    return tool;
  }
}
