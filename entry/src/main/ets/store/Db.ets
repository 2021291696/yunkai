// 数据库单例：eli5.db 三张表（会话/消息/技能）
import { relationalStore } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { ReplyKind } from '../service/HtmlGuard';

export class Db {
  private static store: relationalStore.RdbStore | null = null;

  static async get(ctx: common.Context): Promise<relationalStore.RdbStore> {
    if (Db.store !== null) { return Db.store; }
    const cfg: relationalStore.StoreConfig = {
      name: 'eli5.db',
      securityLevel: relationalStore.SecurityLevel.S1
    };
    const s = await relationalStore.getRdbStore(ctx, cfg);
    await s.executeSql(`CREATE TABLE IF NOT EXISTS conversations (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      title TEXT NOT NULL,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL)`);
    await s.executeSql(`CREATE TABLE IF NOT EXISTS messages (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      conversation_id INTEGER NOT NULL,
      role TEXT NOT NULL,
      content TEXT NOT NULL,
      plain TEXT NOT NULL DEFAULT '',
      turn_no INTEGER NOT NULL DEFAULT 0,
      created_at INTEGER NOT NULL)`);
    // schema v2：skills 表（Agent 技能配方）
    await s.executeSql(`CREATE TABLE IF NOT EXISTS skills (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      name TEXT UNIQUE,
      description TEXT,
      content TEXT,
      builtin INTEGER DEFAULT 0)`);
    // schema 升级：messages 加 kind 列（'html'|'text'，渲染形态持久化）；已存在则容忍
    try {
      await s.executeSql(`ALTER TABLE messages ADD COLUMN kind TEXT`);
    } catch (err) {
      // 列已存在（升级重跑），忽略
    }
    await Db.backfillKinds(s);
    Db.store = s;
    return s;
  }

  // 存量行 kind 回填：kind IS NULL 的行按内容前缀判定一次（幂等，无行为零开销）
  private static async backfillKinds(s: relationalStore.RdbStore): Promise<void> {
    const rs = await s.querySql(`SELECT id, role, content FROM messages WHERE kind IS NULL`);
    const ids: number[] = [];
    const kinds: string[] = [];
    try {
      while (rs.goToNextRow()) {
        const role: string = rs.getString(rs.getColumnIndex('role'));
        const content: string = rs.getString(rs.getColumnIndex('content'));
        ids.push(rs.getLong(rs.getColumnIndex('id')));
        kinds.push(role === 'assistant' ? ReplyKind.detect(content) : ReplyKind.TEXT);
      }
    } finally {
      rs.close();
    }
    for (let i = 0; i < ids.length; i++) {
      const bucket: relationalStore.ValuesBucket = { 'kind': kinds[i] };
      const pred = new relationalStore.RdbPredicates('messages');
      pred.equalTo('id', ids[i]);
      await s.update(bucket, pred);
    }
  }
}
