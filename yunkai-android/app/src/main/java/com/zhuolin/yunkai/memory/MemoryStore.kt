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

    // ===== archival 管理（M1c 管理页）=====

    /** 删除单条归档（管理页单条删除）。 */
    suspend fun deleteArchival(id: Long)

    /** 清空全部归档，返回删除条数（管理页「清空归档」，确认对话框后调用）。 */
    suspend fun clearArchival(): Int

    /** 归档总条数（管理页 1 万条软告警判据，协议 §2 ARCHIVAL_WARN_COUNT）。 */
    suspend fun archivalCount(): Int

    /** 命中条目 hit_count 批量 +1（协议 §3.4，随检索同批写回；调用方自行吞写失败）。 */
    suspend fun incrementHitCounts(ids: List<Long>)

    // ===== conversation_search =====

    /** 多词 OR LIKE 检索 user/assistant 原文，created_at 倒序，至多 limit 条。 */
    suspend fun conversationSearch(query: String, limit: Int): List<ConversationHit>

    // ===== 旧数据迁移（§1.5）=====

    /** Migrator 产出的旧 KV 行批量写入 archival（content/type/source 已带）。 */
    suspend fun migrationWrite(rows: List<Migrator.LegacyRow>)

    /** 幂等护栏：archival 已存在 legacy-m2 行 = 上次迁移部分成功，重试时跳过写入防重复。 */
    suspend fun hasLegacyRows(): Boolean

    companion object {
        const val BLOCK_HUMAN = "human"
        const val BLOCK_PERSONA = "persona"

        /** 核心块上限（协议 §2）：human 800 / persona 600 字符（含空白）。 */
        const val CORE_HUMAN_LIMIT = 800
        const val CORE_PERSONA_LIMIT = 600

        /** 归档软上限告警（协议 §2）：管理页页顶提示条判据，只提示不淘汰。 */
        const val ARCHIVAL_WARN_COUNT = 10000

        fun coreLimitOf(block: String): Int? = when (block) {
            BLOCK_HUMAN -> CORE_HUMAN_LIMIT
            BLOCK_PERSONA -> CORE_PERSONA_LIMIT
            else -> null
        }

        /** 查询切词：按空白拆多词（空串/纯空白 → 空列表）。双实现共用，保证语义一致。 */
        fun searchTerms(query: String): List<String> =
            query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        /**
         * LIKE 字面转义（协议 §3.5）：查询关键词中的 `%` 与 `_` 按字面匹配，不作通配符语义。
         * 先转义转义符 `\` 本身，再转义 `%`/`_`；SQL 侧须配 `ESCAPE '\'` 子句使用。
         * 纯函数，JVM 可测（RoomMemoryStore.conversationSearch 与管理页共用）。
         */
        fun escapeLike(term: String): String =
            term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        /** LIKE 绑定参数：前后通配 + 字面转义（conversationSearch 的 `%词%` 统一出口）。 */
        fun likePattern(term: String): String = "%${escapeLike(term)}%"
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
