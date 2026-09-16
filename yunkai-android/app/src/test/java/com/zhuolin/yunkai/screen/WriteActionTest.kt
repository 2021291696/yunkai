package com.zhuolin.yunkai.screen

import com.zhuolin.yunkai.service.screen.WriteAction
import com.zhuolin.yunkai.service.screen.validate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 写操作动作模型单测（M2a）：解析 / 缺省与钳制 / 非法输入收敛 / 越界校验
class WriteActionTest {

    // ── 解析 ──
    @Test fun parse_tap() {
        assertEquals(WriteAction.Tap(100, 200), WriteAction.fromJson("""{"type":"tap","x":100,"y":200}"""))
    }

    @Test fun parse_swipe_durDefaultAndCoerce() {
        assertEquals(
            WriteAction.Swipe(1, 2, 3, 4, 400),
            WriteAction.fromJson("""{"type":"swipe","x1":1,"y1":2,"x2":3,"y2":4}"""),
        )
        assertEquals(
            WriteAction.Swipe(1, 2, 3, 4, 5000),
            WriteAction.fromJson("""{"type":"swipe","x1":1,"y1":2,"x2":3,"y2":4,"durMs":5000}"""),
        )
        assertEquals(
            WriteAction.Swipe(1, 2, 3, 4, 200),
            WriteAction.fromJson("""{"type":"swipe","x1":1,"y1":2,"x2":3,"y2":4,"durMs":1}"""),
        )
    }

    @Test fun parse_input_emptyIsNull_andTruncatesTo200() {
        assertNull(WriteAction.fromJson("""{"type":"input","text":""}"""))
        val long = "字".repeat(201)
        val a = WriteAction.fromJson("""{"type":"input","text":"$long"}""")
        assertTrue(a is WriteAction.Input)
        assertEquals(200, (a as WriteAction.Input).text.length)
        assertEquals("字".repeat(200), a.text)
    }

    @Test fun parse_controlActions() {
        assertEquals(WriteAction.Back, WriteAction.fromJson("""{"type":"back"}"""))
        assertEquals(WriteAction.Home, WriteAction.fromJson("""{"type":"home"}"""))
        assertEquals(WriteAction.Finished, WriteAction.fromJson("""{"type":"finished"}"""))
    }

    // ── 非法输入收敛为 null ──
    @Test fun parse_unknownTypeIsNull() {
        assertNull(WriteAction.fromJson("""{"type":"shake"}"""))
        assertNull(WriteAction.fromJson("""{"x":1,"y":2}"""))
    }

    @Test fun parse_badJsonIsNull() {
        assertNull(WriteAction.fromJson("not-json"))
        assertNull(WriteAction.fromJson(""))
    }

    @Test fun parse_tapMissingCoordIsNull() {
        assertNull(WriteAction.fromJson("""{"type":"tap","x":100}"""))
        assertNull(WriteAction.fromJson("""{"type":"tap","x":"abc","y":200}"""))
    }

    // ── 校验 ──
    @Test fun validate_tapOutOfBounds() {
        val v = WriteAction.Tap(2000, 100).validate(1080, 2400)
        assertNotNull(v)
        assertTrue(v!!.contains("越界"))
    }

    @Test fun validate_tapInBounds() {
        assertNull(WriteAction.Tap(100, 100).validate(1080, 2400))
    }

    @Test fun validate_emptyInput() {
        assertEquals("input 文本为空", WriteAction.Input("").validate(1080, 2400))
        assertNull(WriteAction.Input("hi").validate(1080, 2400))
    }
}
