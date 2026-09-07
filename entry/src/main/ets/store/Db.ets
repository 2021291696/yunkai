// 数据库单例：eli5.db 三张表（会话/消息/技能）
import { relationalStore } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';

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
    Db.store = s;
    return s;
  }
}
