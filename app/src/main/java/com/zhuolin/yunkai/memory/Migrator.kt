package com.zhuolin.yunkai.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 旧数据迁移（忆枢协议 §1.5，一次性，启动时）。
 *
 * 合法 JSON 对象：每个 KV 迁为一行，content = `<key>：<value>`（全角冒号连接）；
 * value 非字符串时以紧凑 JSON（分隔符 `,` `:` 无空格）字符串化；
 * value 为字符串时取原文（保留 \n 等已解码字符）。
 * 损坏 / 非对象 JSON → 视为空库（零迁移行）。键序按原 JSON 出现顺序保留。
 */
object Migrator {
    const val TYPE_FACT = "fact"
    const val SOURCE_LEGACY_M2 = "legacy-m2"

    data class LegacyRow(val content: String, val type: String, val source: String)

    private val json = Json { ignoreUnknownKeys = true }

    fun migrate(jsonText: String): List<LegacyRow> {
        val root = try {
            json.parseToJsonElement(jsonText)
        } catch (_: Exception) {
            return emptyList()
        }
        if (root !is JsonObject) return emptyList()
        return root.map { (key, value) ->
            LegacyRow("$key：${stringify(value)}", TYPE_FACT, SOURCE_LEGACY_M2)
        }
    }

    // JsonNull 是 JsonPrimitive 子类，content 即 "null"，无需单列分支
    private fun stringify(value: JsonElement): String = when (value) {
        is JsonPrimitive -> value.content   // 字符串取解码后原文；数字/布尔/null 取字面量
        is JsonObject, is JsonArray -> compact(value)
    }

    /** 紧凑序列化：kotlinx 默认即 `,` `:` 无空格、不转义非 ASCII、保留键序。 */
    private fun compact(element: JsonElement): String =
        Json.encodeToString(JsonElement.serializer(), element)
}
