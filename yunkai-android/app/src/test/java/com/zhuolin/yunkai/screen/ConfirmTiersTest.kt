package com.zhuolin.yunkai.screen

import com.zhuolin.yunkai.service.screen.ConfirmMode
import com.zhuolin.yunkai.service.screen.ConfirmTiers
import com.zhuolin.yunkai.service.screen.WriteAction
import com.zhuolin.yunkai.service.screen.extractConfirmFlags
import com.zhuolin.yunkai.service.screen.predictPause
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 分档判定纯函数表驱动（逻辑审查 W3/W5 收口）：
// ConfirmTiers.requiresConfirm 三档判定表 + extractConfirmFlags（plan 下标 +1 偏移）+ predictPause 预测表。
// 纯 JVM 无 Android 依赖；执行器侧行为（闸门真的停）由 WritePlanExecutorTest 覆盖，本文件管判定本身。
class ConfirmTiersTest {
    private val tap = WriteAction.Tap(1, 2)
    private val plainInput = WriteAction.Input("你好")
    private val sensitiveInput = WriteAction.Input("请填写支付密码")

    @Test fun `fullAuto 恒不确认 即使敏感词输入或模型标记`() {
        for (a in listOf(tap, plainInput, sensitiveInput)) {
            assertFalse(ConfirmTiers.requiresConfirm(ConfirmMode.FULL_AUTO, a, flagged = true))
        }
    }

    @Test fun `smart 敏感词输入确认 普通输入与低危不确认 标记动作确认`() {
        assertTrue(ConfirmTiers.requiresConfirm(ConfirmMode.SMART, sensitiveInput, flagged = false))
        assertFalse(ConfirmTiers.requiresConfirm(ConfirmMode.SMART, plainInput, flagged = false))
        assertTrue(ConfirmTiers.requiresConfirm(ConfirmMode.SMART, tap, flagged = true))
        assertFalse(ConfirmTiers.requiresConfirm(ConfirmMode.SMART, tap, flagged = false))
        assertFalse(ConfirmTiers.requiresConfirm(ConfirmMode.SMART, WriteAction.Back, flagged = false))
    }

    @Test fun `strict 输入必确认 低危动作免确认`() {
        assertTrue(ConfirmTiers.requiresConfirm(ConfirmMode.STRICT, plainInput, flagged = false))
        assertTrue(ConfirmTiers.requiresConfirm(ConfirmMode.STRICT, sensitiveInput, flagged = false))
        val lowRisk = listOf(
            tap, WriteAction.Swipe(0, 0, 1, 1), WriteAction.Back, WriteAction.Home,
            WriteAction.OpenApp("com.android.settings"),
        )
        for (a in lowRisk) {
            assertFalse(ConfirmTiers.requiresConfirm(ConfirmMode.STRICT, a, flagged = false))
        }
    }

    @Test fun `extractConfirmFlags plan下标整体加1 只认true 布尔字符串都收`() {
        val arr = Json.parseToJsonElement(
            """[{"type":"tap","x":1,"y":2},""" +
                """{"type":"input","text":"hi","confirm":true},""" +
                """{"type":"tap","x":3,"y":4,"confirm":"true"},""" +
                """{"type":"back","confirm":false}]""",
        ).let { it as JsonArray }
        // plan 下标 1/2 标记 → 执行器 actions 下标 2/3（头部多一个 OpenApp）；下标 3 的 false 不收
        assertEquals(setOf(2, 3), extractConfirmFlags(arr))
    }

    @Test fun `extractConfirmFlags 无标记返回空集`() {
        val arr = Json.parseToJsonElement("""[{"type":"tap","x":1,"y":2},{"type":"back"}]""").let { it as JsonArray }
        assertEquals(emptySet<Int>(), extractConfirmFlags(arr))
    }

    @Test fun `predictPause 与执行闸门同源 fullAuto恒false smart看标记 strict看input`() {
        val actions = listOf(WriteAction.OpenApp("pkg"), tap, plainInput)
        assertFalse(predictPause(ConfirmMode.FULL_AUTO, actions, setOf(2)))
        assertTrue(predictPause(ConfirmMode.SMART, actions, setOf(2)))
        assertFalse(predictPause(ConfirmMode.SMART, actions, emptySet()))
        assertTrue(predictPause(ConfirmMode.STRICT, actions, emptySet()))
    }
}
