package com.zhuolin.yunkai.memory

import com.zhuolin.yunkai.store.ConfigStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * M1c 补测（协议 §2/§3.4/§3.5 + 管理页支撑逻辑）：
 * - escapeLike/likePattern：§3.5 `%`/`_` 字面匹配的转义纯函数；
 * - 归档删/清空/计数：管理页新扩的 MemoryStore 方法（双实现对齐 InMemory 侧）；
 * - archival_memory_search 命中 hit_count +1（§3.4）且写失败不阻塞返回；
 * - 隐私挡位 wire 往返（§5.1，设置页写入字符串与闸门判定同一来源）。
 */
class MemoryManageM1cTest {
    private val json = Json
    private lateinit var store: InMemoryMemoryStore

    @Before
    fun setUp() {
        store = InMemoryMemoryStore()
    }

    // ===== §3.5 LIKE 字面转义（纯函数）=====

    @Test
    fun `escapeLike 普通字符原样保留`() {
        assertEquals("用户的猫", MemoryStore.escapeLike("用户的猫"))
        assertEquals("abc123 API-key", MemoryStore.escapeLike("abc123 API-key"))
    }

    @Test
    fun `escapeLike 转义百分号下划线与反斜杠本身`() {
        assertEquals("100\\%", MemoryStore.escapeLike("100%"))
        assertEquals("a\\_b", MemoryStore.escapeLike("a_b"))
        // 反斜杠先转义（否则后续转义符会被二次转义）
        assertEquals("a\\\\b", MemoryStore.escapeLike("a\\b"))
        assertEquals("\\%\\_\\\\\\%", MemoryStore.escapeLike("%_\\%"))
    }

    @Test
    fun `likePattern 前后加通配且内部已转义`() {
        assertEquals("%50\\%%", MemoryStore.likePattern("50%"))
        assertEquals("%win\\_loss%", MemoryStore.likePattern("win_loss"))
    }

    // ===== 协议 §2 常量钉死 =====

    @Test
    fun `软告警与默认挡位常量与协议一致`() {
        assertEquals(10000, MemoryStore.ARCHIVAL_WARN_COUNT)
        assertEquals("strict", ConfigStore.PRIVACY_GEAR_DEFAULT)
    }

    // ===== 管理页：删 / 清空 / 计数 =====

    @Test
    fun `deleteArchival 只删目标行`() = runTest {
        val a = store.insertArchival("甲", "fact", "agent")
        val b = store.insertArchival("乙", "fact", "agent")
        store.deleteArchival(a)
        val rest = store.allArchival()
        assertEquals(1, rest.size)
        assertEquals(b, rest[0].id)
    }

    @Test
    fun `clearArchival 清空并返回条数`() = runTest {
        repeat(3) { store.insertArchival("条$it", "fact", "agent") }
        assertEquals(3, store.archivalCount())
        assertEquals(3, store.clearArchival())
        assertEquals(0, store.archivalCount())
        assertEquals(0, store.clearArchival())   // 空库清空返回 0
    }

    // ===== §3.4 hit_count 写回 =====

    @Test
    fun `检索命中条目 hit_count 各加一`() = runTest {
        store.insertArchival("用户的猫叫团子", "fact", "agent")
        store.insertArchival("用户在杭州上班", "fact", "agent")
        val zero = store.insertArchival("无关内容占位", "fact", "agent")
        run(store, "archival_memory_search", """{"query":"用户"}""")
        val hits = store.allArchival()
        assertEquals(1, hits[0].hitCount)
        assertEquals(1, hits[1].hitCount)
        // §8.5：得分为 0 的文档不过滤、照常参与排序 → 同属返回结果，hit_count 同样 +1
        assertEquals(1, hits.first { it.id == zero }.hitCount)
        // 再检索一次：累加而非覆盖
        run(store, "archival_memory_search", """{"query":"杭州"}""")
        assertEquals(2, store.allArchival().first { it.id == hits[1].id }.hitCount)
    }

    @Test
    fun `检索写回失败不阻塞返回`() = runTest {
        store.insertArchival("用户的猫叫团子", "fact", "agent")
        val failing = FailingIncrementStore(store)
        val r = run(failing, "archival_memory_search", """{"query":"猫"}""")
        val arr = r["results"]!!.jsonArray
        assertEquals(1, arr.size)
        assertEquals(1L, arr[0].jsonObject["id"]!!.jsonPrimitive.long)
    }

    @Test
    fun `空查询回退最近列表同样写回命中`() = runTest {
        store.insertArchival("条一", "fact", "agent")
        store.insertArchival("条二", "fact", "agent")
        run(store, "archival_memory_search", """{"query":""}""")
        assertEquals(listOf(1, 1), store.allArchival().map { it.hitCount })
    }

    // ===== §5.1 挡位 wire 往返（设置页三选写什么，闸门就认什么）=====

    @Test
    fun `gear wire 往返与未知回退 strict`() {
        assertEquals("strict", PrivacyGate.Gear.STRICT.wire)
        assertEquals("standard", PrivacyGate.Gear.STANDARD.wire)
        assertEquals("free", PrivacyGate.Gear.FREE.wire)
        for (g in PrivacyGate.Gear.entries) {
            assertEquals(g, PrivacyGate.Gear.fromWire(g.wire))
        }
        assertEquals(PrivacyGate.Gear.STRICT, PrivacyGate.Gear.fromWire("bogus"))
        assertEquals(PrivacyGate.Gear.STRICT, PrivacyGate.Gear.fromWire(""))
    }

    // ===== helpers =====

    private suspend fun run(target: MemoryStore, name: String, args: String): kotlinx.serialization.json.JsonObject {
        val tool = createMemoryTools(target) { "strict" }.first { it.name == name }
        return json.parseToJsonElement(tool.execute(args)).jsonObject
    }

    /** 只在 incrementHitCounts 上注入失败的委托实现（§3.4「写失败不阻塞返回」的反向验证）。 */
    private class FailingIncrementStore(private val backing: InMemoryMemoryStore) : MemoryStore by backing {
        override suspend fun incrementHitCounts(ids: List<Long>) {
            throw IllegalStateException("simulated write failure")
        }
    }
}
