package com.zhuolin.yunkai.store

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zhuolin.yunkai.model.AppConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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
            // 忆枢隐私挡位（协议 §2 PRIVACY_GEAR_DEFAULT）：默认 strict，未匹配值由
            // PrivacyGate.Gear.fromWire 在使用点回退 strict，这里原样存取（M1c 进设置 UI）
            memoryGear = p[K_MEMORY_GEAR] ?: "strict",
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
            p[K_MEMORY_GEAR] = cfg.memoryGear
        }
    }

    // 主题模式：system（跟随系统，默认）/ light / dark。与壁纸同理独立于 AppConfig，
    // 设置页写入即生效（MainActivity 订阅本 Flow 重新决定明暗，不必过「保存」）
    val themeModeFlow: Flow<String> = ctx.dataStore.data.map { it[K_THEME_MODE] ?: THEME_SYSTEM }

    suspend fun getThemeMode(): String = ctx.dataStore.data.first()[K_THEME_MODE] ?: THEME_SYSTEM

    suspend fun setThemeMode(mode: String) {
        ctx.dataStore.edit { p -> p[K_THEME_MODE] = mode }
    }

    // 自定义壁纸（file 绝对路径），空串 = 默认壁纸。独立于 AppConfig，与鸿蒙版 ConfigStore.getWallpaper 同语义。
    val wallpaperFlow: Flow<String> = ctx.dataStore.data.map { it[K_WALLPAPER] ?: "" }

    suspend fun getWallpaper(): String = ctx.dataStore.data.first()[K_WALLPAPER] ?: ""

    suspend fun setWallpaper(path: String) {
        ctx.dataStore.edit { p ->
            if (path.isEmpty()) p.remove(K_WALLPAPER) else p[K_WALLPAPER] = path
        }
    }

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        private val K_BASE_URL = stringPreferencesKey("baseUrl")
        private val K_API_KEY = stringPreferencesKey("apiKey")
        private val K_MODEL = stringPreferencesKey("model")
        private val K_LONG_MODEL = stringPreferencesKey("longModel")
        private val K_AUTO_ROUTE = booleanPreferencesKey("autoRoute")
        private val K_SEARCH_PROVIDER = stringPreferencesKey("searchProvider")
        private val K_SEARCH_KEY = stringPreferencesKey("searchApiKey")
        private val K_WALLPAPER = stringPreferencesKey("wallpaper")
        private val K_THEME_MODE = stringPreferencesKey("themeMode")
        private val K_MEMORY_GEAR = stringPreferencesKey("memoryGear")
    }
}
