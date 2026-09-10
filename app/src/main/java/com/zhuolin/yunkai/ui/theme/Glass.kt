package com.zhuolin.yunkai.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// 方向 A 通透系玻璃色板：1:1 移植自鸿蒙端 entry/src/main/resources/{base,dark}/element/color.json。
// mask_* 是壁纸之上的遮罩色（带 alpha），paperBase 是其不透明基色，供需要实底的场景用。
@Immutable
data class GlassScheme(
    val isDark: Boolean,
    val glassBg: Color,
    val glassBgStrong: Color,
    val glassBorder: Color,
    val textHi: Color,
    val textMid: Color,
    val textLow: Color,
    val accent: Color,
    val accentSoft: Color,
    val bubbleUser: Color,
    val paperBase: Color,
    val maskVTop: Color,
    val maskV35: Color,
    val maskV50: Color,
    val maskV70: Color,
    val maskVBot: Color,
    val maskHEdge: Color,
)

val LightGlass = GlassScheme(
    isDark = false,
    glassBg = Color(0x8CFFFFFF),
    glassBgStrong = Color(0xADFFFFFF),
    glassBorder = Color(0xE6FFFFFF),
    textHi = Color(0xFF16181D),
    textMid = Color(0x9E16181D),
    textLow = Color(0x5C16181D),
    accent = Color(0xFF0071E3),
    accentSoft = Color(0x1F0071E3),
    bubbleUser = Color(0x290071E3),
    paperBase = Color(0xFFF9F8F5),
    maskVTop = Color(0x8CF9F8F5),
    maskV35 = Color(0x1FF9F8F5),
    maskV50 = Color(0x0FF9F8F5),
    maskV70 = Color(0x33F9F8F5),
    maskVBot = Color(0x99F9F8F5),
    maskHEdge = Color(0x4DF9F8F5),
)

val DarkGlass = GlassScheme(
    isDark = true,
    glassBg = Color(0x19FFFFFF),
    glassBgStrong = Color(0x24FFFFFF),
    glassBorder = Color(0x47FFFFFF),
    textHi = Color(0xFFF4F4F6),
    textMid = Color(0x9EF4F4F6),
    textLow = Color(0x61F4F4F6),
    accent = Color(0xFF0A84FF),
    accentSoft = Color(0x380A84FF),
    bubbleUser = Color(0x4D0A84FF),
    paperBase = Color(0xFF1A1820),
    maskVTop = Color(0x991A1820),
    maskV35 = Color(0x1A1A1820),
    maskV50 = Color(0x0D1A1820),
    maskV70 = Color(0x261A1820),
    maskVBot = Color(0xB31A1820),
    maskHEdge = Color(0x4D1A1820),
)

val LocalGlassScheme = compositionLocalOf { LightGlass }

// 材质/尺寸常量，对齐鸿蒙 ThemeTokens（Blur 单位 px→Compose 用 dp 近似，见 glassCard 修饰符）
object GlassTokens {
    const val BLUR_CARD_DP = 24
    const val BLUR_DOCK_DP = 28
    const val R_CARD = 20
    const val R_BUBBLE = 22
    const val R_TIGHT = 4
    const val BORDER_W = 0.5f
    const val RISE_MS = 700
}
