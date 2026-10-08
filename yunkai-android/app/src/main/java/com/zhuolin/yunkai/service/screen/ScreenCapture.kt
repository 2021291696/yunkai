package com.zhuolin.yunkai.service.screen

// 截图后处理：长边压缩 + JPEG(85) + base64。
// 2026-10-08 视觉接地轮：capture_screen 升级为「坐标感知元素提取」——视觉模型在**缩放图**
// 坐标系里标注可点击元素，工具侧换算回物理坐标附在结果里，agent 的 tap 不再凭散文猜像素。
object ScreenCapture {
    data class Encoded(val b64: String, val scaledW: Int, val scaledH: Int)

    fun toBase64(bmp: android.graphics.Bitmap, maxEdge: Int = 1280): String = encode(bmp, maxEdge).b64

    fun encode(bmp: android.graphics.Bitmap, maxEdge: Int = 1280): Encoded {
        val long = maxOf(bmp.width, bmp.height)
        val scaled = if (long <= maxEdge) bmp else run {
            val r = maxEdge.toFloat() / long
            android.graphics.Bitmap.createScaledBitmap(
                bmp, (bmp.width * r).toInt().coerceAtLeast(1),
                (bmp.height * r).toInt().coerceAtLeast(1), true)
        }
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        val dim = Encoded(b64, scaled.width, scaled.height)
        if (scaled !== bmp) scaled.recycle()
        return dim
    }
}
