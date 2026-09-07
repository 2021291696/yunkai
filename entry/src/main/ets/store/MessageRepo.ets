// 消息仓库：messages 表增查
import { relationalStore } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { Db } from './Db';
import { Msg } from '../model/Types';
import { HtmlExtractor } from '../service/HtmlExtractor';

export class MessageRepo {
  // 统一入库入口（取代 addUser/addAssistant）：
  // role='user'：turn_no=0、plain=''；role='assistant'：turnNo=现有 assistant 最大 turn_no+1，
  // plain=剥标签纯文本（历史 prompt 与侧栏要点用）；kind='html'|'text' 渲染形态持久化
  static async add(ctx: common.UIAbilityContext, convId: number, role: string,
    content: string, kind: string): Promise<void> {
    let turnNo: number = 0;
    let plain: string = '';
    if (role === 'assistant') {
      const existing: Msg[] = await MessageRepo.listByConv(ctx, convId);
      let maxTurn: number = 0;
      for (let i = 0; i < existing.length; i++) {
        const m: Msg = existing[i];
        if (m.role === 'assistant' && m.turnNo > maxTurn) {
          maxTurn = m.turnNo;
        }
      }
      turnNo = maxTurn + 1;
      plain = HtmlExtractor.stripTags(content);
    }
    const store = await Db.get(ctx);
    const bucket: relationalStore.ValuesBucket = {
      'conversation_id': convId,
      'role': role,
      'content': content,
      'plain': plain,
      'turn_no': turnNo,
      'created_at': Date.now(),
      'kind': kind
    };
    await store.insert('messages', bucket);
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
