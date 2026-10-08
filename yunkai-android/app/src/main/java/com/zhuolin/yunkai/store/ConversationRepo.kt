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

    // 主列表（排除已归档；置顶固定最上方），ORDER BY pinned DESC, updated_at DESC
    suspend fun list(): List<Conv> = dao.list().map {
        Conv(id = it.id, title = it.title, updatedAt = it.updated_at, pinned = it.pinned != 0)
    }

    // ===== 会话管理（2026-10-07 用户定案：置顶/归档/重命名，归档=软删除唯一出口）=====

    companion object {
        // 归档保留期：30 天后自动彻底删（app 启动清扫）
        const val ARCHIVE_RETENTION_MS: Long = 30L * 24 * 3600 * 1000
    }

    // 设置页归档区：已归档会话（含归档时间，供显示剩余天数），最近归档在前
    suspend fun listArchived(): List<Conv> = dao.listArchived().map {
        Conv(id = it.id, title = it.title, updatedAt = it.archived_at)
    }

    suspend fun rename(id: Long, title: String) { if (title.isNotBlank()) dao.rename(id, title.trim()) }

    // 返回置顶后的新状态（UI 即时反映）
    suspend fun togglePin(id: Long): Boolean {
        val cur = dao.getById(id) ?: return false
        val next = if (cur.pinned != 0) 0 else 1
        dao.setPinned(id, next)
        return next == 1
    }

    // 归档（软删除）：archived=1 + 时间戳；30 天后 purgeArchivedBefore 彻底删（messages CASCADE）
    suspend fun archive(id: Long) = dao.archive(id, System.currentTimeMillis())

    // 从归档区恢复：回主列表（不带回原置顶态）
    suspend fun restore(id: Long) = dao.restore(id)

    // 启动清扫：彻底删超期归档（messages CASCADE）。先条件删会话、删中才清 task_state——
    // 反过来先清轨迹，用户恰在此刻点「恢复」会把会话救回来却永久丢「继续任务」轨迹。
    // 边界口径与 SQL 统一为 <= deadline（旧实现 Kotlin 闭区间 vs SQL 开区间，压线行只删轨迹不删会话）
    suspend fun purgeExpired(taskStateDao: TaskStateDao): Int {
        val deadline = System.currentTimeMillis() - ARCHIVE_RETENTION_MS
        var purged = 0
        for (id in dao.archivedIdsBefore(deadline)) {
            if (dao.deleteArchivedById(id) == 1) {
                taskStateDao.delete(id)
                purged++
            }
        }
        return purged
    }

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
