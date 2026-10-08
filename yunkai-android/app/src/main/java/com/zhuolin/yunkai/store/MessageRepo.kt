package com.zhuolin.yunkai.store

import com.zhuolin.yunkai.model.Msg
import com.zhuolin.yunkai.service.HtmlExtractor

// 消息仓库：messages 表增查（语义对齐鸿蒙版 MessageRepo.ets）
class MessageRepo(private val dao: MsgDao) {
    // 编辑重发：截断删除——删掉 fromId 这条及其之后的所有消息
    suspend fun deleteFromPosition(convId: Long, fromId: Long) = dao.deleteFrom(convId, fromId)

    // 统一入库入口：
    // role='user'：turn_no=0、plain=''、kind=TEXT；role='assistant'：turnNo=现有 assistant 最大 turn_no+1，
    // plain=剥标签纯文本（历史 prompt 与侧栏要点用）；kind='html'|'text' 渲染形态持久化（由调用方按
    // HtmlGuard.sanitize+ReplyKind.detect 唯一口径判定后传入）
    suspend fun add(convId: Long, role: String, content: String, kind: String) {
        var turnNo = 0
        var plain = ""
        if (role == "assistant") {
            val existing = listByConv(convId)
            var maxTurn = 0
            for (m in existing) {
                if (m.role == "assistant" && m.turnNo > maxTurn) maxTurn = m.turnNo
            }
            turnNo = maxTurn + 1
            plain = HtmlExtractor.stripTags(content)
        }
        dao.insert(MsgEntity(
            conversation_id = convId, role = role, content = content,
            plain = plain, turn_no = turnNo,
            created_at = System.currentTimeMillis(), kind = kind,
        ))
    }

    // ===== 消息生命周期（2026-10-07「发消息没回」治理 B）=====
    // 发送即落库：pending 用户行（历史诚实的第一块砖），返回行 id 供状态收口
    suspend fun addPendingUser(convId: Long, content: String): Long = dao.insert(MsgEntity(
        conversation_id = convId, role = "user", content = content,
        plain = "", turn_no = 0,
        created_at = System.currentTimeMillis(), kind = "text",
        status = "pending",
    ))

    suspend fun markDone(id: Long) = dao.setStatus(id, "done", "")
    suspend fun markFailed(id: Long, error: String) = dao.setStatus(id, "failed", error.take(300))
    suspend fun markPending(id: Long) = dao.setStatus(id, "pending", "")

    // 编辑取消恢复：按原行内容原样回写（新 id；会话内相对顺序由调用方按快照原序保证）
    suspend fun addRestored(m: Msg, kind: String) = dao.insert(MsgEntity(
        conversation_id = m.convId, role = m.role, content = m.content,
        plain = m.plain, turn_no = m.turnNo,
        created_at = m.createdAt, kind = kind,
        status = m.status, error = m.error,   // 保原态：失败行恢复后仍是 failed（重试钮不丢）
    ))

    // 开局清扫：进程死亡/被杀残留的 pending 轮收为 failed（失败角标+重试钮在会话流可见）
    suspend fun failAllPending(error: String): Int = dao.failAllPending(error)

    suspend fun getById(id: Long): Msg? = dao.getById(id)?.let { toMsg(it) }

    // SELECT * FROM messages WHERE conversation_id=? ORDER BY id ASC
    suspend fun listByConv(convId: Long): List<Msg> = dao.listByConv(convId).map { toMsg(it) }

    // 历史上下文只取完成轮：pending（在途）/ failed（从未被回答）不进 LLM 上下文
    suspend fun listDoneByConv(convId: Long): List<Msg> = dao.listDoneByConv(convId).map { toMsg(it) }

    private fun toMsg(e: MsgEntity) = Msg(
        id = e.id, convId = e.conversation_id, role = e.role, content = e.content,
        plain = e.plain, turnNo = e.turn_no, createdAt = e.created_at,
        status = e.status, error = e.error,
    )

    // 按（会话, 轮次）取 assistant 原文；无则空串
    suspend fun getHtmlByTurn(convId: Long, turnNo: Int): String = dao.getHtmlByTurn(convId, turnNo) ?: ""
}
