package com.zhuolin.yunkai

import com.zhuolin.yunkai.model.AppConfig
import com.zhuolin.yunkai.model.ChatMsg
import com.zhuolin.yunkai.service.LlmClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

// 门1 真 LLM 主机直连冒烟（quizlens 先例模式）：设了 DEEPSEEK_API_KEY 才跑，未设自动跳过。
// 验证 OpenAI 兼容端点 + LlmClient 请求体/解析在真实端点上可用（门2 由模拟器 app 内走真链路）。
class DeepSeekChainTest {
    @Test
    fun `llm_chain 主机直连冒烟 无key自动跳过`() {
        val key = System.getenv("DEEPSEEK_API_KEY")
        if (key.isNullOrEmpty()) {
            println("LLM_CHAIN_SKIPPED: DEEPSEEK_API_KEY not set")
            return
        }
        val cfg = AppConfig(
            baseUrl = "https://api.deepseek.com/v1",
            apiKey = key,
            model = "deepseek-chat",
        )
        val reply = runBlocking {
            LlmClient(cfg).chatMessage(
                listOf(ChatMsg(role = "user", content = "只回答两个字：可用")),
                null,
            )
        }
        println("LLM reply: ${reply.content}")
        assertTrue("LLM_CHAIN_FAIL: empty content", reply.content.isNotEmpty())
        println("LLM_CHAIN_OK")
    }
}
