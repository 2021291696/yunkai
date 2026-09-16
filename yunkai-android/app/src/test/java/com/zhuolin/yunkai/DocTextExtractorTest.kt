package com.zhuolin.yunkai

// 二期文档抽取单测：自造最小 docx/xlsx（zip+XML），不依赖真实 Office 文件
import com.zhuolin.yunkai.service.DocTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocTextExtractorTest {

    private fun zipOf(entries: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun makeDocx(paras: List<String>): ByteArray {
        val body = paras.joinToString("") { p ->
            "<w:p><w:r><w:t>$p</w:t></w:r></w:p>"
        }
        return zipOf(mapOf(
            "word/document.xml" to "<?xml version=\"1.0\"?><w:document><w:body>$body</w:body></w:document>",
        ))
    }

    private fun makeXlsx(shared: List<String>, rows: List<List<String>>): ByteArray {
        val ss = shared.joinToString("") { "<si><t>$it</t></si>" }
        val rowXml = rows.joinToString("") { cells ->
            val cs = cells.mapIndexed { i, v ->
                "<c r=\"${('A'.code + i).toChar()}1\" t=\"s\"><v>$v</v></c>"
            }.joinToString("")
            "<row>$cs</row>"
        }
        return zipOf(mapOf(
            "xl/sharedStrings.xml" to "<?xml version=\"1.0\"?><sst>$ss</sst>",
            "xl/worksheets/sheet1.xml" to "<?xml version=\"1.0\"?><worksheet><sheetData>$rowXml</sheetData></worksheet>",
        ))
    }

    @Test
    fun docxExtractsParagraphs() {
        val bytes = makeDocx(listOf("第一段：黑洞", "第二段 &amp; 事件视界 &lt;测试&gt;"))
        val text = DocTextExtractor.extract("测试.docx", bytes)
        assertTrue(text.contains("第一段：黑洞"))
        assertTrue("实体应解码", text.contains("第二段 & 事件视界 <测试>"))
        assertEquals("两段应各占一行", 2, text.lines().size)
    }

    @Test
    fun xlsxExtractsSharedStringsByIndex() {
        val bytes = makeXlsx(
            shared = listOf("姓名", "张三", "城市", "成都"),
            rows = listOf(listOf("0", "1"), listOf("2", "3")),
        )
        val text = DocTextExtractor.extract("表格.xlsx", bytes)
        assertTrue(text.contains("姓名\t张三"))
        assertTrue(text.contains("城市\t成都"))
        assertTrue(text.contains("工作表1"))
    }

    @Test
    fun docxMissingDocumentXmlThrows() {
        val bad = zipOf(mapOf("word/other.xml" to "<x/>"))
        val err = runCatching { DocTextExtractor.extractDocx(bad) }.exceptionOrNull()
        assertTrue("结构异常应显式抛错", err != null && err.message!!.contains("document.xml"))
    }

    @Test
    fun plainTextFallsThrough() {
        val text = DocTextExtractor.extract("说明.txt", "纯文本内容".toByteArray())
        assertEquals("纯文本内容", text)
    }
}
