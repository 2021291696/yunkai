package com.zhuolin.yunkai.service

import android.util.Log
import com.zhuolin.yunkai.model.AppConfig
import com.zhuolin.yunkai.model.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

// 搜索客户端：博查/Tavily/必应爬取 三 provider，snippet 超 200 字截断。
// 语义唯一依据：yunkai-harmony/entry/src/main/ets/service/SearchClient.ets（逐行翻译）
class SearchClient(private val cfg: AppConfig) {

    // 按 cfg.searchProvider 分派（bing 爬取无需 key）。
    // 必应对整句自然语言查询易被疑问词带偏（实测「这周国际上有什么科技大事」搜出单字释义），
    // 只对 bing 做关键词提取；博查/Tavily 本身支持自然语言查询保留原句
    suspend fun search(query: String): List<SearchHit> = when (cfg.searchProvider) {
        "tavily" -> SearchClient.searchTavily(cfg.searchApiKey, query)
        "bing" -> SearchClient.searchBing(SearchClient.extractKeywords(query))
        else -> SearchClient.searchBocha(cfg.searchApiKey, query)
    }

    companion object {
        private const val TAG = "yunkai"

        // Chrome UA：read_web 与必应爬取共用同一伪装，避免被目标站按 UA 拦截
        const val CHROME_UA: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        private val httpClient = OkHttpClient.Builder()
            .readTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()

        // 疑问词/时间词/口语句式停用词：整句提问剥掉后剩核心名词，搜索引擎召回质量更高
        // （STOPWORDS 列表与鸿蒙版逐字一致，一个词都不许改）
        private val STOPWORDS: List<String> = listOf(
            "为什么", "怎么回事", "怎么样", "怎样", "怎么办", "如何", "是什么", "什么是",
            "有什么", "有哪些", "有没有", "是不是", "哪一个", "哪些", "哪个", "哪里", "哪儿",
            "这周", "本周", "上周", "最近", "日前", "请问", "告诉我", "解释一下",
            "介绍一下", "想知道", "想弄懂", "想了解", "讲讲", "说说", "到底", "究竟", "一下",
            "我", "你", "他", "她", "它", "我们", "你们", "吗", "呢", "吧", "啊", "呀",
            "的", "了", "是", "有", "和", "与", "或", "都", "也", "很", "被", "把", "让", "对", "关于",
        )

        // 纯函数：自然语言问题 → 空格分隔的关键词串；全被停用词吃掉则回退原句
        fun extractKeywords(q: String): String {
            var t = q.trim()
            for (sw in STOPWORDS) {
                t = t.split(sw).joinToString(" ")
            }
            // 剥中英文标点，避免「？」等混进搜索词
            t = t.replace(Regex("[，。？！、：；“”‘’（）《》【】,?!:;'\"()\\[\\]{}]"), " ")
            val parts = mutableListOf<String>()
            val raw = t.split(Regex("\\s+"))
            for (w in raw) {
                if (w.isNotEmpty() && !parts.contains(w)) parts.add(w)
            }
            if (parts.isEmpty()) return q.trim()
            return parts.take(6).joinToString(" ")
        }

        // 截断 helper：snippet 超 200 字截断
        private fun truncate(s: String): String = if (s.length > 200) s.substring(0, 200) else s

        private fun postJson(url: String, key: String, body: String): String {
            val req = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $key")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw Exception("搜索 HTTP ${resp.code}: ${text.take(300)}")
                return text
            }
        }

        // POST https://api.bochaai.com/v1/web-search
        // 头 Authorization: Bearer {key}；体 {"query":query,"summary":true,"count":5}
        // 解析 data.webPages.value[]：snippet 取 summary 非空否则 snippet 字段；HTTP≠2xx 抛 `搜索 HTTP ${code}: 前300字`
        suspend fun searchBocha(key: String, query: String): List<SearchHit> = withContext(Dispatchers.IO) {
            val text = postJson(
                "https://api.bochaai.com/v1/web-search", key,
                Json.encodeToString(BochaReq.serializer(), BochaReq(query)),
            )
            parseBocha(text)
        }

        // POST https://api.tavily.com/search
        // 头 Authorization: Bearer {key}；体 {"query":query,"max_results":5}
        // 解析 results[]：snippet 取 content 截断；错误处理同上
        suspend fun searchTavily(key: String, query: String): List<SearchHit> = withContext(Dispatchers.IO) {
            val text = postJson(
                "https://api.tavily.com/search", key,
                Json.encodeToString(TavilyReq.serializer(), TavilyReq(query)),
            )
            parseTavily(text)
        }

        // GET https://cn.bing.com/search?q={query}&mkt=zh-CN（本地爬取，免 key，国内直连稳）
        // 解析 li.b_algo 块：h2>a[href] 取标题与链接，p 取摘要；HTTP≠2xx 抛错同风格
        suspend fun searchBing(query: String): List<SearchHit> = withContext(Dispatchers.IO) {
            val url = "https://cn.bing.com/search?q=" +
                URLEncoder.encode(query, "UTF-8") + "&mkt=zh-CN"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", CHROME_UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()
            val text = httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw Exception("搜索 HTTP ${resp.code}: ${body.take(300)}")
                body
            }
            val hits = parseBing(text)
            if (hits.isEmpty()) {
                // HTTP 200 但解析 0 条：风控验证页/页面结构变化/移动网络出口差异都会走到这，留证据别静默
                Log.w(TAG, "bing 0 hits, body head: ${text.replace(Regex("\\s+"), " ").take(300)}")
            }
            hits
        }

        // 实体解码：命名实体 + 数字实体（顺序与鸿蒙版一致）
        fun decodeEntities(s: String): String {
            var t = s
                .replace(Regex("<[^>]+>"), "")
                .replace("&ensp;", " ").replace("&emsp;", " ").replace("&nbsp;", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&middot;", "·")
                .replace("&#0183;", "·").replace("&#160;", " ").replace("&#183;", "·")
            // 剩余数字实体 &#NNNN;
            t = Regex("&#(\\d+);").replace(t) { m ->
                val code = m.groupValues[1].toInt()
                code.toChar().toString()
            }
            return t.replace(Regex("\\s+"), " ").trim()
        }

        // 纯函数：必应结果页 HTML → SearchHit[]（前 5 条）
        fun parseBing(html: String): List<SearchHit> {
            val out = mutableListOf<SearchHit>()
            val blockRe = Regex(
                "<li class=\"b_algo\"[\\s\\S]*?<h2[^>]*><a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>[\\s\\S]*?</li>",
                RegexOption.IGNORE_CASE,
            )
            val snipRe = Regex("<p[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE)
            for (m in blockRe.findAll(html)) {
                if (out.size >= 5) break
                val url = m.groupValues[1]
                val title = decodeEntities(m.groupValues[2])
                var snippet = ""
                val snipMatch = snipRe.find(m.value)
                if (snipMatch != null) snippet = decodeEntities(snipMatch.groupValues[1])
                if (title.isNotEmpty() && url.isNotEmpty()) {
                    out.add(SearchHit(title = title, url = url, snippet = truncate(snippet)))
                }
            }
            return out
        }

        // 纯函数：博查响应 → SearchHit[]
        fun parseBocha(jsonStr: String): List<SearchHit> {
            val obj = jsonAdaptive.parseToJsonElement(jsonStr) as? JsonObject ?: return emptyList()
            val value = ((obj["data"] as? JsonObject)?.get("webPages") as? JsonObject)
                ?.get("value") as? JsonArray ?: return emptyList()
            val out = mutableListOf<SearchHit>()
            for (item in value) {
                val it = item as? JsonObject ?: continue
                val summary = (it["summary"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: ""
                val snippetRaw = (it["snippet"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: ""
                val raw = if (summary.isNotEmpty()) summary else snippetRaw
                out.add(SearchHit(
                    title = (it["name"] as? JsonPrimitive)?.content ?: "",
                    url = (it["url"] as? JsonPrimitive)?.content ?: "",
                    snippet = truncate(raw),
                ))
            }
            return out
        }

        // 纯函数：Tavily 响应 → SearchHit[]
        fun parseTavily(jsonStr: String): List<SearchHit> {
            val obj = jsonAdaptive.parseToJsonElement(jsonStr) as? JsonObject ?: return emptyList()
            val results = obj["results"] as? JsonArray ?: return emptyList()
            val out = mutableListOf<SearchHit>()
            for (item in results) {
                val it = item as? JsonObject ?: continue
                out.add(SearchHit(
                    title = (it["title"] as? JsonPrimitive)?.content ?: "",
                    url = (it["url"] as? JsonPrimitive)?.content ?: "",
                    snippet = truncate((it["content"] as? JsonPrimitive)?.content ?: ""),
                ))
            }
            return out
        }

        private val jsonAdaptive = Json { ignoreUnknownKeys = true; isLenient = true }

        @kotlinx.serialization.Serializable
        private data class BochaReq(val query: String, val summary: Boolean = true, val count: Int = 5)

        @kotlinx.serialization.Serializable
        private data class TavilyReq(val query: String, @kotlinx.serialization.SerialName("max_results") val maxResults: Int = 5)
    }
}
