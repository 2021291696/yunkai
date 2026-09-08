package com.zhuolin.yunkai

import com.zhuolin.yunkai.model.ChatMsg
import com.zhuolin.yunkai.model.FunctionDef
import com.zhuolin.yunkai.model.JsonSchema
import com.zhuolin.yunkai.model.ToolDef
import com.zhuolin.yunkai.service.LlmClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 请求体构造与响应解析（对应鸿蒙 request_body_* / extract_message_* 六用例）
class LlmClientTest {
    @Test
    fun `request_body_plain 带 model 与 stream false 不带 tools`() {
        val msgs = listOf(ChatMsg("system", "s"), ChatMsg("user", "q"))
        val body = LlmClient.buildRequestBody("glm-4.7", msgs, null)
        assertTrue(body.contains("\"model\":\"glm-4.7\""))
        assertTrue(body.contains("\"stream\":false"))
        assertFalse(body.contains("web_search"))
        assertFalse(body.contains("\"tools\""))
    }

    @Test
    fun `request_body 恒写 maxTokens 16384`() {
        val msgs = listOf(ChatMsg("user", "q"))
        val body = LlmClient.buildRequestBody("glm-4.7", msgs, null)
        assertTrue(body.contains("\"max_tokens\":16384"))
    }

    @Test
    fun `request_body_tool_defs 非 null 才写入`() {
        val msgs = listOf(ChatMsg("user", "q"))
        val td = listOf(ToolDef("function", FunctionDef("web_search", "搜索", JsonSchema("object"))))
        val body = LlmClient.buildRequestBody("glm-4.7", msgs, td)
        assertTrue(body.contains("web_search"))
        assertTrue(body.contains("\"type\":\"function\""))
    }

    @Test
    fun `extract_message_parses_tool_calls`() {
        val j = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"web_search\",\"arguments\":\"{\\\"query\\\":\\\"AI\\\"}\"}}]}}]}"
        val m = LlmClient.extractMessage(j)
        assertEquals(1, m.toolCalls!!.size)
        assertEquals("web_search", m.toolCalls!![0].function.name)
    }

    @Test
    fun `extract_message_null_content 归一为空串且 toolCalls 保留`() {
        // tool_calls 轮普遍返回 content:null → 归一为 ''
        val j = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"web_search\",\"arguments\":\"{}\"}}]}}]}"
        val m = LlmClient.extractMessage(j)
        assertEquals("", m.content)
        assertEquals(1, m.toolCalls!!.size)
    }

    @Test
    fun `extract_message_tool_calls 非数组守卫`() {
        // 畸形响应：tool_calls 非数组（字符串）→ 视为未携带，content 照常返回
        val j = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"纯文本\",\"tool_calls\":\"oops\"}}]}"
        val m = LlmClient.extractMessage(j)
        assertEquals("纯文本", m.content)
        assertEquals(null, m.toolCalls)
    }

    @Test(expected = Exception::class)
    fun `extract_message 无 choices 抛错`() {
        LlmClient.extractMessage("{\"choices\":[]}")
    }
}
