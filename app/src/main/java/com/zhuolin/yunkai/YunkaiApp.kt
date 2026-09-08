package com.zhuolin.yunkai

import android.app.Application
import android.util.Log
import com.zhuolin.yunkai.store.ConfigStore
import com.zhuolin.yunkai.store.ConversationRepo
import com.zhuolin.yunkai.store.MessageRepo
import com.zhuolin.yunkai.store.SkillRepo
import com.zhuolin.yunkai.store.YunkaiDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Application：Room/DataStore/Repo 单例持有 + 首启播种内置技能
class YunkaiApp : Application() {
    val db: YunkaiDb by lazy { YunkaiDb.instance(this) }
    val configStore: ConfigStore by lazy { ConfigStore(this) }
    val skillRepo: SkillRepo by lazy { SkillRepo(db.skillDao()) }
    val conversationRepo: ConversationRepo by lazy { ConversationRepo(db.convDao()) }
    val messageRepo: MessageRepo by lazy { MessageRepo(db.msgDao()) }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 首启播种内置 eli5 技能（幂等：无 eli5 行才插；失败不影响启动）
        appScope.launch {
            try {
                val text = resources.openRawResource(R.raw.skill_eli5).readBytes().decodeToString()
                skillRepo.ensureBuiltin(text)
            } catch (e: Exception) {
                Log.e(TAG, "seedIfEmpty failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "yunkai"
    }
}
