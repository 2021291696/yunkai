package com.zhuolin.yunkai.ui.chat

// 代码块卡片（2026-10-02）：黑底独立卡片（用户拍板）——等宽浅色文本 + 复制按钮。
// 黑底不随主题翻转，浅色文字/灰色标签恒定，任何壁纸上都是标准代码块观感。
// 复制走系统剪贴板，反馈「✓ 已复制」1.6s 自愈。
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CodeBg = Color(0xF0141416)      // 近实色黑
private val CodeText = Color(0xFFE8E8EA)    // 浅灰白正文
private val CodeLabel = Color(0xFF8A8A90)   // 灰标签
private val CopyAccent = Color(0xFF4DA3FF)  // 复制钮蓝（黑底提亮版）

@Composable
fun CodeBlockCard(code: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1600)
            copied = false
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CodeBg, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "代码",
                fontSize = 10.5.sp,
                color = CodeLabel,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (copied) "✓ 已复制" else "⧉ 复制",
                fontSize = 12.sp,
                fontWeight = if (copied) FontWeight.Bold else FontWeight.Normal,
                color = if (copied) CopyAccent else CodeLabel,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .clickable {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    },
            )
        }
        Text(
            code,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            fontFamily = FontFamily.Monospace,
            color = CodeText,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
