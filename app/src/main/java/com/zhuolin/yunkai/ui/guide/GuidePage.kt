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

// 引导态：无任何消息时的冷启动页（🌤️ + 快捷问题 chips；第三个 chip 演示 @eli5 强制技能语法）。
// 点 chips 经 onAsk(question) 上抛，由 Chat 页填输入并触发发送
private val QUESTIONS = listOf(
    "为什么天空是蓝色的？",
    "怎么写一封请假邮件？",
    "@eli5 讲讲黑洞是怎么形成的",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuidePage(onAsk: (question: String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🌤️", fontSize = 60.sp)
        Spacer(Modifier.padding(4.dp))
        Text("问我任何问题", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = TextDark)
        Text("我会用大白话和图讲给你听", fontSize = 14.sp, color = TextMuted)
        Spacer(Modifier.padding(8.dp))
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
