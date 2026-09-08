package com.zhuolin.yunkai

import com.zhuolin.yunkai.service.HtmlGuard
import com.zhuolin.yunkai.service.ReplyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 渲染形态判定与 HTML 兜底（对应鸿蒙 guard_* / reply_kind_* 七用例 + 计划补充用例）
class HtmlGuardTest {
    @Test
    fun `guard 合法完整文档原样通过`() {
        val out = HtmlGuard.sanitize("<!DOCTYPE html><html><body>hi</body></html>")
        assertNotNull(out)
        assertTrue(out!!.contains("hi"))
    }

    @Test
    fun `guard 剥markdown围栏并注入viewport`() {
        val raw = "```html\n<!doctype html><html><head><title>t</title></head><body>hi</body></html>\n```"
        val out = HtmlGuard.sanitize(raw)
        assertNotNull(out)
        assertTrue(out!!.contains("name=\"viewport\""))
    }

    @Test
    fun `guard 纯文本拒绝`() {
        assertNull(HtmlGuard.sanitize("抱歉我无法输出"))
    }

    @Test
    fun `guard 剥script块`() {
        val doc = "<!DOCTYPE html><html><head><title>t</title></head><body><script>alert(1)</script><p>正文</p></body></html>"
        val out = HtmlGuard.sanitize(doc)
        assertNotNull(out)
        assertFalse(out!!.contains("<script"))
        assertFalse(out.contains("alert"))
        assertTrue(out.contains("正文"))
    }

    @Test
    fun `guard 自闭合script残片也剥`() {
        val doc = "<!DOCTYPE html><html><head></head><body><script src=\"x\"/><p>正文</p></body></html>"
        val out = HtmlGuard.sanitize(doc)
        assertNotNull(out)
        assertFalse(out!!.contains("<script"))
    }

    @Test
    fun `guard 缺viewport注入head`() {
        val out = HtmlGuard.sanitize("<!DOCTYPE html><html><head></head><body>x</body></html>")!!
        assertTrue(out.contains("name=\"viewport\""))
    }

    @Test
    fun `guard 无head时补head再注入viewport`() {
        val out = HtmlGuard.sanitize("<!DOCTYPE html><html><body>x</body></html>")!!
        assertTrue(out.contains("name=\"viewport\""))
        assertTrue(out.contains("<head>"))
    }

    @Test
    fun `guard 散文夹html子串由Chat层detect兜底`() {
        // 鸿蒙语义：contains("<html") 即过校验门，sanitize 返回原文非 null；
        // 「散文夹 html 子串」的误判由 Chat 落库前 detect(safe)==TEXT 兜底（计划示例断言与源不符，以源为准）
        val out = HtmlGuard.sanitize("我给你讲讲 <html 标签的用法")
        assertNotNull(out)
        assertEquals(ReplyKind.TEXT, ReplyKind.detect(out!!))
    }

    @Test
    fun `guard 超300000字返回null`() {
        assertNull(HtmlGuard.sanitize("<!DOCTYPE html><html>" + "x".repeat(300001)))
    }

    @Test
    fun `reply_kind html 前缀判定`() {
        assertEquals("html", ReplyKind.detect("<!DOCTYPE html><html></html>"))
        assertEquals("text", ReplyKind.detect("普通文本回答"))
    }

    @Test
    fun `reply_kind 大小写不敏感与裸html前缀`() {
        assertEquals("html", ReplyKind.detect("  <!doctype HTML><html><body>x</body></html>"))
        assertEquals("html", ReplyKind.detect("<html><body>裸html前缀</body></html>"))
    }

    @Test
    fun `reply_kind 行内标签片段判text`() {
        assertEquals("text", ReplyKind.detect("<div>非文档的行内标签片段</div>"))
    }
}
