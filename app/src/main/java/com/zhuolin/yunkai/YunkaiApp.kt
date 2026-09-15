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
    val taskStateDao by lazy { db.taskStateDao() }   // M3 继续任务：到顶轨迹存取

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 写操作计划状态机单例（M2a）：UI（计划卡）与工具（propose_plan）共享同一实例，
    // 敏感检测与单动作执行在此注入真实实现（无障碍未开启一律 fail-closed 视为敏感/失败）。
    val writePlanExecutor: com.zhuolin.yunkai.service.screen.WritePlanExecutor by lazy {
        com.zhuolin.yunkai.service.screen.WritePlanExecutor(
            scope = appScope,
            sensitiveChecker = sens@{ _ ->
                val svc = com.zhuolin.yunkai.service.screen.ScreenSenseService.instance
                    ?: return@sens "读不到当前页面（无障碍未开启），保守视为敏感"
                val cur = svc.readForeground()
                    ?: return@sens "读不到当前页面，保守视为敏感"
                if (com.zhuolin.yunkai.service.screen.ScreenBlacklist.isBlocked(
                        cur.first, configStore.getUserBlacklist())) {
                    return@sens "当前应用在隐私黑名单"
                }
                val hit = cur.second.firstOrNull {
                    it.isPassword || com.zhuolin.yunkai.service.screen.ScreenGuard.hasSensitive(it.text)
                }
                hit?.let { "当前页面命中敏感内容（${if (it.isPassword) "密码框" else "敏感词"}）" }
            },
            executor = exec@{ action ->
                val svc = com.zhuolin.yunkai.service.screen.ScreenSenseService.instance
                    ?: return@exec false
                when (action) {
                    is com.zhuolin.yunkai.service.screen.WriteAction.Tap ->
                        svc.performTap(action.x.toFloat(), action.y.toFloat())
                    is com.zhuolin.yunkai.service.screen.WriteAction.Swipe ->
                        svc.performSwipe(action.x1.toFloat(), action.y1.toFloat(), action.x2.toFloat(), action.y2.toFloat(), action.durMs)
                    is com.zhuolin.yunkai.service.screen.WriteAction.Input -> svc.setTextFocused(action.text)
                    is com.zhuolin.yunkai.service.screen.WriteAction.Back -> svc.pressBack()
                    is com.zhuolin.yunkai.service.screen.WriteAction.Home -> svc.pressHome()
                    is com.zhuolin.yunkai.service.screen.WriteAction.Finished -> true
                }
            },
        )
    }

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
                    // 幂等护栏（门0 审查项）：库里已有 legacy-m2 行 = 上次迁移部分成功，跳过写入防重复
                    if (!memoryStore.hasLegacyRows()) {
                        memoryStore.migrationWrite(rows)
                    }
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
