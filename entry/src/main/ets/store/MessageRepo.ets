// 消息仓库：messages 表增查
import { relationalStore } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { Db } from './Db';
import { Msg } from '../model/Types';

export class MessageRepo {
  // INSERT messages(conversation_id,'user',问题原文,'',0,now)
  static async addUser(ctx: common.UIAbilityContext, convId: number, question: string): Promise<void> {
    const store = await Db.get(ctx);
    const bucket: relationalStore.ValuesBucket = {
      'conversation_id': convId,
      'role': 'user',
      'content': question,
      'plain': '',
      'turn_no': 0,
      'created_at': Date.now()
    };
    await store.insert('messages', bucket);
  }

  // INSERT assistant 行；turnNo = 现有 assistant 最大 turn_no + 1，返回新 turnNo
  static async addAssistant(ctx: common.UIAbilityContext, convId: number, html: string, plain: string): Promise<number> {
    const existing: Msg[] = await MessageRepo.listByConv(ctx, convId);
    let maxTurn: number = 0;
    for (let i = 0; i < existing.length; i++) {
      const m: Msg = existing[i];
      if (m.role === 'assistant' && m.turnNo > maxTurn) {
        maxTurn = m.turnNo;
      }
    }
    const turnNo: number = maxTurn + 1;
    const store = await Db.get(ctx);
    const bucket: relationalStore.ValuesBucket = {
      'conversation_id': convId,
      'role': 'assistant',
      'content': html,
      'plain': plain,
      'turn_no': turnNo,
      'created_at': Date.now()
    };
    await store.insert('messages', bucket);
    return turnNo;
  }

  // SELECT * FROM messages WHERE conversation_id=? ORDER BY id ASC → Msg[]
  static async listByConv(ctx: common.UIAbilityContext, convId: number): Promise<Msg[]> {
    const store = await Db.get(ctx);
    const predicates = new relationalStore.RdbPredicates('messages');
    predicates.equalTo('conversation_id', convId);
    predicates.orderByAsc('id');
    const cols: string[] = ['id', 'conversation_id', 'role', 'content', 'plain', 'turn_no', 'created_at'];
    const rs = await store.query(predicates, cols);
    const out: Msg[] = [];
    try {
      while (rs.goToNextRow()) {
        const m = new Msg();
        m.id = rs.getLong(rs.getColumnIndex('id'));
        m.convId = rs.getLong(rs.getColumnIndex('conversation_id'));
        m.role = rs.getString(rs.getColumnIndex('role'));
        m.content = rs.getString(rs.getColumnIndex('content'));
        m.plain = rs.getString(rs.getColumnIndex('plain'));
        m.turnNo = rs.getLong(rs.getColumnIndex('turn_no'));
        m.createdAt = rs.getLong(rs.getColumnIndex('created_at'));
        out.push(m);
      }
    } finally {
      rs.close();
    }
    return out;
  }

  // SELECT content FROM messages WHERE conversation_id=? AND turn_no=? AND role='assistant'；无则 ''
  static async getHtmlByTurn(ctx: common.UIAbilityContext, convId: number, turnNo: number): Promise<string> {
    const store = await Db.get(ctx);
    const predicates = new relationalStore.RdbPredicates('messages');
    predicates.equalTo('conversation_id', convId);
    predicates.equalTo('turn_no', turnNo);
    predicates.equalTo('role', 'assistant');
    const rs = await store.query(predicates, ['content']);
    let html: string = '';
    try {
      if (rs.goToNextRow()) {
        html = rs.getString(rs.getColumnIndex('content'));
      }
    } finally {
      rs.close();
    }
    return html;
  }
}
