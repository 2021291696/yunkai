package com.zhuolin.yunkai

import com.zhuolin.yunkai.model.AgentSkill
import com.zhuolin.yunkai.model.AppConfig
import com.zhuolin.yunkai.model.ChatMsg
import com.zhuolin.yunkai.model.ToolCall
import com.zhuolin.yunkai.model.ToolDef
import com.zhuolin.yunkai.service.AgentLoop
import com.zhuolin.yunkai.service.LoopEvent
import com.zhuolin.yunkai.service.OpenAiMessage
import com.zhuolin.yunkai.service.tools.AgentTool
import com.zhuolin.yunkai.service.tools.BuiltinTools
import com.zhuolin.yunkai.store.SkillSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// 引擎循环语义（对应鸿蒙 loop_* / pick_model_* 九用例 + 计划补充用例）
private class FakeSkillRepo(private val skills: List<AgentSkill> = emptyList()) : SkillSource {
    override suspend fun list(): List<AgentSkill> = skills
    override suspend fun getByName(name: String): AgentSkill? = skills.firstOrNull { it.name == name }
}

class AgentLoopTest {
    private fun cfg() = AppConfig(
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        model = "glm-5.3-flash", longModel = "glm-4.7",
    )
    private fun repo(vararg skills: AgentSkill) = FakeSkillRepo(skills.toList())

    private fun toolCall(name: String, args: String = "{}", id: String = "call_1") =
        ToolCall(id, "function", com.zhuolin.yunkai.model.FunctionCall(name, args))

    // ===== pickModel（纯函数）=====

    @Test
    fun `pick_model 未激活长文回退主模型`() {
        assertEquals("glm-5.3-flash", AgentLoop.pickModel(cfg(), false))
    }

    @Test
    fun `pick_model 激活且智谱端点切长文模型`() {
        assertEquals("glm-4.7", AgentLoop.pickModel(cfg(), true))
    }

    @Test
    fun `pick_model 非智谱端点不切换`() {
        val c = cfg().apply { baseUrl = "https://api.deepseek.com/v1"; model = "deepseek-chat" }
        assertEquals("deepseek-chat", AgentLoop.pickModel(c, true))
        assertEquals("deepseek-chat", AgentLoop.pickModel(c, false))
    }

    @Test
    fun `pick_model longModel 空串不切换`() {
        val c = cfg().apply { longModel = "" }
        assertEquals("glm-5.3-flash", AgentLoop.pickModel(c, true))
        assertEquals("glm-5.3-flash", AgentLoop.pickModel(c, false))
    }

    // ===== 循环语义 =====

    @Test
    fun `无工具调用直接作答 steps=1`() = runTest {
        val r = AgentLoop.run(cfg(), repo(), emptyList(), "你好", null, {},
            fakeChat = { _, _, _ -> OpenAiMessage("直接回答") })
        assertEquals("直接回答", r.answer)
        assertEquals(1, r.steps)
    }

    @Test
    fun `工具调用循环后作答`() = runTest {
        var calls = 0
        val events = mutableListOf<String>()
        // read_web 指向必失败地址：fast-fail 无真实网络依赖（web_search 必应轨会打真网，单测避开）
        val r = AgentLoop.run(cfg(), repo(), emptyList(), "读网页", null,
            { e -> events.add("${e.kind}:${e.toolName}") },
            fakeChat = { _, _, _ ->
                calls++
                if (calls == 1) OpenAiMessage("", listOf(toolCall("read_web", "{\"url\":\"http://127.0.0.1:1/x\"}")))
                else OpenAiMessage("搜索后的回答")
            })
        assertEquals("搜索后的回答", r.answer)
        assertEquals(2, r.steps)
        assertEquals("tool_start:read_web", events[0])
        assertTrue(events.contains("tool_done:read_web"))
    }

    @Test
    fun `未知工具收敛为 error JSON 不中断循环`() = runTest {
        var calls = 0
        var sawErrorToolMsg = false
        val r = AgentLoop.run(cfg(), repo(), emptyList(), "调用不存在的工具", null, {},
            fakeChat = { ms, _, _ ->
                calls++
                if (calls == 1) {
                    OpenAiMessage("", listOf(toolCall("no_such_tool")))
                } else {
                    for (m in ms) {
                        if (m.role == "tool" && m.content.contains("未知工具")) sawErrorToolMsg = true
                    }
                    OpenAiMessage("好的，那我直接回答")
                }
            })
        assertTrue(sawErrorToolMsg)
        assertEquals("好的，那我直接回答", r.answer)
        assertEquals(2, r.steps)
    }

    @Test
    fun `工具失败以 error 回传给模型自行调整`() = runTest {
        var calls = 0
        var sawErrorToolMsg = false
        val r = AgentLoop.run(cfg(), repo(), emptyList(), "读这个网页", null, {},
            fakeChat = { ms, _, _ ->
                calls++
                if (calls == 1) {
                    OpenAiMessage("", listOf(toolCall("read_web", "{\"url\":\"http://127.0.0.1:1/x\"}")))
                } else {
                    for (m in ms) {
                        if (m.role == "tool" && m.content.contains("{\"error\"")) sawErrorToolMsg = true
                    }
                    OpenAiMessage("该网页暂时无法访问")
                }
            })
        assertTrue(sawErrorToolMsg)
        assertEquals("该网页暂时无法访问", r.answer)
        assertEquals(2, r.steps)
    }

    @Test
    fun `步数上限后强制无 tools 收尾 steps=MAX加1`() = runTest {
        var calls = 0
        val kinds = mutableListOf<String>()
        val r = AgentLoop.run(cfg(), repo(), emptyList(), "继续搜", null,
            { e -> kinds.add(e.kind) },
            fakeChat = { _, tools, _ ->
                calls++
                if (tools == null) OpenAiMessage("步数上限收尾回答")
                else OpenAiMessage("", listOf(toolCall("read_web", "{\"url\":\"http://127.0.0.1:1/x\"}", id = "call_x$calls")))
            })
        assertEquals("步数上限收尾回答", r.answer)
        assertTrue(kinds.contains("limit"))
        assertEquals(AgentLoop.MAX_STEPS + 1, r.steps)
    }

    @Test
    fun `步数上限收尾空回答用兜底文案`() = runTest {
        val r = AgentLoop.run(cfg(), repo(), emptyList(), "继续搜", null, {},
            fakeChat = { _, tools, _ ->
                if (tools == null) OpenAiMessage("")
                else OpenAiMessage("", listOf(toolCall("read_web", "{\"url\":\"http://127.0.0.1:1/x\"}")))
            })
        assertEquals("已达到本轮工具步数上限，请稍后重试或换个问法", r.answer)
    }

    @Test
    fun `取消旗标命中抛 CANCELLED 且不再发起 LLM 请求`() = runTest {
        var fakeCalls = 0
        var threw = false
        var cancelErrMsg = ""
        try {
            AgentLoop.run(cfg(), repo(), emptyList(), "取消我", null, {},
                fakeChat = { _, _, _ ->
                    fakeCalls++
                    OpenAiMessage("", listOf(toolCall("read_web", "{\"url\":\"http://127.0.0.1:1/x\"}")))
                },
                isCancelled = { fakeCalls >= 1 })
        } catch (e: Exception) {
            threw = true
            cancelErrMsg = e.message ?: ""
        }
        assertTrue(threw)
        assertEquals(AgentLoop.CANCELLED_MSG, cancelErrMsg)
        assertEquals(1, fakeCalls)
    }

    @Test
    fun `最后一步工具执行期间取消则跳过收尾调用`() = runTest {
        var fakeCalls = 0
        var toolDoneCount = 0
        var threw = false
        try {
            AgentLoop.run(cfg(), repo(), emptyList(), "执行中取消", null,
                { e -> if (e.kind == "tool_done") toolDoneCount++ },
                fakeChat = { _, _, _ ->
                    fakeCalls++
                    OpenAiMessage("", listOf(toolCall("read_web", "{\"url\":\"http://127.0.0.1:1/x\"}", id = "call_w$fakeCalls")))
                },
                isCancelled = { toolDoneCount >= 10 })
        } catch (e: Exception) {
            threw = true
            assertEquals(AgentLoop.CANCELLED_MSG, e.message)
        }
        assertTrue(threw)
        assertEquals(10, fakeCalls)
    }

    @Test
    fun `use_skill 成功后本轮余下调用切长文模型`() = runTest {
        val seen = mutableListOf<String>()
        val forced = AgentSkill(1, "eli5", "讲解", "说明书正文")
        AgentLoop.run(cfg(), repo(), emptyList(), "@eli5 为什么天空是蓝的", forced, {},
            fakeChat = { _, _, model ->
                seen.add(model)
                if (seen.size == 1) OpenAiMessage("", listOf(toolCall("use_skill", "{\"name\":\"eli5\"}")))
                else OpenAiMessage("讲解正文")
            })
        assertEquals(listOf("glm-5.3-flash", "glm-4.7"), seen)
    }

    @Test
    fun `use_skill 失败不切长文模型`() = runTest {
        val seen = mutableListOf<String>()
        AgentLoop.run(cfg(), repo(), emptyList(), "讲黑洞", null, {},
            fakeChat = { _, _, model ->
                seen.add(model)
                if (seen.size == 1) OpenAiMessage("", listOf(toolCall("use_skill", "{\"name\":\"不存在的\"}")))
                else OpenAiMessage("直接讲解")
            })
        assertEquals(listOf("glm-5.3-flash", "glm-5.3-flash"), seen)
    }

    @Test
    fun `autoRoute 关闭时 system prompt 不注入技能清单`() = runTest {
        val c = cfg().apply { autoRoute = false }
        var sysPrompt = ""
        val r = AgentLoop.run(c, repo(), emptyList(), "你好", null, {},
            fakeChat = { ms, _, _ ->
                if (sysPrompt.isEmpty()) sysPrompt = ms[0].content
                OpenAiMessage("好的")
            })
        assertEquals("好的", r.answer)
        assertTrue(!sysPrompt.contains("[可用技能清单]"))
        assertTrue(sysPrompt.contains("@技能名"))
    }

    @Test
    fun `forced 技能不受 autoRoute 关闭影响`() = runTest {
        val c = cfg().apply { autoRoute = false }
        var sysPrompt = ""
        val forced = AgentSkill(1, "eli5", "科普讲解", "说明书正文")
        val r = AgentLoop.run(c, repo(), emptyList(), "讲黑洞", forced, {},
            fakeChat = { ms, _, _ ->
                if (sysPrompt.isEmpty()) sysPrompt = ms[0].content
                OpenAiMessage("讲解")
            })
        assertEquals("讲解", r.answer)
        assertTrue(sysPrompt.contains("[用户已指定技能]"))
        assertTrue(sysPrompt.contains("eli5"))
    }

    @Test
    fun `autoRoute 开启时注入全部技能清单`() = runTest {
        var sysPrompt = ""
        AgentLoop.run(cfg(), repo(AgentSkill(1, "eli5", "科普", "正文")), emptyList(), "你好", null, {},
            fakeChat = { ms, _, _ ->
                if (sysPrompt.isEmpty()) sysPrompt = ms[0].content
                OpenAiMessage("好的")
            })
        assertTrue(sysPrompt.contains("[可用技能清单]"))
        assertTrue(sysPrompt.contains("- eli5：科普"))
    }

    @Test
    fun `历史消息进 system 之后 user 之前`() = runTest {
        val history = listOf(ChatMsg("user", "上一问"), ChatMsg("assistant", "上一答"))
        var firstUserIdx = -1
        AgentLoop.run(cfg(), repo(), history, "新问题", null, {},
            fakeChat = { ms, _, _ ->
                firstUserIdx = ms.indexOfFirst { it.role == "user" }
                OpenAiMessage("好")
            })
        // messages[0]=system，历史 user/assistant 在其后，最后才是本轮 user
        assertEquals(1, firstUserIdx)
    }
}
