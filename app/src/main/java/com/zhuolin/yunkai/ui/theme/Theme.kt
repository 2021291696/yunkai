package com.zhuolin.yunkai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 暖纸玻璃风（与鸿蒙版同一色族）：暖纸底 + 暖橙主色 + 深棕正文 + 灰棕弱化文字
val PaperBg = Color(0xFFFAF6EF)
val WarmOrange = Color(0xFFE8930C)
val WarmOrangeDeep = Color(0xFFB25E00)
val TextDark = Color(0xFF3D3325)
val TextMuted = Color(0xFF8A7A5A)
val TextFaint = Color(0xFFA08C66)
val CardGlass = Color(0xAAFFFFFF)   // #FFFFFFA6
val CardBorder = Color(0xCCFFFFFF)  // #FFFFFFCC
val TopBarGlass = Color(0xB0F2EADC) // #F2EADCB0
val UserBubble = WarmOrange
val ErrorRed = Color(0xFFFF453A)

private val LightColors = lightColorScheme(
    primary = WarmOrange,
    onPrimary = Color.White,
    secondary = WarmOrangeDeep,
    background = PaperBg,
    onBackground = TextDark,
    surface = PaperBg,
    onSurface = TextDark,
    surfaceVariant = CardGlass,
    onSurfaceVariant = TextMuted,
    outline = CardBorder,
    error = ErrorRed,
)

private val DarkColors = darkColorScheme(
    primary = WarmOrange,
    onPrimary = Color.White,
    secondary = WarmOrangeDeep,
    background = PaperBg, // 自用 app 锁暖纸浅色：鸿蒙版同样固定浅色纸底
    onBackground = TextDark,
    surface = PaperBg,
    onSurface = TextDark,
    surfaceVariant = CardGlass,
    onSurfaceVariant = TextMuted,
    outline = CardBorder,
    error = ErrorRed,
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextDark),
    titleMedium = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium, color = TextDark),
    bodyLarge = TextStyle(fontSize = 15.sp, color = TextDark),
    bodyMedium = TextStyle(fontSize = 14.sp, color = TextDark),
    bodySmall = TextStyle(fontSize = 12.sp, color = TextFaint),
)

@Composable
fun YunkaiTheme(content: @Composable () -> Unit) {
    // 鸿蒙版固定浅色暖纸底，深色模式不做换肤
    MaterialTheme(colorScheme = LightColors, typography = AppTypography, content = content)
}
