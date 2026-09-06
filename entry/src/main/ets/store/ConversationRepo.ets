// 会话仓库：conversations 表增删改查
import { relationalStore } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { Db } from './Db';
import { Conv } from '../model/Types';

export class ConversationRepo {
  // INSERT conversations(title, now, now)，返回新 id
  static async create(ctx: common.UIAbilityContext, title: string): Promise<number> {
    const store = await Db.get(ctx);
    const now: number = Date.now();
    const bucket: relationalStore.ValuesBucket = {
      'title': title,
      'created_at': now,
      'updated_at': now
    };
    return await store.insert('conversations', bucket);
  }

  // 查全部，ORDER BY updated_at DESC，映射成 Conv[]
  static async list(ctx: common.UIAbilityContext): Promise<Conv[]> {
    const store = await Db.get(ctx);
    const predicates = new relationalStore.RdbPredicates('conversations');
    predicates.orderByDesc('updated_at');
    const rs = await store.query(predicates, ['id', 'title', 'updated_at']);
    const out: Conv[] = [];
    try {
      while (rs.goToNextRow()) {
        const c = new Conv();
        c.id = rs.getLong(rs.getColumnIndex('id'));
        c.title = rs.getString(rs.getColumnIndex('title'));
        c.updatedAt = rs.getLong(rs.getColumnIndex('updated_at'));
        out.push(c);
      }
    } finally {
      rs.close();
    }
    return out;
  }

  // UPDATE conversations SET updated_at=now WHERE id=?
  static async touch(ctx: common.UIAbilityContext, id: number): Promise<void> {
    const store = await Db.get(ctx);
    const bucket: relationalStore.ValuesBucket = {
      'updated_at': Date.now()
    };
    const predicates = new relationalStore.RdbPredicates('conversations');
    predicates.equalTo('id', id);
    await store.update(bucket, predicates);
  }

  // title==='新对话' 时 UPDATE title = question 截前20字
  static async setTitleIfPlaceholder(ctx: common.UIAbilityContext, id: number, question: string): Promise<void> {
    const store = await Db.get(ctx);
    const bucket: relationalStore.ValuesBucket = {
      'title': question.substring(0, 20)
    };
    const predicates = new relationalStore.RdbPredicates('conversations');
    predicates.equalTo('id', id);
    predicates.equalTo('title', '新对话');
    await store.update(bucket, predicates);
  }

  // 先删 messages WHERE conversation_id=id，再删 conversations WHERE id=id
  static async remove(ctx: common.UIAbilityContext, id: number): Promise<void> {
    const store = await Db.get(ctx);
    const msgPred = new relationalStore.RdbPredicates('messages');
    msgPred.equalTo('conversation_id', id);
    await store.delete(msgPred);
    const convPred = new relationalStore.RdbPredicates('conversations');
    convPred.equalTo('id', id);
    await store.delete(convPred);
  }
}
