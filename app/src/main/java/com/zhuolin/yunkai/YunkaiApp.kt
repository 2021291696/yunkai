package com.zhuolin.yunkai

import android.app.Application
import android.util.Log
import com.zhuolin.yunkai.memory.Migrator
import com.zhuolin.yunkai.memory.MemoryStore
import com.zhuolin.yunkai.memory.RoomMemoryStore
import com.zhuolin.yunkai.store.ConfigStore
import com.zhuolin.yunkai.store.ConversationRepo
import com.zhuolin.yunkai.store.MessageRepo
import com.zhuolin.yunkai.store.SkillRepo
import com.zhuolin.yunkai.store.YunkaiDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

// Application：Room/DataStore/Repo 单例持有 + 首启播种内置技能 + 忆枢旧记忆迁移
class YunkaiApp : Application() {
    val db: YunkaiDb by lazy { YunkaiDb.instance(this) }
    val configStore: ConfigStore by lazy { ConfigStore(this) }
    val skillRepo: SkillRepo by lazy { SkillRepo(db.skillDao()) }
    val conversationRepo: ConversationRepo by lazy { ConversationRepo(db.convDao()) }
    val messageRepo: MessageRepo by lazy { MessageRepo(db.msgDao()) }
    val memoryStore: MemoryStore by lazy { RoomMemoryStore(db) }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 二期 PDF 抽取：PdfBox-Android 需要初始化资源加载器（字体/编码表），否则抽文本抛错
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
        // 首启播种内置 eli5 技能（幂等：无 eli5 行才插；失败不影响启动）
        appScope.launch {
            try {
                val text = resources.openRawResource(R.raw.skill_eli5).readBytes().decodeToString()
                skillRepo.ensureBuiltin(text)
            } catch (e: Exception) {
                Log.e(TAG, "seedIfEmpty failed: ${e.message}")
            }
        }
        // 忆枢旧数据迁移（协议 §1.5，一次性）：filesDir/agent_memory.json 存在 → KV 迁入
        // archival（source=legacy-m2）→ 原文件改名 .bak（损坏文件也改名，视为空库）。
        // 幂等：.bak 已存在即跳过，防重复迁移出重复行；失败不影响启动（下轮再试）。
        appScope.launch {
            try {
                val legacy = File(filesDir, "agent_memory.json")
                val bak = File(filesDir, "agent_memory.json.bak")
                if (legacy.exists() && !bak.exists()) {
                    val rows = Migrator.migrate(legacy.readText())
                    memoryStore.migrationWrite(rows)
                    if (!legacy.renameTo(bak)) {
                        Log.e(TAG, "legacy memory rename failed: $legacy")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "migrate legacy memory failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "yunkai"
    }
}
