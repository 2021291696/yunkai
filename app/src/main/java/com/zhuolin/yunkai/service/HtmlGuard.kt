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
    // 剥```围栏→校验<!DOCTYPE html或<html(不区分大小写)→剥<script>块/内联on*=/javascript:伪协议/<iframe>
    // →缺viewport则注入→超300000字返回null→非法返回null
    // 安全面：模型幻觉或 read_web 拓回的不可信内容夹带的脚本/事件处理器/伪协议 URL 不得在 WebView 生效
    fun sanitize(raw: String): String? {
        var t = raw.trim()
        if (t.startsWith("```")) {
            t = t.replace(Regex("^```[a-zA-Z]*\\s*"), "").replace(Regex("```\\s*$"), "").trim()
        }
        val lower = t.lowercase()
        if (!lower.startsWith("<!doctype html") && !lower.contains("<html")) return null
        t = t.replace(Regex("<script[\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("<script[^>]*/?>", RegexOption.IGNORE_CASE), "")
        // 内联事件属性：onerror= / onload= 等（引号包与裸值两种形态）
        t = t.replace(Regex("\\son[a-zA-Z]+\\s*=\\s*\"[^\"]*\"", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\son[a-zA-Z]+\\s*=\\s*'[^']*'", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\son[a-zA-Z]+\\s*=\\s*[^\\s>]+", RegexOption.IGNORE_CASE), "")
        // 伪协议 URL（href/src/action 等属性值）；命中即整个属性值置空
        t = t.replace(Regex("(\\s(?:href|src|action|xlink:href)\\s*=\\s*)(?:\"[^\"]*(?:javascript|vbscript)[^\"]*\"|'[^']*(?:javascript|vbscript)[^']*'|(?:javascript|vbscript):[^\\s>]+)", RegexOption.IGNORE_CASE), "$1\"\"")
        // iframe 整块剥（嵌套的外部页面不可控）
        t = t.replace(Regex("<iframe[\\s\\S]*?</iframe\\s*>", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("<iframe[^>]*/?>", RegexOption.IGNORE_CASE), "")
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
