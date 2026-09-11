package com.zhuolin.yunkai.service

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

// 二期文档文本抽取（纯端上）：
// docx/xlsx 走自研 zip+XML（与鸿蒙 ArkTS 实现同构，零依赖不增包体）；
// PDF（文字型）走 PdfBox-Android（仅 Android；扫描件/图片型 PDF 与鸿蒙 PDF 挂 backlog）。
// 抽取失败一律抛异常，由调用方转成"发送前提示"，不静默丢内容。
object DocTextExtractor {

    // 统一入口：按文件名后缀分派；未知后缀按 UTF-8 文本处理
    fun extract(fileName: String, bytes: ByteArray): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".docx") -> extractDocx(bytes)
            lower.endsWith(".xlsx") -> extractXlsx(bytes)
            lower.endsWith(".pdf") -> extractPdf(bytes)
            else -> String(bytes, Charsets.UTF_8)
        }
    }

    // 读 zip 内某个 entry 的 UTF-8 文本；不存在返回 null
    private fun readZipEntry(bytes: ByteArray, entryName: String): String? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                if (e.name == entryName) return zin.readBytes().toString(Charsets.UTF_8)
                e = zin.nextEntry
            }
        }
        return null
    }

    // 遍历 zip 内匹配前缀的 entry 名（按名排序，保证 sheet1/2/3 顺序稳定）
    private fun listZipEntries(bytes: ByteArray, prefix: String, suffix: String): List<String> {
        val out = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                if (e.name.startsWith(prefix) && e.name.endsWith(suffix)) out.add(e.name)
                e = zin.nextEntry
            }
        }
        return out.sorted()
    }

    // XML 实体解码（docx/xlsx 正文里的 &amp; &lt; &gt; &quot; &apos;）
    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&amp;", "&")

    // ── docx：word/document.xml 里按段落 <w:p> 切，抽段内全部 <w:t> 文本 ──
    fun extractDocx(bytes: ByteArray): String {
        val xml = readZipEntry(bytes, "word/document.xml")
            ?: throw Exception("docx 结构异常：缺少 word/document.xml")
        val sb = StringBuilder()
        // 段落：<w:p ...> ... </w:p>（自闭合 <w:p/> 视为空段）
        val paraRe = Regex("<w:p(?:\\s[^>]*)?>(.*?)</w:p>", RegexOption.DOT_MATCHES_ALL)
        val textRe = Regex("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
        for (m in paraRe.findAll(xml)) {
            val body = m.groupValues[1]
            val line = textRe.findAll(body).joinToString("") { unescape(it.groupValues[1]) }
            sb.append(line).append('\n')
        }
        return sb.toString().trim()
    }

    // ── xlsx：xl/sharedStrings.xml 建共享串表，各 sheet 的 <c> 单元格按行拼 ──
    fun extractXlsx(bytes: ByteArray): String {
        val shared = mutableListOf<String>()
        val ssXml = readZipEntry(bytes, "xl/sharedStrings.xml")
        if (ssXml != null) {
            // 每个 <si> 是一个字符串（可能由多个 <t> 富文本段组成）
            val siRe = Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
            val tRe = Regex("<t(?:\\s[^>]*)?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
            for (m in siRe.findAll(ssXml)) {
                shared.add(tRe.findAll(m.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) })
            }
        }
        val sheets = listZipEntries(bytes, "xl/worksheets/sheet", ".xml")
        if (sheets.isEmpty()) throw Exception("xlsx 结构异常：缺少工作表")
        val sb = StringBuilder()
        val rowRe = Regex("<row(?:\\s[^>]*)?>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
        val cellRe = Regex("<c(?:\\s[^>]*)?>(.*?)</c>", RegexOption.DOT_MATCHES_ALL)
        val typeRe = Regex("t=\"([^\"]+)\"")
        val vRe = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
        val isRe = Regex("<is>(.*?)</is>", RegexOption.DOT_MATCHES_ALL)
        val isTRe = Regex("<t(?:\\s[^>]*)?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
        for ((idx, name) in sheets.withIndex()) {
            val sheetXml = readZipEntry(bytes, name) ?: continue
            sb.append("【工作表${idx + 1}】").append('\n')
            for (row in rowRe.findAll(sheetXml)) {
                val cells = mutableListOf<String>()
                for (c in cellRe.findAll(row.groupValues[1])) {
                    val attrs = c.groupValues[0]
                    val inner = c.groupValues[1]
                    val t = typeRe.find(attrs)?.groupValues?.get(1) ?: ""
                    val v = vRe.find(inner)?.groupValues?.get(1)
                    val text = when {
                        t == "s" && v != null -> {
                            val i = v.trim().toIntOrNull()
                            if (i != null && i in shared.indices) shared[i] else ""
                        }
                        t == "inlineStr" -> {
                            val isSeg = isRe.find(inner)?.groupValues?.get(1) ?: inner
                            isTRe.findAll(isSeg).joinToString("") { unescape(it.groupValues[1]) }
                        }
                        v != null -> unescape(v.trim())
                        else -> ""
                    }
                    if (text.isNotEmpty()) cells.add(text)
                }
                if (cells.isNotEmpty()) sb.append(cells.joinToString("\t")).append('\n')
            }
            sb.append('\n')
        }
        return sb.toString().trim()
    }

    // ── PDF（文字型）：PdfBox-Android 抽文本；扫描件无文字层时返回空 → 调用方提示 ──
    fun extractPdf(bytes: ByteArray): String {
        val doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(bytes)
        try {
            val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
            return stripper.getText(doc).trim()
        } finally {
            doc.close()
        }
    }
}
