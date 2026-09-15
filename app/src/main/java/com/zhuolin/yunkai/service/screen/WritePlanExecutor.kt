package com.zhuolin.yunkai.service.screen

// 写操作计划状态机（M2a-T5，三层安全核心）：
//   计划提交（Submitted 预览）→ 用户批准（approve）→ 逐步执行（StepEcho 每步回显、
//   敏感页急停 SensitivePaused、Input 外发二次确认 SendConfirmNeeded）→ 终态（Done/Stopped）。
// 纯状态机：敏感检测（ScreenGuard）与单动作执行（手势基元）均从外部注入，UI/工具接线不在本类职责内。
// 单计划模型：同时只有一个活跃计划（Pending/Executing/两种 Paused）；活跃期间重复 submit 返回 false，
// 终态（Done/Stopped）后可提交新计划。
// sensitiveHint 语义（契约未细化，取保守解）：true 表示计划生成时页面已命中敏感 → 首步执行前
// 强制一次 SensitivePaused 批准（即便逐动作检测未命中）；只加闸门，不减闸门。
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class PlanEvent {
    data class Submitted(val planId: String, val labels: List<String>) : PlanEvent()
    data class StepEcho(val planId: String, val index: Int, val label: String) : PlanEvent()
    data class SensitivePaused(val planId: String, val index: Int, val reason: String) : PlanEvent()
    data class SendConfirmNeeded(val planId: String, val index: Int) : PlanEvent()
    data class Done(val planId: String, val executed: Int) : PlanEvent()
    data class Stopped(val planId: String, val executed: Int) : PlanEvent()
}

class WritePlanExecutor(
    private val scope: CoroutineScope,
    private val sensitiveChecker: suspend (WriteAction) -> String?,
    private val executor: suspend (WriteAction) -> Boolean,
) {
    sealed class PlanState {
        object Pending : PlanState()
        data class Executing(val index: Int) : PlanState()            // 正在执行第 index 步（0 起）
        data class SensitivePaused(val index: Int) : PlanState()
        data class SendConfirmPaused(val index: Int) : PlanState()    // 外发动作（Input）二次确认
        data class Done(val executed: Int) : PlanState()
        data class Stopped(val executed: Int) : PlanState()
    }

    private val _state = MutableStateFlow<PlanState?>(null)
    val state: StateFlow<PlanState?> = _state.asStateFlow()

    // replay=0 + 64 缓冲：订阅方先挂上再提交即可收全事件；无订阅时事件丢弃（终态仍可从 state 读到）
    private val _events = MutableSharedFlow<PlanEvent>(extraBufferCapacity = 64)
    val events: MutableSharedFlow<PlanEvent> = _events

    // 最近一次 submit 的步骤文案快照（UI 计划卡读它渲染步骤列表；执行中 state 只带 index）。
    // 写发生在 submit 的 synchronized 块内、置 Pending 之前；读在 Compose 主线程，用 @Volatile 保证可见性。
    @Volatile var lastLabels: List<String> = emptyList()
        private set

    // 最近一次 submit 的 planId：approve/approveSend/cancel 都要按 planId 校验，
    // 而 planId 不在 PlanState 里（state 只有 index），计划卡按钮需要它 → 与 lastLabels 同源暴露。
    @Volatile var lastPlanId: String = ""
        private set

    private val lock = Any()
    private var planId: String = ""
    private var actions: List<WriteAction> = emptyList()
    private var labels: List<String> = emptyList()
    private var gate: CompletableDeferred<Unit>? = null
    @Volatile private var cancelRequested: Boolean = false
    @Volatile private var sensitiveHint: Boolean = false

    // 提交计划：置 Pending 并发 Submitted；已有活跃计划时返回 false。
    // 执行循环在 scope 上起独立 Job，先挂在初始闸门上等 approve。
    fun submit(planId: String, actions: List<WriteAction>, labels: List<String>, sensitiveHint: Boolean): Boolean {
        synchronized(lock) {
            if (isActive(_state.value)) return false
            this.planId = planId
            this.actions = actions
            this.labels = labels
            this.lastLabels = labels
            this.lastPlanId = planId
            this.sensitiveHint = sensitiveHint
            this.cancelRequested = false
            this.gate = CompletableDeferred()
            _state.value = PlanState.Pending
        }
        _events.tryEmit(PlanEvent.Submitted(planId, labels))
        scope.launch { runPlan() }
        return true
    }

    // Pending → 开始执行；SensitivePaused → 继续（批准后本步照常执行，不重复敏感检测）
    fun approve(planId: String) {
        val d = synchronized(lock) {
            if (this.planId != planId) return
            val s = _state.value
            if (s !== PlanState.Pending && s !is PlanState.SensitivePaused) return
            gate
        } ?: return
        d.complete(Unit)
    }

    // SendConfirmPaused → 继续（外发动作放行）
    fun approveSend(planId: String) {
        val d = synchronized(lock) {
            if (this.planId != planId) return
            if (_state.value !is PlanState.SendConfirmPaused) return
            gate
        } ?: return
        d.complete(Unit)
    }

    // 任意活跃态 → 收敛为 Stopped：置取消旗标并唤醒挂起点；执行中则当前步完成后停。
    fun cancel(planId: String) {
        val d = synchronized(lock) {
            if (this.planId != planId) return
            val s = _state.value
            if (s == null || s is PlanState.Done || s is PlanState.Stopped) return
            cancelRequested = true
            gate
        }
        d?.complete(Unit)
    }

    private suspend fun runPlan() {
        var executed = 0
        try {
            awaitGate()                                   // Pending：等用户批准
            val id = planId
            var i = 0
            while (i < actions.size) {
                if (cancelRequested) { stop(id, executed); return }
                _state.value = PlanState.Executing(i)
                val action = actions[i]
                val label = labels.getOrElse(i) { "步骤 ${i + 1}" }

                // ① 提交时的敏感提示（可选的额外闸门）
                if (sensitiveHint && i == 0) {
                    pause(PlanState.SensitivePaused(0), PlanEvent.SensitivePaused(id, 0, HINT_REASON))
                    if (cancelRequested) { stop(id, executed); return }
                }
                // ② 敏感页急停：命中即挂起，approve 后继续
                val reason = sensitiveChecker(action)
                if (reason != null) {
                    pause(PlanState.SensitivePaused(i), PlanEvent.SensitivePaused(id, i, reason))
                    if (cancelRequested) { stop(id, executed); return }
                }
                // ③ 外发动作二次确认 —— 在敏感检查之后
                if (action is WriteAction.Input) {
                    pause(PlanState.SendConfirmPaused(i), PlanEvent.SendConfirmNeeded(id, i))
                    if (cancelRequested) { stop(id, executed); return }
                }
                // ④ 执行前回显；失败不中断，以「[失败]」前缀再次回显后继续下一步
                _events.tryEmit(PlanEvent.StepEcho(id, i, label))
                val ok = executor(action)
                if (ok) executed++ else _events.tryEmit(PlanEvent.StepEcho(id, i, FAIL_PREFIX + label))
                i++
            }
            finish(PlanState.Done(executed), PlanEvent.Done(id, executed))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // 注入实现抛意外异常时不悬挂状态机：收敛为 Stopped
            stop(planId, executed)
        }
    }

    // 进入暂停：先换新闸门再发布状态/事件，保证 approve 看到暂停态时闸门一定就绪
    private suspend fun pause(paused: PlanState, event: PlanEvent) {
        val d = synchronized(lock) {
            val fresh = CompletableDeferred<Unit>()
            gate = fresh
            if (cancelRequested) fresh.complete(Unit)     // 竞态兜底：取消先到就不等批准
            _state.value = paused
            fresh
        }
        _events.tryEmit(event)
        d.await()
    }

    private suspend fun awaitGate() {
        val d = synchronized(lock) { gate } ?: return
        d.await()
    }

    private fun stop(id: String, executed: Int) {
        finish(PlanState.Stopped(executed), PlanEvent.Stopped(id, executed))
    }

    private fun finish(terminal: PlanState, event: PlanEvent) {
        _state.value = terminal
        _events.tryEmit(event)
    }

    private fun isActive(s: PlanState?): Boolean =
        s is PlanState.Pending || s is PlanState.Executing ||
            s is PlanState.SensitivePaused || s is PlanState.SendConfirmPaused

    private companion object {
        const val FAIL_PREFIX = "[失败] "
        const val HINT_REASON = "计划生成时页面已命中敏感关键词"
    }
}
