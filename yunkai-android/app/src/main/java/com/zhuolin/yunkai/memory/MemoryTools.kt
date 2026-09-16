package com.zhuolin.yunkai.memory

import com.zhuolin.yunkai.memory.MemoryStore.Companion.coreLimitOf
import com.zhuolin.yunkai.service.tools.AgentTool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * 忆枢五工具（协议 §3）：core_memory_append / core_memory_replace / archival_memory_insert /
 * archival_memory_search / conversation_search。
 *
 * 契约纪律：失败一律返回 {"error":"<说明>"} 字符串，不抛异常（yunkai 工具统一约定）；
 * 写入类（append/replace/insert）前置隐私闸门（协议 §5，挡位经 gearProvider 每次执行时从
 * ConfigStore 读，未匹配回退 strict）；超限整次拒绝不部分写入（§3.1）。
 * 出参字段名按协议蛇形（created_at / conversation_id / turn_no），score 4 位小数（Bm25 内完成）。
 */
object MemoryTools {
    const val TOOL_CORE_APPEND = "core_memory_append"
    const val TOOL_CORE_REPLACE = "core_memory_replace"
    const val TOOL_ARCHIVAL_INSERT = "archival_memory_insert"
    const val TOOL_ARCHIVAL_SEARCH = "archival_memory_search"
    const val TOOL_CONVERSATION_SEARCH = "conversation_search"

    /** 五工具名集合：AgentLoop 长文形态（eli5）撤下时用（协议 §4.3）。 */
    val ALL_NAMES = setOf(
        TOOL_CORE_APPEND, TOOL_CORE_REPLACE, TOOL_ARCHIVAL_INSERT,
        TOOL_ARCHIVAL_SEARCH, TOOL_CONVERSATION_SEARCH,
    )

    const val ARCHIVAL_TOP_K = 5            // 协议 §2：默认返回条数
    const val ARCHIVAL_TOP_K_MAX = 20
    const val CONVERSATION_SEARCH_LIMIT = 10
    const val CONVERSATION_SEARCH_LIMIT_MAX = 50

    val ARCHIVAL_TYPES = listOf("fact", "episode", "preference", "identity")
}

private val memJson = Json { ignoreUnknownKeys = true; isLenient = true }
private val memEnc = Json { ignoreUnknownKeys = true }

@Serializable
private data class MemError(val error: String)

@Serializable
private data class CoreAppendResult(val appended: Boolean, val block: String, val chars: Int, val limit: Int)

@Serializable
private data class CoreReplaceResult(val replaced: Boolean, val block: String, val chars: Int, val limit: Int)

@Serializable
private data class ArchivalInsertResult(val saved: Boolean, val id: Long)

@Serializable
private data class ArchivalSearchHit(
    val id: Long,
    val content: String,
    val type: String,
    val created_at: Long,
    val score: Double,
)

@Serializable
private data class ArchivalSearchResult(val results: List<ArchivalSearchHit>, val count: Int)

@Serializable
private data class ConversationSearchHit(
    @SerialName("conversation_id") val conversationId: Long,
    @SerialName("turn_no") val turnNo: Int,
    val role: String,
    val content: String,
    val created_at: Long,
)

@Serializable
private data class ConversationSearchResult(val results: List<ConversationSearchHit>, val count: Int)

private fun memParse(argsJson: String): JsonObject? =
    try {
        memJson.parseToJsonElement(argsJson) as? JsonObject
    } catch (_: Exception) {
        null
    }

private fun memStr(obj: JsonObject, key: String): String? =
    (obj[key] as? JsonPrimitive)?.let { if (it.isString) it.content else null }

private fun memOptInt(obj: JsonObject, key: String): Int? =
    (obj[key] as? JsonPrimitive)?.takeIf { !it.isString }?.let { runCatching { it.jsonPrimitive.int }.getOrNull() }

private fun memErr(msg: String): String = memEnc.encodeToString(MemError.serializer(), MemError(msg))

/** 隐私闸门拒绝文案（协议 §5.2 命中拒绝返回，逐字模板）。 */
private fun memPrivacyErr(gearWire: String, category: PrivacyGate.Category): String =
    memErr("[隐私闸门-$gearWire] 检测到疑似${category.wire}，已拒存；该挡位可在设置中调整")

/** 写入前置闸门：拒绝时返回错误文案，放行返回 null。 */
private suspend fun gateReject(content: String, gearProvider: suspend () -> String): String? {
    val gear = PrivacyGate.Gear.fromWire(gearProvider())   // 未匹配回退 strict（协议 §5.1）
    return when (val r = PrivacyGate.check(content, gear)) {
        is PrivacyGate.GearResult.Reject -> memPrivacyErr(gear.wire, r.category)
        PrivacyGate.GearResult.Allow -> null
    }
}

/** 超限文案（协议 §3.1/3.2 失败示例模板；X=写入后总字符，Y=上限）。 */
private fun coreFullErr(block: String, newTotal: Int, limit: Int): String =
    memErr("核心记忆 $block 块已满($newTotal/$limit)，请用 core_memory_replace 替换不再需要的旧内容")

// ===== 3.1 core_memory_append：块内换行追加；追加后超限整次拒绝 =====
internal class CoreMemoryAppendTool(
    private val store: MemoryStore,
    private val gearProvider: suspend () -> String,
) : AgentTool() {
    override val name = MemoryTools.TOOL_CORE_APPEND
    override val description =
        "往核心记忆追加内容：human 块记用户的持久档案（身份/偏好/长期事实），persona 块记你希望长期保持的自我定义；每次追加一条，适合分次补充。"
    override val parametersJson =
        """{"type":"object","properties":{"block":{"type":"string","enum":["human","persona"],"description":"目标记忆块"},"content":{"type":"string","description":"要追加的一条内容"}},"required":["block","content"]}"""

    override suspend fun execute(argsJson: String): String {
        val obj = memParse(argsJson) ?: return memErr("参数格式非法")
        val block = memStr(obj, "block") ?: return memErr("缺少 block")
        val content = memStr(obj, "content") ?: return memErr("缺少 content")
        val limit = coreLimitOf(block) ?: return memErr("block 须为 human 或 persona")
        gateReject(content, gearProvider)?.let { return it }
        val cur = store.getCoreBlock(block)?.content ?: ""
        val next = if (cur.isEmpty()) content else "$cur\n$content"
        if (next.length > limit) return coreFullErr(block, next.length, limit)
        store.putCoreBlock(block, next)
        return memEnc.encodeToString(CoreAppendResult.serializer(), CoreAppendResult(true, block, next.length, limit))
    }
}

// ===== 3.2 core_memory_replace：old_content 须精确出现且唯一；替换后超限整次拒绝 =====
internal class CoreMemoryReplaceTool(
    private val store: MemoryStore,
    private val gearProvider: suspend () -> String,
) : AgentTool() {
    override val name = MemoryTools.TOOL_CORE_REPLACE
    override val description =
        "修改核心记忆：用 old_content 精确定位块内一段原文（须唯一出现）替换为 new_content；块满或内容过时（如改了地址）时用它。"
    override val parametersJson =
        """{"type":"object","properties":{"block":{"type":"string","enum":["human","persona"],"description":"目标记忆块"},"old_content":{"type":"string","description":"块内已有的原文片段，须精确且唯一"},"new_content":{"type":"string","description":"替换后的新内容"}},"required":["block","old_content","new_content"]}"""

    override suspend fun execute(argsJson: String): String {
        val obj = memParse(argsJson) ?: return memErr("参数格式非法")
        val block = memStr(obj, "block") ?: return memErr("缺少 block")
        val oldContent = memStr(obj, "old_content") ?: return memErr("缺少 old_content")
        val newContent = memStr(obj, "new_content") ?: return memErr("缺少 new_content")
        val limit = coreLimitOf(block) ?: return memErr("block 须为 human 或 persona")
        gateReject(newContent, gearProvider)?.let { return it }
        val cur = store.getCoreBlock(block)?.content ?: ""
        val hits = countOccurrences(cur, oldContent)
        if (hits == 0) return memErr("old_content 未找到")
        if (hits > 1) return memErr("old_content 匹配到 $hits 处，请提供更长的片段")
        val next = cur.replaceFirst(oldContent, newContent)
        if (next.length > limit) return coreFullErr(block, next.length, limit)
        store.putCoreBlock(block, next)
        return memEnc.encodeToString(CoreReplaceResult.serializer(), CoreReplaceResult(true, block, next.length, limit))
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = haystack.indexOf(needle, idx + needle.length)
        }
        return count
    }
}

// ===== 3.3 archival_memory_insert：一句话独立事实归档；type 默认 fact =====
internal class ArchivalMemoryInsertTool(
    private val store: MemoryStore,
    private val gearProvider: suspend () -> String,
) : AgentTool() {
    override val name = MemoryTools.TOOL_ARCHIVAL_INSERT
    override val description =
        "把一条一次性情节或背景事实归档备查（如今天聊过的项目进展）；不适合长期档案，长期信息请用 core_memory_append。"
    override val parametersJson =
        """{"type":"object","properties":{"content":{"type":"string","description":"一句独立、可脱离上下文理解的事实"},"type":{"type":"string","enum":["fact","episode","preference","identity"],"description":"内容类型，默认 fact"}},"required":["content"]}"""

    override suspend fun execute(argsJson: String): String {
        val obj = memParse(argsJson) ?: return memErr("参数格式非法")
        val content = memStr(obj, "content") ?: return memErr("缺少 content")
        val type = memStr(obj, "type") ?: "fact"
        if (type !in MemoryTools.ARCHIVAL_TYPES) {
            return memErr("type 须为 fact|episode|preference|identity")
        }
        gateReject(content, gearProvider)?.let { return it }
        val id = store.insertArchival(content, type, "agent")
        return memEnc.encodeToString(ArchivalInsertResult.serializer(), ArchivalInsertResult(true, id))
    }
}

// ===== 3.4 archival_memory_search：BM25×新近度（Bm25 已封装），top_k 默认 5 上限 20 =====
internal class ArchivalMemorySearchTool(private val store: MemoryStore) : AgentTool() {
    override val name = MemoryTools.TOOL_ARCHIVAL_SEARCH
    override val description = "按关键词/短句检索已归档的记忆，返回最相关的若干条；需要旧情节、背景资料时用。"
    override val parametersJson =
        """{"type":"object","properties":{"query":{"type":"string","description":"关键词或短句"},"top_k":{"type":"integer","description":"返回条数，默认5，最大20"}},"required":["query"]}"""

    override suspend fun execute(argsJson: String): String {
        val obj = memParse(argsJson) ?: return memErr("参数格式非法")
        val query = memStr(obj, "query") ?: ""
        val topK = (memOptInt(obj, "top_k") ?: MemoryTools.ARCHIVAL_TOP_K)
            .coerceIn(1, MemoryTools.ARCHIVAL_TOP_K_MAX)
        val corpus = store.allArchival().map { Bm25.Doc(it.id, it.content, it.createdAt) }
        val hits = Bm25.search(corpus, query, topK, System.currentTimeMillis())
        // §3.4 边界：命中条目 hit_count 各 +1，随检索同批写回；写失败不阻塞返回
        if (hits.isNotEmpty()) {
            runCatching { store.incrementHitCounts(hits.map { it.id }) }
        }
        val rows = store.archivalByIds(hits.map { it.id }).associateBy { it.id }
        val results = hits.mapNotNull { hit ->
            rows[hit.id]?.let {
                ArchivalSearchHit(it.id, it.content, it.type, it.createdAt, hit.score)
            }
        }
        return memEnc.encodeToString(ArchivalSearchResult.serializer(), ArchivalSearchResult(results, results.size))
    }
}

// ===== 3.5 conversation_search：user/assistant 原文多词 OR LIKE，created_at 倒序 =====
internal class ConversationSearchTool(private val store: MemoryStore) : AgentTool() {
    override val name = MemoryTools.TOOL_CONVERSATION_SEARCH
    override val description =
        "在过往对话原文中搜关键词（只搜你和用户说过的话，不含工具结果）；用户提起「之前/上次聊过」时用它找回上下文。"
    override val parametersJson =
        """{"type":"object","properties":{"query":{"type":"string","description":"一个或多个关键词（空格分隔，任一命中即返回）"},"limit":{"type":"integer","description":"返回条数，默认10，最大50"}},"required":["query"]}"""

    override suspend fun execute(argsJson: String): String {
        val obj = memParse(argsJson) ?: return memErr("参数格式非法")
        val query = memStr(obj, "query") ?: ""
        val limit = (memOptInt(obj, "limit") ?: MemoryTools.CONVERSATION_SEARCH_LIMIT)
            .coerceIn(1, MemoryTools.CONVERSATION_SEARCH_LIMIT_MAX)
        val hits = store.conversationSearch(query, limit)
        val results = hits.map {
            ConversationSearchHit(it.conversationId, it.turnNo, it.role, it.content, it.createdAt)
        }
        return memEnc.encodeToString(ConversationSearchResult.serializer(), ConversationSearchResult(results, results.size))
    }
}

/** 五工具工厂：gearProvider 每次写入时取隐私挡位 wire 字符串（生产传 ConfigStore 读取，测试传固定值）。 */
fun createMemoryTools(store: MemoryStore, gearProvider: suspend () -> String): List<AgentTool> = listOf(
    CoreMemoryAppendTool(store, gearProvider),
    CoreMemoryReplaceTool(store, gearProvider),
    ArchivalMemoryInsertTool(store, gearProvider),
    ArchivalMemorySearchTool(store),
    ConversationSearchTool(store),
)
