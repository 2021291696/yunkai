package com.zhuolin.yunkai.service

// 剥标签：HTML→纯文本（去style/script→去标签→解码实体→压空白→截4000字）。
// 正则步骤与鸿蒙版 HtmlExtractor.ets 逐序直译，保持两端行为一致
object HtmlExtractor {
    fun stripTags(html: String): String {
        var t = html
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), " ")
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        t = t.replace(Regex("\\s+"), " ").trim()
        return if (t.length > 4000) t.substring(0, 4000) else t
    }
}
