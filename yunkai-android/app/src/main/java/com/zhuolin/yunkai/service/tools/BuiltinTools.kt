package com.zhuolin.yunkai.service.tools

import com.zhuolin.yunkai.model.AgentSkill
import com.zhuolin.yunkai.model.AppConfig
import com.zhuolin.yunkai.service.HtmlExtractor
import com.zhuolin.yunkai.service.SearchClient
import com.zhuolin.yunkai.store.SkillSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// 内置三工具：web_search / read_web / use_skill。
// 统一契约：execute 失败一律返回 '{"error":"..."}' 字符串，不向上抛异常。
// 语义唯一依据：yunkai-harmony/entry/src/main/ets/service/tools/BuiltinTools.ets
object BuiltinTools {
    private val http = OkHttpClient.Builder()
        .readTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // 三个工具的工厂；skillForced 非 null（用户 @手动指定技能）时 use_skill 免查库直接返回该技能
    fun createAll(cfg: AppConfig, skillForced: AgentSkill?, skillSource: SkillSource): List<AgentTool> =
        listOf(buildWebSearch(cfg), buildReadWeb(), buildUseSkill(skillForced, skillSource))

    // 失败收敛 helper：统一走 JSON 序列化保证 error 字段名与 JSON 合法性
    fun err(msg: String): String = json.encodeToString(ErrorPayload.serializer(), ErrorPayload(msg))

    @kotlinx.serialization.Serializable
    private data class ErrorPayload(val error: String)

    // 取异常的 message（所有 Throwable 一视同仁；空 message 退化为 toString）
    fun errMsg(e: Throwable): String =
        if (!e.message.isNullOrEmpty()) e.message!! else e.toString()

    // args 解析 + 必填字段校验：返回空串表示缺参（由调用方转 error）
    fun requireField(obj: JsonObject, field: String): String {
        val v = obj[field] as? JsonPrimitive ?: return ""
        if (!v.isString) return ""
        return v.content
    }

    private fun parseArgs(argsJson: String): JsonObject? =
        try {
            json.parseToJsonElement(argsJson) as? JsonObject
        } catch (e: Exception) {
            null
        }

    // web_search：{query} → SearchClient.search（必应轨已带关键词提取）→ 前 5 条拼
    // `[1] 标题\n摘要\n链接` 文本。
    // 密钥前置引导：博查/Tavily 需 key，未配置时直接给可操作的引导文案，
    // 不让裸 401 error 下传给模型（必应爬取免 key 不受影响）。
    private fun buildWebSearch(cfg: AppConfig): AgentTool = object : AgentTool() {
        override val name = "web_search"
        override val description =
            "联网搜索。输入关键词或自然语言问题，返回前5条搜索结果（标题、摘要、链接）。需要时效性信息或自身知识不确定时使用。"
        override val parametersJson =
            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"搜索关键词或自然语言问题\"}},\"required\":[\"query\"]}"

        override suspend fun execute(argsJson: String): String = run {
            try {
            if ((cfg.searchProvider == "bocha" || cfg.searchProvider == "tavily") && cfg.searchApiKey.isEmpty()) {
                return BuiltinTools.err("未配置搜索密钥，请在设置页填写搜索密钥；或将搜索源切换为必应(免key)后重试。")
            }
            val query = BuiltinTools.requireField(parseArgs(argsJson) ?: JsonObject(emptyMap()), "query")
            if (query.isEmpty()) {
                return BuiltinTools.err("web_search 缺少 query 参数")
            }
            val hits = SearchClient(cfg).search(query)
            if (hits.isEmpty()) {
                return "未搜索到相关结果，请换关键词重试或依靠自身知识回答。"
            }
            val lines = mutableListOf<String>()
            for (i in hits.indices) {
                if (i >= 5) break
                lines.add("[${i + 1}] ${hits[i].title}\n${hits[i].snippet}\n${hits[i].url}")
            }
            lines.joinToString("\n")
        } catch (e: Exception) {
            BuiltinTools.err("web_search 失败: ${BuiltinTools.errMsg(e)}")
        }
        }
    }

    // read_web：{url} → http GET（Chrome UA、20s 超时）→ stripTags 截 6000 字；非 2xx/异常 → error
    private fun buildReadWeb(): AgentTool = object : AgentTool() {
        override val name = "read_web"
        override val description =
            "读取网页正文。输入完整 URL，返回剥掉标签后的纯文本（最长6000字）。搜索结果需要展开细节时使用。"
        override val parametersJson =
            "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"要读取的网页完整URL\"}},\"required\":[\"url\"]}"

        override suspend fun execute(argsJson: String): String {
            val args = parseArgs(argsJson)
            if (args == null) {
                return err("read_web 参数不是合法 JSON")
            }
            val url = BuiltinTools.requireField(args, "url")
            if (url.isEmpty()) {
                return err("read_web 缺少 url 参数")
            }
            return withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", SearchClient.CHROME_UA)
                        .header("Accept-Language", "zh-CN,zh;q=0.9")
                        .build()
                    http.newCall(req).execute().use { resp ->
                        val body = resp.body?.string() ?: ""
                        if (!resp.isSuccessful) {
                            return@withContext err("read_web HTTP ${resp.code}: ${body.take(300)}")
                        }
                        var text = HtmlExtractor.stripTags(body)
                        if (text.length > 6000) text = text.substring(0, 6000)
                        if (text.isEmpty()) {
                            err("read_web 页面剥标签后为空，可能不是正文型网页")
                        } else {
                            text
                        }
                    }
                } catch (e: Exception) {
                    err("read_web 失败: ${errMsg(e)}")
                }
            }
        }
    }

    // use_skill：{name} → SkillSource.getByName → 命中返回说明书全文让模型严格照做；未命中 → error。
    // skillForced 非 null 时免查库直接返回（@手动指定场景，且允许指向未入库的临时技能）
    private fun buildUseSkill(skillForced: AgentSkill?, skillSource: SkillSource): AgentTool = object : AgentTool() {
        override val name = "use_skill"
        override val description =
            "调用技能配方。技能是写好的详细操作说明书，命中后你必须严格按说明书执行任务。"
        override val parametersJson =
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"description\":\"技能名称\"}},\"required\":[\"name\"]}"

        override suspend fun execute(argsJson: String): String = run {
            try {
            if (skillForced != null) {
                return BuiltinTools.skillPayload(skillForced.name, skillForced.content)
            }
            val name = BuiltinTools.requireField(parseArgs(argsJson) ?: JsonObject(emptyMap()), "name")
            if (name.isEmpty()) {
                return BuiltinTools.err("use_skill 缺少 name 参数")
            }
            val skill = skillSource.getByName(name)
            if (skill == null) {
                return BuiltinTools.err("未找到名为「${name}」的技能")
            }
            BuiltinTools.skillPayload(skill.name, skill.content)
        } catch (e: Exception) {
            BuiltinTools.err("use_skill 失败: ${BuiltinTools.errMsg(e)}")
        }
        }
    }

    fun skillPayload(name: String, content: String): String =
        "以下是「${name}」skill 的完整说明书，请严格按它执行：\n${content}"
}
