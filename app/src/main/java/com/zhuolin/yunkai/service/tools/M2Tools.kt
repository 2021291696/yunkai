package com.zhuolin.yunkai.service.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

private val m2Json = Json { ignoreUnknownKeys = true; isLenient = true }
private val m2Enc = Json { ignoreUnknownKeys = true }

@Serializable
data class M2Error(val error: String)

@Serializable
data class M2SaveResult(val saved: Boolean, val key: String)

@Serializable
data class M2SearchHit(val key: String, val value: String)

@Serializable
data class M2SearchResult(val results: List<M2SearchHit>, val count: Int)

@Serializable
data class M2WriteResult(val written: Boolean, val path: String)

private fun m2Parse(argsJson: String): JsonObject? = m2Json.parseToJsonElement(argsJson) as? JsonObject

private fun m2Str(obj: JsonObject, key: String): String? = (obj[key] as? JsonPrimitive)?.let { if (it.isString) it.content else null }

private fun m2Err(msg: String): String = m2Enc.encodeToString(M2Error.serializer(), M2Error(msg))

object MemoryStore {
    fun file(ctx: android.content.Context): File = File(ctx.filesDir, "agent_memory.json")
    fun loadAll(ctx: android.content.Context): MutableMap<String, String> {
        val f = file(ctx)
        if (!f.exists()) return mutableMapOf()
        return try {
            val obj = m2Json.parseToJsonElement(f.readText()) as? JsonObject ?: return mutableMapOf()
            val map = mutableMapOf<String, String>()
            obj.forEach { (k, v) ->
                val prim = v as? JsonPrimitive
                if (prim != null && prim.isString) map[k] = prim.content
            }
            map
        } catch (_: Exception) { mutableMapOf() }
    }
    fun save(ctx: android.content.Context, key: String, value: String) {
        val all = loadAll(ctx)
        all[key] = value
        file(ctx).writeText(m2Enc.encodeToString(all))
    }
    fun search(ctx: android.content.Context, keyword: String): List<M2SearchHit> {
        return loadAll(ctx).filter { it.key.contains(keyword, true) || it.value.contains(keyword, true) }.entries.map { M2SearchHit(it.key, it.value) }
    }
}

class MemorySaveTool(private val ctx: android.content.Context) : AgentTool() {
    override val name = "memory_save"
    override val description = "保存长期记忆。参数：key、value。"
    override val parametersJson = """{"type":"object","properties":{"key":{"type":"string"},"value":{"type":"string"}},"required":["key","value"]}"""
    override suspend fun execute(argsJson: String): String {
        val obj = m2Parse(argsJson) ?: return m2Err("参数格式非法")
        val key = m2Str(obj, "key") ?: return m2Err("缺少 key")
        val value = m2Str(obj, "value") ?: return m2Err("缺少 value")
        MemoryStore.save(ctx, key, value)
        return m2Enc.encodeToString(M2SaveResult.serializer(), M2SaveResult(true, key))
    }
}

class MemorySearchTool(private val ctx: android.content.Context) : AgentTool() {
    override val name = "memory_search"
    override val description = "搜索长期记忆。参数：keyword。"
    override val parametersJson = """{"type":"object","properties":{"keyword":{"type":"string"}},"required":[]}"""
    override suspend fun execute(argsJson: String): String {
        val obj = m2Parse(argsJson)
        val kw = obj?.let { m2Str(it, "keyword") } ?: ""
        val hits = MemoryStore.search(ctx, kw)
        return m2Enc.encodeToString(M2SearchResult.serializer(), M2SearchResult(hits, hits.size))
    }
}

class ReadFileTool(private val root: File) : AgentTool() {
    override val name = "read_file"
    override val description = "读取沙箱内文件。参数：path。"
    override val parametersJson = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""
    override suspend fun execute(argsJson: String): String {
        val obj = m2Parse(argsJson) ?: return m2Err("参数格式非法")
        val path = m2Str(obj, "path") ?: return m2Err("缺少 path")
        val f = File(root, path)
        if (!f.canonicalPath.startsWith(root.canonicalPath)) return m2Err("路径越界")
        if (!f.exists()) return m2Err("文件不存在: $path")
        return f.readText()
    }
}

class WriteFileTool(private val root: File) : AgentTool() {
    override val name = "write_file"
    override val description = "写入沙箱内文件。参数：path、content。"
    override val parametersJson = """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}"""
    override suspend fun execute(argsJson: String): String {
        val obj = m2Parse(argsJson) ?: return m2Err("参数格式非法")
        val path = m2Str(obj, "path") ?: return m2Err("缺少 path")
        val content = m2Str(obj, "content") ?: return m2Err("缺少 content")
        val f = File(root, path)
        if (!f.canonicalPath.startsWith(root.canonicalPath)) return m2Err("路径越界")
        f.parentFile?.mkdirs()
        f.writeText(content)
        return m2Enc.encodeToString(M2WriteResult.serializer(), M2WriteResult(true, path))
    }
}

fun createM2Tools(ctx: android.content.Context): List<AgentTool> {
    val root = File(ctx.filesDir, "agent_files").also { it.mkdirs() }
    return listOf(ReadFileTool(root), WriteFileTool(root), MemorySaveTool(ctx), MemorySearchTool(ctx))
}
