package com.zhuolin.yunkai.memory

/**
 * 隐私闸门（忆枢协议 §5，写入前置）。
 *
 * 三类检测模式（§5.1，与 vectors/privacy_cases.json patterns 逐字一致）：
 * - secret：`(?i)(密码|口令|passwd|password|pwd|api[\s_-]?key|secret|密钥)[^\n]{0,20}[:：=为是]\s*\S+`
 *   （Kotlin 侧 (?i) 译为 RegexOption.IGNORE_CASE，不用内联 flag）
 * - idnum：`[1-9]\d{5}(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[\dXx]`
 * - bankcard：`\b\d{16,19}\b` 经 Luhn 校验；跨语言纪律：ASCII 边界用环视
 *   `(?<![0-9A-Za-z_])\d{16,19}(?![0-9A-Za-z_])` 模拟，**禁开 UNICODE_CHARACTER_CLASS**。
 *
 * 挡位矩阵（§5.2）：strict 全拒 / standard 放 secret 拒 idnum·bankcard / free 全放。
 */
object PrivacyGate {
    enum class Category(val wire: String) {
        SECRET("secret"), IDNUM("idnum"), BANKCARD("bankcard");

        companion object {
            fun fromWire(w: String): Category? = entries.firstOrNull { it.wire == w }
        }
    }

    enum class Gear(val wire: String) {
        STRICT("strict"), STANDARD("standard"), FREE("free");

        companion object {
            /** 未知挡位按默认 strict 处理（协议 §2 PRIVACY_GEAR_DEFAULT）。 */
            fun fromWire(w: String): Gear = entries.firstOrNull { it.wire == w } ?: STRICT
        }
    }

    sealed interface GearResult {
        data object Allow : GearResult
        data class Reject(val category: Category) : GearResult
    }

    // `\s`/`\S` 按 ASCII 空白语义（Kotlin Regex 默认即此，不开 UNICODE_CHARACTER_CLASS）
    private val secretPattern = Regex(
        "(密码|口令|passwd|password|pwd|api[\\s_-]?key|secret|密钥)[^\\n]{0,20}[:：=为是]\\s*\\S+",
        RegexOption.IGNORE_CASE
    )
    private val idnumPattern = Regex(
        "[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]"
    )
    private val bankcardPattern = Regex("(?<![0-9A-Za-z_])\\d{16,19}(?![0-9A-Za-z_])")

    /** 标准银行卡 Luhn 校验（模 10 加权，自右起偶数位翻倍）。 */
    fun luhnValid(digits: String): Boolean {
        if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return false
        var sum = 0
        var alt = false
        for (i in digits.length - 1 downTo 0) {
            var d = digits[i] - '0'
            if (alt) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            alt = !alt
        }
        return sum % 10 == 0
    }

    private fun matches(category: Category, text: String): Boolean = when (category) {
        Category.SECRET -> secretPattern.containsMatchIn(text)
        Category.IDNUM -> idnumPattern.containsMatchIn(text)
        Category.BANKCARD ->
            bankcardPattern.findAll(text).any { luhnValid(it.value) }
    }

    /** 检测文本命中的类别，固定顺序 [secret, idnum, bankcard]（与对拍用例 expected_hits 一致）。 */
    fun detect(text: String): List<Category> =
        Category.entries.filter { matches(it, text) }

    /** 三挡矩阵判定：命中且该挡位拒绝 → Reject(首个被拒类别)，否则 Allow。 */
    fun check(content: String, gear: Gear): GearResult {
        val rejected = when (gear) {
            Gear.STRICT -> Category.entries   // 全拒
            Gear.STANDARD -> listOf(Category.IDNUM, Category.BANKCARD)  // secret 放行
            Gear.FREE -> emptyList()          // 全放行
        }
        rejected.firstOrNull { matches(it, content) }?.let { return GearResult.Reject(it) }
        return GearResult.Allow
    }

    fun check(content: String, gear: String): GearResult = check(content, Gear.fromWire(gear))
}
