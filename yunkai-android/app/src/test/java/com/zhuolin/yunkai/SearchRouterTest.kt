package com.zhuolin.yunkai

import com.zhuolin.yunkai.model.AppConfig
import com.zhuolin.yunkai.model.NetMode
import com.zhuolin.yunkai.service.SearchRouter
import org.junit.Assert.assertEquals
import org.junit.Test

// 备胎搜索轨路由（对应鸿蒙 router_* 五用例 1:1；NetMode 序号 BUILTIN=0/EXTERNAL=1/NONE=2 与鸿蒙枚举一致）
class SearchRouterTest {
    @Test
    fun `router_auto 默认 bing 外部轨`() {
        val c = AppConfig(baseUrl = "https://open.bigmodel.cn/api/paas/v4")
        assertEquals(NetMode.EXTERNAL, SearchRouter.resolve(c))
    }

    @Test
    fun `router_auto 智谱端点无key回退内置`() {
        val c = AppConfig(baseUrl = "https://open.bigmodel.cn/api/paas/v4")
        c.searchProvider = "bocha"
        assertEquals(NetMode.BUILTIN, SearchRouter.resolve(c))
    }

    @Test
    fun `router_auto 非智谱有key走外部`() {
        val c = AppConfig(baseUrl = "https://api.deepseek.com/v1")
        c.searchProvider = "bocha"
        c.searchApiKey = "sk-x"
        assertEquals(NetMode.EXTERNAL, SearchRouter.resolve(c))
    }

    @Test
    fun `router_auto 无key非智谱为 none`() {
        val c = AppConfig(baseUrl = "https://api.deepseek.com/v1")
        c.searchProvider = "bocha"
        assertEquals(NetMode.NONE, SearchRouter.resolve(c))
    }

    @Test
    fun `router bing 免key视为外部可用`() {
        val c = AppConfig(baseUrl = "https://api.deepseek.com/v1")
        c.searchProvider = "bing"
        assertEquals(NetMode.EXTERNAL, SearchRouter.resolve(c))
    }

    @Test
    fun `router builtin 强制内置 external 无key为 none`() {
        val c1 = AppConfig(baseUrl = "https://api.deepseek.com/v1", searchProvider = "bocha", searchApiKey = "sk-x")
        c1.searchMode = "builtin"
        assertEquals(NetMode.BUILTIN, SearchRouter.resolve(c1))
        val c2 = AppConfig(baseUrl = "https://api.deepseek.com/v1", searchProvider = "bocha")
        c2.searchMode = "external"
        assertEquals(NetMode.NONE, SearchRouter.resolve(c2))
    }
}
