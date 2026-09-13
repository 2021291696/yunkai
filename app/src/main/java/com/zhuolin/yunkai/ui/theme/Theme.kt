package com.zhuolin.yunkai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── 旧 token 桥接（方向 A 玻璃化过渡层）────────────────────────────
// 存量页面直接 import 这些顶层名；改为 @Composable getter 后零改动换肤，
// 语义映射到 GlassScheme。新代码请直接用 LocalGlassScheme.current。
val PaperBg: Color @Composable get() = LocalGlassScheme.current.paperBase
val WarmOrange: Color @Composable get() = LocalGlassScheme.current.accent
val WarmOrangeDeep: Color @Composable get() = LocalGlassScheme.current.accent
val TextDark: Color @Composable get() = LocalGlassScheme.current.textHi
val TextMuted: Color @Composable get() = LocalGlassScheme.current.textMid
val TextFaint: Color @Composable get() = LocalGlassScheme.current.textLow
val CardGlass: Color @Composable get() = LocalGlassScheme.current.glassBg
val CardBorder: Color @Composable get() = LocalGlassScheme.current.glassBorder
val TopBarGlass: Color @Composable get() = LocalGlassScheme.current.glassBgStrong
val UserBubble: Color @Composable get() = LocalGlassScheme.current.bubbleUser
val ErrorRed = Color(0xFFFF453A)

@Composable
fun YunkaiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    skin: String = com.zhuolin.yunkai.store.ConfigStore.SKIN_CLEAR,
    content: @Composable () -> Unit,
) {
    val glass = schemeFor(skin, darkTheme)
    val colors = remember(darkTheme) {
        val base = if (darkTheme) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = glass.accent,
            onPrimary = Color.White,
            secondary = glass.accent,
            background = glass.paperBase,
            onBackground = glass.textHi,
            surface = glass.paperBase,
            onSurface = glass.textHi,
            surfaceVariant = glass.glassBg,
            onSurfaceVariant = glass.textMid,
            outline = glass.glassBorder,
            error = ErrorRed,
        )
    }
    val typography = remember(darkTheme) {
        Typography(
            titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = glass.textHi),
            titleMedium = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium, color = glass.textHi),
            bodyLarge = TextStyle(fontSize = 15.sp, color = glass.textHi),
            bodyMedium = TextStyle(fontSize = 14.sp, color = glass.textHi),
            bodySmall = TextStyle(fontSize = 12.sp, color = glass.textLow),
        )
    }
    CompositionLocalProvider(LocalGlassScheme provides glass) {
        MaterialTheme(colorScheme = colors, typography = typography, content = content)
    }
}
