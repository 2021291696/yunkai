package com.zhuolin.yunkai.memory

import com.zhuolin.yunkai.memory.PrivacyGate.Category
import com.zhuolin.yunkai.memory.PrivacyGate.Gear
import com.zhuolin.yunkai.memory.PrivacyGate.GearResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隐私闸门对拍回放（忆枢协议 §5 + vectors/privacy_cases.json）。
 * 逐 case 断言 detect 结果等于 expected_hits；另断言挡位矩阵与 §5.2 一致。
 */
class PrivacyVectorTest {
    private val json = Json

    private fun loadVector(name: String): String =
        javaClass.classLoader?.getResource("vectors/$name")?.readText()
            ?: error("测试资源缺失: vectors/$name")

    @Test
    fun `privacy 全量向量回放 detect 等于 expected_hits`() {
        val root = json.parseToJsonElement(loadVector("privacy_cases.json")).jsonObject
        val cases = root["cases"]!!.jsonArray.map { it.jsonObject }
        assertTrue("用例数应大于 0", cases.isNotEmpty())

        val failures = ArrayList<String>()
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val text = c["text"]!!.jsonPrimitive.content
            val expected = c["expected_hits"]!!.jsonArray.map { it.jsonPrimitive.content }
            val actual = PrivacyGate.detect(text).map { it.wire }
            if (actual != expected) {
                failures.add("[$name] text=$text\n  期望 $expected\n  实得 $actual")
            }
        }
        assertTrue("对拍失败 ${failures.size} 处:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `privacy 挡位矩阵 strict 全拒`() {
        assertEquals(
            GearResult.Reject(Category.SECRET),
            PrivacyGate.check("密码：abc123", Gear.STRICT)
        )
        assertEquals(
            GearResult.Reject(Category.IDNUM),
            PrivacyGate.check("证件号11010119900307775X", Gear.STRICT)
        )
        assertEquals(
            GearResult.Reject(Category.BANKCARD),
            PrivacyGate.check("卡号6222020000000000000", Gear.STRICT)
        )
        assertEquals(GearResult.Allow, PrivacyGate.check("今天天气不错", Gear.STRICT))
    }

    @Test
    fun `privacy 挡位矩阵 standard 放 secret 拒 idnum 与 bankcard`() {
        assertEquals(GearResult.Allow, PrivacyGate.check("密码：abc123", Gear.STANDARD))
        assertEquals(
            GearResult.Reject(Category.IDNUM),
            PrivacyGate.check("证件号11010119900307775X", Gear.STANDARD)
        )
        assertEquals(
            GearResult.Reject(Category.BANKCARD),
            PrivacyGate.check("卡号6222020000000000000", Gear.STANDARD)
        )
    }

    @Test
    fun `privacy 挡位矩阵 free 全放`() {
        assertEquals(GearResult.Allow, PrivacyGate.check("密码：abc123", Gear.FREE))
        assertEquals(GearResult.Allow, PrivacyGate.check("证件号11010119900307775X", Gear.FREE))
        assertEquals(GearResult.Allow, PrivacyGate.check("卡号6222020000000000000", Gear.FREE))
    }

    @Test
    fun `privacy 多类命中按 secret idnum bankcard 顺序取首个被拒类别`() {
        val text = "密码：Root@2024，证件11010119900307775X，卡号6222020000000000000"
        assertEquals(
            listOf("secret", "idnum", "bankcard"),
            PrivacyGate.detect(text).map { it.wire }
        )
        assertEquals(GearResult.Reject(Category.SECRET), PrivacyGate.check(text, Gear.STRICT))
        assertEquals(GearResult.Reject(Category.IDNUM), PrivacyGate.check(text, Gear.STANDARD))
        assertEquals(GearResult.Allow, PrivacyGate.check(text, Gear.FREE))
    }

    @Test
    fun `privacy 挡位字符串映射 未知按 strict`() {
        assertEquals(Gear.STRICT, Gear.fromWire("strict"))
        assertEquals(Gear.STANDARD, Gear.fromWire("standard"))
        assertEquals(Gear.FREE, Gear.fromWire("free"))
        assertEquals(Gear.STRICT, Gear.fromWire("whatever"))
    }

    @Test
    fun `privacy luhn 校验抽查`() {
        assertTrue(PrivacyGate.luhnValid("6222020000000000000"))
        assertTrue(PrivacyGate.luhnValid("6222020000000007"))
        assertTrue(PrivacyGate.luhnValid("621700000000000006"))
        assertTrue(PrivacyGate.luhnValid("110101199003070013"))
        assertTrue(!PrivacyGate.luhnValid("6222020000000000001"))
        assertTrue(!PrivacyGate.luhnValid("123456789012345"))
        assertTrue(!PrivacyGate.luhnValid(""))
    }
}
