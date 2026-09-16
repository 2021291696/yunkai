package com.zhuolin.yunkai.service

import com.zhuolin.yunkai.model.AgentSkill

// SKILL.md 导入解析器（纯函数，单文件）：frontmatter 的 name/description + 正文 content；
// 无 frontmatter 时 name 降级取首个 "# " 标题行、description 取正文前 80 字；
// 解析不出 name（空白输入/无栅栏又无标题）返回 null。独立实现，不依赖 SkillRepo。
object SkillImporter {
    fun parse(text: String): AgentSkill? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        var name = ""
        var description = ""
        var body = trimmed

        // 剥 frontmatter：开栅栏 --- 与闭栅栏 --- 之间为元数据，其后为正文
        val fm = Regex("^---\\s*\\n([\\s\\S]*?)\\n---\\s*\\n([\\s\\S]*)$").find(text)
        if (fm != null) {
            val meta = fm.groupValues[1]
            body = fm.groupValues[2].trim()
            val nm = Regex("^name:\\s*(.+)$", RegexOption.MULTILINE).find(meta)
            if (nm != null) name = nm.groupValues[1].trim()
            val ds = Regex("^description:\\s*(.+)$", RegexOption.MULTILINE).find(meta)
            if (ds != null) description = ds.groupValues[1].trim()
        }

        // name 降级：frontmatter 没有 name（或没有 frontmatter）时取首个 "# " 标题行
        if (name.isEmpty()) {
            val head = Regex("^#\\s+(.+)$", RegexOption.MULTILINE).find(body)
            if (head != null) name = head.groupValues[1].trim()
        }
        if (name.isEmpty()) return null

        // description 降级：取正文前 80 字
        if (description.isEmpty()) {
            description = if (body.length > 80) body.substring(0, 80) else body
        }

        return AgentSkill(name = name, description = description, content = body)
    }
}
