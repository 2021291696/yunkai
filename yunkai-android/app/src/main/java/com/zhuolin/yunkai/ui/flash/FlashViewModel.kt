package com.zhuolin.yunkai.ui.flash

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhuolin.yunkai.YunkaiApp
import com.zhuolin.yunkai.service.AgentLoop
import com.zhuolin.yunkai.service.HtmlGuard
import com.zhuolin.yunkai.service.LoopEvent
import com.zhuolin.yunkai.service.ReplyKind
import com.zhuolin.yunkai.store.FlashEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// 闪问（M2b）：一次性问答的独立会话，与主 ChatViewModel 完全隔离——不写 conversations/messages，
// 只把一问一答写进 flash_sessions；工具面与主对话一致（M2 文件/忆枢记忆/屏幕感知），
// system/记忆注入由 AgentLoop 内部完成（history 为空 → 单轮）。
// 画布形态降级：HTML 终稿不进气泡，覆盖写 filesDir/flash_canvas_last.html，answer 只留提示文案。
class FlashViewModel(private val app: YunkaiApp) : ViewModel() {
    var input = mutableStateOf("")
    var answer = mutableStateOf("")        // 终稿文本（画布降级时为提示文案）
    var streamText = mutableStateOf("")    // 流式增量；非空=正在生成
    var loading = mutableStateOf(false)
    var failed = mutableStateOf(false)
    var thinking = mutableStateOf("")
    val timeline = mutableStateListOf<LoopEvent>()
    // 非空=有画布可打开：HTML 已落该文件路径（WebView 加载点）
    var canvasFile = mutableStateOf("")

    private var genId = 0L

    // 与主对话同口径的画布文件名（固定路径，覆盖写=只留最近一份）
    private fun canvasPath(): String = File(app.filesDir, "flash_canvas_last.html").absolutePath

    fun cancel() {
        genId += 1
        loading.value = false
        streamText.value = ""
    }

    // 屏幕感知工具（豆包对齐 M1）：总开关开才进工具表（与 ChatViewModel 同一门控）
    private suspend fun screenTools(): List<com.zhuolin.yunkai.service.tools.AgentTool> =
        if (app.configStore.getScreenSense()) {
            com.zhuolin.yunkai.service.screen.createScreenTools(app)
        } else {
            emptyList()
        }

    // 发送管线：校验配置 → AgentLoop.run（全量工具）→ 形态判定（画布降级）→ 落 flash_sessions。
    // 净空语义：仅成功终稿落库；取消/抛错/未配置都不留记录。
    fun send(context: Context) {
        val q0 = input.value.trim()
        if (q0.isEmpty() || loading.value) return
        input.value = ""
        failed.value = false
        answer.value = ""
        canvasFile.value = ""
        timeline.clear()
        streamText.value = ""
        thinking.value = ""
        val gen = genId + 1
        genId = gen
        loading.value = true

        viewModelScope.launch {
            try {
                val cfg = app.configStore.load()
                if (cfg.baseUrl.isEmpty() || cfg.apiKey.isEmpty() || cfg.model.isEmpty()) {
                    failed.value = true
                    input.value = q0   // 失败还原输入，用户不必重打
                    toast(context, "请先在设置页配置 API 地址/密钥/模型")
                    return@launch
                }

                val r = AgentLoop.run(
                    cfg, app.skillRepo, emptyList(), q0, null,
                    onEvent = { e ->
                        if (gen == genId) {
                            timeline.add(e)
                        }
                    },
                    isCancelled = { gen != genId },
                    onDelta = { partial ->
                        if (gen == genId) streamText.value = partial
                    },
                    onThinking = { acc ->
                        if (gen == genId) thinking.value = acc
                    },
                    extraTools = com.zhuolin.yunkai.service.tools.createM2Tools(app) + screenTools() +
                        com.zhuolin.yunkai.memory.createMemoryTools(app.memoryStore) {
                            app.configStore.load().memoryGear
                        },
                    memory = app.memoryStore,
                    maxSteps = cfg.maxSteps,
                )
                if (gen != genId) return@launch

                // 输出形态口径与主对话一致：sanitize 通过且 sanitize 后仍以 <!DOCTYPE/<html 开头才算画布
                val safe = HtmlGuard.sanitize(r.answer)
                val text = if (safe != null && ReplyKind.detect(safe) == ReplyKind.HTML) {
                    // 画布降级：HTML 写文件，气泡只留可点击提示；失败（IO/空串）退回纯文本
                    withContext(Dispatchers.IO) {
                        try {
                            File(canvasPath()).writeText(safe)
                            true
                        } catch (e: Exception) {
                            Log.e("yunkai", "flash canvas write failed: ${e.message}")
                            false
                        }
                    }
                } else {
                    false
                }
                val content = if (text) {
                    canvasFile.value = canvasPath()
                    "[画布内容] 已生成，点此在云开中打开"
                } else {
                    r.answer
                }
                answer.value = content
                withContext(Dispatchers.IO) {
                    app.flashDao.insert(FlashEntity(
                        question = q0, answer = content, created_at = System.currentTimeMillis(),
                    ))
                }
            } catch (e: Exception) {
                if (gen == genId) {
                    failed.value = true
                    val em = e.message ?: ""
                    if (em != AgentLoop.CANCELLED_MSG) {
                        input.value = q0
                        Log.e("yunkai", "flash send failed: $em")
                        toast(context, "出错了：$em")
                    }
                }
            } finally {
                if (gen == genId) {
                    loading.value = false
                    streamText.value = ""
                }
            }
        }
    }

    fun toast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }
}
