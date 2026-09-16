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
    // 用户气泡竖向渐变 top→base（clear 两值同色=实色半透明；aurora 品牌渐变）
    val bubbleUserTop: Color,
    val bubbleUser: Color,
    val paperBase: Color,
    val maskVTop: Color,
    val maskV35: Color,
    val maskV50: Color,
    val maskV70: Color,
    val maskVBot: Color,
    val maskHEdge: Color,
    // 极光四团（仅 aurora 皮肤；clear 为空表）。坐标为屏宽/屏高比例，radius 为屏宽比例
    val glows: List<GlowSpot> = emptyList(),
    // aurora 上下可读性纱（clear 不用）
    val auroraVeilTop: Color = Color.Transparent,
    val auroraVeilBot: Color = Color.Transparent,
)

// 一团极光辉光：中心比例坐标 + 半径（屏宽比例）
@Immutable
data class GlowSpot(
    val xFrac: Float,
    val yFrac: Float,
    val radiusFrac: Float,
    val color: Color,
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
    bubbleUserTop = Color(0x290071E3),
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
    bubbleUserTop = Color(0x4D0A84FF),
    bubbleUser = Color(0x4D0A84FF),
    paperBase = Color(0xFF1A1820),
    maskVTop = Color(0x991A1820),
    maskV35 = Color(0x1A1A1820),
    maskV50 = Color(0x0D1A1820),
    maskV70 = Color(0x261A1820),
    maskVBot = Color(0xB31A1820),
    maskHEdge = Color(0x4D1A1820),
)

// 极光系（皮肤二）：设计简报 design-explorations/aurora-brief.md §2，
// 与鸿蒙端 theme/Schemes.ets AURORA_LIGHT/DARK 逐值对齐
val LightAurora = GlassScheme(
    isDark = false,
    glassBg = Color(0x99FFFFFF),
    glassBgStrong = Color(0xBDFFFFFF),
    glassBorder = Color(0xD9FFFFFF),
    textHi = Color(0xFF1B1F27),
    textMid = Color(0x941B1F27),
    textLow = Color(0x5C1B1F27),
    accent = Color(0xFF4B7BF5),
    accentSoft = Color(0x1F4B7BF5),
    bubbleUserTop = Color(0xFF5F8EFF),
    bubbleUser = Color(0xFF4468E8),
    paperBase = Color(0xFFF4F5F8),
    maskVTop = Color(0x4DFFFFFF),
    maskV35 = Color.Transparent,
    maskV50 = Color.Transparent,
    maskV70 = Color.Transparent,
    maskVBot = Color(0x40FFFFFF),
    maskHEdge = Color.Transparent,
    glows = listOf(
        GlowSpot(0.77f, 0.075f, 0.67f, Color(0xEB70B2FF)),
        GlowSpot(0.12f, 0.26f, 0.72f, Color(0xCCBCA8FF)),
        GlowSpot(0.55f, 0.46f, 0.62f, Color(0xB8FFD0AC)),
        GlowSpot(0.30f, 0.65f, 0.51f, Color(0x4DFFBA9E)),
    ),
    auroraVeilTop = Color(0x4DFFFFFF),
    auroraVeilBot = Color(0x40FFFFFF),
)

val DarkAurora = GlassScheme(
    isDark = true,
    glassBg = Color(0x851C212E),
    glassBgStrong = Color(0x9E222838),
    glassBorder = Color(0x1AFFFFFF),
    textHi = Color(0xFFECEFF5),
    textMid = Color(0x94ECEFF5),
    textLow = Color(0x57ECEFF5),
    accent = Color(0xFF6D96FF),
    accentSoft = Color(0x1F6D96FF),
    bubbleUserTop = Color(0xFF5A79DE),
    bubbleUser = Color(0xFF4860C8),
    paperBase = Color(0xFF0C0E14),
    maskVTop = Color(0x1AFFFFFF),
    maskV35 = Color.Transparent,
    maskV50 = Color.Transparent,
    maskV70 = Color.Transparent,
    maskVBot = Color(0x4D0C0E14),
    maskHEdge = Color.Transparent,
    glows = listOf(
        GlowSpot(0.77f, 0.075f, 0.67f, Color(0x5C6092F0)),
        GlowSpot(0.12f, 0.26f, 0.72f, Color(0x52987EF0)),
        GlowSpot(0.55f, 0.46f, 0.62f, Color(0x38EEA47C)),
        GlowSpot(0.30f, 0.65f, 0.51f, Color(0x2E78C8E6)),
    ),
    auroraVeilTop = Color(0x1AFFFFFF),
    auroraVeilBot = Color(0x4D0C0E14),
)

// 皮肤常量（与鸿蒙 ThemeMode/Schemes 与 ConfigStore 键值一致）
const val SKIN_CLEAR = "clear"
const val SKIN_AURORA = "aurora"

// skin × 生效明暗 → scheme 实例（未知 skin 回退 clear）
fun schemeFor(skin: String, isDark: Boolean): GlassScheme = when {
    skin == SKIN_AURORA -> if (isDark) DarkAurora else LightAurora
    else -> if (isDark) DarkGlass else LightGlass
}

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
