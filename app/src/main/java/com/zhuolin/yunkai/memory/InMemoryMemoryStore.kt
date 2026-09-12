package com.zhuolin.yunkai.memory

/**
 * MemoryStore 的内存假实现：JVM 纯 JUnit 测试用（五工具契约/注入拼装不依赖 Android）。
 * 语义与 RoomMemoryStore 对齐：
 * - conversationSearch：MemoryStore.searchTerms 切词后多词 OR contains(ignoreCase)，
 *   等价 SQLite LIKE 的 ASCII 大小写不敏感；只认 user/assistant，createdAt 倒序；
 * - 测试播种口：coreBlocks 里直接 putCoreBlock、messages 直接 add。
 */
class InMemoryMemoryStore : MemoryStore {
    val coreBlocks = linkedMapOf<String, CoreBlock>()
    private val archivalRows = mutableListOf<ArchivalRow>()
    private var nextArchivalId = 1L

    /** conversation_search 的数据源（测试播种；生产由 Room 查 messages 表）。 */
    val messages = mutableListOf<ConversationHit>()

    override suspend fun getCoreBlock(name: String): CoreBlock? = coreBlocks[name]

    override suspend fun putCoreBlock(name: String, content: String) {
        coreBlocks[name] = CoreBlock(name, content, System.currentTimeMillis())
    }

    override suspend fun insertArchival(content: String, type: String, source: String): Long {
        val now = System.currentTimeMillis()
        val row = ArchivalRow(
            id = nextArchivalId++, content = content, type = type, source = source,
            hitCount = 0, createdAt = now, updatedAt = now,
        )
        archivalRows.add(row)
        return row.id
    }

    override suspend fun allArchival(): List<ArchivalRow> = archivalRows.toList()

    override suspend fun archivalByIds(ids: List<Long>): List<ArchivalRow> {
        val byId = archivalRows.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    override suspend fun conversationSearch(query: String, limit: Int): List<ConversationHit> {
        val terms = MemoryStore.searchTerms(query)
        return messages.asSequence()
            .filter { it.role == "user" || it.role == "assistant" }
            .filter { hit -> terms.any { hit.content.contains(it, ignoreCase = true) } }
            .sortedByDescending { it.createdAt }
            .take(limit)
            .toList()
    }

    override suspend fun migrationWrite(rows: List<Migrator.LegacyRow>) {
        rows.forEach { insertArchival(it.content, it.type, it.source) }
    }
}
