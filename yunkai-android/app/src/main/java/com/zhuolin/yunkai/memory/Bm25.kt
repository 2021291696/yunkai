package com.zhuolin.yunkai.memory

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ln

/**
 * BM25 检索引擎（忆枢协议 §8，Kotlin 侧同构实现）。
 *
 * 切词（§8.1）：ASCII 转小写；CJK 段（U+4E00–U+9FFF）二元滑窗、段长 1 单字成 token；
 * 连续 ASCII 字母数字段整段一个 token；其余字符仅作分隔。
 * 打分（§8.2）：k1=1.2、b=0.75，idf=ln(1+(N-df+0.5)/(df+0.5))，查询 token 去重。
 * 新近度（§8.3）：final = bm25 · (1 + 0.1 · recency)，recency = max(0, 1 - ageDays/30)。
 * 排序（§8.4）：final 降序 → createdAt 新者前 → id 小者前；分数四舍五入到 4 位小数。
 * 空查询（含纯标点切词后为空，§8.5）→ 返回最近 topK 条，score 0.0；0 分文档不过滤。
 */
object Bm25 {
    const val K1 = 1.2
    const val B = 0.75
    const val RECENCY_WEIGHT = 0.1

    private const val MS_PER_DAY = 86400000.0
    private const val RECENCY_WINDOW_DAYS = 30.0

    data class Doc(val id: Long, val content: String, val createdAt: Long)
    data class Hit(val id: Long, val score: Double)

    fun isCjk(c: Char): Boolean = c.code in 0x4E00..0x9FFF

    fun isAsciiAlnum(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9')

    /** 整串转小写（仅 ASCII 范围，CJK 与非 ASCII 不受影响）。 */
    private fun lowerAscii(c: Char): Char =
        if (c in 'A'..'Z') ((c.code + 32).toChar()) else c

    fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val n = text.length
        while (i < n) {
            val raw = text[i]
            val c = lowerAscii(raw)
            when {
                isAsciiAlnum(c) -> {
                    val sb = StringBuilder()
                    while (i < n) {
                        val lc = lowerAscii(text[i])
                        if (!isAsciiAlnum(lc)) break
                        sb.append(lc)
                        i++
                    }
                    out.add(sb.toString())
                }
                isCjk(c) -> {
                    val start = i
                    while (i < n && isCjk(text[i])) i++
                    val run = text.substring(start, i)
                    if (run.length == 1) {
                        out.add(run)
                    } else {
                        for (k in 0..run.length - 2) out.add(run.substring(k, k + 2))
                    }
                }
                else -> i++
            }
        }
        return out
    }

    fun search(corpus: List<Doc>, query: String, topK: Int, nowMs: Long): List<Hit> {
        val qTokens = tokenize(query).distinct()
        if (qTokens.isEmpty()) {
            // 空查询/纯标点：回退最近 topK 条（createdAt 倒序，并列 id 小者前），score 0.0
            return corpus.asSequence()
                .sortedWith(compareByDescending<Doc> { it.createdAt }.thenBy { it.id })
                .take(topK)
                .map { Hit(it.id, 0.0) }
                .toList()
        }
        if (corpus.isEmpty()) return emptyList()

        val tokenized = corpus.map { it to tokenize(it.content) }
        val n = tokenized.size
        val avgdl = tokenized.sumOf { (_, toks) -> toks.size }.toDouble() / n
        val df = HashMap<String, Int>()
        tokenized.forEach { (_, toks) ->
            toks.distinct().forEach { t -> df.merge(t, 1, Int::plus) }
        }

        data class Scored(val doc: Doc, val score: Double)

        val scored = tokenized.map { (doc, toks) ->
            val tf = HashMap<String, Int>()
            toks.forEach { t -> tf.merge(t, 1, Int::plus) }
            val dl = toks.size.toDouble()
            var bm25 = 0.0
            for (t in qTokens) {
                val f = tf[t] ?: continue
                val d = df[t] ?: 0
                val idf = ln(1.0 + (n - d + 0.5) / (d + 0.5))
                bm25 += idf * (f * (K1 + 1.0)) / (f + K1 * (1.0 - B + B * dl / avgdl))
            }
            val ageDays = maxOf(0.0, (nowMs - doc.createdAt) / MS_PER_DAY)
            val recency = maxOf(0.0, 1.0 - ageDays / RECENCY_WINDOW_DAYS)
            Scored(doc, bm25 * (1.0 + RECENCY_WEIGHT * recency))
        }

        return scored.asSequence()
            .sortedWith(
                compareByDescending<Scored> { it.score }
                    .thenByDescending { it.doc.createdAt }
                    .thenBy { it.doc.id }
            )
            .take(topK)
            .map { Hit(it.doc.id, round4(it.score)) }
            .toList()
    }

    /** 分数四舍五入到 4 位小数（HALF_UP，对拍容差 1e-4，禁依赖 .xxxx5 边界）。 */
    fun round4(v: Double): Double =
        BigDecimal(v).setScale(4, RoundingMode.HALF_UP).toDouble()
}
