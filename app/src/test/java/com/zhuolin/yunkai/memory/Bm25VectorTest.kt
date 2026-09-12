package com.zhuolin.yunkai.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * BM25 对拍回放（忆枢协议 §8 + vectors/bm25_cases.json，Python 参考实现为唯一权威计算器）。
 * 逐 case 断言期望 id 序列一致、每个 score 差 ≤ 1e-4。
 */
class Bm25VectorTest {
    private val json = Json

    private fun loadVector(name: String): String =
        javaClass.classLoader?.getResource("vectors/$name")?.readText()
            ?: error("测试资源缺失: vectors/$name")

    private fun loadCorpus(root: kotlinx.serialization.json.JsonObject): List<Bm25.Doc> =
        root["corpus"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            Bm25.Doc(
                id = o["id"]!!.jsonPrimitive.long,
                content = o["content"]!!.jsonPrimitive.content,
                createdAt = o["created_at"]!!.jsonPrimitive.long
            )
        }

    @Test
    fun `bm25 全量向量回放 id 序列与分数逐 case 一致`() {
        val root = json.parseToJsonElement(loadVector("bm25_cases.json")).jsonObject
        val corpus = loadCorpus(root)
        val cases = root["cases"]!!.jsonArray.map { it.jsonObject }
        assertTrue("用例数应大于 0", cases.isNotEmpty())

        val failures = ArrayList<String>()
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val query = c["query"]!!.jsonPrimitive.content
            val topK = c["top_k"]!!.jsonPrimitive.int
            val nowMs = c["now_ms"]!!.jsonPrimitive.long
            val expected = c["expected"]!!.jsonArray.map { it.jsonObject }

            val hits = Bm25.search(corpus, query, topK, nowMs)
            if (hits.size != expected.size) {
                failures.add("[$name] 条数不符: 期望 ${expected.size} 实得 ${hits.size}")
                continue
            }
            for (i in expected.indices) {
                val expId = expected[i]["id"]!!.jsonPrimitive.long
                val expScore = expected[i]["score"]!!.jsonPrimitive.double
                if (hits[i].id != expId) {
                    failures.add("[$name][$i] id 不符: 期望 $expId 实得 ${hits[i].id} (全序列 ${hits.map { it.id }})")
                    break
                }
                if (abs(hits[i].score - expScore) > 1e-4) {
                    failures.add("[$name][$i] id=$expId 分数超差: 期望 $expScore 实得 ${hits[i].score}")
                }
            }
        }
        assertTrue("对拍失败 ${failures.size} 处:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `bm25 切词规则抽查 中英混排`() {
        // CJK 二元滑窗 + 段长 1 单字 token + ASCII 整段 + 标点分隔
        assertEquals(listOf("用户", "户的", "的猫"), Bm25.tokenize("用户的猫"))
        assertEquals(listOf("猫"), Bm25.tokenize("猫"))
        assertEquals(listOf("api2024"), Bm25.tokenize("api2024"))
        assertEquals(listOf("api2024", "key"), Bm25.tokenize("API2024 Key"))
        assertEquals(emptyList<String>(), Bm25.tokenize("？！"))
        assertEquals(listOf("用户", "户的", "的猫", "叫团", "团子"), Bm25.tokenize("用户的猫，叫团子！"))
    }

    @Test
    fun `bm25 空语料与零分文档边界`() {
        assertTrue(Bm25.search(emptyList(), "查询", 5, 0L).isEmpty())
        // 0 分文档不过滤：不命中查询时返回最近 topK 条，score 0.0
        val corpus = listOf(
            Bm25.Doc(1, "甲乙丙", 100L),
            Bm25.Doc(2, "丁戊", 300L),
            Bm25.Doc(3, "甲甲", 200L)
        )
        val hits = Bm25.search(corpus, "不命中", 2, 1000L)
        assertEquals(listOf(2L, 3L), hits.map { it.id })
        assertTrue(hits.all { it.score == 0.0 })
    }
}
