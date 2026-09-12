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
interface ConvDao {
    @Query("SELECT * FROM conversations ORDER BY updated_at DESC")
    suspend fun list(): List<ConvEntity>

    @Insert
    suspend fun insert(e: ConvEntity): Long

    @Query("UPDATE conversations SET updated_at = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("UPDATE conversations SET title = :title WHERE id = :id AND title = '新对话'")
    suspend fun setTitleIfPlaceholder(id: Long, title: String)

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

    @Query("SELECT COUNT(*) FROM skills WHERE name = 'eli5'")
    suspend fun countEli5(): Int
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
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
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

@Database(
    entities = [
        ConvEntity::class, MsgEntity::class, SkillEntity::class,
        CoreBlockEntity::class, ArchivalEntity::class, TaskStateEntity::class,
    ],
    version = 3,
)
abstract class YunkaiDb : RoomDatabase() {
    abstract fun convDao(): ConvDao
    abstract fun msgDao(): MsgDao
    abstract fun skillDao(): SkillDao
    abstract fun coreBlockDao(): CoreBlockDao
    abstract fun archivalDao(): ArchivalDao

    companion object {
        @Volatile
        private var inst: YunkaiDb? = null

        fun instance(ctx: Context): YunkaiDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, YunkaiDb::class.java, "yunkai.db")
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build().also { inst = it }
        }
    }
}
