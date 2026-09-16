package com.zhuolin.yunkai.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuolin.yunkai.ui.theme.CardBorder
import com.zhuolin.yunkai.ui.theme.CardGlass
import com.zhuolin.yunkai.ui.theme.TextDark
import com.zhuolin.yunkai.ui.theme.TextMuted

// 引导态：无任何消息时的冷启动页（时段问候 + 日期/对话数 + 快捷问题 chips；第三个 chip 演示 @eli5 强制技能语法）。
// 与鸿蒙版 GuidePage.ets 语义一致（时段问候/已有 N 次对话/同一组 chips）。
// 点 chips 经 onAsk(question) 上抛，由 Chat 页填输入并触发发送
private val QUESTIONS = listOf(
    "继续昨天的对话",
    "帮我写周报",
    "@eli5 讲讲黑洞是怎么形成的",
)

// 时段问候：5-11 早上好 / 11-18 下午好 / 其余 晚上好（与鸿蒙版口径一致）
private fun greeting(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when (h) {
        in 5..10 -> "早上好"
        in 11..17 -> "下午好"
        else -> "晚上好"
    }
}

// 「M月d日 · 周X · 已有 N 次对话」（与鸿蒙版文案一致）
private fun dateLine(convCount: Int): String {
    val d = java.util.Calendar.getInstance()
    val week = arrayOf("日", "一", "二", "三", "四", "五", "六")[d.get(java.util.Calendar.DAY_OF_WEEK) - 1]
    return "${d.get(java.util.Calendar.MONTH) + 1}月${d.get(java.util.Calendar.DAY_OF_MONTH)}日 · 周$week · 已有 $convCount 次对话"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuidePage(onAsk: (question: String) -> Unit, convCount: Int = 0) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("${greeting()}，", fontSize = 30.sp, fontWeight = FontWeight.Light, color = TextDark)
        Text("想聊点什么？", fontSize = 30.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
        Text(dateLine(convCount), fontSize = 12.sp, color = TextMuted, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.padding(20.dp))
        FlowRow(
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (q in QUESTIONS) {
                Text(
                    q,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .padding(6.dp)
                        .background(CardGlass, RoundedCornerShape(18.dp))
                        .border(0.5.dp, CardBorder, RoundedCornerShape(18.dp))
                        .clickable { onAsk(q) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}
