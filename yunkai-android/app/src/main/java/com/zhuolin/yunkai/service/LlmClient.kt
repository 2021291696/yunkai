package com.zhuolin.yunkai.service

import com.zhuolin.yunkai.model.AppConfig
import com.zhuolin.yunkai.model.ChatMsg
import com.zhuolin.yunkai.model.ChatMsgListJsonTransform
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
import okhttp3.Response
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
    @Serializable(with = ChatMsgListJsonTransform::class) val messages: List<ChatMsg>,
    val stream: Boolean = false,
    val temperature: Double = 0.6,
    @SerialName("max_tokens") val maxTokens: Long = 16384L,
    val tools: List<ToolDef>? = null,
)

class LlmClient(private val cfg: AppConfig) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val client = OkHttpClient.Builder()
        // 非流式下 glm-4.7 长文（1 万+ token）实测 180s 无字节必超时（模拟器 eli5 两次复现），
        // 放宽到 600s；OkHttp readTimeout 是读间隔超时而非总时长，长连接保活不受影响
        .readTimeout(600, TimeUnit.SECONDS)
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

        // 构造请求体：stream 默认 false（chatStream 显式传 true）；max_tokens 恒写；tools 非 null 才写
        fun buildRequestBody(model: String, messages: List<ChatMsg>, tools: List<ToolDef>?, stream: Boolean = false): String =
            bodyJson.encodeToString(ChatRequestBody.serializer(), ChatRequestBody(model, messages, stream = stream, tools = tools))

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

    // ── B1 SSE 流式对话 ──
    // 与 chatMessage 同协议，但 stream=true：阻塞读响应体逐行解析 SSE（不引 okhttp-sse 依赖）。
    // delta.content 增量经 onDelta(累计文本) 上抛（UI 流式气泡）；delta.tool_calls 分片按 index
    // 组装（id/function.name 取首片，arguments 顺序拼接）。流结束返回与非流式同构的完整 message，
    // AgentLoop 分派逻辑零改动。取消由调用方 isCancelled 轮询在 onDelta 里抛 CANCELLED_MSG。
    // 约束：必须在 IO 线程调用（内部无切线程，与 chatMessage 同纪律）。
    suspend fun chatStream(
        messages: List<ChatMsg>,
        tools: List<ToolDef>?,
        model: String?,
        onDelta: (String) -> Unit,
        onThinking: ((String) -> Unit)? = null,
    ): OpenAiMessage = withContext(Dispatchers.IO) {
        val useModel = if (!model.isNullOrEmpty()) model else cfg.model
        val req = Request.Builder()
            .url("${base()}/chat/completions")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .post(buildRequestBody(useModel, messages, tools, stream = true).toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errText = resp.body?.string() ?: ""
                throw Exception("LLM HTTP ${resp.code}: ${errText.take(300)}")
            }
            val source = resp.body?.source() ?: throw Exception("LLM 响应无 body")
            var content = StringBuilder()
            var think = StringBuilder()   // reasoning_content 累计（思考流）
            // tool_calls 分片组装：index → (id, name, arguments)，arguments 顺序拼接
            val tcIds = mutableMapOf<Int, String>()
            val tcNames = mutableMapOf<Int, String>()
            val tcArgs = mutableMapOf<Int, StringBuilder>()
            var tcOrder: MutableList<Int> = mutableListOf()
            var sawDone = false

            fun applyDelta(delta: JsonObject?) {
                if (delta == null) return
                val r = delta["reasoning_content"] ?: delta["reasoning"]
                if (r is JsonPrimitive && r.isString && r.content.isNotEmpty()) {
                    think.append(r.content)
                    onThinking?.invoke(think.toString())
                }
                val c = delta["content"]
                if (c is JsonPrimitive && c.isString && c.content.isNotEmpty()) {
                    content.append(c.content)
                    onDelta(content.toString())
                }
                val tcs = delta["tool_calls"]
                if (tcs is JsonArray) {
                    for (e in tcs) {
                        val tc = e as? JsonObject ?: continue
                        val idx = (tc["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                        if (!tcArgs.containsKey(idx)) {
                            tcOrder.add(idx)
                            tcArgs[idx] = StringBuilder()
                            val id = (tc["id"] as? JsonPrimitive)?.content
                            if (!id.isNullOrEmpty()) tcIds[idx] = id
                            val fn = tc["function"] as? JsonObject
                            val name = (fn?.get("name") as? JsonPrimitive)?.content
                            if (!name.isNullOrEmpty()) tcNames[idx] = name
                        } else {
                            val id = (tc["id"] as? JsonPrimitive)?.content
                            if (!id.isNullOrEmpty() && !tcIds.containsKey(idx)) tcIds[idx] = id
                            val fn = tc["function"] as? JsonObject
                            val name = (fn?.get("name") as? JsonPrimitive)?.content
                            if (!name.isNullOrEmpty() && !tcNames.containsKey(idx)) tcNames[idx] = name
                        }
                        val fa = (tc["function"] as? JsonObject)?.get("arguments")
                        if (fa is JsonPrimitive && fa.isString) tcArgs[idx]!!.append(fa.content)
                    }
                }
            }

            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") { sawDone = true; break }
                if (payload.isEmpty()) continue
                val obj = runCatching { bodyJson.parseToJsonElement(payload) }.getOrNull() as? JsonObject
                    ?: continue
                val choices = obj["choices"] as? JsonArray ?: continue
                if (choices.isEmpty()) continue
                applyDelta((choices[0] as? JsonObject)?.get("delta") as? JsonObject)
            }

            val toolCalls: List<ToolCall>? = if (tcOrder.isEmpty()) null else tcOrder.mapNotNull { idx ->
                val id = tcIds[idx] ?: "call_$idx"
                val name = tcNames[idx] ?: return@mapNotNull null
                ToolCall(id = id, type = "function", function = com.zhuolin.yunkai.model.FunctionCall(
                    name = name, arguments = tcArgs[idx]?.toString() ?: "{}"))
            }
            OpenAiMessage(content = content.toString(), toolCalls = toolCalls)
        }
    }
}
