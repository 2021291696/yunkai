package com.zhuolin.yunkai

import com.zhuolin.yunkai.service.SearchClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 关键词提取与三轨解析纯函数（对应鸿蒙 extract_keywords_* / parse_* 用例 + 计划补充用例）
class SearchClientTest {
    @Test
    fun `extract_keywords 剥疑问词与时间词`() {
        val k = SearchClient.extractKeywords("这周国际上有什么科技大事")
        assertTrue(k.contains("科技"))
        assertFalse(k.contains("什么"))
        assertFalse(k.contains("这周"))
    }

    @Test
    fun `extract_keywords 全停用词回退原句`() {
        // 「为什么」「呀」都是停用词，全部剥空 → 回退原句
        assertEquals("为什么呀", SearchClient.extractKeywords("为什么呀"))
    }

    @Test
    fun `extract_keywords 单停用词回退原句`() {
        assertEquals("为什么", SearchClient.extractKeywords("为什么"))
    }

    @Test
    fun `extract_keywords 去重保序且最多6词`() {
        // 7 个去重后的词 → 截到前 6 个；「苹果」重复出现只保留首次
        assertEquals(
            "苹果 香蕉 价格 行情 新闻 股价",
            SearchClient.extractKeywords("苹果 香蕉 苹果 价格 行情 新闻 股价 市值 苹果"),
        )
    }

    @Test
    fun `extract_keywords 剥标点`() {
        // 「？」混进搜索词会污染召回，必须剥掉
        val k = SearchClient.extractKeywords("什么是黑洞？")
        assertFalse(k.contains("？"))
        assertTrue(k.contains("黑洞"))
    }

    @Test
    fun `parse_bing 从 b_algo 块提取标题链接摘要`() {
        val html = "<li class=\"b_algo\"><h2 class=\"\"><a target=\"_blank\" href=\"https://baike.baidu.com/item/x\" h=\"ID=SERP\"><strong>安全电压</strong>_百度百科</a></h2><div class=\"b_caption\"><p class=\"b_lineclamp2\">安全电压是指不致使人直接致死或致残的电压&#0183;一般36V&ensp;以下</p></div></li><li class=\"b_algo\"><h2><a href=\"https://zhihu.com/p/1\">知乎讨论</a></h2><div><p>另一种说法</p></div></li>"
        val hits = SearchClient.parseBing(html)
        assertEquals(2, hits.size)
        assertEquals("https://baike.baidu.com/item/x", hits[0].url)
        assertTrue(hits[0].title.contains("安全电压"))
        assertTrue(hits[0].snippet.contains("36V") && hits[0].snippet.contains("·"))
    }

    @Test
    fun `parse_bing 简单块与实体解码`() {
        val html = "<li class=\"b_algo\"><h2><a href=\"https://a.com\">标题&amp;一</a></h2><p>摘要&nbsp;内容</p></li>"
        val hits = SearchClient.parseBing(html)
        assertEquals(1, hits.size)
        assertEquals("标题&一", hits[0].title)
        assertEquals("摘要 内容", hits[0].snippet)
    }

    @Test
    fun `parse_bocha summary 优先于 snippet`() {
        val j = "{\"code\":200,\"data\":{\"webPages\":{\"value\":[{\"name\":\"安全电压\",\"url\":\"https://a.b\",\"summary\":\"36V以下\"},{\"name\":\"t2\",\"url\":\"https://c.d\",\"snippet\":\"snip\"}]}}}"
        val hits = SearchClient.parseBocha(j)
        assertEquals(2, hits.size)
        assertEquals("36V以下", hits[0].snippet)
        assertEquals("snip", hits[1].snippet)
    }

    @Test
    fun `parse_tavily 取 content 截断200`() {
        val long = "长".repeat(300)
        val j = "{\"results\":[{\"title\":\"安全电压\",\"url\":\"https://a.b\",\"content\":\"36V以下\"},{\"title\":\"t2\",\"url\":\"https://c.d\",\"content\":\"$long\"}]}"
        val hits = SearchClient.parseTavily(j)
        assertEquals(2, hits.size)
        assertEquals("安全电压", hits[0].title)
        assertEquals(200, hits[1].snippet.length)
    }

    @Test
    fun `decode_entities 命名与数字实体`() {
        assertEquals("a & b < c > d \" e ' f · g · h", SearchClient.decodeEntities("a &amp; b &lt; c &gt; d &quot; e &#39; f &#183; g &#0183; h"))
    }
}
