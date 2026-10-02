package com.zhuolin.yunkai.screen

import androidx.compose.ui.text.font.FontFamily
import com.zhuolin.yunkai.ui.chat.renderMarkdown
import com.zhuolin.yunkai.ui.chat.renderMarkdownSingle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// MarkdownLite 块级语义（2026-10-02 围栏代码块/有序列表补丁）
class MarkdownLiteTest {
    private val fence = "```"

    @Test fun `围栏代码块整块等宽且去围栏`() {
        val parts = renderMarkdown("前文\n$fence json\n{\"a\":1}\n$fence\n后文")
        // 前文 / 代码块 / 后文 三块
        assertEquals(3, parts.size)
        assertEquals("{\"a\":1}", parts[1].text)
        // 整块 monospace（span 中含 Monospace 字族）
        assertTrue(parts[1].spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test fun `未闭合围栏也整块按代码渲染`() {
        val parts = renderMarkdown("$fence json\n{\"a\":1}\n{\"b\":2}")
        assertEquals(1, parts.size)
        assertEquals("{\"a\":1}\n{\"b\":2}", parts[0].text)
    }

    @Test fun `围栏内不解析行内标记`() {
        val parts = renderMarkdown("$fence\n**不是粗体**\n$fence")
        assertEquals(1, parts.size)                      // 无语言标注围栏：仅代码块一块
        assertEquals("**不是粗体**", parts[0].text)
        // 唯一 span 是代码样式：无粗体字重（围栏内不解析行内标记）
        assertEquals(1, parts[0].spanStyles.size)
        assertEquals(null, parts[0].spanStyles[0].item.fontWeight)
    }

    @Test fun `有序列表渲染为点列`() {
        val parts = renderMarkdown("1. 第一\n2. 第二")
        assertTrue(parts[0].text.startsWith("1. "))
        assertTrue(parts[1].text.startsWith("2. "))
        assertEquals(2, parts.size)
    }

    @Test fun `renderMarkdownSingle 跨块拼接`() {
        val s = renderMarkdownSingle("标题行\n$fence\ncode\n$fence")
        assertTrue(s.text.contains("标题行"))
        assertTrue(s.text.contains("code"))
    }

    @Test fun `blocks结构携带代码原文供复制`() {
        val blocks = com.zhuolin.yunkai.ui.chat.renderMarkdownBlocks("前文\n$fence json\n{\"a\":1}\n$fence")
        assertEquals(2, blocks.size)
        assertFalse(blocks[0].isCode)
        assertTrue(blocks[1].isCode)
        assertEquals("{\"a\":1}", blocks[1].codeText)
    }
}
