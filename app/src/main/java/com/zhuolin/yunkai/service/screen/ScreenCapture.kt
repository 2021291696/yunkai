package com.zhuolin.yunkai.service.screen

// 截图后处理：长边压缩 + JPEG(85) + base64。
object ScreenCapture {
    fun toBase64(bmp: android.graphics.Bitmap, maxEdge: Int = 1280): String {
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
        if (scaled !== bmp) scaled.recycle()
        return b64
    }
}
