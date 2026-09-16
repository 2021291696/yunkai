package com.zhuolin.yunkai

import com.zhuolin.yunkai.model.AppConfig
import com.zhuolin.yunkai.model.ChatMsg
import com.zhuolin.yunkai.model.FunctionCall
import com.zhuolin.yunkai.model.ToolCall
import com.zhuolin.yunkai.service.AgentLoop
import com.zhuolin.yunkai.service.OpenAiMessage
import com.zhuolin.yunkai.service.tools.AgentTool
import com.zhuolin.yunkai.store.SkillSource
import com.zhuolin.yunkai.model.AgentSkill
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// M3 循环升级语义：步数三档 / 工具输出软预算 / 到顶轨迹（继续按钮的引擎侧）
private class FakeSkillRepoM3(private val skills: List<AgentSkill> = emptyList()) : SkillSource {
    override suspend fun list(): List<AgentSkill> = skills
    override suspend fun getByName(name: String): AgentSkill? = skills.firstOrNull { it.name == name }
}

// 定长输出的测试工具：预算截断与到顶轨迹都靠它制造工具轮
private class SpamTool(private val size: Int) : AgentTool() {
    override val name = "spam"
    override val description = "测试工具：返回定长文本"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override suspend fun execute(argsJson: String): String = "x".repeat(size)
}

class AgentLoopM3Test {
    private fun cfg() = AppConfig(baseUrl = "https://x.example", model = "m1")
    private val repo = FakeSkillRepoM3(emptyList())
    private val spam = SpamTool(40000)

    private fun callSpam(id: String) = ToolCall(id, "function", FunctionCall("spam", "{}"))

    // ===== 软预算（协议 §2 TOOL_OUTPUT_BUDGET=30000）=====

    @Test
    fun `工具输出超软预算被截断且带标记`() = runTest {
        val seen = mutableListOf<List<ChatMsg>>()
        val r = AgentLoop.run(cfg(), repo, emptyList(), "问", null, {},
            fakeChat = { ms, ts, _ ->
                seen.add(ms.toList())
                if (ts != null && ms.count { it.role == "tool" } == 0) {
                    OpenAiMessage("", listOf(callSpam("c1")))
                } else {
                    OpenAiMessage("完成")
                }
            },
            extraTools = listOf(spam),
        )
        assertEquals("完成", r.answer)
        assertFalse(r.hitLimit)
        val toolMsg = seen.last().first { it.role == "tool" }
        assertTrue("应带截断标记: ${toolMsg.content.length}", toolMsg.content.contains("已截断"))
        assertTrue("截断后不应远超预算: ${toolMsg.content.length}", toolMsg.content.length < AgentLoop.TOOL_OUTPUT_BUDGET + 100)
    }

    @Test
    fun `预算内输出不截断`() = runTest {
        val small = SpamTool(100)
        val seen = mutableListOf<List<ChatMsg>>()
        AgentLoop.run(cfg(), repo, emptyList(), "问", null, {},
            fakeChat = { ms, ts, _ ->
                seen.add(ms.toList())
                if (ts != null && ms.count { it.role == "tool" } == 0) {
                    OpenAiMessage("", listOf(callSpam("c1")))
                } else {
                    OpenAiMessage("完成")
                }
            },
            extraTools = listOf(small),
        )
        val toolMsg = seen.last().first { it.role == "tool" }
        assertEquals(100, toolMsg.content.length)
        assertFalse(toolMsg.content.contains("已截断"))
    }

    // 预算被前序结果打满（keep==0）：后续工具结果不再截零加标记，直接替换为耗尽提示
    @Test
    fun `预算耗尽后工具结果替换为耗尽提示`() = runTest {
        val full = SpamTool(AgentLoop.TOOL_OUTPUT_BUDGET)   // 首条恰好打满预算（不触发截断）
        val seen = mutableListOf<List<ChatMsg>>()
        AgentLoop.run(cfg(), repo, emptyList(), "问", null, {},
            fakeChat = { ms, _, _ ->
                seen.add(ms.toList())
                when (ms.count { it.role == "tool" }) {
                    0 -> OpenAiMessage("", listOf(callSpam("c1")))   // 第 1 次工具调用：恰好打满预算
                    1 -> OpenAiMessage("", listOf(callSpam("c2")))   // 第 2 次工具调用：预算已耗尽
                    else -> OpenAiMessage("完成")                     // 第 3 次直接收尾（无 toolCalls 即退出循环）
                }
            },
            extraTools = listOf(full),
        )
        val toolMsgs = seen.last().filter { it.role == "tool" }
        assertEquals(2, toolMsgs.size)
        // 首条恰好等于预算上限，原样回传不截断
        assertEquals(AgentLoop.TOOL_OUTPUT_BUDGET, toolMsgs[0].content.length)
        // 第二次工具调用：预算已耗尽 → 固定短提示文案
        assertEquals("本轮工具输出预算已耗尽，请基于已有结果作答", toolMsgs[1].content)
    }

    // ===== 到顶轨迹（继续按钮引擎侧）=====

    @Test
    fun `到顶时 hitLimit=true 且轨迹完整`() = runTest {
        val r = AgentLoop.run(cfg(), repo, emptyList(), "问", null, {},
            fakeChat = { _, ts, _ ->
                if (ts != null) OpenAiMessage("", listOf(callSpam("c${System.nanoTime()}")))
                else OpenAiMessage("收尾说明")
            },
            extraTools = listOf(SpamTool(10)),
            maxSteps = 2,
        )
        assertTrue(r.hitLimit)
        assertEquals(3, r.steps) // maxSteps + 1（收尾）
        val trace = r.trace ?: throw AssertionError("到顶时 trace 应非空")
        assertEquals("system", trace.first().role)
        assertEquals("tool", trace.last().role)          // 最后一条是工具结果（收尾指令不入轨，防续跑被毒化）
        assertFalse(trace.any { it.content.contains("不要再调用工具") })
    }

    @Test
    fun `正常作答 hitLimit=false trace=null`() = runTest {
        val r = AgentLoop.run(cfg(), repo, emptyList(), "问", null, {},
            fakeChat = { _, _, _ -> OpenAiMessage("直接回答") })
        assertFalse(r.hitLimit)
        assertEquals(null, r.trace)
    }

    // ===== 继续任务（seedMessages 续跑）=====

    @Test
    fun `seedMessages 续跑且 system 就地重建`() = runTest {
        // 模拟到顶轨迹：旧 system + user + assistant(toolCalls) + tool
        val trace = listOf(
            ChatMsg(role = "system", content = "旧的人格"),
            ChatMsg(role = "user", content = "原始问题"),
            ChatMsg(role = "assistant", content = "", toolCalls = listOf(ToolCall("c1", "function", FunctionCall("spam", "{}")))),
            ChatMsg(role = "tool", content = "工具结果", toolCallId = "c1"),
        )
        var sawSystem = ""
        val r = AgentLoop.run(cfg(), repo, emptyList(), "", null, {},
            fakeChat = { ms, _, _ ->
                sawSystem = ms.first { it.role == "system" }.content
                OpenAiMessage("接着干完了")
            },
            seedMessages = trace,
        )
        assertEquals("接着干完了", r.answer)
        assertEquals(1, r.steps)
        assertFalse(r.hitLimit)
        assertTrue("system 应就地重建为 AGENT_SYSTEM，而非旧轨迹值: $sawSystem", sawSystem.contains("智能助手"))
    }

    // ===== 轨迹序列化往返（task_state 落库/恢复路径）=====

    @Test
    fun `trace JSON 往返无损`() {
        val trace = listOf(
            ChatMsg(role = "system", content = "s"),
            ChatMsg(role = "user", content = "q"),
            ChatMsg(role = "assistant", content = "", toolCalls = listOf(ToolCall("c1", "function", FunctionCall("spam", "{}")))),
            ChatMsg(role = "tool", content = "r", toolCallId = "c1"),
        )
        val json = Json { ignoreUnknownKeys = true }
        val text = json.encodeToString(ListSerializer(ChatMsg.serializer()), trace)
        val back = json.decodeFromString(ListSerializer(ChatMsg.serializer()), text)
        assertEquals(trace, back)
    }

    // ===== 步数三档合法值（ConfigStore.sanitize 纯函数口径）=====

    @Test
    fun `sanitizeMaxSteps 非法值收敛默认`() {
        assertEquals(25, com.zhuolin.yunkai.store.ConfigStore.sanitizeMaxSteps(null))
        assertEquals(25, com.zhuolin.yunkai.store.ConfigStore.sanitizeMaxSteps(30))
        assertEquals(25, com.zhuolin.yunkai.store.ConfigStore.sanitizeMaxSteps(-1))
        assertEquals(10, com.zhuolin.yunkai.store.ConfigStore.sanitizeMaxSteps(10))
        assertEquals(50, com.zhuolin.yunkai.store.ConfigStore.sanitizeMaxSteps(50))
    }
}
