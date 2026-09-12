package com.zhuolin.yunkai.store

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

// 数据库：yunkai.db 三张表（会话/消息/技能），字段与鸿蒙版 Db.ets 一一对应（含 messages.kind）。
// 鸿蒙版的 backfillKinds 存量回填不移植：Android 全新安装无存量；kind 列在 MessageRepo.add 时写入。
@Entity(tableName = "conversations")
data class ConvEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val created_at: Long,
    val updated_at: Long,
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

// schema version 2：对齐鸿蒙版 v2（messages.kind 升级）。
// fallbackToDestructiveMigration 自用 app 开发期允许毁库重建（M2 的 B3/B4 会加表）；
// 若日后有正式用户数据再换显式 Migration
@Database(entities = [ConvEntity::class, MsgEntity::class, SkillEntity::class], version = 2)
abstract class YunkaiDb : RoomDatabase() {
    abstract fun convDao(): ConvDao
    abstract fun msgDao(): MsgDao
    abstract fun skillDao(): SkillDao

    companion object {
        @Volatile
        private var inst: YunkaiDb? = null

        fun instance(ctx: Context): YunkaiDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, YunkaiDb::class.java, "yunkai.db")
                .fallbackToDestructiveMigration()
                .build().also { inst = it }
        }
    }
}
