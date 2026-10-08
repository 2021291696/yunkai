package com.zhuolin.yunkai.ui.chat

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhuolin.yunkai.YunkaiApp
import com.zhuolin.yunkai.model.ChatMsg
import com.zhuolin.yunkai.model.ContentPart
import com.zhuolin.yunkai.model.Msg
import com.zhuolin.yunkai.service.LoopEvent
import com.zhuolin.yunkai.service.ReplyKind
import kotlinx.coroutines.launch

// 消息流渲染单元：role='user'|'assistant'；kind='html'|'text'（画布卡/文本气泡）。
// dbId=messages 表行 id（失败重发/状态收口用）；status/error=消息生命周期（B 治理，
// 发送管线在 ChatViewModelSend.kt）：failed 行渲染错误角标+重试钮
data class RenderMsg(
    val id: Long, val role: String, val content: String, val kind: String,
    val thinking: String = "", val steps: Int = 0, val thinkingCollapsed: Boolean = true,
    val dbId: Long = 0, val status: String = "done", val error: String = "",
)

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

// M3 到顶轨迹落库瘦身：带图轮的 user 消息 contentParts 含 base64 大图，全量持久化会把
// task_state 撑到 MB 级。深拷贝 trace，把每条含 contentParts 的消息替换为单条占位文本
// （图/附件只服务当轮请求，续跑上下文不再需要原图）；无 contentParts 的轮次原样返回（同实例）。
// 顶层 internal 而非类私有：ChatViewModel 依赖 Android 运行时无法在 JVM 单测实例化，
// StripTraceTest 直接以纯函数口径钉死此行为。
internal fun stripTraceForPersist(trace: List<ChatMsg>): List<ChatMsg> {
    if (trace.all { it.contentParts.isNullOrEmpty() }) return trace
    return trace.map { m ->
        if (m.contentParts.isNullOrEmpty()) m
        else m.copy(contentParts = listOf(ContentPart(type = "text", text = "[图片/附件内容已于首轮消费，续跑上下文省略]")))
    }
}

class ChatViewModel(internal val app: YunkaiApp) : ViewModel() {
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
    // M3 继续任务：上一轮到顶（hitLimit）后为真，UI 出「▶ 继续」；换会话时按 task_state 恢复
    var canContinue = mutableStateOf(false)
    var convId: Long = -1L
    // 会话切换版本号：load() 递增，ChatScreen 观察它重置操作条/确认弹窗等瞬时 UI 态
    //（修「归档当前会话 → load(-1) 不经过抽屉回调 → 操作条残留命中新会话同 id 消息」）
    var uiEpoch = mutableStateOf(0)
        private set
    internal var nextId: Long = 1L
    internal var genId: Long = 0L
    private var initialized = false

    // 流式节流：SSE chunk 高频直刷 @State 会驱动整页每 chunk 重组（流式期间卡顿主因）。
    // 90ms 时间闸合并中间帧；收尾无损——落库走全量 content 渲染，streamText 仅是过程预览。
    // 思考流与回答流独立计时，互不吞帧。
    private var lastStreamFlushAt = 0L
    private var lastThinkingFlushAt = 0L
    internal fun flushStream(partial: String) {
        val now = System.currentTimeMillis()
        if (now - lastStreamFlushAt >= 90) { lastStreamFlushAt = now; streamText.value = partial }
    }
    internal fun flushThinking(acc: String) {
        val now = System.currentTimeMillis()
        if (now - lastThinkingFlushAt >= 90) { lastThinkingFlushAt = now; thinking.value = acc }
    }

    // 首次组合才加载：从设置页/画布页返回时 NavHost 会重组 chat，
    // 重复 init 会把进行中的会话重置成草稿（鸿蒙版 router.back 不重初始化，语义对齐）
    fun initIfNeed(id: Long) {
        if (initialized) return
        load(id)
    }

    /** 悬浮球面板等外部入口：共享大脑从未初始化时加载最近活跃会话（无则新对话草稿）；
     *  已初始化则不动——面板呈现主界面当前状态（含生成中的流式输出）。 */
    fun ensureStarted() {
        if (initialized) return
        viewModelScope.launch {
            refreshConvs()
            load(convs.firstOrNull()?.id ?: -1L)
        }
    }

    fun load(id: Long) {
        initialized = true
        // 换会话即作废在途 send（genId 失配 → 该轮 return）：否则旧轮回来后会把回答写进新会话，
        // 且 nextId 已归 1 会和 loadTurns 写回的 id 撞车 → LazyColumn("重复 key") 崩。
        // 鸿蒙版靠 replaceUrl 换新页实例天然规避，移植成单 Activity + 原地换会话后必须显式作废
        genId += 1
        loading.value = false
        // 编辑态跨会话必须作废（2026-10-07 用户报「切换对话后还是上一个对话框的内容」）：
        // 先把旧会话被截断的消息从快照恢复（否则切走=旧会话静默丢一截），再清编辑标识与输入框——
        // 编辑锁会话，不跟随切换
        if (editing.value) {
            val snapshot = editSnapshot
            editSnapshot = emptyList()
            editing.value = false
            viewModelScope.launch {
                for ((m, kind) in snapshot) app.messageRepo.addRestored(m, kind)
            }
        }
        input.value = ""
        // 跨会话串台治理（2026-10-07 审查 R2/Y7）：排队问题、失败角标、半截流式文本都是
        // 会话级状态，不随 load 清就会在新会话渲染/误发送
        queued.value = ""
        failed.value = false
        streamText.value = ""
        thinking.value = ""
        steps.value = 0
        canContinue.value = false
        uiEpoch.value += 1   // UI 侧瞬时状态（操作条/弹窗）观察此版本号重置
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
            // M3：换会话时按 task_state 恢复「继续」可用态
            canContinue.value = convId > 0 && app.taskStateDao.get(convId) != null
        }
    }

    internal suspend fun loadTurns() {
        val past = app.messageRepo.listByConv(convId)
        turns.clear()
        turns.addAll(past)
        msgs.clear()
        for (m in past) {
            msgs.add(RenderMsg(
                id = nextId++, role = m.role, content = m.content,
                kind = if (m.role == "assistant") ReplyKind.detect(m.content) else ReplyKind.TEXT,
                dbId = m.id, status = m.status, error = m.error,
            ))
        }
    }

    internal suspend fun refreshConvs() {
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

    // uri 图片压缩/附件抽取已拆至 ChatAttachments.kt（门0 P5 收口）

    // 编辑态随发送终结（2026-10-07 审查 R1）：截断从此永久生效、快照作废——
    // 否则编辑条残留，之后点 ✕ 会把快照旧消息回写在新问答之后（重复且持久化，
    // 并使 add() 的 turn_no=max+1 与快照旧行撞号，污染摘要边界判定）
    fun discardEdit() {
        if (editing.value) {
            editing.value = false
            editSnapshot = emptyList()
        }
    }

    fun cancelLoading() {
        // 取消=genId 失配 → AgentLoop 抛 CANCELLED_MSG → send 管线 catch 里 markFailed("已取消")
        // 统一收口（此处不再补刀：failAllPending 会误伤同会话其它在途/排队轮的 pending 行）
        genId += 1
        loading.value = false
    }

    // ✕ 撤回排队：文字退回输入框
    fun clearQueued() {
        input.value = queued.value
        queued.value = ""
    }

    // M3 轨迹序列化器已拆至 TraceCodec.kt（门0 P5 收口，同包顶层免 import）

    // 屏幕感知工具（豆包对齐 M1）：总开关开才进工具表；关=彻底不暴露给模型，不产生任何系统能力调用
    internal suspend fun screenTools(): List<com.zhuolin.yunkai.service.tools.AgentTool> =
        if (app.configStore.getScreenSense()) {
            com.zhuolin.yunkai.service.screen.createScreenTools(app)
        } else {
            emptyList()
        }

    // M3 继续任务 resumeTask 已拆至 ChatViewModelResume.kt 扩展（门0 P5 收口）
    // 立即：打断当前回答，马上发排队那句
    fun sendQueuedNow(context: Context) {
        if (queued.value.isEmpty()) return
        input.value = queued.value
        queued.value = ""
        cancelLoading()
        send(context)
    }

    // 编辑重发（2026-10-07 用户定案终态）：点 ✎ 直接进编辑态（无弹窗）——被截断的消息先存内存
    // 快照（editSnapshot），UI 出编辑标识条；「取消」从快照原样恢复（含 DB 回写），不再不可逆真删。
    // 发送即真截断（deleteSnapshot）：重发成功后快照才作废。
    var editing = mutableStateOf(false)
        private set
    private var editSnapshot: List<Pair<Msg, String>> = emptyList()   // (消息行, kind)

    fun startEdit(index: Int) {
        if (loading.value || editing.value || index < 0 || index >= turns.size) return
        // 同步段先完成全部状态翻转（快照+editing 立即生效）：置位若留到协程尾部，连点两行 ✎
        // 会覆盖快照且 DB 被截断两次——中间的行既不在快照也不在库，永久丢失
        val fromId = turns[index].id
        val text = msgs.getOrNull(index)?.content ?: return
        editSnapshot = turns.drop(index).map { it to kindOfRow(it) }
        editing.value = true
        viewModelScope.launch {
            // 抢发守卫：协程排队期间用户已 ↑ 发送（discardEdit 终结编辑）→ 放弃截断，
            // 旧消息原地保留、新消息追加在末尾——两边内容都不丢
            if (!editing.value) return@launch
            // 忆枢 M2 编辑重发防护（协议 §4.4）：截断点落入已摘要跨度 → 清空摘要状态防陈旧
            val state = app.conversationRepo.summaryState(convId)
            if (state != null && state.untilTurn > 0) {
                val rows = app.messageRepo.listByConv(convId)
                val boundary = rows.lastOrNull { it.role == "assistant" && it.turnNo <= state.untilTurn }
                if (boundary != null && fromId <= boundary.id) {
                    app.conversationRepo.clearSummary(convId)
                }
            }
            app.messageRepo.deleteFromPosition(convId, fromId)
            // 忆枢 M3：编辑使到顶轨迹失效（轨迹引用的轮次被改写），同步清 task_state
            if (app.taskStateDao.get(convId) != null) {
                app.taskStateDao.delete(convId)
                canContinue.value = false
            }
            for (k in turns.size - 1 downTo index) turns.removeAt(k)
            for (k in msgs.size - 1 downTo index) msgs.removeAt(k)
            input.value = text
        }
    }

    private fun kindOfRow(m: Msg): String =
        if (m.role == "assistant" && com.zhuolin.yunkai.service.ReplyKind.detect(m.content) == com.zhuolin.yunkai.service.ReplyKind.HTML) "html" else "text"

    // 取消编辑：从快照恢复被截断的消息（DB 回写+UI 重建），输入框还原
    fun cancelEdit() {
        if (!editing.value) return
        // editing 立即复位+快照先取本地副本：恢复循环是 N 次 Room 写，期间再点 ✕ 或切会话
        // 会触发第二个恢复协程，同一快照回写两遍（全部消息翻倍且持久化）
        editing.value = false
        val snapshot = editSnapshot
        editSnapshot = emptyList()
        input.value = ""
        viewModelScope.launch {
            for ((m, kind) in snapshot) {
                app.messageRepo.addRestored(m, kind)
            }
            loadTurns()
        }
    }

    // 忆枢 M2 会话摘要编排（协议 §4.4）：窗口超阈值 → 最旧一半轮次交给主模型压缩 ≤300 字，
    // 追加进 conversations.summary 并前移换出边界。任何异常静默跳过（摘要属增益）。
    internal suspend fun maybeSummarize(cfg: com.zhuolin.yunkai.model.AppConfig) {
        summarizeIfNeeded(app, cfg, convId)
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

    // 单条消息删除（操作条 🗑）：与编辑重发同款截断语义（删该条+其后全部，摘要边界防护同款），
    // 但不回填输入框
    fun deleteFrom(index: Int) {
        if (loading.value || index < 0 || index >= turns.size) return
        viewModelScope.launch {
            val fromId = turns[index].id
            val state = app.conversationRepo.summaryState(convId)
            if (state != null && state.untilTurn > 0) {
                val rows = app.messageRepo.listByConv(convId)
                val boundary = rows.lastOrNull { it.role == "assistant" && it.turnNo <= state.untilTurn }
                if (boundary != null && fromId <= boundary.id) {
                    app.conversationRepo.clearSummary(convId)
                }
            }
            app.messageRepo.deleteFromPosition(convId, fromId)
            if (app.taskStateDao.get(convId) != null) {
                app.taskStateDao.delete(convId)
                canContinue.value = false
            }
            for (k in turns.size - 1 downTo index) turns.removeAt(k)
            for (k in msgs.size - 1 downTo index) msgs.removeAt(k)
        }
    }

    // ===== 会话管理（2026-10-07 定案：重命名/置顶/归档；直接删除退役）=====
    fun renameConversation(id: Long, newTitle: String) {
        viewModelScope.launch {
            app.conversationRepo.rename(id, newTitle)
            refreshConvs()
            if (id == convId) {
                title.value = newTitle.trim().ifEmpty { title.value }
            }
        }
    }

    fun togglePinConversation(id: Long) {
        viewModelScope.launch {
            app.conversationRepo.togglePin(id)
            refreshConvs()
        }
    }

    fun archiveConversation(id: Long) {
        viewModelScope.launch {
            app.conversationRepo.archive(id)
            toast(app, "已归档（30 天后自动删除）")
            refreshConvs()
            if (id == convId) load(-1L)   // 归档当前会话 → 回新对话草稿
        }
    }

}
