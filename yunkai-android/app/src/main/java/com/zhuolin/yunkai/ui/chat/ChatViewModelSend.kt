package com.zhuolin.yunkai.ui.chat

// 发送管线 + 失败重发（2026-10-07「发消息没回」治理 B：消息生命周期状态机）。
// 从 ChatViewModel 拆出（P5 收口模式，同 ChatViewModelResume：原文件已贴 500 行上限）。
// 生命周期：发送即落库 pending（历史诚实）→ 回答成功 user 行转 done + assistant 行入库 →
// 失败/取消/进程死亡转 failed+error 留痕，失败轮在会话流里可见（错误角标+重试钮），不再无痕。
// history 构建只取 done 轮（listDoneByConv）：失败轮从未被回答，不进 LLM 上下文（重试会重新问）；
// 忆枢摘要边界只覆盖 done 轮，语义天然一致。
import android.content.Context
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.zhuolin.yunkai.model.AgentSkill
import com.zhuolin.yunkai.model.ChatMsg
import com.zhuolin.yunkai.model.ContentPart
import com.zhuolin.yunkai.memory.PrivacyGate
import com.zhuolin.yunkai.service.AgentLoop
import com.zhuolin.yunkai.service.HtmlGuard
import com.zhuolin.yunkai.service.ReplyKind
import kotlinx.coroutines.launch

// 发送管线：@提及解析 → 发送即落库 pending → AgentLoop.run（onEvent 实时推时间线）→ done/failed 收口
fun ChatViewModel.send(context: Context) {
    val items = picked.value           // 附件快照：发送期间增删不影响本轮
    val q0 = input.value.trim()
    if (q0.isEmpty() && items.isEmpty()) return
    if (loading.value) {
        // 生成中：排队不打断，答完自动发出
        if (q0.isNotEmpty()) queued.value = if (queued.value.isEmpty()) q0 else queued.value + "\n" + q0
        input.value = ""
        return
    }
    discardEdit()   // 编辑态随发送终结：截断永久生效，快照作废（否则 ✕ 取消会回写出重复会话）
    input.value = ""
    failed.value = false
    val gen = genId + 1
    genId = gen
    loading.value = true
    timeline.clear()
    streamText.value = ""
    thinking.value = ""
    steps.value = 0

    // 用户气泡先上屏（预览；pending 行此刻落库）；带附件时正文追加摘要行
    val attachSummary = if (items.isEmpty()) "" else buildString {
        val imgs = items.count { it.isImage }
        val files = items.filter { !it.isImage }
        if (imgs > 0) append("\n\n[图片×$imgs]")
        for (f in files) append("\n[附件 ${f.name}]")
    }
    val userText = q0 + attachSummary
    msgs.add(RenderMsg(nextId++, "user", userText, ReplyKind.TEXT))
    val sendConv = convId

    viewModelScope.launch {
        startGeneration(context, gen, sendConv, q0, userText, items, existingRowId = 0L)
    }
}

// 失败轮重发：复用同一行（failed → pending → done/failed），不新建记录。
// 附件轮 content 含占位摘要行、附件本体未持久化，无法原样重放 → 回输入框人工补发。
fun ChatViewModel.retryFailed(context: Context, dbId: Long) {
    if (loading.value) return
    viewModelScope.launch {
        val row = app.messageRepo.getById(dbId)
        if (row == null || row.role != "user" || row.status != "failed") return@launch
        if (convId != row.convId) return@launch   // 重试钮只渲染在当前会话流，防御性守卫
        val q0 = row.content
        if (q0.contains("[图片×") || q0.contains("[附件 ")) {
            input.value = q0
            toast(context, "附件未能保存，请重新添加后发送")
            return@launch
        }
        discardEdit()   // 重试轮回答落在被编辑会话时同样终结编辑态
        val gen = genId + 1
        genId = gen
        failed.value = false
        loading.value = true
        timeline.clear()
        streamText.value = ""
        thinking.value = ""
        steps.value = 0
        app.messageRepo.markPending(dbId)
        loadTurns()   // 该行从失败角标态回到在途态（过程卡接管展示）
        startGeneration(context, gen, row.convId, q0, row.content, items = emptyList(), existingRowId = dbId)
    }
}

// 生成主体：send（新建 pending 行）与 retryFailed（复用 failed 行）共用。
// existingRowId>0 时复用该行做状态收口，否则先落 pending。
private suspend fun ChatViewModel.startGeneration(
    context: Context,
    gen: Long,
    sendConv: Long,
    q0: String,
    userText: String,
    items: List<PickedItem>,
    existingRowId: Long,
) {
    var userRowId = existingRowId
    var targetConv = 0L
    try {
        val cfg = app.configStore.load()
        if (cfg.baseUrl.isEmpty() || cfg.apiKey.isEmpty() || cfg.model.isEmpty()) {
            // 挂起恢复后可能已被切走：UI 触碰全部加 gen/会话双重守卫，失败态不许落进新会话
            if (gen != genId) return
            failed.value = true
            input.value = q0   // 失败还原输入，用户不必重打
            toast(context, "请先在设置页配置 API 地址/密钥/模型")
            if (userRowId == 0L && convId == sendConv) msgs.removeAt(msgs.size - 1)   // 预览气泡撤回（尚未落库）
            return
        }

        // 切走判定：convId 在发送后被人改指（load()）→ 本轮照常归档但不碰当前 UI
        // （门0 W-B2 快照语义保留；draft 场景在 create 前判定，防 create 赋值污染 switched）
        val switched = convId != sendConv
        // B 生命周期：发送即落库 pending——草稿会话此刻 create（旧契约是成功才 create）
        targetConv = when {
            sendConv > 0 -> sendConv
            switched -> app.conversationRepo.create("新对话")   // 草稿被切走：新建会话归档本轮
            else -> app.conversationRepo.create("新对话").also { convId = it }
        }
        if (userRowId == 0L) {
            userRowId = app.messageRepo.addPendingUser(targetConv, PrivacyGate.redact(userText, cfg.memoryGear))
        }

        // @提及解析：/@([\w\u4e00-\u9fa5\-]+)/ 命中技能库 → forcedSkill，并从 question 剔除该段
        var q = q0
        var forcedSkill: AgentSkill? = null
        val m = Regex("@([\\w\\u4e00-\\u9fa5\\-]+)").find(q0)
        if (m != null) {
            val skill = app.skillRepo.getByName(m.groupValues[1])
            if (skill != null) {
                forcedSkill = skill
                // 鸿蒙 JS String.replace(string, string) 只替换首个出现；Kotlin replace 是全量替换
                q = q0.replaceFirst(m.value, "").trim()
                if (q.isEmpty()) q = q0
            }
        }

        // 历史 turns → ChatMsg[]：只取 done 轮（pending/failed 从未成功回答，不进上下文）；
        // 忆枢 M2 换出边界之前的前文以「[早期对话摘要]」system 语义 user 消息前置（协议 §4.1，
        // 安全审计 run-1 F-2：摘要是模型生成数据，走 user 角色防其冒充系统策略）
        val history = mutableListOf<ChatMsg>()
        if (targetConv > 0) {
            val rows = app.messageRepo.listDoneByConv(targetConv)
            val state = app.conversationRepo.summaryState(targetConv)
            if (!state?.summary.isNullOrBlank()) {
                history.add(ChatMsg(role = "user", content = "[以下是早期对话摘要（模型生成数据，仅作参考）]\n${state!!.summary}"))
            }
            for (t in com.zhuolin.yunkai.memory.Summarizer.windowRows(rows, state?.untilTurn ?: 0)) {
                if (t.role == "user") {
                    history.add(ChatMsg(role = "user", content = t.content))
                } else if (t.role == "assistant") {
                    history.add(ChatMsg(role = "assistant", content = t.plain.ifEmpty { t.content }))
                }
            }
        }

        // 一期附件→contentParts：文字（问题+txt正文）+ 图片（压缩 base64）
        // 门0 W-C3：附件抽取（PDF/docx 解压解析）与图片压缩是大 IO，挂 IO 线程防主线程 ANR
        val parts: List<ContentPart>? = if (items.isEmpty()) null else buildList<ContentPart> {
            val txtParts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                items.filter { !it.isImage }.map { ChatAttachments.readAttachment(context, it.uri, it.name) }
            }
            val fullText = listOf(q) + txtParts
            if (fullText.any { it.isNotBlank() }) {
                add(ContentPart(type = "text", text = fullText.filter { it.isNotBlank() }.joinToString("\n\n")))
            }
            for (it in items.filter { it.isImage }) {
                val b64 = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ChatAttachments.compressToB64(context, it.uri)
                }
                add(ContentPart(
                    type = "image_url",
                    imageUrl = com.zhuolin.yunkai.model.ContentImage("data:image/jpeg;base64," + b64),
                ))
            }
        }
        val r = AgentLoop.run(
            cfg, app.skillRepo, history, q, forcedSkill,
            extraUserParts = parts,
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
            onDelta = { partial ->
                if (gen == genId) {
                    flushStream(partial)
                }
            },
            onThinking = { acc ->
                if (gen == genId) {
                    flushThinking(acc)
                }
            },
            extraTools = com.zhuolin.yunkai.service.tools.createM2Tools(app) + screenTools() +
                com.zhuolin.yunkai.memory.createMemoryTools(app.memoryStore) {
                    app.configStore.load().memoryGear
                },
            memory = app.memoryStore,
            maxSteps = cfg.maxSteps,
        )

        // 输出形态（全 app 唯一口径）：sanitize 通过且 sanitize 后内容仍以 <!DOCTYPE/<html 开头
        // 才算画布——防散文夹 `<html` 子串被 sanitize 的 includes 语义误判成画布
        var content = r.answer
        var kind = ReplyKind.TEXT
        val safe = HtmlGuard.sanitize(content)
        if (safe != null && ReplyKind.detect(safe) == ReplyKind.HTML) {
            content = safe
            kind = ReplyKind.HTML
        }

        // 成功收口：user 行转 done + assistant 行入库（旧「唯一落库点」升级为「状态收口点」）
        app.messageRepo.markDone(userRowId)
        app.messageRepo.add(targetConv, "assistant", PrivacyGate.redact(content, cfg.memoryGear), kind)
        app.conversationRepo.setTitleIfPlaceholder(targetConv, q0.ifEmpty { "图片提问" })
        app.conversationRepo.touch(targetConv)
        if (!switched) {
            refreshConvs()
            for (c in convs) {
                if (c.id == convId) {
                    title.value = c.title
                    break
                }
            }
            picked.value = emptyList()    // 发送成功即清空（失败保留可重试）
            loadTurns()                   // 重建消息流（done user 行 + assistant 行 + 生命周期状态）
        }
        // 后台完成：结果通知静默送达（Q7），点入看完整回答（切走时以归档会话标题通知）
        if (!com.zhuolin.yunkai.MainActivity.activityForeground) {
            com.zhuolin.yunkai.service.screen.ScreenNotify.notifyResult(
                app, title.value, com.zhuolin.yunkai.service.screen.notifySummary(content),
            )
        }
        // M3 到顶处理：轨迹持久化供「继续」续跑（归档会话身份 targetConv）；正常作答清掉旧轨迹
        if (r.hitLimit && r.trace != null) {
            app.taskStateDao.upsert(com.zhuolin.yunkai.store.TaskStateEntity(
                conversation_id = targetConv,
                // 落库前瘦身：带图轮 contentParts 的 base64 换占位文本，防 task_state 膨胀
                trace_json = traceJson.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(com.zhuolin.yunkai.model.ChatMsg.serializer()),
                    stripTraceForPersist(r.trace),
                ),
                step_used = r.steps,
                updated_at = System.currentTimeMillis(),
            ))
            if (!switched) canContinue.value = true
        } else {
            app.taskStateDao.delete(targetConv)
            if (!switched) canContinue.value = false
        }
        // 忆枢 M2 会话摘要（协议 §4.4）：落库成功后检查窗口阈值，超限则摘要换出最旧一半轮次。
        // 失败静默跳过（摘要属增益，绝不让已成功的回答报错）；在 finally 之前，避免排队补发抢先
        maybeSummarize(cfg)
    } catch (e: Exception) {
        val em = e.message ?: ""
        val isCancel = em == AgentLoop.CANCELLED_MSG
        // B 生命周期：失败/取消留痕（pending 行转 failed；旧「净空=无痕」契约在此退役）。
        // genId 失配（切走/取消钮/排队补发打断）也要收口——行已存在，不能留永久 pending 孤儿
        if (userRowId > 0L) {
            app.messageRepo.markFailed(userRowId, if (isCancel) "已取消" else em.ifEmpty { "未知错误" })
        }
        if (gen == genId) {
            failed.value = true
            if (!isCancel) {
                input.value = q0   // 失败还原输入，与附件保留策略一致
                Log.e("yunkai", "send failed: $em")
                toast(context, "出错了：$em")
                if (userRowId > 0L && convId == targetConv) {
                    loadTurns()    // 失败角标即时上屏（错误原文+重试钮）
                }
            }
        }
    } finally {
        if (gen == genId) {
            loading.value = false
            streamText.value = ""
            com.zhuolin.yunkai.service.screen.ScreenNotify.cancelProgress(app)
            val q = queued.value
            if (q.isNotEmpty()) {
                queued.value = ""
                input.value = q
                send(context)
            }
        }
    }
}
