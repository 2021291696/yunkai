package com.zhuolin.yunkai.ui.chat

// B2 轻量 markdown → AnnotatedString：assistant 气泡内的行内样式（粗体/斜体/行内码）+ 块级（## 标题 / - 列表）。
// 只覆盖讲解页高频语法，不追求完整 GFM——复杂结构（表格/嵌套列表/代码块围栏）保持纯文本不丢内容。

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

private data class MdToken(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

// 行内解析：**bold**、*italic*、`code`（code 内不解析其他标记）
private fun parseInline(text: String): List<MdToken> {
    val tokens = mutableListOf<MdToken>()
    var i = 0
    val buf = StringBuilder()
    fun flush() { if (buf.isNotEmpty()) { tokens.add(MdToken(buf.toString())); buf.clear() } }
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 1) {
                    flush()
                    tokens.add(MdToken(text.substring(i + 2, end), bold = true))
                    i = end + 2
                } else { buf.append(text[i]); i++ }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush()
                    tokens.add(MdToken(text.substring(i + 1, end), code = true))
                    i = end + 1
                } else { buf.append(text[i]); i++ }
            }
            text[i] == '*' && (i + 1 >= text.length || text[i + 1] != '*') -> {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1) {
                    flush()
                    tokens.add(MdToken(text.substring(i + 1, end), italic = true))
                    i = end + 1
                } else { buf.append(text[i]); i++ }
            }
            else -> { buf.append(text[i]); i++ }
        }
    }
    flush()
    return tokens
}

// 块级入口：按行切分，## / ### 开头→粗体大字，- / * / 数字. 开头→列表缩进，其余按行内解析。
// 返回多段 AnnotatedString（每段对应一个视觉块），由调用方选择渲染粒度。
fun renderMarkdown(text: String): List<AnnotatedString> {
    val result = mutableListOf<AnnotatedString>()
    for (line in text.lines()) {
        val trimmed = line.trimEnd()
        if (trimmed.isBlank()) { result.add(buildAnnotatedString { append(" ") }); continue }
        val body: List<MdToken> = when {
            trimmed.startsWith("### ") -> listOf(MdToken(trimmed.removePrefix("### "), bold = true))
            trimmed.startsWith("## ") -> listOf(MdToken(trimmed.removePrefix("## "), bold = true))
            trimmed.startsWith("# ") -> listOf(MdToken(trimmed.removePrefix("# "), bold = true))
            trimmed.startsWith("- ") -> listOf(MdToken("• ")) + parseInline(trimmed.removePrefix("- "))
            trimmed.startsWith("* ") -> listOf(MdToken("• ")) + parseInline(trimmed.removePrefix("* "))
            else -> parseInline(trimmed)
        }
        result.add(buildAnnotatedString {
            for (tk in body) {
                val st = SpanStyle(
                    fontWeight = if (tk.bold) FontWeight.Bold else null,
                    fontStyle = if (tk.italic) FontStyle.Italic else null,
                    fontFamily = if (tk.code) FontFamily.Monospace else null,
                )
                if (st != SpanStyle()) withStyle(st) { append(tk.text) } else append(tk.text)
            }
        })
    }
    return result
}

// 单段便捷版（流式气泡等逐行渲染场景）
fun renderMarkdownSingle(text: String): AnnotatedString {
    val parts = renderMarkdown(text)
    return buildAnnotatedString {
        parts.forEachIndexed { i, part ->
            if (i > 0) append("\n")
            append(part)
        }
    }
}
