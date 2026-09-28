package com.zhuolin.yunkai

import com.zhuolin.yunkai.service.HtmlGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 门0 Important 回归：sanitize 安全面三类剥离（on* / 伪协议 / iframe）
class HtmlGuardHardeningTest {

    private fun doc(body: String) = "<!DOCTYPE html><html><head><title>t</title></head><body>$body</body></html>"

    @Test
    fun `guard 剥内联on事件_双引号`() {
        val out = HtmlGuard.sanitize(doc("""<img src="x" onerror="alert(1)">"""))
        assertNotNull(out)
        assertFalse(out!!.contains("onerror"))
        assertFalse(out.contains("alert"))
        assertTrue(out.contains("<img src=\"x\""))
    }

    @Test
    fun `guard 剥内联on事件_单引号与裸值`() {
        val out = HtmlGuard.sanitize(doc("""<body onload='x()'><div onclick=evil()><p>正文</p></div>"""))
        assertNotNull(out)
        assertFalse(out!!.contains("onload"))
        assertFalse(out.contains("onclick"))
        assertFalse(out.contains("evil"))
    }

    @Test
    fun `guard 剥javascript伪协议`() {
        val out = HtmlGuard.sanitize(doc("""<a href="javascript:doIt()">go</a><img src="javascript:x()">"""))
        assertNotNull(out)
        assertFalse(out!!.contains("javascript:"))
        assertTrue(out.contains("<a href=\"\">"))
    }

    @Test
    fun `guard 剥iframe整块`() {
        val out = HtmlGuard.sanitize(doc("""<iframe src="https://evil.example"></iframe><p>正文</p>"""))
        assertNotNull(out)
        assertFalse(out!!.contains("iframe"))
        assertTrue(out.contains("<p>正文</p>"))
    }

    @Test
    fun `guard 正常内容不受加固影响`() {
        val doc = doc("""<a href="https://example.com" title="ok">链接</a><img src="pic.png" alt="on">""")
        val out = HtmlGuard.sanitize(doc)
        assertNotNull(out)
        assertTrue(out!!.contains("https://example.com"))
        assertTrue(out.contains("pic.png"))
    }

    // 安全审计 run-1 NV-3 配套回归：导航类原语剥离（meta refresh / object / embed / base）
    @Test
    fun `guard 剥meta_refresh防画布跳转`() {
        val out = HtmlGuard.sanitize(doc("""<meta http-equiv="refresh" content="0;url=https://evil.example"><p>正文</p>"""))
        assertNotNull(out)
        assertFalse(out!!.contains("http-equiv"))
        assertFalse(out.contains("evil.example"))
        assertTrue(out.contains("<p>正文</p>"))
    }

    @Test
    fun `guard 剥object_embed_base`() {
        val out = HtmlGuard.sanitize(doc(
            """<object data="https://evil.example/x.html"></object><embed src="https://evil.example/y">""" +
                """<base href="https://evil.example/"><p>正文</p>"""))
        assertNotNull(out)
        assertFalse(out!!.contains("<object"))
        assertFalse(out.contains("<embed"))
        assertFalse(out.contains("<base"))
        assertFalse(out.contains("evil.example"))
        assertTrue(out.contains("<p>正文</p>"))
    }

    @Test
    fun `guard 保留普通meta不误伤`() {
        val out = HtmlGuard.sanitize(doc("""<meta name="author" content="someone"><p>正文</p>"""))
        assertNotNull(out)
        assertTrue(out!!.contains("meta name=\"author\""))
    }

    @Test
    fun `replykind 存量口径不变`() {
        org.junit.Assert.assertEquals(HtmlGuardReplyCompat.HTML, ReplyKindCompat.detect("<!doctype html><p>x</p>"))
    }
}

// 别名避免与既有测试类冲突（口径与 HtmlGuardTest 一致）
private typealias ReplyKindCompat = com.zhuolin.yunkai.service.ReplyKind
private typealias HtmlGuardReplyCompat = com.zhuolin.yunkai.service.ReplyKind
