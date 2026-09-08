package com.zhuolin.yunkai

import com.zhuolin.yunkai.service.HtmlExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 剥标签纯文本化（对应鸿蒙 extractor_strips_tags_and_entities）
class HtmlExtractorTest {
    @Test
    fun `strip_tags 去style去标签解码实体`() {
        val plain = HtmlExtractor.stripTags("<style>.a{}</style><h1>电 &amp; 磁</h1><p>讲解</p>")
        assertTrue(plain.contains("电 & 磁"))
        assertFalse(plain.contains("<h1>"))
        assertTrue(plain.contains("讲解"))
    }

    @Test
    fun `strip_tags 压空白截4000字`() {
        val long = "字".repeat(5000)
        val out = HtmlExtractor.stripTags("<p>$long</p>")
        assertEquals(4000, out.length)
    }
}
