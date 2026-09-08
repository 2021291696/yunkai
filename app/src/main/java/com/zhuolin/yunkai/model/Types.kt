package com.zhuolin.yunkai.model

// 数据类型与枚举：全 app 共用的最小类型集合
// ⚠️ 线上协议字段名是蛇形（tool_calls / tool_call_id），Kotlin 属性保持驼峰，
//    一律用 @SerialName 映射——缺了它 function calling 在真机上静默失效
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// OpenAI 兼容对话消息：assistant 发起工具调用时携带 toolCalls；
// role=tool 回传结果时 toolCallId 对应调用 id
@Serializable
data class ChatMsg(
    val role: String,
    val content: String,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

// OpenAI 兼容 function calling 协议
@Serializable
data class ToolCall(val id: String, val type: String, val function: FunctionCall)

@Serializable
data class FunctionCall(val name: String, val arguments: String) // arguments 是 JSON 字符串

// 工具定义（请求体 tools[] 元素）：OpenAI function calling 协议（type + function 描述）
// function 字段名本身就是 function，无需映射
@Serializable
data class ToolDef(val type: String, val function: FunctionDef? = null)

@Serializable
data class FunctionDef(
    val name: String,
    val description: String,
    val parameters: JsonSchema? = null,
)

@Serializable
data class JsonSchema(
    val type: String,
    val properties: Map<String, kotlinx.serialization.json.JsonObject>? = null,
    val required: List<String>? = null,
)

@Serializable
data class SearchHit(val title: String, val url: String, val snippet: String)

// 会话列表行（conversations 表的 UI 视图）
data class Conv(val id: Long = 0, val title: String = "", val updatedAt: Long = 0)

// 消息行（messages 表的 UI 视图）
data class Msg(
    val id: Long = 0,
    val convId: Long = 0,
    val role: String = "",
    val content: String = "",   // user=问题原文; assistant=HTML 原文
    val plain: String = "",     // assistant 剥标签纯文本
    val turnNo: Int = 0,        // assistant 从 1 递增
    val createdAt: Long = 0,
)

// 应用配置（DataStore 持久化；键与字段同名）
data class AppConfig(
    var baseUrl: String = "",
    var apiKey: String = "",
    var model: String = "",
    var longModel: String = "glm-4.7",   // use_skill 后长文生成模型；M1 不进设置 UI，M2 再做
    var autoRoute: Boolean = true,       // 技能自动路由；false=仅 @名字 手动触发技能
    // 保留字段（auto|builtin|external）：鸿蒙版 agent 重构后 UI 三选已下线，
    // 字段保留供 SearchRouter（备胎搜索轨，未接线）语义完整；不进设置页
    var searchMode: String = "auto",
    var searchProvider: String = "bing", // bing(免key爬取) | bocha | tavily
    var searchApiKey: String = "",
)

// Agent 技能配方：skills 表一行（内置 eli5 与用户自建技能共用）
data class AgentSkill(
    val id: Long = 0,
    val name: String = "",
    val description: String = "",
    val content: String = "",
    val builtin: Boolean = false,
)

// 搜索路由的可用形态（备胎轨道 SearchRouter 用；现役链路不经过它）
enum class NetMode { BUILTIN, EXTERNAL, NONE }
