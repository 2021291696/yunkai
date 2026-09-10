package com.zhuolin.yunkai.ui.theme

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.zhuolin.yunkai.R
import com.zhuolin.yunkai.store.ConfigStore

// 全局壁纸层：照片 + 亮度/饱和度滤镜 + 纵向/横向渐变遮罩。置于页面 Box 最底层。
// 换壁纸 = 设置页写入 ConfigStore.wallpaper（绝对路径），这里用 Flow 订阅即时生效。
// 滤镜语义对齐鸿蒙版：暗色 brightness 0.45 / 亮色 1.06，saturate 0.85。
@Composable
fun WallpaperLayer(modifier: Modifier = Modifier) {
    val glass = LocalGlassScheme.current
    val context = LocalContext.current
    val store = remember { ConfigStore(context.applicationContext) }
    val customPath by produceState(initialValue = "") {
        store.wallpaperFlow.collect { value = it }
    }
    val customBitmap = remember(customPath) {
        if (customPath.isNotEmpty()) {
            runCatching { BitmapFactory.decodeFile(customPath)?.asImageBitmap() }.getOrNull()
        } else null
    }
    // saturate(0.85) 再乘亮度系数；亮度 >1 的部分由 ColorMatrix 自然钳制
    val filter = remember(glass.isDark) {
        val m = ColorMatrix().apply { setToSaturation(0.85f) }
        val b = if (glass.isDark) 0.45f else 1.06f
        m.timesAssign(ColorMatrix().apply { setToScale(b, b, b, 1f) })
        ColorFilter.colorMatrix(m)
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (customBitmap != null) {
            Image(
                bitmap = customBitmap,
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
