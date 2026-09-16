package com.zhuolin.yunkai.memory

import com.zhuolin.yunkai.model.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 会话摘要纯函数单测（忆枢协议 §4.4 / 换出边界 M2 钉死语义）。
// 构造辅助：mkRows(n) 生成 n 轮（每轮 user+assistant，assistant turn_no=1..n 递增）。
class SummarizerTest {

    private fun mkRows(n: Int): List<Msg> {
        val rows = mutableListOf<Msg>()
        for (i in 1..n) {
            rows.add(Msg(id = rows.size + 1L, convId = 1L, role = "user", content = "问$i", plain = "", turnNo = 0, createdAt = i * 10L))
            rows.add(Msg(id = rows.size + 1L, convId = 1L, role = "assistant", content = "答$i", plain = "答$i", turnNo = i, createdAt = i * 10L + 1))
        }
        return rows
    }

    // [S2] untilTurn=0 → 全量窗口
    @Test
    fun window_zero_until_turn_returns_all() {
        val rows = mkRows(3)
        assertEquals(rows, Summarizer.windowRows(rows, 0))
    }

    // [S2] 边界=turn_no≤untilTurn 的最后一条 assistant 行，窗口为其后全部行（含下一轮 user 行）
    @Test
    fun window_cuts_after_boundary_assistant_row() {
        val rows = mkRows(5) // 行序: u1,a1,u2,a2,...,u5,a5（a_i.turn_no=i）
        val w = Summarizer.windowRows(rows, 3)
        assertEquals(4, w.size)
        assertEquals("问4", w[0].content)
        assertEquals("答5", w.last().content)
    }

    // [S2] 边界 turn_no 大于现有最大轮次 → 全部行都属已摘要跨度，窗口为空（历史只剩摘要）
    @Test
    fun window_until_turn_beyond_max_returns_empty() {
        val rows = mkRows(3)
        assertEquals(emptyList<Msg>(), Summarizer.windowRows(rows, 99))
    }

    // [S1] 阈值钉死：12 轮不触发、13 轮触发（assistant 行数口径）
    @Test
    fun trigger_turn_threshold() {
        assertFalse(Summarizer.shouldTrigger(mkRows(12)))
        assertTrue(Summarizer.shouldTrigger(mkRows(13)))
    }

    // [S1] 字符超阈值触发：短轮次但总量大
    @Test
    fun trigger_char_threshold() {
        val rows = mutableListOf<Msg>()
        val big = "x".repeat(2000)
        for (i in 1..5) {
            rows.add(Msg(id = rows.size + 1L, convId = 1L, role = "user", content = big, plain = "", turnNo = 0, createdAt = i * 10L))
            rows.add(Msg(id = rows.size + 1L, convId = 1L, role = "assistant", content = big, plain = big, turnNo = i, createdAt = i * 10L + 1))
        }
        // 5 轮（<12）但字符总量 ≈ 5*(2*(4+1+2000)) = 20050 > 16000
        assertTrue(Summarizer.shouldTrigger(rows))
    }

    // [S3] 跨度切分：13 轮取 7 轮（向上取整），跨度含第 7 条 assistant 行及其前导 user 行
    @Test
    fun span_oldest_half_round_up() {
        val window = mkRows(13)
        val span = Summarizer.spanToSummarize(window)!!
        assertEquals(14, span.rows.size) // 7 轮 × (user+assistant)
        assertEquals(7, span.endTurnNo)
        assertEquals("答7", span.rows.last().content)
    }

    // [S3] 不足 2 轮返回 null（防御）
    @Test
    fun span_too_few_turns_returns_null() {
        assertNull(Summarizer.spanToSummarize(mkRows(1)))
    }

    // [S4] 提示词构造：user 原文、assistant 优先 plain
    @Test
    fun prompt_uses_plain_for_assistant() {
        val rows = listOf(
            Msg(id = 1, convId = 1, role = "user", content = "<p>问题</p>", plain = "", turnNo = 0, createdAt = 1),
            Msg(id = 2, convId = 1, role = "assistant", content = "<html>大页面</html>", plain = "纯文本回答", turnNo = 1, createdAt = 2),
        )
        val p = Summarizer.buildPrompt(rows)
        assertTrue(p.contains("用户：<p>问题</p>"))
        assertTrue(p.contains("助手：纯文本回答"))
        assertFalse(p.contains("<html>大页面</html>"))
    }

    // [S5] 摘要拼接：旧空直用、非空 \n 连接
    @Test
    fun join_summary() {
        assertEquals("新摘要", Summarizer.joinSummary("", "新摘要"))
        assertEquals("旧\n新", Summarizer.joinSummary("旧", "新"))
    }
}
