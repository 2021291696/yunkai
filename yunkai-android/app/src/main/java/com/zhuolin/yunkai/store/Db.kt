package com.zhuolin.yunkai.store

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery

// 数据库：yunkai.db（会话/消息/技能 + 忆枢记忆三表），字段与鸿蒙版 Db.ets 一一对应（含 messages.kind）。
// 鸿蒙版的 backfillKinds 存量回填不移植：Android 全新安装无存量；kind 列在 MessageRepo.add 时写入。
@Entity(tableName = "conversations")
data class ConvEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val created_at: Long,
    val updated_at: Long,
    // 忆枢协议 §1.3（M2 会话摘要用，本期只加列不接线）
    val summary: String? = null,
    // defaultValue 声明与 MIGRATION_2_3 的 ADD COLUMN ... DEFAULT 0 对齐（缺了真机迁移校验会炸）
    @ColumnInfo(defaultValue = "0") val summarized_until_turn: Int = 0,
    // 会话管理三态（2026-10-07 用户定案）：置顶（列表最上方分区）/ 归档（软删除，设置页归档区可恢复）
    @ColumnInfo(defaultValue = "0") val pinned: Int = 0,
    @ColumnInfo(defaultValue = "0") val archived: Int = 0,
    @ColumnInfo(defaultValue = "0") val archived_at: Long = 0,
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ConvEntity::class, parentColumns = ["id"],
        childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE,
    )],
)
data class MsgEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversation_id: Long,
    val role: String,
    val content: String,
    val plain: String = "",
    val turn_no: Int = 0,
    val created_at: Long,
    val kind: String? = null,
    // 消息生命周期（2026-10-07「发消息没回」治理 B）：pending=生成中 / done=完成 / failed=失败。
    // 发送即落库 pending（历史诚实），回答成功转 done；失败/取消/进程死亡转 failed 留痕可重发，
    // 替代旧「净空=失败无痕」契约。存量行经 MIGRATION_5_6 回填 done（旧契约下只存在成功轮）。
    @ColumnInfo(defaultValue = "done") val status: String = "done",
    @ColumnInfo(defaultValue = "") val error: String = "",
)

@Entity(tableName = "skills", indices = [androidx.room.Index(value = ["name"], unique = true)])
data class SkillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String?,
    val content: String?,
    val builtin: Int = 0,
)

// ===== 忆枢记忆三表（协议 §1.1/1.2/1.4）=====
// DEFAULT 值以 @ColumnInfo(defaultValue=...) 声明，与 Migration(2,3) 的 SQL 逐字对齐
// （Room 迁移后按实体校验实际 schema，缺声明会在真机上抛 "Migration didn't properly handle"）。
// 主键列在协议 SQL 基础上显式补 NOT NULL（SQLite 非整型主键不隐含非空；Room 对非空 Kotlin 类型要求它）。

@Entity(tableName = "core_blocks")
data class CoreBlockEntity(
    @PrimaryKey val name: String,   // 'human' | 'persona'
    @ColumnInfo(defaultValue = "") val content: String,
    val updated_at: Long,
)

@Entity(
    tableName = "archival",
    indices = [Index(name = "idx_archival_created", value = ["created_at"])],
)
data class ArchivalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    @ColumnInfo(defaultValue = "fact") val type: String,     // fact|episode|preference|identity
    @ColumnInfo(defaultValue = "agent") val source: String,  // agent|legacy-m2
    @ColumnInfo(defaultValue = "0") val hit_count: Int = 0,
    val created_at: Long,
    val updated_at: Long,
)

// task_state（继续按钮用，M2 接线；本期只建表）
@Entity(tableName = "task_state")
data class TaskStateEntity(
    @PrimaryKey val conversation_id: Long,
    val trace_json: String,
    val step_used: Int,
    val updated_at: Long,
)

@Dao
interface TaskStateDao {
    @Upsert
    suspend fun upsert(e: TaskStateEntity)

    @Query("SELECT * FROM task_state WHERE conversation_id = :convId LIMIT 1")
    suspend fun get(convId: Long): TaskStateEntity?

    @Query("DELETE FROM task_state WHERE conversation_id = :convId")
    suspend fun delete(convId: Long)
}

// 闪问旁路已下线（2026-09-19 重构：悬浮球=主对话入口，见 design-explorations 重构计划）。
// flash_sessions 表随 Migration 3→4 保留在存量用户设备上（不清不迁，无害残留）。

@Dao
interface ConvDao {
    // 主列表：排除已归档；置顶固定最上方（同区按最近更新排）
    @Query("SELECT * FROM conversations WHERE archived = 0 ORDER BY pinned DESC, updated_at DESC")
    suspend fun list(): List<ConvEntity>

    @Query("SELECT * FROM conversations WHERE archived = 1 ORDER BY archived_at DESC")
    suspend fun listArchived(): List<ConvEntity>

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Int)

    @Query("UPDATE conversations SET archived = 1, archived_at = :now WHERE id = :id")
    suspend fun archive(id: Long, now: Long)

    @Query("UPDATE conversations SET archived = 0, archived_at = 0, pinned = 0 WHERE id = :id")
    suspend fun restore(id: Long)

    // 30 天自动清扫（归档=软删除的唯一彻底删除出口）：messages 由外键 CASCADE
    @Query("SELECT id FROM conversations WHERE archived = 1 AND archived_at > 0 AND archived_at <= :deadline")
    suspend fun archivedIdsBefore(deadline: Long): List<Long>

    // 条件删除：归档途中被用户恢复（archived=0）时返回 0 不删——调用方据此决定是否清 task_state
    @Query("DELETE FROM conversations WHERE id = :id AND archived = 1")
    suspend fun deleteArchivedById(id: Long): Int

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ConvEntity?

    @Insert
    suspend fun insert(e: ConvEntity): Long

    @Query("UPDATE conversations SET updated_at = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("UPDATE conversations SET title = :title WHERE id = :id AND title = '新对话'")
    suspend fun setTitleIfPlaceholder(id: Long, title: String)

    // 忆枢 M2 会话摘要（协议 §4.4）：写摘要文本并前移换出边界
    @Query("UPDATE conversations SET summary = :summary, summarized_until_turn = :untilTurn WHERE id = :id")
    suspend fun updateSummary(id: Long, summary: String, untilTurn: Int)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long) // messages 由 CASCADE 级联删除
}

@Dao
interface MsgDao {
    @Insert
    suspend fun insert(e: MsgEntity): Long

    @Query("SELECT * FROM messages WHERE conversation_id = :convId ORDER BY id ASC")
    suspend fun listByConv(convId: Long): List<MsgEntity>

    @Query("DELETE FROM messages WHERE conversation_id = :convId AND id >= :fromId")
    suspend fun deleteFrom(convId: Long, fromId: Long)

    @Query("SELECT content FROM messages WHERE conversation_id = :convId AND turn_no = :turnNo AND role = 'assistant' LIMIT 1")
    suspend fun getHtmlByTurn(convId: Long, turnNo: Int): String?

    // 消息生命周期（B 治理）：单行读取 / 状态迁移 / 开局 pending 清扫 / 历史只取完成轮
    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MsgEntity?

    @Query("UPDATE messages SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, error: String)

    @Query("UPDATE messages SET status = 'failed', error = :error WHERE status = 'pending'")
    suspend fun failAllPending(error: String): Int

    @Query("SELECT * FROM messages WHERE conversation_id = :convId AND status = 'done' ORDER BY id ASC")
    suspend fun listDoneByConv(convId: Long): List<MsgEntity>

    // conversation_search（忆枢协议 §3.5）：多词 OR LIKE 动态拼 SQL（词数可变，Room 编译期
    // @Query 表达不了；词由 MemoryStore.searchTerms 切出，RoomMemoryStore 负责绑定 %词%）
    @RawQuery(observedEntities = [MsgEntity::class])
    suspend fun rawSearch(query: SupportSQLiteQuery): List<MsgEntity>
}

@Dao
interface SkillDao {
    @Query("SELECT * FROM skills ORDER BY id ASC")
    suspend fun list(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): SkillEntity?

    // UNIQUE 冲突直接抛异常，由 SkillRepo 收敛为 -1（UI 依赖该契约判断重名）
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(e: SkillEntity): Long

    @Upsert
    suspend fun upsert(e: SkillEntity): Long

    @Delete
    suspend fun delete(e: SkillEntity)

    @Query("SELECT COUNT(*) FROM skills WHERE name = :name")
    suspend fun countByName(name: String): Int
}

@Dao
interface CoreBlockDao {
    @Query("SELECT * FROM core_blocks WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): CoreBlockEntity?

    @Upsert
    suspend fun upsert(e: CoreBlockEntity)
}

@Dao
interface ArchivalDao {
    @Insert
    suspend fun insert(e: ArchivalEntity): Long

    @Query("SELECT * FROM archival ORDER BY id ASC")
    suspend fun listAll(): List<ArchivalEntity>

    @Query("SELECT * FROM archival WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<ArchivalEntity>

    // M1c 管理页 + §3.4 hit_count 写回
    @Query("DELETE FROM archival WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM archival")
    suspend fun clearAll(): Int

    @Query("SELECT COUNT(*) FROM archival")
    suspend fun count(): Int

    // 忆枢迁移幂等护栏：已存在 legacy-m2 行则跳过重迁（门0 审查项）
    @Query("SELECT COUNT(*) FROM archival WHERE source = 'legacy-m2'")
    suspend fun countLegacy(): Int

    // hit_count 批量 +1（不动 updated_at：命中统计不是内容变更）
    @Query("UPDATE archival SET hit_count = hit_count + 1 WHERE id IN (:ids)")
    suspend fun incrementHits(ids: List<Long>)
}

// schema version 3：忆枢 M1b（core_blocks/archival/task_state 建表 + conversations 加摘要两列）。
// MIGRATION_2_3 按 §1.1–1.4 逐字建表；fallbackToDestructiveMigration 仅留作更早版本兜底
// （自用 app 开发期 v1/v2 存量直接毁库重建），v2→3 正式用户数据走显式迁移不毁库。
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS core_blocks (" +
                "name TEXT NOT NULL PRIMARY KEY, " +
                "content TEXT NOT NULL DEFAULT '', " +
                "updated_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS archival (" +
                // Room 校验按字面比对：必须显式 NOT NULL（同 3→4 的 flash_sessions，缺了存量升级启动即崩，门0 B1）
                "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                "content TEXT NOT NULL, " +
                "type TEXT NOT NULL DEFAULT 'fact', " +
                "source TEXT NOT NULL DEFAULT 'agent', " +
                "hit_count INTEGER NOT NULL DEFAULT 0, " +
                "created_at INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_archival_created ON archival(created_at)")
        db.execSQL("ALTER TABLE conversations ADD COLUMN summary TEXT")
        db.execSQL("ALTER TABLE conversations ADD COLUMN summarized_until_turn INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS task_state (" +
                "conversation_id INTEGER NOT NULL PRIMARY KEY, " +
                "trace_json TEXT NOT NULL, " +
                "step_used INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL)"
        )
    }
}

// schema version 4：M2b 闪问存档表（flash_sessions）。沿用 MIGRATION_2_3 风格：显式 CREATE TABLE
// 保住存量对话/记忆数据，不毁库（fallbackToDestructiveMigration 仍只兜更早版本）。
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS flash_sessions (" +
                // Room 校验按字面比对：必须显式 NOT NULL（INTEGER PRIMARY KEY 语义非空但 schema 报 notNull=false）
                "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                "question TEXT NOT NULL, " +
                "answer TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL)"
        )
    }
}
// schema version 5：闪问旁路下线（悬浮球=主对话入口重构）。空迁移：只推进版本号，
// flash_sessions 表与旧记录按 D4 定案保留在用户设备上（不清不迁，Room 不校验多余表，无害残留）。
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // intentionally empty — keep legacy flash_sessions table & rows
    }
}


// schema version 6：消息生命周期（2026-10-07「发消息没回」治理 B）——messages 加 status/error 两列。
// 存量行回填 done（旧「净空」契约下库里只存在成功轮）；pending/failed 由新发送管线产生。
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN status TEXT NOT NULL DEFAULT 'done'")
        db.execSQL("ALTER TABLE messages ADD COLUMN error TEXT NOT NULL DEFAULT ''")
    }
}


// 门0 P10：迁移链收敛单点——新增版本时往数组追加即可，连续性由 DbMigrationChainTest 钉死
// schema version 7：会话管理（2026-10-07 用户定案）——置顶/归档/归档时间三列，存量回填默认值
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE conversations ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE conversations ADD COLUMN archived_at INTEGER NOT NULL DEFAULT 0")
    }
}


// 门0 P10：迁移链收敛单点——新增版本时往数组追加即可，连续性由 DbMigrationChainTest 钉死
internal val DB_MIGRATIONS = arrayOf(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)

// 当前 schema 版本（门0 P10：单一定义点，测试与 builder 同源）
internal const val DB_VERSION = 7

@Database(
    entities = [
        ConvEntity::class, MsgEntity::class, SkillEntity::class,
        CoreBlockEntity::class, ArchivalEntity::class, TaskStateEntity::class,
    ],
    version = DB_VERSION,
)
abstract class YunkaiDb : RoomDatabase() {
    abstract fun convDao(): ConvDao
    abstract fun msgDao(): MsgDao
    abstract fun skillDao(): SkillDao
    abstract fun coreBlockDao(): CoreBlockDao
    abstract fun archivalDao(): ArchivalDao
    abstract fun taskStateDao(): TaskStateDao

    companion object {
        @Volatile
        private var inst: YunkaiDb? = null

        fun instance(ctx: Context): YunkaiDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, YunkaiDb::class.java, "yunkai.db")
                .addMigrations(*DB_MIGRATIONS)
                .fallbackToDestructiveMigration()
                .build().also { inst = it }
        }
    }
}
