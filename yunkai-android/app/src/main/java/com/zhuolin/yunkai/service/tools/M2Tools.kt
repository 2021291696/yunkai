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
data class M2WriteResult(val written: Boolean, val path: String)

private fun m2Parse(argsJson: String): JsonObject? = m2Json.parseToJsonElement(argsJson) as? JsonObject

private fun m2Str(obj: JsonObject, key: String): String? = (obj[key] as? JsonPrimitive)?.let { if (it.isString) it.content else null }

private fun m2Err(msg: String): String = m2Enc.encodeToString(M2Error.serializer(), M2Error(msg))

// 旧 KV 记忆工具（memory_save/memory_search + 文件版 MemoryStore）已随忆枢 M1b 下线：
// 五工具替代（memory/MemoryTools.kt），旧数据由启动钩子经 Migrator 迁入 archival（协议 §1.5）。

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
    return listOf(ReadFileTool(root), WriteFileTool(root))
}
