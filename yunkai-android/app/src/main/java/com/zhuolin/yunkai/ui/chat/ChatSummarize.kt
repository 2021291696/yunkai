package com.zhuolin.yunkai.ui.chat

// 忆枢 M2 会话摘要（自 ChatViewModel 拆出，门0 P5 收口）：窗口阈值触发→LLM 摘要换出最旧轮次。
// 失败静默跳过（摘要属增益，绝不让已成功的回答报错）。
import android.util.Log
import com.zhuolin.yunkai.YunkaiApp
import com.zhuolin.yunkai.model.AppConfig
import com.zhuolin.yunkai.model.ChatMsg
import com.zhuolin.yunkai.service.LlmClient

internal suspend fun summarizeIfNeeded(app: YunkaiApp, cfg: AppConfig, convId: Long) {
    try {
        if (convId <= 0) return
        val state = app.conversationRepo.summaryState(convId) ?: return
        val rows = app.messageRepo.listByConv(convId)
        val window = com.zhuolin.yunkai.memory.Summarizer.windowRows(rows, state.untilTurn)
        if (!com.zhuolin.yunkai.memory.Summarizer.shouldTrigger(window)) return
        val span = com.zhuolin.yunkai.memory.Summarizer.spanToSummarize(window) ?: return
        val llm = LlmClient(cfg)
        val resp = llm.chatMessage(
            listOf(ChatMsg(role = "user", content = com.zhuolin.yunkai.memory.Summarizer.buildPrompt(span.rows))),
            null,
        )
        val text = resp.content.trim()
        if (text.isEmpty()) return
        app.conversationRepo.appendSummary(
            convId,
            text.take(com.zhuolin.yunkai.memory.Summarizer.SUMMARY_MAX_CHARS),
            span.endTurnNo,
        )
    } catch (e: Exception) {
        Log.w("yunkai", "summarize skipped: ${e.message}")
    }
}
