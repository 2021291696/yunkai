package com.zhuolin.yunkai.memory

import com.zhuolin.yunkai.model.Msg

// 会话摘要（忆枢协议 §4.4）：纯函数部分集中于此（可 JVM 测），LLM 调用由调用方（ChatViewModel）
// 用 LlmClient.chatMessage 完成。换出边界语义（协议 §4.1/4.4，M2 钉死）：
// - messages 表 user 行 turn_no 恒 0，无法按 turn_no 过滤窗口 → 按「行序」定义：
//   截断点 = turn_no ≤ untilTurn 的最后一条 assistant 行，其前导 user 行与该行一起属已摘要跨度；
// - 窗口 = 截断点之后的全部行（untilTurn=0 或找不到截断点 → 全量窗口）；
// - 触发（§2 常量）：窗口内 assistant 轮数 > 12 或窗口字符总量 > 16000；
// - 摘要跨度 = 窗口内最旧的一半轮次（向上取整），到该半块最后一条 assistant 行（含其前导 user 行）；
// - 追加：多次摘要以 \n 连接；单次摘要文本 ≤300 字。
object Summarizer {
    const val SUMMARY_TURN_THRESHOLD = 12
    const val SUMMARY_CHAR_THRESHOLD = 16000
    const val SUMMARY_MAX_CHARS = 300

    const val SUMMARY_PROMPT: String =
        "请把以下对话压缩成不超过300字的中文摘要，保留关键事实、用户偏好、结论与未尽事项，" +
        "用平铺陈述句，不要逐句罗列，不要添加对话中没有的内容。"

    data class SummaryState(val summary: String, val untilTurn: Int)
    data class Span(val rows: List<Msg>, val endTurnNo: Int)

    // 窗口计算：见对象头注释。untilTurn≤0 → 全量窗口
    fun windowRows(rows: List<Msg>, untilTurn: Int): List<Msg> {
        if (untilTurn <= 0) return rows
        var cut = -1
        for ((i, m) in rows.withIndex()) {
            if (m.role == "assistant" && m.turnNo <= untilTurn) cut = i
        }
        return if (cut < 0) rows else rows.subList(cut + 1, rows.size)
    }

    // 触发判定（§4.4）：轮数超阈值或字符超阈值。轮数按 assistant 行计（一行=一轮）。
    fun shouldTrigger(window: List<Msg>): Boolean {
        val turns = window.count { it.role == "assistant" }
        if (turns > SUMMARY_TURN_THRESHOLD) return true
        val chars = window.sumOf { it.role.length + 1 + it.content.length }
        return chars > SUMMARY_CHAR_THRESHOLD
    }

    // 待摘要跨度：窗口最旧一半轮次（向上取整）；不足 2 轮无摘要价值返回 null（防御，阈值前不会被调到）
    fun spanToSummarize(window: List<Msg>): Span? {
        val turnIdx = window.indices.filter { window[it].role == "assistant" }
        if (turnIdx.size < 2) return null
        val half = (turnIdx.size + 1) / 2
        val endIdx = turnIdx[half - 1]
        return Span(window.subList(0, endIdx + 1), window[endIdx].turnNo)
    }

    // 摘要请求正文：assistant 行优先用 plain（防大 HTML 撑爆摘要请求）
    fun buildPrompt(span: List<Msg>): String {
        val body = span.joinToString("\n") { m ->
            if (m.role == "user") "用户：${m.content}"
            else "助手：${m.plain.ifEmpty { m.content }}"
        }
        return "$SUMMARY_PROMPT\n\n$body"
    }

    // 多次摘要拼接：旧摘要空 → 直接用新增；否则 \n 连接（§4.4）
    fun joinSummary(old: String, addition: String): String =
        if (old.isBlank()) addition else "$old\n$addition"
}
