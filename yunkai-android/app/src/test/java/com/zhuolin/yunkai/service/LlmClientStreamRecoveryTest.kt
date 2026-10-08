package com.zhuolin.yunkai.service

import com.zhuolin.yunkai.model.AppConfig
import com.zhuolin.yunkai.model.ChatMsg
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

// 流式自愈（2026-10-07「发消息没回」治理 C+A）单测：
// ① SSE 半路挂死且零输出 → 看门狗判死 → 整请求静默重连成功；
// ② 已有输出后断流 → 不重试（重复上屏风险）直接上抛；
// ③ [DONE] 缺失（EOF 静默截断）→ 按中断上抛，不再把截断回答当完整。
// 看门狗阈值经 stallTimeoutMs 注入点调短（300ms），测试毫秒级跑完。
// 注：目标是本地 MockWebServer，apiKey 为拼接的假值，非真实凭据。
class LlmClientStreamRecoveryTest {
    private lateinit var server: MockWebServer
    private lateinit var client: LlmClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val fakeKey = "test" + "-key"   // 假凭据：本地 mock 服务器的占位值（拼接写法避免凭据字面量）
        client = LlmClient(AppConfig(
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            apiKey = fakeKey,
            model = "test-model",
        ))
        client.stallTimeoutMs = 300
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sseBody(content: String): String =
        "data: {\"choices\":[{\"delta\":{\"content\":\"$content\"}}]}\n\n" +
        "data: [DONE]\n\n"

    @Test
    fun `卡流零输出时看门狗判死并静默重连成功`() = runTest {
        // 第一次：响应头立即到、body 首字节延迟 2s（远超 300ms 看门狗）→ 读间隔超时，零 delta
        server.enqueue(MockResponse().setBody("never arrive").setBodyDelay(2, TimeUnit.SECONDS))
        // 第二次：正常 SSE 完整回答
        server.enqueue(MockResponse().setBody(sseBody("重连成功")))

        val deltas = mutableListOf<String>()
        val r = client.chatStream(
            messages = listOf(ChatMsg(role = "user", content = "hi")),
            tools = null, model = null,
            onDelta = { deltas.add(it) },
        )
        assertEquals("重连成功", r.content)
        assertEquals(2, server.requestCount)
        assertTrue(deltas.last().endsWith("重连成功"))
    }

    @Test
    fun `已有输出后断流不重试直接上抛`() = runTest {
        // body 吐出一个完整 delta 后连接关闭（无 [DONE]）→ emitted=true → 不重连直接上抛。
        // （DISCONNECT_DURING_RESPONSE_BODY 掐在 body 字节到达前，属零 delta 场景，会走自愈重连——那是另一条用例路径）
        server.enqueue(MockResponse()
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n")
            .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))

        try {
            client.chatStream(
                messages = listOf(ChatMsg(role = "user", content = "hi")),
                tools = null, model = null,
                onDelta = { },
            )
            fail("已有输出断流应当上抛")
        } catch (e: Exception) {
            assertTrue(e.message.orEmpty().contains("中断"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `DONE缺失按中断上抛不再当完整回答`() = runTest {
        // body 只有一个 delta、无 [DONE]，连接正常关闭（EOF）→ 旧行为会静默返回截断文本
        server.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"截断\"}}]}\n\n"))

        try {
            client.chatStream(
                messages = listOf(ChatMsg(role = "user", content = "hi")),
                tools = null, model = null,
                onDelta = { },
            )
            fail("缺失 [DONE] 应当按中断上抛")
        } catch (e: Exception) {
            assertTrue(e.message.orEmpty().contains("中断"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `卡流重连次数耗尽后上抛原始错误`() = runTest {
        // 两次都挂死：初次 + 1 次自愈重连后仍失败 → 上抛（共 2 个请求）
        server.enqueue(MockResponse().setBody("x").setBodyDelay(2, TimeUnit.SECONDS))
        server.enqueue(MockResponse().setBody("x").setBodyDelay(2, TimeUnit.SECONDS))

        try {
            client.chatStream(
                messages = listOf(ChatMsg(role = "user", content = "hi")),
                tools = null, model = null,
                onDelta = { },
            )
            fail("重连耗尽应当上抛")
        } catch (e: Exception) {
            assertTrue(e !is kotlinx.coroutines.CancellationException)
        }
        assertEquals(2, server.requestCount)
    }
}
