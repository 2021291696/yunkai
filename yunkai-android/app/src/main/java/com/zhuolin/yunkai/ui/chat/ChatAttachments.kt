package com.zhuolin.yunkai.ui.chat

// 附件管线（自 ChatViewModel 拆出，门0 P5 单文件 500 行收口）：
// 图片压缩 base64 + 文档正文抽取。纯函数对象，无状态。
import android.content.Context

internal object ChatAttachments {

    // uri 图片 → 长边 1280 JPEG(85) → base64（同 Wallpaper 的采样探测思路，防 12MP 原图撑爆请求）
    fun compressToB64(context: Context, uri: String): String {
        val resolver = context.contentResolver
        val input = resolver.openInputStream(android.net.Uri.parse(uri)) ?: throw Exception("读取图片失败")
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeStream(input, null, bounds)
        input.close()
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw Exception("图片解码失败")
        var sample = 1
        val maxSide = 1280
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val input2 = resolver.openInputStream(android.net.Uri.parse(uri)) ?: throw Exception("图片解码失败")
        val bmp = android.graphics.BitmapFactory.decodeStream(input2, null,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        input2.close()
            ?: throw Exception("图片解码失败")
        val safeBmp = bmp ?: throw Exception("图片解码失败")
        val out = java.io.ByteArrayOutputStream()
        safeBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    // 附件读取（≤5MB；正文截断 3 万字并标注）：txt 直读；docx/xlsx/pdf 走 DocTextExtractor
    // （二期；鸿蒙侧对应 docx/xlsx，PDF 挂 backlog）
    fun readAttachment(context: Context, uri: String, name: String): String {
        val input = context.contentResolver.openInputStream(android.net.Uri.parse(uri))
        val bytes = input?.readBytes() ?: throw Exception("读取文件失败")
        input.close()
        if (bytes.size > 5 * 1024 * 1024) throw Exception("文件超过 5MB 上限")
        var text = com.zhuolin.yunkai.service.DocTextExtractor.extract(name, bytes)
        if (text.isBlank()) throw Exception("未能从该文件抽取到文本（扫描件或空文档）")
        if (text.length > 30000) {
            text = text.substring(0, 30000) + "\n…（已截断）"
        }
        return text
    }
}
