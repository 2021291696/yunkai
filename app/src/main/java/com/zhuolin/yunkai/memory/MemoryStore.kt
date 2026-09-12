package com.zhuolin.yunkai.memory

/**
 * 忆枢存储接口（协议 §1）：存储层抽象是协议卖点——上层（五工具/注入）只依赖本接口，
 * 双端各自演化；测试用 [InMemoryMemoryStore]，生产用 [RoomMemoryStore]。
 *
 * 表语义：
 * - core_blocks（§1.1）：human/persona 两块常驻核心记忆，content 为自由文本；
 * - archival（§1.2）：一次性情节/事实归档，只增不淘汰（1 万条软告警归管理页，M1c）；
 * - conversation_search（§3.5）：messages 表 role IN (user,assistant) 的 content
 *   全库 LIKE 多词 OR 匹配，created_at 倒序（不含工具结果与摘要，§6）；
 * - migrationWrite（§1.5）：旧 agent_memory.json KV 一次性迁入 archival。
 */
interface MemoryStore {
    // ===== core_blocks =====

    /** 按 name 取核心块；不存在返回 null（种子判断依据「行缺失」）。 */
    suspend fun getCoreBlock(name: String): CoreBlock?

    /** 写入（整体覆盖）核心块；行不存在则插入。 */
    suspend fun putCoreBlock(name: String, content: String)

    // ===== archival =====

    /** 插入归档行，返回新 id（created_at/updated_at 由实现取当前时间）。 */
    suspend fun insertArchival(content: String, type: String, source: String): Long

    /** 全量归档（archival_memory_search 的 BM25 语料来源，协议 §3.4）。 */
    suspend fun allArchival(): List<ArchivalRow>

    /** 按 id 批量取，保持入参顺序（Bm25 检索命中后取行拼装结果）。 */
    suspend fun archivalByIds(ids: List<Long>): List<ArchivalRow>

    // ===== conversation_search =====

    /** 多词 OR LIKE 检索 user/assistant 原文，created_at 倒序，至多 limit 条。 */
    suspend fun conversationSearch(query: String, limit: Int): List<ConversationHit>

    // ===== 旧数据迁移（§1.5）=====

    /** Migrator 产出的旧 KV 行批量写入 archival（content/type/source 已带）。 */
    suspend fun migrationWrite(rows: List<Migrator.LegacyRow>)

    companion object {
        const val BLOCK_HUMAN = "human"
        const val BLOCK_PERSONA = "persona"

        /** 核心块上限（协议 §2）：human 800 / persona 600 字符（含空白）。 */
        const val CORE_HUMAN_LIMIT = 800
        const val CORE_PERSONA_LIMIT = 600

        fun coreLimitOf(block: String): Int? = when (block) {
            BLOCK_HUMAN -> CORE_HUMAN_LIMIT
            BLOCK_PERSONA -> CORE_PERSONA_LIMIT
            else -> null
        }

        /** 查询切词：按空白拆多词（空串/纯空白 → 空列表）。双实现共用，保证语义一致。 */
        fun searchTerms(query: String): List<String> =
            query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }
}

/** core_blocks 一行（协议 §1.1）。 */
data class CoreBlock(val name: String, val content: String, val updatedAt: Long)

/** archival 一行（协议 §1.2）。 */
data class ArchivalRow(
    val id: Long,
    val content: String,
    val type: String,
    val source: String,
    val hitCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

/** conversation_search 命中行（协议 §3.5 出参形状）。 */
data class ConversationHit(
    val conversationId: Long,
    val turnNo: Int,
    val role: String,
    val content: String,
    val createdAt: Long,
)
