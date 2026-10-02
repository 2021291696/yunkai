package com.zhuolin.yunkai.ui.chat

// B2 轻量 markdown → AnnotatedString：assistant 气泡内的行内样式（粗体/斜体/行内码）+ 块级（## 标题 / - 列表 / 数字列表 / ``` 围栏代码块）。
// 只覆盖讲解页高频语法，不追求完整 GFM——表格/嵌套列表保持纯文本不丢内容。
// 代码块（2026-10-02 补）：``` 围栏整块等宽+半透明底（未闭合围栏也整块按代码渲染），JSON 等由此获得代码块形态。
// 复制支持（2026-10-02）：renderMarkdownBlocks 输出结构化块（isCode+原文），ChatScreen 据此给代码块
// 挂 CodeBlockCard（复制按钮）；renderMarkdown/renderMarkdownSingle 保留为兼容出口。

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

private data class MdToken(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

// 代码块 span：等宽 + 中性半透明底（深浅壁纸都读得出块状）
private val CodeBlockStyle = SpanStyle(
    fontFamily = FontFamily.Monospace,
    background = Color(0x1A888888),
)

// 有序列表行首：`12. ` / `3) `
private val OrderedPrefix = Regex("^\\d{1,3}[.)]\\s")

// 结构化块：isCode=true 时 codeText 为未加样式的原文（复制按钮用）
data class MdBlock(val an: AnnotatedString, val isCode: Boolean = false, val codeText: String = "")

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

private fun proseBlock(body: List<MdToken>): MdBlock =
    MdBlock(an = buildAnnotatedString {
        for (tk in body) {
            val st = SpanStyle(
                fontWeight = if (tk.bold) FontWeight.Bold else null,
                fontStyle = if (tk.italic) FontStyle.Italic else null,
                fontFamily = if (tk.code) FontFamily.Monospace else null,
            )
            if (st != SpanStyle()) withStyle(st) { append(tk.text) } else append(tk.text)
        }
    })

// 块级入口：按行切分；``` 围栏整块收集为代码块（跳过语言标注行）；## / ### 标题、
// - / * / 数字. 列表、其余行内解析。
fun renderMarkdownBlocks(text: String): List<MdBlock> {
    val result = mutableListOf<MdBlock>()
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val trimmed = lines[i].trimEnd()
        when {
            trimmed.isBlank() -> { result.add(MdBlock(an = buildAnnotatedString { append(" ") })); i++ }
            trimmed.startsWith("```") -> {
                // 围栏代码块：首行（含语言标注）跳过，收集到闭合围栏或文本结束
                val buf = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimEnd().startsWith("```")) {
                    buf.append(lines[i]).append('\n')
                    i++
                }
                if (i < lines.size) i++   // 吃掉闭合围栏
                val body = buf.toString().trimEnd('\n')
                result.add(if (body.isEmpty()) {
                    MdBlock(an = buildAnnotatedString { append(" ") })
                } else {
                    MdBlock(
                        an = buildAnnotatedString { withStyle(CodeBlockStyle) { append(body) } },
                        isCode = true, codeText = body,
                    )
                })
            }
            else -> {
                val body: List<MdToken> = when {
                    trimmed.startsWith("### ") -> listOf(MdToken(trimmed.removePrefix("### "), bold = true))
                    trimmed.startsWith("## ") -> listOf(MdToken(trimmed.removePrefix("## "), bold = true))
                    trimmed.startsWith("# ") -> listOf(MdToken(trimmed.removePrefix("# "), bold = true))
                    trimmed.startsWith("- ") -> listOf(MdToken("• ")) + parseInline(trimmed.removePrefix("- "))
                    trimmed.startsWith("* ") -> listOf(MdToken("• ")) + parseInline(trimmed.removePrefix("* "))
                    OrderedPrefix.containsMatchIn(trimmed) -> {
                        val sp = trimmed.indexOfFirst { it == '.' || it == ')' }
                        listOf(MdToken(trimmed.take(sp + 1) + " ")) + parseInline(trimmed.substring(sp + 1).trimStart())
                    }
                    else -> parseInline(trimmed)
                }
                result.add(proseBlock(body))
                i++
            }
        }
    }
    return result
}

// 兼容出口：纯 AnnotatedString 列表（无复制语义场景）
fun renderMarkdown(text: String): List<AnnotatedString> =
    renderMarkdownBlocks(text).map { it.an }

// 单段便捷版（流式气泡等逐行渲染场景）
fun renderMarkdownSingle(text: String): AnnotatedString {
    val parts = renderMarkdownBlocks(text)
    return buildAnnotatedString {
        parts.forEachIndexed { i, part ->
            if (i > 0) append("\n")
            append(part.an)
        }
    }
}
