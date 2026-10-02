package com.zhuolin.yunkai.service

import com.zhuolin.yunkai.model.AppConfig
import com.zhuolin.yunkai.model.ChatMsg
import com.zhuolin.yunkai.model.ChatMsgListJsonTransform
import com.zhuolin.yunkai.model.ToolCall
import com.zhuolin.yunkai.model.ToolDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import java.io.IOException
import java.util.concurrent.TimeUnit

// LLM 客户端：OpenAI 兼容 /chat/completions 与 /models。
// 请求体显式 max_tokens（16384，长 HTML 讲解不截断）；tools 走 OpenAI function calling 协议。
// 语义唯一依据：yunkai-harmony/entry/src/main/ets/service/LlmClient.ets
// 2026-10-02 执行到底改造：连接失败与 5xx/429 自动重试（指数退避），瞬时网络抖动不再
// 直接炸掉整轮对话；4xx（密钥/参数错）与流式读中断裂不重试。

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

    companion object {
        // 单次响应 token 上限：常量化显式下发，防端点默认值截断长讲解
        const val MAX_TOKENS: Int = 16384

        // 网络重试（2026-10-02 执行到底改造）：连接失败/5xx/429 重试 2 次，退避 1s/2s
        const val HTTP_RETRIES: Int = 2
        const val RETRY_BACKOFF_MS: Long = 1000L

        // 门0 W-C5：进程级共享 OkHttpClient——每次 send 新建 LlmClient 时 TLS 连接池无法复用，
        // 白付握手延迟；超时配置全实例一致，共享无副作用
        private val sharedClient: OkHttpClient = OkHttpClient.Builder()
            // 非流式下 glm-4.7 长文（1 万+ token）实测 180s 无字节必超时（模拟器 eli5 两次复现），
            // 放宽到 600s；OkHttp readTimeout 是读间隔超时而非总时长，长连接保活不受影响
            .readTimeout(600, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()

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
                    toolCalls = bodyJson.decodeFromJsonElement(normalizeToolCallArgs(tcs))
                }
            }
            return OpenAiMessage(content = content, toolCalls = toolCalls)
        }

        // 门0 W-C7：tool_calls.arguments 严格按 String 反序列化，端点直接回 JSON 对象/数组
        // （或不带）时整个 decode 炸掉、整轮失败。此处把非字符串形态归一为 JSON 文本，
        // 缺省补 "{}"，下游 execTool 的解析路径零改动。
        private fun normalizeToolCallArgs(tcs: JsonArray): JsonArray = JsonArray(tcs.map { tc ->
            val obj = tc as? JsonObject ?: return@map tc
            val fn = obj["function"] as? JsonObject ?: return@map tc
            val args = fn["arguments"]
            if (args is JsonPrimitive && args.isString) return@map tc
            val normalized = args?.toString() ?: "{}"
            val newFn = JsonObject(fn.toMap() + ("arguments" to JsonPrimitive(normalized)))
            JsonObject(obj.toMap() + ("function" to newFn))
        })
    }

    private fun base(): String = cfg.baseUrl.replace(Regex("/+$"), "")

    private val client: OkHttpClient get() = sharedClient

    // 带重试的请求执行：连接失败（IOException）与 5xx/429 重试 HTTP_RETRIES 次（线性退避），
    // 其余 4xx 直接抛（密钥/参数错重试无意义）。成功响应（含流式的 body）由调用方 use{} 消费。
    // cancelled（门0 W-C2）：调用方取消旗标——看门狗协程 400ms 轮询，命中即 cancel() 在途 socket，
    // 否则 readTimeout(600s) 期间 IO 线程被占死、用户取消后仍白跑整段读。必须在 IO 线程调用。
    private suspend fun executeWithRetry(req: Request, cancelled: (() -> Boolean)?): Response = coroutineScope {
        var lastErr: Exception? = null
        for (attempt in 0..HTTP_RETRIES) {
            if (attempt > 0) delay(RETRY_BACKOFF_MS * attempt)
            if (cancelled?.invoke() == true) break
            val call = client.newCall(req)
            val watcher = cancelled?.let { flag ->
                launch {
                    while (!flag.invoke()) {
                        if (call.isCanceled()) return@launch
                        delay(400)
                    }
                    call.cancel()
                }
            }
            try {
                val resp = call.execute()
                if (resp.isSuccessful) {
                    watcher?.cancel()
                    return@coroutineScope resp
                }
                val code = resp.code
                val errText = resp.body?.string() ?: ""
                resp.close()
                if (code >= 500 || code == 429) {
                    lastErr = IOException("LLM HTTP $code: ${errText.take(300)}")
                    continue
                }
                throw Exception("LLM HTTP $code: ${errText.take(300)}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IOException) {
                lastErr = e
            } finally {
                watcher?.cancel()
            }
        }
        if (cancelled?.invoke() == true) throw IOException(AgentLoop.CANCELLED_MSG)
        throw IOException("LLM 请求失败（已重试 $HTTP_RETRIES 次）: ${lastErr?.message ?: "未知错误"}")
    }

    // 非流式对话：返回完整 message（含 tool_calls），tools 由调用方显式给（null 表示不带）。
    // model 可选覆盖：双模型分工——agent 循环 use_skill 后传 longModel 生成长文；
    // 不传或空串回退 cfg.model（其他调用方零改动）。
    // cancelled（门0 W-C2）：取消旗标透传 executeWithRetry，命中即 cancel 在途 socket。
    suspend fun chatMessage(
        messages: List<ChatMsg>,
        tools: List<ToolDef>?,
        model: String? = null,
        cancelled: (() -> Boolean)? = null,
    ): OpenAiMessage =
        withContext(Dispatchers.IO) {
            val useModel = if (!model.isNullOrEmpty()) model else cfg.model
            val req = Request.Builder()
                .url("${base()}/chat/completions")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${cfg.apiKey}")
                .post(buildRequestBody(useModel, messages, tools).toRequestBody("application/json".toMediaType()))
                .build()
            executeWithRetry(req, cancelled).use { resp ->
                val text = resp.body?.string() ?: ""
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
        cancelled: (() -> Boolean)? = null,
    ): OpenAiMessage = withContext(Dispatchers.IO) {
        val useModel = if (!model.isNullOrEmpty()) model else cfg.model
        val req = Request.Builder()
            .url("${base()}/chat/completions")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .post(buildRequestBody(useModel, messages, tools, stream = true).toRequestBody("application/json".toMediaType()))
            .build()
        // 只对「拿到响应前」的失败重试（连接失败/5xx/429）；流式读中断裂不重试——
        // 此时 onDelta 已外发部分文本，重试会让 UI 出现重复内容。
        // cancelled 透传看门狗（W-C2）：流式首字节前挂死也能被用户取消打断
        executeWithRetry(req, cancelled).use { resp ->
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
                        else if (fa != null) tcArgs[idx]!!.append(fa.toString())   // 门0 W-C7：对象/数组形态归一为 JSON 文本
                    }
                }
            }

            try {
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
            } catch (e: IOException) {
                // 门0 W-C2：取消旗标命中导致的断流按「用户取消」收敛（上层静默吞），
                // 否则按网络错上抛
                if (cancelled?.invoke() == true) throw Exception(AgentLoop.CANCELLED_MSG)
                throw e
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
