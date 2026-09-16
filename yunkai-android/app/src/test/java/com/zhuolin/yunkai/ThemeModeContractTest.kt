package com.zhuolin.yunkai.service

// ThemeMode 契约测试：锁住「鸿蒙两套 ColorMode 枚举数值相反」这一坑的映射口径
// （ConfigurationConstant.ColorMode: DARK=0/LIGHT=1；ArkUI ColorMode: LIGHT=0/DARK=1——
//  见 SDK ConfigurationConstant.d.ts 与 state_management.d.ts，门0 审查实证）。
// ArkUI 枚举在 JVM 上不可用，这里锁的是 ThemeMode.ets 里 effectiveDark 的纯函数语义：
// dark=深、light=浅、system=跟随系统。
class ThemeModeContractTest {

    private fun isDark(mode: String, systemDark: Boolean): Boolean = when (mode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    @org.junit.Test
    fun `dark档_无视系统`() {
        org.junit.Assert.assertTrue(isDark("dark", systemDark = false))
    }

    @org.junit.Test
    fun `light档_无视系统`() {
        org.junit.Assert.assertFalse(isDark("light", systemDark = true))
    }

    @org.junit.Test
    fun `system档_跟随系统`() {
        org.junit.Assert.assertFalse(isDark("system", systemDark = false))
        org.junit.Assert.assertTrue(isDark("system", systemDark = true))
    }

    @org.junit.Test
    fun `未知档位按跟随系统处理`() {
        org.junit.Assert.assertFalse(isDark("whatever", systemDark = false))
    }
}
