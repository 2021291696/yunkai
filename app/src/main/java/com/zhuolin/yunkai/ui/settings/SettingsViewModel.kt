package com.zhuolin.yunkai.ui.settings

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhuolin.yunkai.YunkaiApp
import com.zhuolin.yunkai.model.AppConfig
import com.zhuolin.yunkai.service.LlmClient
import kotlinx.coroutines.launch

// 设置页状态：OpenAI 兼容三项配置 + 获取模型列表 + 技能自动路由开关 + 独立搜索配置。
// longModel 不进 UI（与鸿蒙 M1 一致，默认值 glm-4.7）
class SettingsViewModel(private val app: YunkaiApp) : ViewModel() {
    var baseUrl: MutableState<String> = mutableStateOf("")
    var apiKey: MutableState<String> = mutableStateOf("")
    var model: MutableState<String> = mutableStateOf("")
    var autoRoute: MutableState<Boolean> = mutableStateOf(true)
    var searchProvider: MutableState<String> = mutableStateOf("bing")
    var searchApiKey: MutableState<String> = mutableStateOf("")
    var modelOptions: MutableState<List<String>> = mutableStateOf(emptyList())
    var fetchingModels: MutableState<Boolean> = mutableStateOf(false)

    init {
        viewModelScope.launch {
            val cfg = app.configStore.load()
            baseUrl.value = cfg.baseUrl
            apiKey.value = cfg.apiKey
            model.value = cfg.model
            autoRoute.value = cfg.autoRoute
            searchProvider.value = cfg.searchProvider
            searchApiKey.value = cfg.searchApiKey
        }
    }

    private fun collect(): AppConfig {
        val c = AppConfig()
        c.baseUrl = baseUrl.value.trim()
        c.apiKey = apiKey.value.trim()
        c.model = model.value.trim()
        c.autoRoute = autoRoute.value
        c.searchProvider = searchProvider.value
        c.searchApiKey = searchApiKey.value.trim()
        return c
    }

    // 获取模型列表：先按当前表单值构造 LlmClient（未保存配置也可拉取）；err 非 null 为失败文案
    fun fetchModels(onResult: (count: Int, err: String?) -> Unit) {
        val cfg = collect()
        if (cfg.baseUrl.isEmpty() || cfg.apiKey.isEmpty()) {
            onResult(0, "请先填写 API 地址和密钥")
            return
        }
        fetchingModels.value = true
        viewModelScope.launch {
            try {
                val ids = LlmClient(cfg).listModels()
                if (ids.isEmpty()) {
                    onResult(0, "模型列表为空")
                } else {
                    modelOptions.value = ids
                    onResult(ids.size, null)
                }
            } catch (e: Exception) {
                onResult(0, "获取失败：${e.message}")
            } finally {
                fetchingModels.value = false
            }
        }
    }

    fun save(onDone: (err: String?) -> Unit) {
        val cfg = collect()
        if (cfg.baseUrl.isEmpty() || cfg.apiKey.isEmpty() || cfg.model.isEmpty()) {
            onDone("API 地址、密钥、模型名都不能为空")
            return
        }
        viewModelScope.launch {
            app.configStore.save(cfg)
            onDone(null)
        }
    }
}
