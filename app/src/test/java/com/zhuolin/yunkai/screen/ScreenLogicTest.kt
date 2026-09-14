package com.zhuolin.yunkai.screen

import com.zhuolin.yunkai.service.screen.ScreenBlacklist
import com.zhuolin.yunkai.service.screen.ScreenFormat
import com.zhuolin.yunkai.service.screen.ScreenGuard
import com.zhuolin.yunkai.service.screen.ScreenNode
import com.zhuolin.yunkai.service.screen.ScreenRouteTable
import com.zhuolin.yunkai.service.screen.notifySummary
import com.zhuolin.yunkai.service.screen.parseOpenAppArg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 屏幕感知纯逻辑单测（M1 门1）：路线表 / 黑名单 / 快照格式化 / 敏感词 / 通知摘要 / 参数解析
class ScreenLogicTest {

    // ── 路线表 ──
    @Test fun route_wechatDirectVision() {
        assertTrue(ScreenRouteTable.isVisionRoute("com.tencent.mm", emptySet()))
    }

    @Test fun route_settingsWalksNodeTree() {
        assertFalse(ScreenRouteTable.isVisionRoute("com.android.settings", emptySet()))
    }

    @Test fun route_learnedPkgTakesVision() {
        assertTrue(ScreenRouteTable.isVisionRoute("com.some.custom", setOf("com.some.custom")))
    }

    @Test fun route_emptyPkgNeverVision() {
        assertFalse(ScreenRouteTable.isVisionRoute("", emptySet()))
    }

    // ── 黑名单 ──
    @Test fun black_alipayBlockedByDefault() {
        assertTrue(ScreenBlacklist.isBlocked("com.eg.android.AlipayGphone", emptySet()))
    }

    @Test fun black_userAddedBlocked() {
        assertTrue(ScreenBlacklist.isBlocked("com.foo.bank", setOf("com.foo.bank")))
    }

    @Test fun black_normalAppPasses() {
        assertFalse(ScreenBlacklist.isBlocked("com.android.settings", emptySet()))
    }

    @Test fun black_emptyPkgPasses() {
        assertFalse(ScreenBlacklist.isBlocked("", emptySet()))
    }

    // ── 快照格式化 ──
    private val nodes = listOf(
        ScreenNode("设置", "TextView", 540, 120, false, false),
        ScreenNode("", "Switch", 990, 300, true, false),
        ScreenNode("secret", "EditText", 540, 420, true, true),
    )

    @Test fun fmt_containsTextAndBounds() {
        val s = ScreenFormat.format("com.android.settings", nodes)
        assertTrue(s.contains("当前应用: com.android.settings"))
        assertTrue(s.contains("\"设置\" @(540,120)"))
        assertTrue(s.contains("可点击"))
    }

    @Test fun fmt_passwordMarkedNoValue() {
        val s = ScreenFormat.format("pkg", nodes)
        assertTrue(s.contains("[密码框]"))
        assertFalse(s.contains("secret"))
    }

    @Test fun fmt_textNodeCountReported() {
        val s = ScreenFormat.format("pkg", nodes)
        assertTrue(s.contains("可读文本节点 2 个"))
    }

    // ── 敏感页关键词 ──
    @Test fun guard_hitsSensitive() {
        assertTrue(ScreenGuard.hasSensitive("确认转账 100 元"))
        assertTrue(ScreenGuard.hasSensitive("请输入验证码"))
    }

    @Test fun guard_passesNormal() {
        assertFalse(ScreenGuard.hasSensitive("今天的天气很好"))
    }

    // ── 通知摘要 ──
    @Test fun summary_takesFirstNonBlankLine() {
        assertEquals("微信 xx 群：3 条正事", notifySummary("\n微信 xx 群：3 条正事\n详情如下"))
    }

    @Test fun summary_truncatesLongLine() {
        val long = "很长的回答".repeat(50)
        val s = notifySummary(long, max = 60)
        assertEquals(61, s.length)
        assertTrue(s.endsWith("…"))
    }

    // ── open_app 参数解析 ──
    @Test fun arg_pkgWins() {
        assertEquals("com.tencent.mm", parseOpenAppArg("""{"pkg":"com.tencent.mm","name":"微信"}"""))
    }

    @Test fun arg_nameFallbackAndInvalid() {
        assertEquals("微信", parseOpenAppArg("""{"name":"微信"}"""))
        assertEquals("", parseOpenAppArg("not-json"))
        assertEquals("", parseOpenAppArg("""{}"""))
    }
}
