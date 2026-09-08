package com.zhuolin.yunkai.store

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zhuolin.yunkai.model.AppConfig
import kotlinx.coroutines.flow.first

// 配置存取：DataStore preferences 'yunkai_cfg'，键与 AppConfig 字段同名。
// searchMode 是鸿蒙版保留字段（UI 三选已下线），Android 全新安装无存量 schema 负担，
// 不落盘、load 恒回默认 'auto'，语义与鸿蒙版键缺失路径一致。
private val Context.dataStore by preferencesDataStore(name = "yunkai_cfg")

class ConfigStore(private val ctx: Context) {
    suspend fun load(): AppConfig {
        val p = ctx.dataStore.data.first()
        return AppConfig(
            baseUrl = p[K_BASE_URL] ?: "",
            apiKey = p[K_API_KEY] ?: "",
            model = p[K_MODEL] ?: "",
            longModel = p[K_LONG_MODEL] ?: "glm-4.7",
            autoRoute = p[K_AUTO_ROUTE] ?: true,
            searchProvider = p[K_SEARCH_PROVIDER] ?: "bing",
            searchApiKey = p[K_SEARCH_KEY] ?: "",
        )
    }

    suspend fun save(cfg: AppConfig) {
        ctx.dataStore.edit { p ->
            p[K_BASE_URL] = cfg.baseUrl
            p[K_API_KEY] = cfg.apiKey
            p[K_MODEL] = cfg.model
            p[K_LONG_MODEL] = cfg.longModel
            p[K_AUTO_ROUTE] = cfg.autoRoute
            p[K_SEARCH_PROVIDER] = cfg.searchProvider
            p[K_SEARCH_KEY] = cfg.searchApiKey
        }
    }

    companion object {
        private val K_BASE_URL = stringPreferencesKey("baseUrl")
        private val K_API_KEY = stringPreferencesKey("apiKey")
        private val K_MODEL = stringPreferencesKey("model")
        private val K_LONG_MODEL = stringPreferencesKey("longModel")
        private val K_AUTO_ROUTE = booleanPreferencesKey("autoRoute")
        private val K_SEARCH_PROVIDER = stringPreferencesKey("searchProvider")
        private val K_SEARCH_KEY = stringPreferencesKey("searchApiKey")
    }
}
