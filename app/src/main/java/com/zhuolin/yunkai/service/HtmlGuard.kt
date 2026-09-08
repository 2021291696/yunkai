package com.zhuolin.yunkai.service

// 渲染形态判定：一条回答该渲染成画布卡还是文本气泡的唯一入口。
// 前缀 <!DOCTYPE（大小写不敏感）或 <html → 'html'，否则 'text'；
// 画布/气泡双形态、存量旧 eli5 HTML 轮的兼容渲染都走这里。
object ReplyKind {
    const val HTML: String = "html"
    const val TEXT: String = "text"

    fun detect(content: String): String {
        val t = content.trim().lowercase()
        return if (t.startsWith("<!doctype") || t.startsWith("<html")) HTML else TEXT
    }
}

// HTML 兜底：剥 markdown 围栏、校验、补 viewport、长度限制。
// script 剥离是安全硬化：eli5 页面无需 JS，模型幻觉/被注入内容夹带的脚本不得在 WebView 执行
object HtmlGuard {
    // 剥```围栏→校验<!DOCTYPE html或<html(不区分大小写)→剥<script>块→缺viewport则注入→超300000字返回null→非法返回null
    fun sanitize(raw: String): String? {
        var t = raw.trim()
        if (t.startsWith("```")) {
            t = t.replace(Regex("^```[a-zA-Z]*\\s*"), "").replace(Regex("```\\s*$"), "").trim()
        }
        val lower = t.lowercase()
        if (!lower.startsWith("<!doctype html") && !lower.contains("<html")) return null
        t = t.replace(Regex("<script[\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("<script[^>]*/?>", RegexOption.IGNORE_CASE), "")
        if (!lower.contains("name=\"viewport\"")) {
            t = t.replace(Regex("<head([^>]*)>", RegexOption.IGNORE_CASE)) { m ->
                "<head${m.groupValues[1]}><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
            }
            if (!t.lowercase().contains("<head")) {
                t = t.replace(Regex("<html([^>]*)>", RegexOption.IGNORE_CASE)) { m ->
                    "<html${m.groupValues[1]}><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"></head>"
                }
            }
        }
        if (t.length > 300000) return null
        return t
    }
}
