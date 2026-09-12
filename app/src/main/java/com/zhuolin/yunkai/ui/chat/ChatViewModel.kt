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
import com.zhuolin.yunkai.model.ContentImage
import com.zhuolin.yunkai.model.ContentPart
import com.zhuolin.yunkai.service.ReplyKind
import kotlinx.coroutines.launch

// 消息流渲染单元：role='user'|'assistant'；kind='html'|'text'（画布卡/文本气泡）
data class RenderMsg(val id: Long, val role: String, val content: String, val kind: String, val thinking: String = "", val steps: Int = 0, val thinkingCollapsed: Boolean = true)

// 对话页状态：发送管线走 AgentLoop（onEvent 实时推时间线；取消旗标 genId 失配即停）
// 一期附件：图片（多图≤5，压缩后走多模态 contentParts）或 txt（单文件≤5MB/正文3万字截断）
data class PickedItem(
    val uri: String,
    val name: String,
    val mime: String,
    val isImage: Boolean,
) {
    val label: String
        get() = name
}

class ChatViewModel(private val app: YunkaiApp) : ViewModel() {
    val msgs = mutableStateListOf<RenderMsg>()
    val turns = mutableStateListOf<Msg>()
    val convs = mutableStateListOf<com.zhuolin.yunkai.model.Conv>()
    val timeline = mutableStateListOf<LoopEvent>()
    var input = mutableStateOf("")
    var loading = mutableStateOf(false)
    var title = mutableStateOf("")
    var showHistory = mutableStateOf(false)
    // B1 流式：非空=正在流式生成（值=当前累计文本）；完成/取消/失败后清空
    var streamText = mutableStateOf("")
    var thinking = mutableStateOf("")   // 本轮 reasoning_content 累计（思考流）
    var steps = mutableStateOf(0)       // 工具步数
    var queued = mutableStateOf("")     // 生成中排队的下一问
    var failed = mutableStateOf(false)  // 本轮失败/取消：过程卡保持展开
    var picked = mutableStateOf(listOf<PickedItem>())
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
        // 换会话即作废在途 send（genId 失配 → 该轮 return）：否则旧轮回来后会把回答写进新会话，
        // 且 nextId 已归 1 会和 loadTurns 写回的 id 撞车 → LazyColumn("重复 key") 崩。
        // 鸿蒙版靠 replaceUrl 换新页实例天然规避，移植成单 Activity + 原地换会话后必须显式作废
        genId += 1
        loading.value = false
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

    // ===== 一期附件 =====
    // 鏀寔鐨勬枃浠跺悗缂€锛堜簩鏈燂紱PDF 浠呮枃瀛楀瀷锛屾壂鎻忎欢鍦ㄦ娊鍙栧悗鎻愮ず鏈彇鍒版枃鏈級
    fun isSupportedFile(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".csv") ||
            n.endsWith(".docx") || n.endsWith(".xlsx") || n.endsWith(".pdf")
    }

    fun addPicked(item: PickedItem) {
        if (picked.value.size >= 5) { return }          // 上限 5（图片与 txt 合计）
        if (picked.value.any { it.uri == item.uri }) { return }
        picked.value = picked.value + item
    }

    fun removePicked(uri: String) {
        picked.value = picked.value.filter { it.uri != uri }
    }

    // uri 图片 → 长边 1280 JPEG(85) → base64（同 Wallpaper 的采样探测思路，防 12MP 原图撑爆请求）
    private fun compressToB64(context: Context, uri: String): String {
        val resolver = context.contentResolver
        val input = resolver.openInputStream(android.net.Uri.parse(uri)) ?: throw Exception("读取图片失败")
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeStream(input, null, bounds)
        input.close()
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw Exception("图片解码失败")
        var sample = 1
        val maxSide = 1280
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val input2 = resolver.openInputStream(android.net.Uri.parse(uri)) ?: throw Exception("图片解码失败")
        val bmp = android.graphics.BitmapFactory.decodeStream(input2, null,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        input2.close()
            ?: throw Exception("图片解码失败")
        val safeBmp = bmp ?: throw Exception("图片解码失败")
        val out = java.io.ByteArrayOutputStream()
        safeBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    // 附件读取（≤5MB；正文截断 3 万字并标注）：txt 直读；docx/xlsx/pdf 走 DocTextExtractor
    // （二期；鸿蒙侧对应 docx/xlsx，PDF 挂 backlog）
    private fun readAttachment(context: Context, uri: String, name: String): String {
        val input = context.contentResolver.openInputStream(android.net.Uri.parse(uri))
        val bytes = input?.readBytes() ?: throw Exception("读取文件失败")
        input.close()
        if (bytes.size > 5 * 1024 * 1024) throw Exception("文件超过 5MB 上限")
        var text = com.zhuolin.yunkai.service.DocTextExtractor.extract(name, bytes)
        if (text.isBlank()) throw Exception("未能从该文件抽取到文本（扫描件或空文档）")
        if (text.length > 30000) {
            text = text.substring(0, 30000) + "\n…（已截断）"
        }
        return text
    }

    fun cancelLoading() {
        genId += 1
        loading.value = false
    }

    // ✕ 撤回排队：文字退回输入框
    fun clearQueued() {
        input.value = queued.value
        queued.value = ""
    }

    // 立即：打断当前回答，马上发排队那句
    fun sendQueuedNow(context: Context) {
        if (queued.value.isEmpty()) return
        input.value = queued.value
        queued.value = ""
        cancelLoading()
        send(context)
    }

    // 编辑已发送消息：截断删除该条及其之后，内容回填输入框（改完重发即重新生成）
    fun startEdit(index: Int) {
        if (loading.value || index < 0 || index >= turns.size) return
        viewModelScope.launch {
            val fromId = turns[index].id
            val text = msgs.getOrNull(index)?.content ?: return@launch
            app.messageRepo.deleteFromPosition(convId, fromId)
            for (k in turns.size - 1 downTo index) turns.removeAt(k)
            for (k in msgs.size - 1 downTo index) msgs.removeAt(k)
            input.value = text
        }
    }

    // 发送管线：@提及解析 → 构建 history → AgentLoop.run（onEvent 实时推时间线）→ 画布卡/气泡入库渲染。
    // 净空语义：唯一落库点=真实回答成功之后（取消/抛错/写库失败都不留记录）；
    // 草稿会话此刻才 create，user 行先于 assistant 行写入
    fun send(context: Context) {
        val items = picked.value           // 附件快照：发送期间增删不影响本轮
        val q0 = input.value.trim()
        if (q0.isEmpty() && items.isEmpty()) return
        if (loading.value) {
            // 生成中：排队不打断，答完自动发出
            if (q0.isNotEmpty()) queued.value = if (queued.value.isEmpty()) q0 else queued.value + "\n" + q0
            input.value = ""
            return
        }
        input.value = ""
        failed.value = false
        val gen = genId + 1
        genId = gen
        loading.value = true
        timeline.clear()
        streamText.value = ""
        thinking.value = ""
        steps.value = 0

        // 用户气泡先上屏（草稿会话此时尚未落库）；带附件时正文追加摘要行
        val attachSummary = if (items.isEmpty()) "" else buildString {
            val imgs = items.count { it.isImage }
            val files = items.filter { !it.isImage }
            if (imgs > 0) append("\n\n[图片×$imgs]")
            for (f in files) append("\n[附件 ${f.name}]")
        }
        val userText = q0 + attachSummary
        msgs.add(RenderMsg(nextId++, "user", userText, ReplyKind.TEXT))

        viewModelScope.launch {
            try {
                val cfg = app.configStore.load()
                if (cfg.baseUrl.isEmpty() || cfg.apiKey.isEmpty() || cfg.model.isEmpty()) {
                    failed.value = true
                    input.value = q0   // 失败还原输入，用户不必重打
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

                // 一期附件→contentParts：文字（问题+txt正文）+ 图片（压缩 base64）
                val parts: List<ContentPart>? = if (items.isEmpty()) null else buildList<ContentPart> {
                    val txtParts = items.filter { !it.isImage }.map { readAttachment(context, it.uri, it.name) }
                    val fullText = listOf(q) + txtParts
                    if (fullText.any { it.isNotBlank() }) {
                        add(ContentPart(type = "text", text = fullText.filter { it.isNotBlank() }.joinToString("\n\n")))
                    }
                    for (it in items.filter { it.isImage }) {
                        add(ContentPart(
                            type = "image_url",
                            imageUrl = ContentImage("data:image/jpeg;base64," + compressToB64(context, it.uri)),
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
                        }
                    },
                    isCancelled = { gen != genId },
                    onDelta = { partial ->
                        if (gen == genId) {
                            streamText.value = partial
                        }
                    },
                    onThinking = { acc ->
                        if (gen == genId) {
                            thinking.value = acc
                        }
                    },
                    extraTools = com.zhuolin.yunkai.service.tools.createM2Tools(app),
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
                app.messageRepo.add(convId, "user", userText, ReplyKind.TEXT)
                app.messageRepo.add(convId, "assistant", content, kind)
                app.conversationRepo.setTitleIfPlaceholder(convId, q0.ifEmpty { "图片提问" })
                app.conversationRepo.touch(convId)
                refreshConvs()
                for (c in convs) {
                    if (c.id == convId) {
                        title.value = c.title
                        break
                    }
                }
                msgs.add(RenderMsg(nextId++, "assistant", content, kind, thinking = thinking.value, steps = steps.value))
                picked.value = emptyList()    // 发送成功即清空（失败保留可重试）
                loadTurns()
            } catch (e: Exception) {
                if (gen == genId) {
                    failed.value = true
                    val em = e.message ?: ""
                    if (em != AgentLoop.CANCELLED_MSG) {
                        input.value = q0   // 失败还原输入，与附件保留策略一致
                        Log.e("yunkai", "send failed: $em")
                        toast(context, "出错了：$em")
                    }
                }
            } finally {
                if (gen == genId) {
                    loading.value = false
                    streamText.value = ""
                    val q = queued.value
                    if (q.isNotEmpty()) {
                        queued.value = ""
                        input.value = q
                        send(context)
                    }
                }
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
