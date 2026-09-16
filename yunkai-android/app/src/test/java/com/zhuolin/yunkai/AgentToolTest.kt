package com.zhuolin.yunkai

import com.zhuolin.yunkai.model.AgentSkill
import com.zhuolin.yunkai.model.AppConfig
import com.zhuolin.yunkai.service.tools.AgentTool
import com.zhuolin.yunkai.service.tools.BuiltinTools
import com.zhuolin.yunkai.store.SkillSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

// 三内置工具契约（对应鸿蒙 use_skill_tool / tool_error / web_search_guides 用例；
// 鸿蒙版走真库/真网络的部分改为 FakeSkillRepo 与 fast-fail URL，逻辑分支等价）
class FakeSkillSource(private val skills: List<AgentSkill> = emptyList()) : SkillSource {
    override suspend fun list(): List<AgentSkill> = skills
    override suspend fun getByName(name: String): AgentSkill? = skills.firstOrNull { it.name == name }
}

class AgentToolTest {
    private fun repo(vararg skills: AgentSkill) = FakeSkillSource(skills.toList())

    @Test
    fun `web_search bocha 未配 key 返回引导文案而非裸401`() = runTest {
        val cfg = AppConfig(searchProvider = "bocha", searchApiKey = "")
        val tool = BuiltinTools.createAll(cfg, null, repo()).first { it.name == "web_search" }
        val out = tool.execute("""{"query":"AI"}""")
        assertTrue(out.contains("未配置搜索密钥"))
        assertTrue(out.contains("必应"))
    }

    @Test
    fun `web_search 缺 query 参数返回 error JSON`() = runTest {
        val tool = BuiltinTools.createAll(AppConfig(), null, repo()).first { it.name == "web_search" }
        assertTrue(tool.execute("""{}""").startsWith("{\"error\""))
    }

    @Test
    fun `web_search 参数不是合法JSON返回 error JSON`() = runTest {
        val tool = BuiltinTools.createAll(AppConfig(), null, repo()).first { it.name == "web_search" }
        assertTrue(tool.execute("not-json").startsWith("{\"error\""))
    }

    @Test
    fun `read_web 缺 url 返回 error JSON`() = runTest {
        val tool = BuiltinTools.createAll(AppConfig(), null, repo()).first { it.name == "read_web" }
        assertTrue(tool.execute("""{}""").startsWith("{\"error\""))
    }

    @Test
    fun `read_web 连接失败收敛为 error JSON 不抛异常`() = runTest {
        val tool = BuiltinTools.createAll(AppConfig(), null, repo()).first { it.name == "read_web" }
        val out = tool.execute("""{"url":"http://127.0.0.1:1/x"}""")
        assertTrue(out.contains("\"error\""))
    }

    @Test
    fun `use_skill 命中返回说明书全文包裹文案`() = runTest {
        val r = repo(AgentSkill(1, "eli5", "讲解", "说明书正文，含章节"))
        val tool = BuiltinTools.createAll(AppConfig(), null, r).first { it.name == "use_skill" }
        val out = tool.execute("""{"name":"eli5"}""")
        assertTrue(out.contains("说明书正文"))
        assertTrue(out.contains("请严格按它执行"))
    }

    @Test
    fun `use_skill 未命中返回 error JSON`() = runTest {
        val tool = BuiltinTools.createAll(AppConfig(), null, repo()).first { it.name == "use_skill" }
        val out = tool.execute("""{"name":"不存在的技能"}""")
        assertTrue(out.startsWith("{\"error\""))
        assertTrue(out.contains("未找到名为"))
    }

    @Test
    fun `use_skill forced 时免查库直返`() = runTest {
        // skillForced 非 null：即使库是空的也直接返回该技能
        val forced = AgentSkill(9, "临时技能", "临时", "临时说明书正文")
        val tool = BuiltinTools.createAll(AppConfig(), forced, repo()).first { it.name == "use_skill" }
        val out = tool.execute("""{"name":"随便"}""")
        assertTrue(out.contains("临时说明书正文"))
    }

    @Test
    fun `use_skill 缺 name 参数返回 error JSON`() = runTest {
        val tool = BuiltinTools.createAll(AppConfig(), null, repo()).first { it.name == "use_skill" }
        assertTrue(tool.execute("""{}""").startsWith("{\"error\""))
    }
}
