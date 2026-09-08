package com.zhuolin.yunkai.ui.chat

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhuolin.yunkai.YunkaiApp
import com.zhuolin.yunkai.model.AgentSkill
import com.zhuolin.yunkai.model.ChatMsg
import com.zhuolin.yunkai.model.Msg
import com.zhuolin.yunkai.service.AgentLoop
import com.zhuolin.yunkai.service.HtmlGuard
import com.zhuolin.yunkai.service.LoopEvent
import com.zhuolin.yunkai.service.ReplyKind
import kotlinx.coroutines.launch

// 消息流渲染单元：role='user'|'assistant'；kind='html'|'text'（画布卡/文本气泡）
data class RenderMsg(val id: Long, val role: String, val content: String, val kind: String)

// 对话页状态：发送管线走 AgentLoop（onEvent 实时推时间线；取消旗标 genId 失配即停）
class ChatViewModel(private val app: YunkaiApp) : ViewModel() {
    val msgs = mutableStateListOf<RenderMsg>()
    val turns = mutableStateListOf<Msg>()
    val convs = mutableStateListOf<com.zhuolin.yunkai.model.Conv>()
    val timeline = mutableStateListOf<LoopEvent>()
    var input = mutableStateOf("")
    var loading = mutableStateOf(false)
    var title = mutableStateOf("")
    var showHistory = mutableStateOf(false)
    var convId: Long = -1L
    private var nextId: Long = 1L
    private var genId: Long = 0L
    private var initialized = false

    // 首次组合才加载：从设置页/画布页返回时 NavHost 会重组 chat，
    // 重复 init 会把进行中的会话重置成草稿（鸿蒙版 router.back 不重初始化，语义对齐）
    fun initIfNeed(id: Long) {
        if (initialized) return
        load(id)
    }

    private fun load(id: Long) {
        initialized = true
        convId = id
        msgs.clear()
        turns.clear()
        timeline.clear()
        nextId = 1
        viewModelScope.launch {
            refreshConvs()
            if (convId <= 0) {
                title.value = "新对话"
                return@launch
            }
            for (c in convs) {
                if (c.id == convId) {
                    title.value = c.title
                    break
                }
            }
            loadTurns()
        }
    }

    private suspend fun loadTurns() {
        val past = app.messageRepo.listByConv(convId)
        turns.clear()
        turns.addAll(past)
        msgs.clear()
        for (m in past) {
            msgs.add(RenderMsg(
                id = nextId++, role = m.role, content = m.content,
                kind = if (m.role == "assistant") ReplyKind.detect(m.content) else ReplyKind.TEXT,
            ))
        }
    }

    private suspend fun refreshConvs() {
        val list = app.conversationRepo.list()
        convs.clear()
        convs.addAll(list)
    }

    fun toast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    fun cancelLoading() {
        genId += 1
        loading.value = false
    }

    // 发送管线：@提及解析 → 构建 history → AgentLoop.run（onEvent 实时推时间线）→ 画布卡/气泡入库渲染。
    // 净空语义：唯一落库点=真实回答成功之后（取消/抛错/写库失败都不留记录）；
    // 草稿会话此刻才 create，user 行先于 assistant 行写入
    fun send(context: Context) {
        val q0 = input.value.trim()
        if (q0.isEmpty() || loading.value) return
        input.value = ""
        val gen = genId + 1
        genId = gen
        loading.value = true
        timeline.clear()

        // 用户气泡先上屏（草稿会话此时尚未落库）
        msgs.add(RenderMsg(nextId++, "user", q0, ReplyKind.TEXT))

        viewModelScope.launch {
            try {
                val cfg = app.configStore.load()
                if (cfg.baseUrl.isEmpty() || cfg.apiKey.isEmpty() || cfg.model.isEmpty()) {
                    toast(context, "请先在设置页配置 API 地址/密钥/模型")
                    msgs.removeAt(msgs.size - 1)
                    return@launch
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

                // 历史 turns → ChatMsg[]（assistant 用 plain 防大 HTML 撑上下文）
                val history = mutableListOf<ChatMsg>()
                if (convId > 0) {
                    for (t in app.messageRepo.listByConv(convId)) {
                        if (t.role == "user") {
                            history.add(ChatMsg(role = "user", content = t.content))
                        } else if (t.role == "assistant") {
                            history.add(ChatMsg(role = "assistant", content = t.plain.ifEmpty { t.content }))
                        }
                    }
                }

                val r = AgentLoop.run(
                    cfg, app.skillRepo, history, q, forcedSkill,
                    onEvent = { e ->
                        if (gen == genId) {
                            timeline.add(e)
                        }
                    },
                    isCancelled = { gen != genId },
                )
                if (gen != genId) return@launch

                // 输出形态（全 app 唯一口径）：sanitize 通过且 sanitize 后内容仍以 <!DOCTYPE/<html 开头
                // 才算画布——防散文夹 `<html` 子串被 sanitize 的 includes 语义误判成画布；
                // 入库 content=safe，reload 时 ReplyKind.detect 与本次判定天然一致；否则纯文本气泡
                var content = r.answer
                var kind = ReplyKind.TEXT
                val safe = HtmlGuard.sanitize(content)
                if (safe != null && ReplyKind.detect(safe) == ReplyKind.HTML) {
                    content = safe
                    kind = ReplyKind.HTML
                }

                if (convId <= 0) {
                    convId = app.conversationRepo.create("新对话")
                }
                app.messageRepo.add(convId, "user", q0, ReplyKind.TEXT)
                app.messageRepo.add(convId, "assistant", content, kind)
                app.conversationRepo.setTitleIfPlaceholder(convId, q0)
                app.conversationRepo.touch(convId)
                refreshConvs()
                for (c in convs) {
                    if (c.id == convId) {
                        title.value = c.title
                        break
                    }
                }
                msgs.add(RenderMsg(nextId++, "assistant", content, kind))
                loadTurns()
            } catch (e: Exception) {
                val em = e.message ?: ""
                if (em != AgentLoop.CANCELLED_MSG) {
                    Log.e("yunkai", "send failed: $em")
                    toast(context, "出错了：$em")
                }
            } finally {
                if (gen == genId) loading.value = false
            }
        }
    }

    // ===== 抽屉动作 =====

    suspend fun openHistory() {
        refreshConvs()
        showHistory.value = true
    }

    fun startNewConversation() {
        showHistory.value = false
        if (convId <= 0) return
        load(-1L)
    }

    fun openConversation(id: Long) {
        showHistory.value = false
        if (id == convId) return
        load(id)
    }

    suspend fun doDelete(id: Long, context: Context) {
        app.conversationRepo.remove(id)
        toast(context, "已删除")
        refreshConvs()
        if (id == convId) {
            load(-1L)
        }
    }
}
