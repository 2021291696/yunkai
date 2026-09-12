package com.zhuolin.yunkai.store

import com.zhuolin.yunkai.memory.Summarizer
import com.zhuolin.yunkai.model.Conv

// 会话仓库：conversations 表增删改查（语义对齐鸿蒙版 ConversationRepo.ets）
class ConversationRepo(private val dao: ConvDao) {
    // INSERT conversations(title, now, now)，返回新 id
    suspend fun create(title: String): Long {
        val now = System.currentTimeMillis()
        return dao.insert(ConvEntity(title = title, created_at = now, updated_at = now))
    }

    // 查全部，ORDER BY updated_at DESC
    suspend fun list(): List<Conv> = dao.list().map { Conv(id = it.id, title = it.title, updatedAt = it.updated_at) }

    // UPDATE conversations SET updated_at=now WHERE id=?
    suspend fun touch(id: Long) = dao.touch(id, System.currentTimeMillis())

    // title==='新对话' 时 UPDATE title = question 截前20字
    suspend fun setTitleIfPlaceholder(id: Long, question: String) =
        dao.setTitleIfPlaceholder(id, question.take(20))

    // 删除会话；messages 由外键 CASCADE 级联清除（鸿蒙版为两条 DELETE 手工先删，效果一致）
    suspend fun remove(id: Long) = dao.delete(id)

    // ===== 忆枢 M2 会话摘要（协议 §4.4） =====

    // 摘要状态（summary + 换出边界）；会话不存在返回 null
    suspend fun summaryState(id: Long): Summarizer.SummaryState? {
        val e = dao.getById(id) ?: return null
        return Summarizer.SummaryState(summary = e.summary ?: "", untilTurn = e.summarized_until_turn)
    }

    // 追加一次摘要（多次以 \n 连接）并把换出边界前移到跨度末轮
    suspend fun appendSummary(id: Long, addition: String, untilTurn: Int) {
        val cur = dao.getById(id) ?: return
        dao.updateSummary(id, Summarizer.joinSummary(cur.summary ?: "", addition), untilTurn)
    }

    // 清空摘要状态（M2 编辑重发防护：截断点落入已摘要跨度时调用，防陈旧摘要）
    suspend fun clearSummary(id: Long) = dao.updateSummary(id, "", 0)
}
