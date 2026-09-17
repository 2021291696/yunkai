// 技能仓库：skills 表增删查 + 首启从 rawfile 播种内置技能（eli5 / 狗头军师）
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

  // INSERT skills(name, description, content, builtin)；任何失败（如 name UNIQUE 冲突抛
  // BusinessError）统一收敛为 -1，下游 UI 依赖该契约判断重名
  static async insert(ctx: common.Context, name: string, description: string,
    content: string, builtin: boolean): Promise<number> {
    const store = await Db.get(ctx);
    const bucket: relationalStore.ValuesBucket = {
      'name': name,
      'description': description,
      'content': content,
      'builtin': builtin ? 1 : 0
    };
    try {
      return await store.insert('skills', bucket);
    } catch (err) {
      return -1;
    }
  }

  // DELETE FROM skills WHERE id=?
  static async remove(ctx: common.Context, id: number): Promise<void> {
    const store = await Db.get(ctx);
    const predicates = new relationalStore.RdbPredicates('skills');
    predicates.equalTo('id', id);
    await store.delete(predicates);
  }

  // 首启播种内置技能（幂等：无同名行才插，重复启动不重插）。内置多技能共用本入口。
  static async seedBuiltins(ctx: common.Context): Promise<void> {
    await SkillRepo.seedOne(ctx, 'skill_eli5.md', 'eli5');
    await SkillRepo.seedOne(ctx, 'skill_goutoujunshi.md', '狗头军师');
  }

  private static async seedOne(ctx: common.Context, rawFile: string,
    expectedName: string): Promise<void> {
    if (await SkillRepo.getByName(ctx, expectedName) !== null) {
      return;
    }
    const raw: Uint8Array = await ctx.resourceManager.getRawFileContent(rawFile);
    const text: string = util.TextDecoder.create('utf-8').decodeToString(raw);
    const skill = SkillRepo.parseMarkdown(text);
    await SkillRepo.insert(ctx, skill.name, skill.description, skill.content, true);
  }

  // 拆 frontmatter 与正文：按行扫描，首行 --- 为开启栅栏，其后第一条 --- 行为闭合栅栏，
  // 栅栏之间解析 name:/description: 两行；无配对栅栏时整篇按正文处理
  static parseMarkdown(text: string): AgentSkill {
    const skill = new AgentSkill();
    const lines: string[] = text.trim().split('\n');
    let bodyStart = 0;
    if (lines.length > 1 && lines[0].trim() === '---') {
      let close = -1;
      for (let i = 1; i < lines.length; i++) {
        if (lines[i].trim() === '---') {
          close = i;
          break;
        }
        if (lines[i].startsWith('name:')) {
          skill.name = lines[i].substring(5).trim();
        } else if (lines[i].startsWith('description:')) {
          skill.description = lines[i].substring(12).trim();
        }
      }
      if (close > 0) {
        bodyStart = close + 1;
      }
    }
    skill.content = lines.slice(bodyStart).join('\n').trim();
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
