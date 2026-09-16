package com.zhuolin.yunkai

import com.zhuolin.yunkai.model.ChatMsg
import com.zhuolin.yunkai.model.AppConfig
import com.zhuolin.yunkai.model.FunctionCall
import com.zhuolin.yunkai.model.ToolCall
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Types 序列化行为：线上协议蛇形键名是 function calling 的生死线
class TypesTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `ChatMsg 序列化为线上协议蛇形键名（tool_calls）`() {
        val msg = ChatMsg(
            role = "assistant", content = "",
            toolCalls = listOf(ToolCall("c1", "function", FunctionCall("web_search", "{\"query\":\"x\"}"))),
        )
        val s = json.encodeToString(msg)
        // OpenAI 协议要求蛇形；驼峰会让 function calling 静默失效
        assertTrue(s.contains("\"tool_calls\""))
        assertFalse(s.contains("\"toolCalls\""))
        val back = json.decodeFromString<ChatMsg>(s)
        assertEquals("web_search", back.toolCalls!![0].function.name)
    }

    @Test
    fun `AppConfig 默认值与鸿蒙版一致`() {
        val cfg = AppConfig()
        assertEquals("glm-4.7", cfg.longModel)
        assertEquals("bing", cfg.searchProvider)
        assertTrue(cfg.autoRoute)
        assertEquals("auto", cfg.searchMode)
    }
}
