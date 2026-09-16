package com.zhuolin.yunkai.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧数据迁移对拍回放（忆枢协议 §1.5 + vectors/migrate_cases.json）。
 * 逐 case 断言 expected_rows 的 content/type/source 全字段。
 */
class MigrateVectorTest {
    private val json = Json

    private fun loadVector(name: String): String =
        javaClass.classLoader?.getResource("vectors/$name")?.readText()
            ?: error("测试资源缺失: vectors/$name")

    @Test
    fun `migrate 全量向量回放 rows 全字段一致`() {
        val root = json.parseToJsonElement(loadVector("migrate_cases.json")).jsonObject
        val cases = root["cases"]!!.jsonArray.map { it.jsonObject }
        assertTrue("用例数应大于 0", cases.isNotEmpty())

        val failures = ArrayList<String>()
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val inputJson = c["input_json"]!!.jsonPrimitive.content
            val expectedRows = c["expected_rows"]!!.jsonArray.map { it.jsonObject }

            val actualRows = Migrator.migrate(inputJson)
            if (actualRows.size != expectedRows.size) {
                failures.add("[$name] 行数不符: 期望 ${expectedRows.size} 实得 ${actualRows.size}")
                continue
            }
            for (i in expectedRows.indices) {
                val exp = expectedRows[i]
                val act = actualRows[i]
                val expTriple = Triple(
                    exp["content"]!!.jsonPrimitive.content,
                    exp["type"]!!.jsonPrimitive.content,
                    exp["source"]!!.jsonPrimitive.content
                )
                val actTriple = Triple(act.content, act.type, act.source)
                if (expTriple != actTriple) {
                    failures.add("[$name][$i] 行不符:\n  期望 $expTriple\n  实得 $actTriple")
                }
            }
        }
        assertTrue("对拍失败 ${failures.size} 处:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `migrate 非字符串值紧凑 JSON 字符串化 无空格`() {
        val rows = Migrator.migrate("""{"tags":["a","b"],"profile":{"nick":"小张","level":3}}""")
        assertEquals(2, rows.size)
        assertEquals("""tags：["a","b"]""", rows[0].content)
        assertEquals("""profile：{"nick":"小张","level":3}""", rows[1].content)
    }

    @Test
    fun `migrate 全角冒号连接与固定 type source`() {
        val rows = Migrator.migrate("""{"city":"杭州"}""")
        assertEquals(1, rows.size)
        assertEquals("city：杭州", rows[0].content)
        assertEquals("fact", rows[0].type)
        assertEquals("legacy-m2", rows[0].source)
    }

    @Test
    fun `migrate 损坏与非对象 JSON 返回空列表`() {
        assertTrue(Migrator.migrate("""{"user_name":"张三","city":""").isEmpty())
        assertTrue(Migrator.migrate("""["a","b","c"]""").isEmpty())
        assertTrue(Migrator.migrate(""""just a string"""").isEmpty())
        assertTrue(Migrator.migrate("not json at all").isEmpty())
        assertTrue(Migrator.migrate("").isEmpty())
    }

    @Test
    fun `migrate 键序保留与空对象空键`() {
        val rows = Migrator.migrate("""{"b_key":1,"a_key":2,"中键":3}""")
        assertEquals(listOf("b_key：1", "a_key：2", "中键：3"), rows.map { it.content })
        assertTrue(Migrator.migrate("{}").isEmpty())
        assertEquals("：空键名的值", Migrator.migrate("""{"":"空键名的值"}""").single().content)
    }
}
