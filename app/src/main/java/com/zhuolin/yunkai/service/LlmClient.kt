package com.zhuolin.yunkai.service

import com.zhuolin.yunkai.model.AppConfig
import com.zhuolin.yunkai.model.ChatMsg
import com.zhuolin.yunkai.model.ToolCall
import com.zhuolin.yunkai.model.ToolDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// LLM 客户端：OpenAI 兼容 /chat/completions 与 /models。
// 请求体显式 max_tokens（16384，长 HTML 讲解不截断）；tools 走 OpenAI function calling 协议。
// 语义唯一依据：yunkai-harmony/entry/src/main/ets/service/LlmClient.ets

// 完整 message（含 tool_calls），供 agent 循环按调用分派；导出供测试 fake 构造脚本
@Serializable
data class OpenAiMessage(
    val content: String = "",
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
)

@Serializable
private data class ChatRequestBody(
    val model: String,
    val messages: List<ChatMsg>,
    val stream: Boolean = false,
    val temperature: Double = 0.6,
    @SerialName("max_tokens") val maxTokens: Long = 16384L,
    val tools: List<ToolDef>? = null,
)

class LlmClient(private val cfg: AppConfig) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val client = OkHttpClient.Builder()
        .readTimeout(180, TimeUnit.SECONDS) // 整页 HTML 生成慢
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun base(): String = cfg.baseUrl.replace(Regex("/+$"), "")

    companion object {
        // 单次响应 token 上限：常量化显式下发，防端点默认值截断长讲解
        const val MAX_TOKENS: Int = 16384

        private val bodyJson = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }

        // 构造请求体：stream 固定 false；max_tokens 恒写；tools 非 null 才写（OpenAI 兼容 function calling）
        fun buildRequestBody(model: String, messages: List<ChatMsg>, tools: List<ToolDef>?): String =
            bodyJson.encodeToString(ChatRequestBody.serializer(), ChatRequestBody(model, messages, tools = tools))

        // 解析完整 message（含 tool_calls），供 agent 循环按调用分派。
        // 解析边界归一（对齐鸿蒙版）：
        // - content:null → ''（tool_calls 轮普遍返回 content:null；null 扩散会打穿 messages 回传
        //   序列化 / HtmlGuard.sanitize 的 trim / messages 表 NOT NULL）；
        // - tool_calls 做数组性守卫（非数组视为未携带），防畸形响应带病下行；
        // - choices 空抛错。
        fun extractMessage(jsonStr: String): OpenAiMessage {
            val obj = bodyJson.parseToJsonElement(jsonStr).let { it as? JsonObject }
                ?: throw Exception("LLM 响应格式非法")
            val choices = obj["choices"] as? JsonArray ?: throw Exception("LLM 响应无 choices")
            if (choices.isEmpty()) throw Exception("LLM 响应无 choices")
            val raw = (choices[0] as? JsonObject)?.get("message") as? JsonObject
            var content = ""
            var toolCalls: List<ToolCall>? = null
            if (raw != null) {
                val c = raw["content"]
                if (c is JsonPrimitive && c.isString) content = c.content
                val tcs = raw["tool_calls"]
                if (tcs is JsonArray) {
                    toolCalls = bodyJson.decodeFromJsonElement(tcs)
                }
            }
            return OpenAiMessage(content = content, toolCalls = toolCalls)
        }
    }

    // 非流式对话：返回完整 message（含 tool_calls），tools 由调用方显式给（null 表示不带）。
    // model 可选覆盖：双模型分工——agent 循环 use_skill 后传 longModel 生成长文；
    // 不传或空串回退 cfg.model（其他调用方零改动）
    suspend fun chatMessage(messages: List<ChatMsg>, tools: List<ToolDef>?, model: String? = null): OpenAiMessage =
        withContext(Dispatchers.IO) {
            val useModel = if (!model.isNullOrEmpty()) model else cfg.model
            val req = Request.Builder()
                .url("${base()}/chat/completions")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${cfg.apiKey}")
                .post(buildRequestBody(useModel, messages, tools).toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    throw Exception("LLM HTTP ${resp.code}: ${text.take(300)}")
                }
                extractMessage(text)
            }
        }

    // GET {base}/models → data[].id 数组
    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${base()}/models")
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw Exception("模型列表 HTTP ${resp.code}: ${text.take(300)}")
            }
            val obj = json.parseToJsonElement(text) as? JsonObject ?: throw Exception("模型列表格式非法")
            val data = obj["data"] as? JsonArray ?: emptyList()
            data.mapNotNull { (it as? JsonObject)?.get("id")?.let { v -> (v as? JsonPrimitive)?.content } }
        }
    }
}
