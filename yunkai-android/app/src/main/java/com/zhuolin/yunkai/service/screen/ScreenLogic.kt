package com.zhuolin.yunkai.service.screen

// 屏幕感知纯逻辑层：节点快照格式化 / 视觉路线表 / 隐私黑名单 / 敏感页关键词 / 通知摘要。
// 不 import android.*——全部 JVM 可测（ScreenLogicTest 直测本文件）。
// 设计依据：project/yunkai/design-explorations/doubao-agent-brief.md §二（路线调度）、§五（隐私）。

// 节点快照行：无障碍树 DFS 采出的最小字段集。坐标=屏幕系中心点（写操作 tap 的锚定口径）。
data class ScreenNode(
    val text: String,
    val cls: String,
    val cx: Int,
    val cy: Int,
    val clickable: Boolean,
    val isPassword: Boolean,
)

object ScreenFormat {
    // 节点树贫瘠阈值：可读文本节点少于此数 → 视为自绘界面，提示转视觉路线并运行时学习
    const val MIN_TEXT_NODES = 5

    // 快照 → 控件清单文本（模型可读 + 坐标可锚定）。密码框只出标记不出值。
    fun format(pkg: String, nodes: List<ScreenNode>): String {
        val sb = StringBuilder()
        sb.append("当前应用: ").append(pkg)
        sb.append("（可读文本节点 ").append(nodes.count { it.text.isNotEmpty() }).append(" 个）")
        sb.append('\n')
        var i = 0
        for (n in nodes) {
            i++
            val t = if (n.isPassword) "[密码框]" else "\"" + n.text + "\""
            sb.append('[').append(i).append("] ")
            if (n.cls.isNotEmpty()) sb.append(n.cls).append(' ')
            sb.append(t).append(" @(").append(n.cx).append(',').append(n.cy).append(')')
            if (n.clickable) sb.append(" 可点击")
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }
}

object ScreenRouteTable {
    // 已知自绘引擎 app（内置白名单）：节点树贫瘠，直接视觉路线，不浪费节点树调用。
    // 设计简报 §二：常见 app 提前规划。
    val VISION_DEFAULT = setOf(
        "com.tencent.mm",           // 微信
        "com.tencent.mobileqq",     // QQ
        "com.ss.android.ugc.aweme", // 抖音
        "com.smile.gifmaker",       // 快手
        "com.xingin.xhs",           // 小红书
        "com.sina.weibo",           // 微博
        "tv.danmaku.bili",          // 哔哩哔哩
        "com.taobao.taobao",        // 淘宝
        "com.jingdong.app.mall",    // 京东
        "com.xunmeng.pinduoduo",    // 拼多多
    )

    // 运行时学习集（ConfigStore 持久化）：read_screen 贫瘠的应用自动补入，下次直走视觉
    fun isVisionRoute(pkg: String, learned: Set<String>): Boolean =
        pkg.isNotEmpty() && (pkg in VISION_DEFAULT || pkg in learned)
}

object ScreenBlacklist {
    // 内置银行/支付类：命中即拒绝读屏/截图（Q6 默认不信，显式放行制）。
    // 用户增补集走 ConfigStore（设置页管理 M2 接入，本期能力先生效）。
    val DEFAULT = setOf(
        "com.eg.android.AlipayGphone",        // 支付宝
        "com.unionpay",                       // 云闪付
        "com.icbc",                           // 工商银行
        "com.chinamworld.main",               // 建设银行
        "com.chinamworld.bocmbci",            // 中国银行
        "com.android.bankabc",                // 农业银行
        "com.cmbchina.ccd.pluto.cmbActivity", // 招商银行
        "com.bankcomm.BankComm",              // 交通银行
    )

    fun isBlocked(pkg: String, userAdded: Set<String>): Boolean =
        pkg.isNotEmpty() && (pkg in DEFAULT || pkg in userAdded)
}

object ScreenGuard {
    // 敏感页关键词（M1 定义+单测；M2 写操作急停据此判定）：节点树文本命中任一即视为敏感页。
    // 敏感页语义（设计简报 §四）：agent 只有「看」的权利，没有「动」的权利。
    val SENSITIVE_KEYWORDS = listOf("支付", "付款", "转账", "收款", "银行卡", "验证码", "密码")

    fun hasSensitive(text: String): Boolean {
        for (k in SENSITIVE_KEYWORDS) if (text.contains(k)) return true
        return false
    }
}

// Q7 结果通知正文口径：取回答首个非空行，超长截断加省略号
fun notifySummary(answer: String, max: Int = 60): String {
    val first = answer.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
    val t = first.trim()
    return if (t.length <= max) t else t.take(max) + "…"
}

// open_app 参数解析：优先 pkg，其次 name（工具层拿 name 做模糊匹配）。非法 JSON 返回空串。
fun parseOpenAppArg(argsJson: String): String {
    return try {
        val obj = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
            .parseToJsonElement(argsJson) as? kotlinx.serialization.json.JsonObject
        fun s(k: String): String {
            val v = obj?.get(k) as? kotlinx.serialization.json.JsonPrimitive ?: return ""
            return if (v.isString) v.content else ""
        }
        s("pkg").ifEmpty { s("name") }.trim()
    } catch (e: Exception) {
        ""
    }
}
