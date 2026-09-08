package com.zhuolin.yunkai.service

import com.zhuolin.yunkai.model.AppConfig
import com.zhuolin.yunkai.model.NetMode

// 搜索路由（备胎轨道，鸿蒙版现役但未接、M2 裁决；1:1 移植保 43 用例全覆盖）：
// builtin 强制内置；external 走外部；auto 必应外部优先，不可用时智谱系回退内置
// （实测 glm-4.7 不执行 web_search 工具，glm-4.5 可用——内置联网只在用户明确选择时用）
// 外部轨道可用性 = 有搜索 key，或 provider=bing（爬取免 key）
object SearchRouter {
    private fun externalAvailable(cfg: AppConfig): Boolean =
        cfg.searchApiKey.isNotEmpty() || cfg.searchProvider == "bing"

    fun resolve(cfg: AppConfig): NetMode {
        if (cfg.searchMode == "builtin") return NetMode.BUILTIN
        if (cfg.searchMode == "external") {
            return if (externalAvailable(cfg)) NetMode.EXTERNAL else NetMode.NONE
        }
        // auto：必应外部优先（免费且全模型可用）；必应不可用时智谱系回退内置
        if (externalAvailable(cfg)) return NetMode.EXTERNAL
        return if (cfg.baseUrl.contains("bigmodel.cn")) NetMode.BUILTIN else NetMode.NONE
    }
}
