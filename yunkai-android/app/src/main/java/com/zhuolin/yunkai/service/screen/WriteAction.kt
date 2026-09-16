package com.zhuolin.yunkai.service.screen

// 写操作动作模型（M2a）：agent 计划的最小执行单元。
// 坐标为屏幕系像素（与 read_screen 快照同口径）；文本动作 Input 上限 200 字。
// fromJson 非法输入返回 null（不抛异常，调用方收敛为 {"error":...}）。
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed class WriteAction {
    data class Tap(val x: Int, val y: Int) : WriteAction()
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durMs: Long = 400) : WriteAction()
    data class Input(val text: String) : WriteAction()      // 输入到「当前聚焦的输入框」
    data class OpenApp(val pkg: String) : WriteAction()     // 拉起目标应用（计划卡回前台后的第一步）
    object Back : WriteAction()
    object Home : WriteAction()
    object Finished : WriteAction()                          // agent 显式声明任务完成

    companion object {
        const val MAX_TEXT = 200
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun fromJson(raw: String): WriteAction? = try {
            val obj = json.parseToJsonElement(raw) as? JsonObject
            obj?.let { fromObj(it) }
        } catch (e: Exception) {
            null
        }

        // 字段缺失/类型不符/未知 type 一律返回 null。
        // 取值局部函数返回可空，判空在 fromObj 体内用 `?: return null` 做
        // （局部函数里的 return 只作用于局部函数自身，不能替他向外返回）。
        fun fromObj(obj: JsonObject): WriteAction? {
            val type = (obj["type"] as? JsonPrimitive)?.content ?: return null
            fun i(k: String): Int? = (obj[k] as? JsonPrimitive)?.content?.toIntOrNull()
            fun l(k: String): Long? = (obj[k] as? JsonPrimitive)?.content?.toLongOrNull()
            fun s(k: String): String = (obj[k] as? JsonPrimitive)?.content ?: ""
            if (type == "tap") {
                val x = i("x") ?: return null
                val y = i("y") ?: return null
                return Tap(x, y)
            }
            if (type == "swipe") {
                val x1 = i("x1") ?: return null
                val y1 = i("y1") ?: return null
                val x2 = i("x2") ?: return null
                val y2 = i("y2") ?: return null
                return Swipe(x1, y1, x2, y2, (l("durMs") ?: 400L).coerceIn(200, 10000))
            }
            if (type == "input") {
                val t = s("text").take(MAX_TEXT)
                return if (t.isEmpty()) null else Input(t)
            }
            if (type == "open_app") {
                val pkg = s("pkg").trim()
                return if (pkg.isEmpty()) null else OpenApp(pkg)
            }
            return when (type) {
                "back" -> Back
                "home" -> Home
                "finished" -> Finished
                else -> null
            }
        }
    }
}

// 校验：坐标在屏幕界内、文本非空。boundW/boundH 来自调用方的 displayMetrics。
fun WriteAction.validate(boundW: Int, boundH: Int): String? = when (this) {
    is WriteAction.Tap -> if (x in 0..boundW && y in 0..boundH) null else "tap 坐标越界 ($x,$y)"
    is WriteAction.Swipe -> if (x1 in 0..boundW && y1 in 0..boundH && x2 in 0..boundW && y2 in 0..boundH) null else "swipe 坐标越界"
    is WriteAction.Input -> if (text.isNotEmpty()) null else "input 文本为空"
    is WriteAction.OpenApp -> if (pkg.isNotEmpty()) null else "open_app 缺少包名"
    else -> null
}
