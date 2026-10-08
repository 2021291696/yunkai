package com.zhuolin.yunkai.ui.chat

// M3 继续任务（自 ChatViewModel 拆出为扩展，门0 P5 单文件 500 行收口）：
// 取到顶轨迹续跑——system 就地重建（AgentLoop.seedMessages）、步数与软预算重置。
// 完成后照常落库；若再次到顶则更新轨迹（canContinue 保持），否则清掉 task_state。
// 依赖 ChatViewModel 的 internal 成员（app/genId/nextId/screenTools/loadTurns/refreshConvs/maybeSummarize）。
import android.content.Context
import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.zhuolin.yunkai.service.AgentLoop
import com.zhuolin.yunkai.service.HtmlGuard
import com.zhuolin.yunkai.service.ReplyKind

internal fun ChatViewModel.resumeTask(context: Context) {
    discardEdit()   // 续跑轮落在被编辑会话时终结编辑态（同 send 口径）
    if (loading.value || convId <= 0) return
    val gen = genId + 1
    genId = gen
    loading.value = true
    timeline.clear()
    streamText.value = ""
    thinking.value = ""
    steps.value = 0
    canContinue.value = false
    viewModelScope.launch {
        try {
            val cfg = app.configStore.load()
            // 挂起期间被切走：失败态不许落进新会话（与 send 管线配置早退同款守卫）
            if (gen != genId) return@launch
            if (cfg.baseUrl.isEmpty() || cfg.apiKey.isEmpty() || cfg.model.isEmpty()) {
                failed.value = true
                canContinue.value = true
                toast(context, "请先在设置页配置 API 地址/密钥/模型")
                return@launch
            }
            val ent = app.taskStateDao.get(convId) ?: return@launch
            val trace = traceJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(com.zhuolin.yunkai.model.ChatMsg.serializer()),
                ent.trace_json,
            )
            val r = AgentLoop.run(
                cfg, app.skillRepo, emptyList(), "", null,
                onEvent = { e ->
                    if (gen == genId) {
                        timeline.add(e)
                        if (e.kind == "tool_start") steps.value += 1
                        // 后台执行时每步回显进度通知（Q7）；前台有实时时间线，不打扰
                        if (!com.zhuolin.yunkai.MainActivity.activityForeground) {
                            com.zhuolin.yunkai.service.screen.ScreenNotify.notifyProgress(
                                app, "步骤 " + steps.value + "：" + e.toolName,
                            )
                        }
                    }
                },
                isCancelled = { gen != genId },
                extraTools = com.zhuolin.yunkai.service.tools.createM2Tools(app) + screenTools() +
                    com.zhuolin.yunkai.memory.createMemoryTools(app.memoryStore) {
                        app.configStore.load().memoryGear
                    },
                memory = app.memoryStore,
                maxSteps = cfg.maxSteps,
                seedMessages = trace,
            )
            if (gen != genId) return@launch
            var content = r.answer
            var kind = ReplyKind.TEXT
            val safe = HtmlGuard.sanitize(content)
            if (safe != null && ReplyKind.detect(safe) == ReplyKind.HTML) {
                content = safe
                kind = ReplyKind.HTML
            }
            app.messageRepo.add(convId, "assistant", com.zhuolin.yunkai.memory.PrivacyGate.redact(content, app.configStore.load().memoryGear), kind)
            app.conversationRepo.touch(convId)
            refreshConvs()
            if (r.hitLimit && r.trace != null) {
                app.taskStateDao.upsert(com.zhuolin.yunkai.store.TaskStateEntity(
                    conversation_id = convId,
                    // 落库前瘦身：带图轮 contentParts 的 base64 换占位文本，防 task_state 膨胀
                    trace_json = traceJson.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(com.zhuolin.yunkai.model.ChatMsg.serializer()),
                        stripTraceForPersist(r.trace),
                    ),
                    step_used = r.steps,
                    updated_at = System.currentTimeMillis(),
                ))
                canContinue.value = true
            } else {
                app.taskStateDao.delete(convId)
                canContinue.value = false
            }
            msgs.add(RenderMsg(nextId++, "assistant", content, kind, thinking = thinking.value, steps = steps.value))
            // 后台完成：结果通知静默送达（Q7）
            if (!com.zhuolin.yunkai.MainActivity.activityForeground) {
                com.zhuolin.yunkai.service.screen.ScreenNotify.notifyResult(
                    app, title.value, com.zhuolin.yunkai.service.screen.notifySummary(content),
                )
            }
            loadTurns()
            // 忆枢 M2：resume 续跑同样走摘要检查（长收尾回答恰易触发阈值）
            maybeSummarize(cfg)
        } catch (e: Exception) {
            if (gen == genId) {
                failed.value = true
                com.zhuolin.yunkai.service.screen.ScreenNotify.cancelProgress(app)
                // 失败不清 task_state：恢复「继续」可用态，用户可重试（轨迹仍在）
                canContinue.value = app.taskStateDao.get(convId) != null
                Log.e("yunkai", "resume failed: ${e.message}")
                toast(context, "出错了：${e.message}")
            }
        } finally {
            if (gen == genId) {
                loading.value = false
                streamText.value = ""
            }
        }
    }
}
