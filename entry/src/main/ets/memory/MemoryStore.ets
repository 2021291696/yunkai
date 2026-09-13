// 忆枢记忆存储（协议 §1/§3）：RDB 单实现。Android 侧对应 MemoryStore.kt + RoomMemoryStore.kt。
// 表：core_blocks / archival / task_state / conversations(summary, summarized_until_turn)。
// 归档检索在本类内完成（BM25 + 新近度 + hit_count 递增），调用方只拿结果。
import { relationalStore } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { Db } from '../store/Db';
import { Bm25, Doc } from './Bm25';
import { Summarizer, SummaryState } from './Summarizer';
import { LegacyRow } from './Migrator';

export class CoreBlock {
  name: string = '';
  content: string = '';
}

export class ArchivalRow {
  id: number = 0;
  content: string = '';
  type: string = 'fact';
  source: string = 'agent';
  hitCount: number = 0;
  createdAt: number = 0;
}

export class SearchHitRow {
  id: number = 0;
  content: string = '';
  type: string = 'fact';
  createdAt: number = 0;
  score: number = 0;
}

export class ConversationHitRow {
  conversationId: number = 0;
  turnNo: number = 0;
  role: string = '';
  content: string = '';
  createdAt: number = 0;
}

export class MemoryStore {
  private s: relationalStore.RdbStore;

  private constructor(store: relationalStore.RdbStore) {
    this.s = store;
  }

  static async create(ctx: common.Context): Promise<MemoryStore> {
    const s = await Db.get(ctx);
    return new MemoryStore(s);
  }

  // ===== 核心块（协议 §1.1 / §3.1/3.2）=====

  async getCoreBlock(name: string): Promise<CoreBlock | null> {
    const pred = new relationalStore.RdbPredicates('core_blocks');
    pred.equalTo('name', name);
    const rs = await this.s.querySql('SELECT name, content FROM core_blocks WHERE name = ?', [name]);
    let out: CoreBlock | null = null;
    try {
      while (rs.goToNextRow()) {
        const b = new CoreBlock();
        b.name = rs.getString(rs.getColumnIndex('name'));
        b.content = rs.getString(rs.getColumnIndex('content'));
        out = b;
      }
    } finally {
      rs.close();
    }
    return out;
  }

  async putCoreBlock(name: string, content: string): Promise<void> {
    // upsert：有行则 UPDATE，无行则 INSERT（种子幂等判据=「行缺失」，协议 §4.1）
    const existing = await this.getCoreBlock(name);
    if (existing !== null) {
      const bucket: relationalStore.ValuesBucket = { 'content': content, 'updated_at': Date.now() };
      const pred = new relationalStore.RdbPredicates('core_blocks');
      pred.equalTo('name', name);
      await this.s.update(bucket, pred);
    } else {
      const bucket: relationalStore.ValuesBucket = { 'name': name, 'content': content, 'updated_at': Date.now() };
      await this.s.insert('core_blocks', bucket);
    }
  }

  // ===== 归档（协议 §1.2 / §3.3/3.4）=====

  async insertArchival(content: string, type: string, source: string): Promise<number> {
    const now = Date.now();
    const bucket: relationalStore.ValuesBucket = {
      'content': content, 'type': type, 'source': source,
      'hit_count': 0, 'created_at': now, 'updated_at': now,
    };
    return await this.s.insert('archival', bucket);
  }

  async allArchival(): Promise<ArchivalRow[]> {
    const rs = await this.s.querySql('SELECT id, content, type, source, hit_count, created_at FROM archival ORDER BY created_at DESC, id DESC');
    const out: ArchivalRow[] = [];
    try {
      while (rs.goToNextRow()) {
        const r = new ArchivalRow();
        r.id = rs.getLong(rs.getColumnIndex('id'));
        r.content = rs.getString(rs.getColumnIndex('content'));
        r.type = rs.getString(rs.getColumnIndex('type'));
        r.source = rs.getString(rs.getColumnIndex('source'));
        r.hitCount = rs.getLong(rs.getColumnIndex('hit_count'));
        r.createdAt = rs.getLong(rs.getColumnIndex('created_at'));
        out.push(r);
      }
    } finally {
      rs.close();
    }
    return out;
  }

  async deleteArchival(id: number): Promise<void> {
    const pred = new relationalStore.RdbPredicates('archival');
    pred.equalTo('id', id);
    await this.s.delete(pred);
  }

  async clearArchival(): Promise<number> {
    const n = await this.archivalCount();
    await this.s.delete(new relationalStore.RdbPredicates('archival'));
    return n;
  }

  async archivalCount(): Promise<number> {
    const rs = await this.s.querySql('SELECT COUNT(*) AS c FROM archival');
    let n = 0;
    try {
      while (rs.goToNextRow()) {
        n = rs.getLong(rs.getColumnIndex('c'));
      }
    } finally {
      rs.close();
    }
    return n;
  }

  // 命中计数 +1（不刷新 updated_at，协议 §3.4 澄清）；写失败不阻塞返回由调用方兜
  async incrementHitCounts(ids: number[]): Promise<void> {
    for (const id of ids) {
      try {
        await this.s.executeSql('UPDATE archival SET hit_count = hit_count + 1 WHERE id = ?', [id]);
      } catch (err) {
        // 单条失败继续（写失败不阻塞返回）
      }
    }
  }

  // 归档检索：BM25(§8) × 新近度；命中条目 hit_count 同批 +1（写失败不阻塞）
  async searchArchival(query: string, topK: number, nowMs: number): Promise<SearchHitRow[]> {
    const rows = await this.allArchival();
    const corpus: Doc[] = [];
    for (const r of rows) {
      const d: Doc = { id: r.id, content: r.content, createdAt: r.createdAt };
      corpus.push(d);
    }
    const hits = Bm25.search(corpus, query, topK, nowMs);
    const byId = new Map<number, ArchivalRow>();
    for (const r of rows) {
      byId.set(r.id, r);
    }
    const out: SearchHitRow[] = [];
    const hitIds: number[] = [];
    for (const h of hits) {
      const r = byId.get(h.id);
      if (r === undefined) { continue; }
      const row = new SearchHitRow();
      row.id = r.id;
      row.content = r.content;
      row.type = r.type;
      row.createdAt = r.createdAt;
      row.score = h.score;
      out.push(row);
      hitIds.push(r.id);
    }
    if (hitIds.length > 0) {
      await this.incrementHitCounts(hitIds);
    }
    return out;
  }

  // ===== 会话摘要（协议 §1.3 / §4.4）=====

  async summaryState(convId: number): Promise<SummaryState> {
    const rs = await this.s.querySql('SELECT summary, summarized_until_turn FROM conversations WHERE id = ?', [convId]);
    const st = new SummaryState();
    try {
      while (rs.goToNextRow()) {
        const sm = rs.getColumnIndex('summary');
        st.summary = rs.getString(sm) ?? '';
        st.untilTurn = rs.getLong(rs.getColumnIndex('summarized_until_turn'));
      }
    } finally {
      rs.close();
    }
    return st;
  }

  async appendSummary(convId: number, addition: string, untilTurn: number): Promise<void> {
    const st = await this.summaryState(convId);
    const joined = Summarizer.joinSummary(st.summary, addition);
    await this.s.executeSql('UPDATE conversations SET summary = ?, summarized_until_turn = ? WHERE id = ?',
      [joined, untilTurn, convId]);
  }

  async clearSummary(convId: number): Promise<void> {
    await this.s.executeSql('UPDATE conversations SET summary = ?, summarized_until_turn = ? WHERE id = ?',
      ['', 0, convId]);
  }

  // ===== conversation_search（协议 §3.5）：多词 OR LIKE，%/_ 字面转义 =====

  static escapeLike(term: string): string {
    return term.replace(/\\/g, '\\\\').replace(/%/g, '\\%').replace(/_/g, '\\_');
  }

  static likePattern(term: string): string {
    return '%' + MemoryStore.escapeLike(term) + '%';
  }

  async conversationSearch(query: string, limit: number): Promise<ConversationHitRow[]> {
    // 空查询语义与 Android 对齐：返回全库最近 limit 条（不带 content 过滤）
    const terms = query.trim().split(/\s+/).filter((t: string) => t !== '');
    const conds: string[] = [];
    const args: relationalStore.ValueType[] = [];
    for (const t of terms) {
      conds.push("content LIKE ? ESCAPE '\\'");
      args.push(MemoryStore.likePattern(t));
    }
    const where = conds.length > 0 ? ' AND (' + conds.join(' OR ') + ')' : '';
    args.push(limit);
    const sql = "SELECT conversation_id, turn_no, role, content, created_at FROM messages" +
      " WHERE role IN ('user','assistant')" + where +
      " ORDER BY created_at DESC LIMIT ?";
    const rs = await this.s.querySql(sql, args);
    const out: ConversationHitRow[] = [];
    try {
      while (rs.goToNextRow()) {
        const r = new ConversationHitRow();
        r.conversationId = rs.getLong(rs.getColumnIndex('conversation_id'));
        r.turnNo = rs.getLong(rs.getColumnIndex('turn_no'));
        r.role = rs.getString(rs.getColumnIndex('role'));
        r.content = rs.getString(rs.getColumnIndex('content'));
        r.createdAt = rs.getLong(rs.getColumnIndex('created_at'));
        out.push(r);
      }
    } finally {
      rs.close();
    }
    return out;
  }

  // ===== task_state（协议 §1.4 / §4.5）=====

  async saveTaskState(convId: number, traceJson: string, stepUsed: number): Promise<void> {
    await this.s.executeSql('DELETE FROM task_state WHERE conversation_id = ?', [convId]);
    await this.s.executeSql('INSERT INTO task_state (conversation_id, trace_json, step_used, updated_at) VALUES (?, ?, ?, ?)',
      [convId, traceJson, stepUsed, Date.now()]);
  }

  async getTaskState(convId: number): Promise<string | null> {
    const rs = await this.s.querySql('SELECT trace_json FROM task_state WHERE conversation_id = ?', [convId]);
    let out: string | null = null;
    try {
      while (rs.goToNextRow()) {
        out = rs.getString(rs.getColumnIndex('trace_json'));
      }
    } finally {
      rs.close();
    }
    return out;
  }

  async deleteTaskState(convId: number): Promise<void> {
    await this.s.executeSql('DELETE FROM task_state WHERE conversation_id = ?', [convId]);
  }

  // ===== 旧数据迁移落库（协议 §1.5；文件改名 .bak 由 EntryAbility 负责）=====

  async migrationWrite(rows: LegacyRow[]): Promise<void> {
    for (const r of rows) {
      const now = Date.now();
      const bucket: relationalStore.ValuesBucket = {
        'content': r.content, 'type': r.type, 'source': r.source,
        'hit_count': 0, 'created_at': now, 'updated_at': now,
      };
      await this.s.insert('archival', bucket);
    }
  }
}
