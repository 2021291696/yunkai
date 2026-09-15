package com.zhuolin.yunkai.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhuolin.yunkai.service.screen.PlanEvent
import com.zhuolin.yunkai.service.screen.WritePlanExecutor
import com.zhuolin.yunkai.ui.theme.GlassTokens
import com.zhuolin.yunkai.ui.theme.LocalGlassScheme

// 写操作计划卡（M2a-T6）：propose_plan 提交后出现在对话流里，用户在此批准/取消整批写操作。
// 可见性由状态机决定：非终态（Pending/Executing/两种 Paused）才渲染，Done/Stopped 自动消失。
// 卡片只读 state（步骤/进度）+ lastLabels（步骤文案）；失败步与敏感暂停原因只在 events 里，
// 故额外收集事件（卡片在 Pending 时即上屏，早于任何 StepEcho/SensitivePaused 事件）。
// 已知降级：若用户在计划执行中途才回到本页，早于订阅的事件已丢弃 → 之前的失败步会显示为已完成。
@Composable
fun PlanCardHost(exec: WritePlanExecutor) {
    val st = exec.state.collectAsState().value
    if (st != null && st !is WritePlanExecutor.PlanState.Done && st !is WritePlanExecutor.PlanState.Stopped) {
        PlanCard(exec, st)
    }
}

@Composable
private fun PlanCard(exec: WritePlanExecutor, st: WritePlanExecutor.PlanState) {
    val glass = LocalGlassScheme.current
    val labels = exec.lastLabels
    val planId = exec.lastPlanId
    // StepEcho 成功 1 次、失败 2 次（失败会补一条 [失败] 回显）→ 计数判定失败步，避免耦合前缀文案
    var echoCount by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var pauseReason by remember { mutableStateOf("") }
    LaunchedEffect(exec) {
        exec.events.collect { e ->
            when (e) {
                is PlanEvent.StepEcho -> echoCount = echoCount + (e.index to (echoCount[e.index] ?: 0) + 1)
                is PlanEvent.SensitivePaused -> pauseReason = e.reason
                else -> {}
            }
        }
    }

    // 当前步号：Paused 停在哪一步，就高亮哪一步（Pending 时无当前步）
    val current = when (st) {
        is WritePlanExecutor.PlanState.Executing -> st.index
        is WritePlanExecutor.PlanState.SensitivePaused -> st.index
        is WritePlanExecutor.PlanState.SendConfirmPaused -> st.index
        else -> -1
    }
    val status = when (st) {
        is WritePlanExecutor.PlanState.Pending -> "待批准：点「执行」后开始逐步操作"
        is WritePlanExecutor.PlanState.Executing -> "执行中 ${st.index + 1}/${labels.size}"
        is WritePlanExecutor.PlanState.SensitivePaused ->
            if (pauseReason.isNotEmpty()) "敏感暂停：$pauseReason" else "敏感暂停：当前页面命中敏感内容"
        is WritePlanExecutor.PlanState.SendConfirmPaused ->
            "外发确认：第 ${st.index + 1} 步将输入文本（可能发送给他人）"
        else -> ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GlassTokens.R_CARD.dp))
            .background(glass.glassBg)
            .border(GlassTokens.BORDER_W.dp, glass.glassBorder, RoundedCornerShape(GlassTokens.R_CARD.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("写操作计划", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = glass.textHi)
        for (i in labels.indices) {
            val failed = (echoCount[i] ?: 0) >= 2
            val mark = when {
                failed -> "✗"
                current >= 0 && i < current -> "✓"
                i == current -> "▶"
                else -> "·"
            }
            val color = when {
                failed -> MaterialTheme.colorScheme.error
                i == current -> glass.accent
                current >= 0 && i < current -> glass.textMid
                else -> glass.textLow
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(mark, fontSize = 12.sp, color = color)
                Text(labels[i], fontSize = 13.sp, color = color, maxLines = 2)
            }
        }
        if (status.isNotEmpty()) {
            Text(status, fontSize = 12.sp, color = if (st is WritePlanExecutor.PlanState.SensitivePaused) MaterialTheme.colorScheme.error else glass.textMid)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (st) {
                is WritePlanExecutor.PlanState.Pending -> {
                    PlanBtn("执行", true) { exec.approve(planId) }
                    PlanBtn("取消", false) { exec.cancel(planId) }
                }
                is WritePlanExecutor.PlanState.SensitivePaused -> {
                    PlanBtn("继续", true) { exec.approve(planId) }
                    PlanBtn("取消", false) { exec.cancel(planId) }
                }
                is WritePlanExecutor.PlanState.SendConfirmPaused -> {
                    PlanBtn("确认发送", true) { exec.approveSend(planId) }
                    PlanBtn("取消", false) { exec.cancel(planId) }
                }
                else -> {}
            }
        }
    }
}

// 卡片内按钮：本地简化玻璃样式（不引用 settings 包 internal 的 GlassCard）
@Composable
private fun PlanBtn(label: String, accent: Boolean, onClick: () -> Unit) {
    val glass = LocalGlassScheme.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (accent) glass.accentSoft else glass.glassBgStrong)
            .border(GlassTokens.BORDER_W.dp, glass.glassBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(label, fontSize = 13.sp, color = if (accent) glass.accent else glass.textMid)
    }
}
