package com.zhuolin.yunkai

// 一期图片链路：请求体多模态格式单测（ContentParts → OpenAI content 数组）
// 这是端到端最容易静默失败的一环：数组形态不对 → 模型报 400 或忽略图片
import com.zhuolin.yunkai.model.ChatMsg
import com.zhuolin.yunkai.model.ContentImage
import com.zhuolin.yunkai.model.ContentPart
import com.zhuolin.yunkai.service.LlmClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultimodalBodyTest {

    @Test
    fun imagePartsSerializeAsContentArray() {
        val parts = listOf(
            ContentPart(type = "text", text = "这张图里有什么"),
            ContentPart(type = "image_url", imageUrl = ContentImage("data:image/jpeg;base64,AAAA")),
        )
        val msg = ChatMsg(role = "user", content = "这张图里有什么", contentParts = parts)
        val body = LlmClient.buildRequestBody("glm-5.3-flash", listOf(msg), null)

        val obj = Json.parseToJsonElement(body).jsonObject
        val content = obj["messages"]!!.jsonArray[0].jsonObject["content"]!!
        assertTrue("content 应为数组", content is JsonArray)
        val arr = content.jsonArray
        assertEquals(2, arr.size)
        assertEquals("text", arr[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("这张图里有什么", arr[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image_url", arr[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(
            "image url 应为 data:base64",
            arr[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
                .startsWith("data:image/jpeg;base64,"),
        )
        // 内部字段不得泄漏到请求体
        assertTrue("content_parts 不应出现在请求体", !body.contains("content_parts"))
    }

    @Test
    fun plainMessageStaysStringContent() {
        val msg = ChatMsg(role = "user", content = "你好")
        val body = LlmClient.buildRequestBody("glm-5.3-flash", listOf(msg), null)
        val obj = Json.parseToJsonElement(body).jsonObject
        val content = obj["messages"]!!.jsonArray[0].jsonObject["content"]!!
        assertTrue("纯文本 content 应保持字符串", content is JsonPrimitive)
        assertEquals("你好", content.jsonPrimitive.content)
    }

    @Test
    fun toolMessageKeepsToolCallId() {
        val msg = ChatMsg(role = "tool", content = "{}", toolCallId = "call_1")
        val body = LlmClient.buildRequestBody("glm-5.3-flash", listOf(msg), null)
        assertTrue(body.contains("tool_call_id"))
        assertTrue(body.contains("call_1"))
    }
}
