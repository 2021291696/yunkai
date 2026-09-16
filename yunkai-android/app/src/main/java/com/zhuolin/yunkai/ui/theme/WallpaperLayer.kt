package com.zhuolin.yunkai.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zhuolin.yunkai.R
import com.zhuolin.yunkai.store.ConfigStore

// 全局壁纸层：按皮肤分叉（皮肤 token 见 Glass.kt，与鸿蒙 WallpaperLayer.ets 同构）——
// clear：照片 + 亮度/饱和度滤镜 + 纵横渐变遮罩（现役实现）。
// aurora：实底画布 + 四团 radialGradient 辉光（14s translate 往返漂移，不逐帧重建 Brush）
//   + 上下可读性纱。自定义壁纸仅 clear 皮肤显示。
// 换壁纸 = 设置页写入 ConfigStore.wallpaper（绝对路径），这里用 Flow 订阅即时生效。
// 滤镜语义对齐鸿蒙版：暗色 brightness 0.45 / 亮色 1.06，saturate 0.85。

// 壁纸解码上限：按屏幕高度的两倍采样（壁纸是 Crop 铺满，宽度由纵横比自然覆盖）。
// 用户可能选 12MP 相册照——全尺寸解码 ≈48MB 位图且在主线程会冻结首帧，必须先探测尺寸再降采样。
private const val WALLPAPER_MAX_HEIGHT_PX = 2400

private fun decodeScaled(path: String): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outHeight / (sample * 2) >= WALLPAPER_MAX_HEIGHT_PX) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

@Composable
fun WallpaperLayer(modifier: Modifier = Modifier) {
    val glass = LocalGlassScheme.current
    val context = LocalContext.current
    val store = remember { ConfigStore(context.applicationContext) }
    val skin by store.skinFlow.collectAsState(initial = ConfigStore.SKIN_CLEAR)
    // 壁纸源 = path + gen 代数：设置页固定写同一文件名（wallpaper_custom.jpg），二次换壁纸时
    // 路径不变内容变——只以路径为 key 不触发重解码，gen 随每次 flow 发射递增保证必触发
    var wallSrc by remember { mutableStateOf("" to 0) }
    LaunchedEffect(Unit) {
        store.wallpaperFlow.collect { path -> wallSrc = path to (wallSrc.second + 1) }
    }
    // 解码放 Default 线程（12MP 照片解码数百 ms，主线程会掉帧）；失败时不卡 UI，回落默认壁纸
    val customBitmap = produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, wallSrc) {
        val (path, _) = wallSrc
        value = if (path.isEmpty()) null
        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            decodeScaled(path)?.asImageBitmap()
        }
    }
    val bitmap = customBitmap.value
    // saturate(0.85) 再乘亮度系数；亮度 >1 的部分由 ColorMatrix 自然钳制
    val filter = remember(glass.isDark) {
        val m = ColorMatrix().apply { setToSaturation(0.85f) }
        val b = if (glass.isDark) 0.45f else 1.06f
        m.timesAssign(ColorMatrix().apply { setToScale(b, b, b, 1f) })
        ColorFilter.colorMatrix(m)
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (skin == ConfigStore.SKIN_AURORA) {
            AuroraBackground(glass)
        } else if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                colorFilter = filter,
            )
        } else {
            Image(
                painter = painterResource(R.drawable.wallpaper_default),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                colorFilter = filter,
            )
        }
        // 纵向暗角（对齐鸿蒙 0/0.35/0.5/0.7/1.0 五段）
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.0f to glass.maskVTop,
                    0.35f to glass.maskV35,
                    0.5f to glass.maskV50,
                    0.7f to glass.maskV70,
                    1.0f to glass.maskVBot,
                )
            )
        )
        // 横向收边
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0.0f to glass.maskHEdge,
                    0.3f to Color.Transparent,
                    0.7f to Color.Transparent,
                    1.0f to glass.maskHEdge,
                )
            )
        )
    }
}

// aurora 背景（皮肤二）：实底 + 四团辉光 + 上下可读性纱。
// 漂移 = 整个辉光层 graphicsLayer 平移，t 由 infiniteTransition 14s 往返驱动；
// Brush 在 draw 阶段按 drawscope 尺寸构建（屏宽比例定位），不随 t 重建。
@Composable
private fun AuroraBackground(glass: GlassScheme) {
    val transition = rememberInfiniteTransition(label = "auroraDrift")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "auroraDriftT",
    )
    Box(Modifier.fillMaxSize().background(glass.paperBase)) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = -10.dp.toPx() * t
                    translationY = 8.dp.toPx() * t
                }
                .drawBehind { drawGlows(glass) }
        )
        // 可读性纱：顶部轻提亮、底部压暗（问候区与输入坞）
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.0f to glass.auroraVeilTop,
                    0.28f to Color.Transparent,
                    0.62f to Color.Transparent,
                    1.0f to glass.auroraVeilBot,
                )
            )
        )
    }
}

private fun DrawScope.drawGlows(glass: GlassScheme) {
    glass.glows.forEach { spot ->
        val r = size.width * spot.radiusFrac
        val center = Offset(size.width * spot.xFrac, size.height * spot.yFrac)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(spot.color, Color.Transparent),
                center = center,
                radius = r,
            ),
            radius = r,
            center = center,
        )
    }
}
