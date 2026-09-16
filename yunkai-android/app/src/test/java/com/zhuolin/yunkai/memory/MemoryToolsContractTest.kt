package com.zhuolin.yunkai.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 五工具契约（忆枢协议 §3）：入参/出参 JSON 形状、错误文案格式、预算拒绝、隐私挡位、
 * 检索排序形状。用 InMemoryMemoryStore 隔离存储，不依赖 Android；
 * @Before 每用例重建 store（用例间互不污染，JUnit 方法序不可依赖）。
 */
class MemoryToolsContractTest {
    private val json = Json
    private lateinit var store: InMemoryMemoryStore
    private var gear: String = "strict"

    @Before
    fun setUp() {
        store = InMemoryMemoryStore()
        gear = "strict"
    }

    private fun tool(name: String) = createMemoryTools(store) { gear }.first { it.name == name }

    private suspend fun run(name: String, args: String): JsonObject =
        json.parseToJsonElement(tool(name).execute(args)).jsonObject

    private fun errOf(o: JsonObject): String? =
        (o["error"] as? kotlinx.serialization.json.JsonPrimitive)?.content

    // ===== 3.1 core_memory_append =====

    @Test
    fun `append 成功按换行拼接并回传 chars 与 limit`() = runTest {
        val r1 = run("core_memory_append", """{"block":"human","content":"用户叫张三"}""")
        assertEquals(true, r1["appended"]!!.jsonPrimitive.boolean)
        assertEquals("human", r1["block"]!!.jsonPrimitive.content)
        assertEquals(5, r1["chars"]!!.jsonPrimitive.int)
        assertEquals(800, r1["limit"]!!.jsonPrimitive.int)
        val r2 = run("core_memory_append", """{"block":"human","content":"喜欢猫"}""")
        assertEquals(9, r2["chars"]!!.jsonPrimitive.int)
        assertEquals("用户叫张三\n喜欢猫", store.coreBlocks["human"]!!.content)
    }

    @Test
    fun `append 超限整次拒绝不部分写入`() = runTest {
        store.putCoreBlock("persona", "x".repeat(595))
        val before = store.coreBlocks["persona"]!!.content
        val r = run("core_memory_append", """{"block":"persona","content":"yyyyyyyyyy"}""")
        // 595 + 换行 + 10 = 606 > 600
        assertEquals("核心记忆 persona 块已满(606/600)，请用 core_memory_replace 替换不再需要的旧内容", errOf(r))
        assertEquals(before, store.coreBlocks["persona"]!!.content)
    }

    @Test
    fun `append 隐私闸门 strict 拒绝 secret`() = runTest {
        val r = run("core_memory_append", """{"block":"human","content":"我的密码：abc123"}""")
        assertEquals("[隐私闸门-strict] 检测到疑似secret，已拒存；该挡位可在设置中调整", errOf(r))
        assertTrue(store.coreBlocks["human"]?.content.isNullOrEmpty())
    }

    @Test
    fun `append standard 挡放行 secret`() = runTest {
        gear = "standard"
        val r = run("core_memory_append", """{"block":"human","content":"我的密码：abc123"}""")
        assertEquals(true, r["appended"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `append 未知挡位回退 strict`() = runTest {
        gear = "yolo"
        val r = run("core_memory_append", """{"block":"human","content":"我的密码：abc123"}""")
        assertEquals("[隐私闸门-strict] 检测到疑似secret，已拒存；该挡位可在设置中调整", errOf(r))
    }

    @Test
    fun `append block 非法或缺参报错`() = runTest {
        assertTrue(errOf(run("core_memory_append", """{"block":"notes","content":"x"}"""))!!.contains("block 须为"))
        assertTrue(errOf(run("core_memory_append", """{"block":"human"}"""))!!.contains("缺少 content"))
        assertTrue(errOf(run("core_memory_append", "not-json"))!!.contains("参数格式非法"))
    }

    // ===== 3.2 core_memory_replace =====

    @Test
    fun `replace 成功精确替换`() = runTest {
        store.putCoreBlock("human", "用户名叫张三\n住在杭州")
        val r = run("core_memory_replace", """{"block":"human","old_content":"住在杭州","new_content":"住在上海"}""")
        assertEquals(true, r["replaced"]!!.jsonPrimitive.boolean)
        assertEquals("human", r["block"]!!.jsonPrimitive.content)
        assertEquals("用户名叫张三\n住在上海", store.coreBlocks["human"]!!.content)
    }

    @Test
    fun `replace 未找到与多匹配分别报错`() = runTest {
        store.putCoreBlock("human", "AAA BBB AAA")
        assertEquals("old_content 未找到", errOf(run("core_memory_replace", """{"block":"human","old_content":"CCC","new_content":"D"}""")))
        assertEquals("old_content 匹配到 2 处，请提供更长的片段", errOf(run("core_memory_replace", """{"block":"human","old_content":"AAA","new_content":"D"}""")))
        assertEquals("AAA BBB AAA", store.coreBlocks["human"]!!.content)
    }

    @Test
    fun `replace 替换后超限整次拒绝`() = runTest {
        store.putCoreBlock("human", "M" + "x".repeat(798))   // 799 字
        val r = run("core_memory_replace", """{"block":"human","old_content":"M","new_content":"MMM"}""")
        assertEquals("核心记忆 human 块已满(801/800)，请用 core_memory_replace 替换不再需要的旧内容", errOf(r))
        assertEquals(799, store.coreBlocks["human"]!!.content.length)
    }

    @Test
    fun `replace 新内容过隐私闸门 strict`() = runTest {
        store.putCoreBlock("human", "笔记占位")
        val r = run("core_memory_replace", """{"block":"human","old_content":"占位","new_content":"口令是 xyz123"}""")
        assertTrue(errOf(r)!!.startsWith("[隐私闸门-strict]"))
    }

    // ===== 3.3 archival_memory_insert =====

    @Test
    fun `insert 成功默认 fact 且 id 递增`() = runTest {
        val r1 = run("archival_memory_insert", """{"content":"用户的猫叫团子"}""")
        assertEquals(true, r1["saved"]!!.jsonPrimitive.boolean)
        assertEquals(1L, r1["id"]!!.jsonPrimitive.long)
        val r2 = run("archival_memory_insert", """{"content":"聊过杭州搬家","type":"episode"}""")
        assertEquals(2L, r2["id"]!!.jsonPrimitive.long)
        val rows = store.allArchival()
        assertEquals("fact", rows[0].type)
        assertEquals("episode", rows[1].type)
        assertEquals("agent", rows[0].source)
    }

    @Test
    fun `insert 非法 type 与隐私命中拒绝`() = runTest {
        assertTrue(errOf(run("archival_memory_insert", """{"content":"x","type":"diary"}"""))!!.contains("type 须为"))
        assertTrue(errOf(run("archival_memory_insert", """{"content":"身份证是110101199003070778"}"""))!!
            .startsWith("[隐私闸门-strict] 检测到疑似idnum"))
        assertTrue(store.allArchival().isEmpty())
    }

    // ===== 3.4 archival_memory_search =====

    @Test
    fun `search 出参形状与相关度排序符合协议`() = runTest {
        store.insertArchival("用户的猫叫团子", "fact", "agent")
        store.insertArchival("用户在杭州上班", "fact", "agent")
        store.insertArchival("今天下雨了", "episode", "agent")
        val r = run("archival_memory_search", """{"query":"用户"}""")
        val arr = r["results"]!!.jsonArray
        assertEquals(r["count"]!!.jsonPrimitive.int, arr.size)
        // 0 分文档不过滤（协议 §8.5）：命中的 2 条排前（score>0），「今天下雨了」以 0 分垫底
        assertEquals(3, arr.size)
        val firstTwoIds = arr.take(2).map { it.jsonObject["id"]!!.jsonPrimitive.long }.toSet()
        assertEquals(setOf(1L, 2L), firstTwoIds)
        assertTrue(arr.take(2).all { it.jsonObject["score"]!!.jsonPrimitive.double > 0.0 })
        assertEquals(0.0, arr[2].jsonObject["score"]!!.jsonPrimitive.double, 1e-9)
        for (e in arr) {
            val o = e.jsonObject
            assertTrue(o.containsKey("id") && o.containsKey("content") &&
                o.containsKey("type") && o.containsKey("created_at") && o.containsKey("score"))
        }
    }

    @Test
    fun `search top_k 默认 5 上限 20`() = runTest {
        repeat(22) { store.insertArchival("词$it", "fact", "agent") }
        assertEquals(20, run("archival_memory_search", """{"query":"词","top_k":25}""")["count"]!!.jsonPrimitive.int)
        assertEquals(8, run("archival_memory_search", """{"query":"词","top_k":8}""")["count"]!!.jsonPrimitive.int)
        assertEquals(5, run("archival_memory_search", """{"query":"词"}""")["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun `search 空查询回退最近 top_k`() = runTest {
        repeat(7) { store.insertArchival("第${it}条", "fact", "agent") }
        val r = run("archival_memory_search", """{"query":"","top_k":5}""")
        assertEquals(5, r["count"]!!.jsonPrimitive.int)
        val r2 = run("archival_memory_search", """{"query":"？！"}""")
        assertEquals(5, r2["count"]!!.jsonPrimitive.int)
        assertTrue(r2["results"]!!.jsonArray.all { it.jsonObject["score"]!!.jsonPrimitive.double == 0.0 })
    }

    // ===== 3.5 conversation_search =====

    private fun seedMsg(convId: Long, turn: Int, role: String, content: String, at: Long) {
        store.messages.add(ConversationHit(convId, turn, role, content, at))
    }

    @Test
    fun `conversation 多词 OR 命中且过滤非对话角色`() = runTest {
        seedMsg(1, 0, "user", "找一下我的猫", 1000)
        seedMsg(1, 1, "assistant", "你的猫在沙发上", 2000)
        seedMsg(1, 2, "tool", "tool_result_喵", 2500)   // 工具结果不参与（协议 §6）
        seedMsg(2, 0, "user", "今天天气如何", 3000)
        val r = run("conversation_search", """{"query":"猫 沙发"}""")
        val arr = r["results"]!!.jsonArray
        assertEquals(2, arr.size)
        val first = arr[0].jsonObject
        assertEquals(1L, first["conversation_id"]!!.jsonPrimitive.long)
        assertEquals(1, first["turn_no"]!!.jsonPrimitive.int)
        assertEquals("assistant", first["role"]!!.jsonPrimitive.content)
        assertEquals("你的猫在沙发上", first["content"]!!.jsonPrimitive.content)
        assertEquals(2000L, first["created_at"]!!.jsonPrimitive.long)
        assertEquals(1000L, arr[1].jsonObject["created_at"]!!.jsonPrimitive.long)   // createdAt 倒序
    }

    @Test
    fun `conversation limit 默认 10 上限 50`() = runTest {
        repeat(60) { seedMsg(it.toLong(), 0, "user", "词$it", it.toLong()) }
        assertEquals(10, run("conversation_search", """{"query":"词"}""")["count"]!!.jsonPrimitive.int)
        assertEquals(50, run("conversation_search", """{"query":"词","limit":100}""")["count"]!!.jsonPrimitive.int)
    }

    // ===== §1.5 迁移写入 =====

    @Test
    fun `migrationWrite 把旧 KV 行写入 archival`() = runTest {
        val rows = Migrator.migrate("""{"k1":"v1","k2":{"a":1}}""")
        store.migrationWrite(rows)
        val all = store.allArchival()
        assertEquals(2, all.size)
        assertEquals("k1：v1", all[0].content)
        assertEquals("fact", all[0].type)
        assertEquals("legacy-m2", all[0].source)
    }
}
