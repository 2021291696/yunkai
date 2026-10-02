package com.zhuolin.yunkai.store

// 门0 P10：迁移链连续性 JVM 测试——「链上有跳号/漏挂迁移」类缺陷的回归锚点
//（B3 崩溃缺陷即迁移盲区产物；MigrationTestHelper 全量用例受历史版本 schema JSON 缺失所限，
// 前置=schema 导出已开（app/schemas/），自 v5 起后续版本可逐版补 helper 用例）。
import androidx.room.migration.Migration
import org.junit.Assert.assertEquals
import org.junit.Test

class DbMigrationChainTest {

    @Test
    fun `迁移链连续无跳号`() {
        var expectedStart = 2
        for (m in DB_MIGRATIONS) {
            assertEquals("迁移链跳号：期望 start=$expectedStart，实际 ${m.startVersion}", expectedStart, m.startVersion)
            assertEquals("迁移步长必须 +1：${m.startVersion}→${m.endVersion}", expectedStart + 1, m.endVersion)
            expectedStart = m.endVersion
        }
    }

    @Test
    fun `链末版本等于当前 schema 版本`() {
        val last = DB_MIGRATIONS.maxByOrNull { it.endVersion } ?: throw AssertionError("迁移链为空")
        assertEquals(DB_VERSION, last.endVersion)
    }

    @Test
    fun `迁移链覆盖从 2 起的全部版本且无重复`() {
        val covered = DB_MIGRATIONS.map { it.startVersion }.sorted()
        assertEquals(listOf(2, 3, 4), covered)
        assertEquals(DB_MIGRATIONS.size, DB_MIGRATIONS.map { it.startVersion }.toSet().size)
    }

    @Test
    fun `全部迁移对象都是 Migration 实例（数组类型守卫）`() {
        DB_MIGRATIONS.forEach { assertEquals(true, it is Migration) }
    }
}
