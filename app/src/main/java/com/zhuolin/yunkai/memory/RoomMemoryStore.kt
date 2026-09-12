package com.zhuolin.yunkai.memory

import androidx.sqlite.db.SimpleSQLiteQuery
import com.zhuolin.yunkai.store.ArchivalEntity
import com.zhuolin.yunkai.store.CoreBlockEntity
import com.zhuolin.yunkai.store.YunkaiDb

/**
 * MemoryStore 的 Room 实现（协议 §1 schema 落在 YunkaiDb v3 三张忆枢表）。
 * - core_memory 读写走 core_blocks 表（upsert 整体覆盖，updated_at 刷新）；
 * - archival_search 的 BM25 语料从 archival 全量取（协议 §3.4），新近度加权在 Bm25 内完成；
 * - conversationSearch 动态拼多词 OR LIKE（词数可变，走 MsgDao.rawSearch）。
 */
class RoomMemoryStore(private val db: YunkaiDb) : MemoryStore {

    override suspend fun getCoreBlock(name: String): CoreBlock? =
        db.coreBlockDao().getByName(name)?.let { CoreBlock(it.name, it.content, it.updated_at) }

    override suspend fun putCoreBlock(name: String, content: String) {
        db.coreBlockDao().upsert(CoreBlockEntity(name = name, content = content, updated_at = System.currentTimeMillis()))
    }

    override suspend fun insertArchival(content: String, type: String, source: String): Long {
        val now = System.currentTimeMillis()
        return db.archivalDao().insert(
            ArchivalEntity(
                content = content, type = type, source = source,
                created_at = now, updated_at = now,
            )
        )
    }

    override suspend fun allArchival(): List<ArchivalRow> = db.archivalDao().listAll().map { it.toRow() }

    override suspend fun archivalByIds(ids: List<Long>): List<ArchivalRow> {
        if (ids.isEmpty()) return emptyList()
        val byId = db.archivalDao().getByIds(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it]?.toRow() }   // 保持 Bm25 排序的入参顺序
    }

    // ===== archival 管理（M1c 管理页）=====

    override suspend fun deleteArchival(id: Long) {
        db.archivalDao().deleteById(id)
    }

    override suspend fun clearArchival(): Int = db.archivalDao().clearAll()

    override suspend fun archivalCount(): Int = db.archivalDao().count()

    override suspend fun incrementHitCounts(ids: List<Long>) {
        if (ids.isEmpty()) return
        db.archivalDao().incrementHits(ids)
    }

    override suspend fun conversationSearch(query: String, limit: Int): List<ConversationHit> {
        val terms = MemoryStore.searchTerms(query)
        val sql = StringBuilder("SELECT * FROM messages WHERE role IN ('user','assistant')")
        val args = mutableListOf<Any>()
        if (terms.isNotEmpty()) {
            sql.append(" AND (")
            terms.forEachIndexed { i, t ->
                if (i > 0) sql.append(" OR ")
                // §3.5：`%`/`_` 按字面匹配——likePattern 内做转义，SQL 配 ESCAPE '\' 子句
                sql.append("content LIKE ? ESCAPE '\\'")
                args.add(MemoryStore.likePattern(t))
            }
            sql.append(")")
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?")
        args.add(limit.toLong())
        return db.msgDao().rawSearch(SimpleSQLiteQuery(sql.toString(), args.toTypedArray())).map {
            ConversationHit(
                conversationId = it.conversation_id, turnNo = it.turn_no,
                role = it.role, content = it.content, createdAt = it.created_at,
            )
        }
    }

    override suspend fun migrationWrite(rows: List<Migrator.LegacyRow>) {
        rows.forEach { insertArchival(it.content, it.type, it.source) }
    }

    private fun ArchivalEntity.toRow() = ArchivalRow(
        id = id, content = content, type = type, source = source,
        hitCount = hit_count, createdAt = created_at, updatedAt = updated_at,
    )
}
