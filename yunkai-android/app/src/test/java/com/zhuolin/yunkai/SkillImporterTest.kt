package com.zhuolin.yunkai

import com.zhuolin.yunkai.service.SkillImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// SKILL.md 导入解析（对应鸿蒙 importer_* 三用例）
class SkillImporterTest {
    @Test
    fun `标准 frontmatter 解析出三段`() {
        val md = "---\nname:翻译\ndescription:中英互译\n---\n# 翻译\n你是翻译官。"
        val s = SkillImporter.parse(md)!!
        assertEquals("翻译", s.name)
        assertEquals("中英互译", s.description)
        assertTrue(s.content.contains("翻译官"))
    }

    @Test
    fun `计划格式 frontmatter 带空格`() {
        val md = "---\nname: eli5\ndescription: 大图少字讲解\n---\n正文内容"
        val s = SkillImporter.parse(md)!!
        assertEquals("eli5", s.name)
        assertEquals("大图少字讲解", s.description)
        assertTrue(s.content.contains("正文内容"))
    }

    @Test
    fun `无frontmatter 降级取首标题`() {
        val s = SkillImporter.parse("# 写作助手\n帮你写文章。")
        assertEquals("写作助手", s!!.name)
        assertTrue(s.content.isNotEmpty())
    }

    @Test
    fun `无frontmatter无标题 解析不出name返回null`() {
        val body = "介".repeat(100)
        assertNull(SkillImporter.parse(body))
    }

    @Test
    fun `frontmatter缺description 降级取正文前80字`() {
        val body = "介".repeat(100)
        val md = "---\nname: 记忆术\n---\n" + body
        val s = SkillImporter.parse(md)!!
        assertEquals("记忆术", s.name)
        assertEquals(80, s.description.length)
    }

    @Test
    fun `空白输入返回null`() {
        assertNull(SkillImporter.parse("   "))
    }

    @Test
    fun `中文名多行description解析`() {
        // 狗头军师内置技能同构：中文 name + 多行 frontmatter（末行 description 之后闭栅栏）
        val md = "---\nname: 狗头军师\ndescription: 恋爱军师与情绪支持。先接住情绪，再分清事实，最后给能执行的选择。\n---\n# 狗头军师\n先接住情绪。"
        val s = SkillImporter.parse(md)!!
        assertEquals("狗头军师", s.name)
        assertTrue(s.description.startsWith("恋爱军师"))
        assertTrue(s.content.contains("先接住情绪"))
    }
}
