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

    // SELECT * FROM messages WHERE conversation_id=? ORDER BY id ASC
    suspend fun listByConv(convId: Long): List<Msg> = dao.listByConv(convId).map {
        Msg(
            id = it.id, convId = it.conversation_id, role = it.role, content = it.content,
            plain = it.plain, turnNo = it.turn_no, createdAt = it.created_at,
        )
    }

    // 按（会话, 轮次）取 assistant 原文；无则空串
    suspend fun getHtmlByTurn(convId: Long, turnNo: Int): String = dao.getHtmlByTurn(convId, turnNo) ?: ""
}
