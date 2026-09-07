// SKILL.md 导入解析器（纯函数，单文件）：frontmatter 的 name/description + 正文 content；
// 无 frontmatter 时 name 降级取首个 "# " 标题行、description 取正文前 80 字；
// 解析不出 name（空白输入/无栅栏又无标题）返回 null。独立实现，不依赖 SkillRepo。
import { AgentSkill } from '../model/Types';

export class SkillImporter {
  static parse(text: string): AgentSkill | null {
    const trimmed: string = text.trim();
    if (trimmed.length === 0) { return null; }

    const skill = new AgentSkill();
    let body: string = trimmed;

    // 剥 frontmatter：开栅栏 --- 与闭栅栏 --- 之间为元数据，其后为正文
    const fm: RegExpExecArray | null = /^---\s*\n([\s\S]*?)\n---\s*\n([\s\S]*)$/.exec(text);
    if (fm !== null) {
      const meta: string = fm[1];
      body = fm[2].trim();
      const nm: RegExpExecArray | null = /^name:\s*(.+)$/m.exec(meta);
      if (nm !== null) { skill.name = nm[1].trim(); }
      const ds: RegExpExecArray | null = /^description:\s*(.+)$/m.exec(meta);
      if (ds !== null) { skill.description = ds[1].trim(); }
    }

    // name 降级：frontmatter 没有 name（或没有 frontmatter）时取首个 "# " 标题行
    if (skill.name.length === 0) {
      const head: RegExpExecArray | null = /^#\s+(.+)$/m.exec(body);
      if (head !== null) { skill.name = head[1].trim(); }
    }
    if (skill.name.length === 0) { return null; }

    // description 降级：取正文前 80 字
    if (skill.description.length === 0) {
      skill.description = body.length > 80 ? body.substring(0, 80) : body;
    }

    skill.content = body;
    return skill;
  }
}
