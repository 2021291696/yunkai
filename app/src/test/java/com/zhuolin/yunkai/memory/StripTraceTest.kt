package com.zhuolin.yunkai.memory

import com.zhuolin.yunkai.model.ChatMsg
import com.zhuolin.yunkai.model.ContentImage
import com.zhuolin.yunkai.model.ContentPart
import com.zhuolin.yunkai.model.FunctionCall
import com.zhuolin.yunkai.model.ToolCall
import com.zhuolin.yunkai.ui.chat.stripTraceForPersist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// M3 到顶轨迹落库瘦身：带图轮 user 消息 contentParts 含 base64 大图，持久化 task_state 前
// 替换为占位文本（ChatViewModel.send / resumeTask 两处落库共用同一函数）；无图轮原样返回。
class StripTraceTest {

    // 含图轮：user 消息 contentParts（文字+base64 图）→ 整组替换为单条占位文本；其余消息原样保留
    @Test
    fun `含图轮 contentParts 替换为占位文本`() {
        val bigB64 = "data:image/jpeg;base64," + "A".repeat(10000)
        val trace = listOf(
            ChatMsg(role = "system", content = "系统提示"),
            ChatMsg(
                role = "user", content = "这张图里是什么",
                contentParts = listOf(
                    ContentPart(type = "text", text = "这张图里是什么"),
                    ContentPart(type = "image_url", imageUrl = ContentImage(bigB64)),
                ),
            ),
            ChatMsg(role = "assistant", content = "", toolCalls = listOf(ToolCall("c1", "function", FunctionCall("web_search", "{}")))),
            ChatMsg(role = "tool", content = "工具结果", toolCallId = "c1"),
        )
        val stripped = stripTraceForPersist(trace)
        assertEquals(4, stripped.size)
        // 无 contentParts 的消息原样保留
        assertEquals(ChatMsg(role = "system", content = "系统提示"), stripped[0])
        assertEquals("tool", stripped[3].role)
        assertEquals("工具结果", stripped[3].content)
        // 带图 user 消息：contentParts 换成单条占位文本
        val parts = stripped[1].contentParts
        assertTrue("占位后应只剩 1 个 part: $parts", parts != null && parts.size == 1)
        assertEquals("text", parts!![0].type)
        assertEquals("[图片/附件内容已于首轮消费，续跑上下文省略]", parts[0].text)
        // base64 不再出现在落库内容里
        assertFalse("base64 应被剔除", stripped.toString().contains("AAAA"))
        // 深拷贝：原 trace（供当轮内存续用）不被污染
        assertEquals(bigB64, trace[1].contentParts!![1].imageUrl?.url)
    }

    // 无图轮：全部消息 contentParts 为空 → 原样返回（同实例，零拷贝零改动）
    @Test
    fun `无图轮原样返回`() {
        val trace = listOf(
            ChatMsg(role = "system", content = "系统提示"),
            ChatMsg(role = "user", content = "问题"),
            ChatMsg(role = "assistant", content = "回答"),
        )
        assertSame(trace, stripTraceForPersist(trace))
        assertEquals(trace, stripTraceForPersist(trace))
    }
}
