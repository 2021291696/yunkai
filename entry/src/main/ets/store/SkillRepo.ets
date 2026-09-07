// 技能仓库：skills 表增删查 + 首启从 rawfile 播种内置 eli5 配方
import { relationalStore } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import { Db } from './Db';
import { AgentSkill } from '../model/Types';

export class SkillRepo {
  // SELECT * FROM skills ORDER BY id ASC → AgentSkill[]
  static async list(ctx: common.Context): Promise<AgentSkill[]> {
    const store = await Db.get(ctx);
    const predicates = new relationalStore.RdbPredicates('skills');
    predicates.orderByAsc('id');
    const cols: string[] = ['id', 'name', 'description', 'content', 'builtin'];
    const rs = await store.query(predicates, cols);
    const out: AgentSkill[] = [];
    try {
      while (rs.goToNextRow()) {
        out.push(SkillRepo.fromRow(rs));
      }
    } finally {
      rs.close();
    }
    return out;
  }

  // SELECT * FROM skills WHERE name=?；无则 null
  static async getByName(ctx: common.Context, name: string): Promise<AgentSkill | null> {
    const store = await Db.get(ctx);
    const predicates = new relationalStore.RdbPredicates('skills');
    predicates.equalTo('name', name);
    const cols: string[] = ['id', 'name', 'description', 'content', 'builtin'];
    const rs = await store.query(predicates, cols);
    let out: AgentSkill | null = null;
    try {
      if (rs.goToNextRow()) {
        out = SkillRepo.fromRow(rs);
      }
    } finally {
      rs.close();
    }
    return out;
  }

  // INSERT skills(name, description, content, builtin)，返回新 id
  static async insert(ctx: common.Context, name: string, description: string,
    content: string, builtin: boolean): Promise<number> {
    const store = await Db.get(ctx);
    const bucket: relationalStore.ValuesBucket = {
      'name': name,
      'description': description,
      'content': content,
      'builtin': builtin ? 1 : 0
    };
    return await store.insert('skills', bucket);
  }

  // DELETE FROM skills WHERE id=?
  static async remove(ctx: common.Context, id: number): Promise<void> {
    const store = await Db.get(ctx);
    const predicates = new relationalStore.RdbPredicates('skills');
    predicates.equalTo('id', id);
    await store.delete(predicates);
  }

  // skills 表为空时，读 rawfile/skill_eli5.md 插入 builtin=1 的内置配方（幂等）
  static async seedIfEmpty(ctx: common.Context): Promise<void> {
    const existing: AgentSkill[] = await SkillRepo.list(ctx);
    if (existing.length > 0) { return; }
    const raw: Uint8Array = await ctx.resourceManager.getRawFileContent('skill_eli5.md');
    const text: string = util.TextDecoder.create('utf-8').decodeToString(raw);
    const skill = SkillRepo.parseMarkdown(text);
    await SkillRepo.insert(ctx, skill.name, skill.description, skill.content, true);
  }

  // 拆 frontmatter（--- 包围的 name/description 两行）与正文
  static parseMarkdown(text: string): AgentSkill {
    const skill = new AgentSkill();
    let body = text.trim();
    if (body.startsWith('---')) {
      const close = body.indexOf('\n---', 3);
      if (close > 0) {
        const meta: string[] = body.substring(3, close).split('\n');
        body = body.substring(close + 4).trim();
        for (const line of meta) {
          if (line.startsWith('name:')) {
            skill.name = line.substring(5).trim();
          } else if (line.startsWith('description:')) {
            skill.description = line.substring(12).trim();
          }
        }
      }
    }
    skill.content = body;
    return skill;
  }

  private static fromRow(rs: relationalStore.ResultSet): AgentSkill {
    const s = new AgentSkill();
    s.id = rs.getLong(rs.getColumnIndex('id'));
    s.name = rs.getString(rs.getColumnIndex('name'));
    s.description = rs.getString(rs.getColumnIndex('description'));
    s.content = rs.getString(rs.getColumnIndex('content'));
    s.builtin = rs.getLong(rs.getColumnIndex('builtin')) === 1;
    return s;
  }
}
