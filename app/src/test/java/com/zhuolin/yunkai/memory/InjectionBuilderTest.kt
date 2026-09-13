package com.zhuolin.yunkai.memory

import com.zhuolin.yunkai.model.AgentSkill
import com.zhuolin.yunkai.model.AppConfig
import com.zhuolin.yunkai.model.FunctionCall
import com.zhuolin.yunkai.model.ToolCall
import com.zhuolin.yunkai.service.AgentLoop
import com.zhuolin.yunkai.service.OpenAiMessage
import com.zhuolin.yunkai.store.SkillSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注入拼装（忆枢协议 §4.1/4.2/4.3）：
 * - 纯函数层：MemoryInjection.coreSection 两块拼接/为空省略/说明块逐字；
 * - 引擎层：AgentLoop.run 接 memory 后种子与注入生效；eli5（use_skill 成功）撤下五工具
 *   与记忆段；memory=null 时维持旧行为。
 */
class InjectionBuilderTest {
    // 协议 §4.2 记忆说明块：逐字锚定（改文案必须先改协议）
    private val guideExpected =
        "[记忆系统说明] 你拥有可持续的记忆。核心记忆常驻你的上下文：human 块是用户档案，\n" +
        "persona 块是你的自我定义，可用 core_memory_append 追加、core_memory_replace 修改\n" +
        "（块满时必须替换不再需要的旧内容）。用户的重要持久信息（身份、偏好、长期事实）\n" +
        "应主动存入核心记忆；一次性情节与背景资料用 archival_memory_insert 归档；\n" +
        "需要旧信息时用 archival_memory_search 检索；用户提及过往对话时用 conversation_search。\n" +
        "只记对用户有用的信息，存取要克制。"

    private fun cfg() = AppConfig(
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        model = "glm-5.3-flash", longModel = "glm-4.7",
    )

    private class EmptySkillRepo : SkillSource {
        override suspend fun list() = emptyList<AgentSkill>()
        override suspend fun getByName(name: String): AgentSkill? = null
    }

    private fun toolCall(name: String, args: String = "{}", id: String = "call_1") =
        ToolCall(id, "function", FunctionCall(name, args))

    // ===== 纯函数层 =====

    @Test
    fun `说明块文案与协议逐字一致`() {
        assertEquals(guideExpected, MemoryInjection.GUIDE)
    }

    @Test
    fun `两块有值时按 human 说明块顺序拼接（persona 由 baseSystem 注入不重复）`() {
        // 门0 审查修正：coreSection 不含 persona（AgentLoop 的 baseSystem 已注入一次）
        assertEquals("H内容\n\n$guideExpected", MemoryInjection.coreSection("P内容", "H内容"))
    }

    @Test
    fun `单块为空时只拼非空块与说明块`() {
        assertEquals("H内容\n\n$guideExpected", MemoryInjection.coreSection("", "H内容"))
        // persona 空而 human 非空：human + 说明块（baseSystem 由调用方回退 AGENT_SYSTEM）
        assertEquals("H内容\n\n$guideExpected", MemoryInjection.coreSection("", "H内容"))
    }

    @Test
    fun `两块皆空时整段省略`() {
        assertNull(MemoryInjection.coreSection("", ""))
        assertNull(MemoryInjection.coreSection(" ", "  "))
    }

    // ===== 引擎层 =====

    @Test
    fun `memory 接线时首跑播种且 system 注入种子与说明块`() = runTest {
        val store = InMemoryMemoryStore()
        var sys = ""
        val r = AgentLoop.run(cfg(), EmptySkillRepo(), emptyList(), "你好", null, {},
            fakeChat = { ms, _, _ ->
                if (sys.isEmpty()) sys = ms[0].content
                OpenAiMessage("好的")
            },
            memory = store)
        assertEquals("好的", r.answer)
        // 种子：persona=AGENT_SYSTEM 原文锚点、human=空串（行存在）
        assertTrue(store.coreBlocks["persona"]!!.content.startsWith(AGENT_SYSTEM_ANCHOR))
        assertEquals("", store.coreBlocks["human"]!!.content)
        // 注入：persona（即 system 开头）+ 说明块
        assertTrue(sys.startsWith(AGENT_SYSTEM_ANCHOR))
        assertTrue(sys.contains(guideExpected))
    }

    private companion object {
        const val AGENT_SYSTEM_ANCHOR = "你是运行在用户"
    }

    @Test
    fun `两块皆空时 system 不含裸说明块`() = runTest {
        val store = InMemoryMemoryStore()
        // 人为把 persona 行置空（行仍在）→ 模拟用户清空：不重播种、整段省略、回退 AGENT_SYSTEM
        store.putCoreBlock("persona", " ")
        store.putCoreBlock("human", "")
        var sys = ""
        AgentLoop.run(cfg(), EmptySkillRepo(), emptyList(), "你好", null, {},
            fakeChat = { ms, _, _ ->
                if (sys.isEmpty()) sys = ms[0].content
                OpenAiMessage("好的")
            },
            memory = store)
        assertFalse(sys.contains("[记忆系统说明]"))
        assertTrue(sys.startsWith(AGENT_SYSTEM_ANCHOR))   // 回退 AGENT_SYSTEM，人格不丢
    }

    @Test
    fun `eli5 撤下五记忆工具且 system 撤记忆段`() = runTest {
        val store = InMemoryMemoryStore()
        val forced = AgentSkill(1, "eli5", "讲解", "说明书正文")
        var callIdx = 0
        val sysByCall = mutableListOf<String>()
        val toolNamesByCall = mutableListOf<List<String>>()
        val r = AgentLoop.run(cfg(), EmptySkillRepo(), emptyList(), "@eli5 讲讲天空", forced, {},
            fakeChat = { ms, tools, _ ->
                callIdx++
                sysByCall.add(ms[0].content)
                toolNamesByCall.add(tools?.map { it.function?.name ?: "" } ?: emptyList())
                if (callIdx == 1) OpenAiMessage("", listOf(toolCall("use_skill", """{"name":"eli5"}""")))
                else OpenAiMessage("讲解正文")
            },
            extraTools = createMemoryTools(store) { "strict" },
            memory = store)
        assertEquals("讲解正文", r.answer)
        // 第 1 步：五工具在表、记忆段已注入
        val names1 = toolNamesByCall[0]
        MemoryTools.ALL_NAMES.forEach { assertTrue("首步应含 $it", it in names1) }
        assertTrue(sysByCall[0].contains("[记忆系统说明]"))
        // 第 2 步（use_skill 成功后）：五工具全撤、记忆段撤下
        val names2 = toolNamesByCall[1]
        MemoryTools.ALL_NAMES.forEach { assertTrue("长文步不应含 $it", it !in names2) }
        assertFalse(sysByCall[1].contains("[记忆系统说明]"))
    }

    @Test
    fun `use_skill 失败不撤记忆工具与记忆段`() = runTest {
        val store = InMemoryMemoryStore()
        var callIdx = 0
        val sysByCall = mutableListOf<String>()
        val toolNamesByCall = mutableListOf<List<String>>()
        AgentLoop.run(cfg(), EmptySkillRepo(), emptyList(), "讲讲天空", null, {},
            fakeChat = { ms, tools, _ ->
                callIdx++
                sysByCall.add(ms[0].content)
                toolNamesByCall.add(tools?.map { it.function?.name ?: "" } ?: emptyList())
                if (callIdx == 1) OpenAiMessage("", listOf(toolCall("use_skill", """{"name":"不存在"}""")))
                else OpenAiMessage("直接讲解")
            },
            extraTools = createMemoryTools(store) { "strict" },
            memory = store)
        val names2 = toolNamesByCall[1]
        MemoryTools.ALL_NAMES.forEach { assertTrue(it in names2) }
        assertTrue(sysByCall[1].contains("[记忆系统说明]"))
    }

    @Test
    fun `memory 未接线时保持旧行为`() = runTest {
        var sys = ""
        AgentLoop.run(cfg(), EmptySkillRepo(), emptyList(), "你好", null, {},
            fakeChat = { ms, _, _ ->
                if (sys.isEmpty()) sys = ms[0].content
                OpenAiMessage("好的")
            })
        assertTrue(sys.startsWith(AGENT_SYSTEM_ANCHOR))
        assertFalse(sys.contains("[记忆系统说明]"))
    }
}
