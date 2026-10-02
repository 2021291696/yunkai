package com.zhuolin.yunkai.service.screen

// 写操作计划状态机（M2a-T5 三层安全核心；2026-10-02 确认分级改造）：
// 计划提交即执行（不再整计划等批准），闸门按用户设置的确认模式（ConfirmMode）分档：
//   FULL_AUTO 完全访问：全自动执行不打扰用户（敏感急停也不停——用户在设置里明示放弃保护）
//   SMART     AI 自审：模型在计划里标记 confirm 的动作 + 敏感词输入 + 敏感页急停（默认档）
//   STRICT    事事过问：除点按/滑动/返回/桌面等低危动作外，输入动作一律暂停等确认
// 纯状态机：敏感检测（ScreenGuard）与单动作执行（手势基元）均从外部注入，UI/工具接线不在本类职责内。
// 单计划模型：同时只有一个活跃计划（Executing/两种 Paused）；活跃期间重复 submit 返回 false，
// 终态（Done/Stopped）后可提交新计划。
// sensitiveHint 语义（契约未细化，取保守解）：true 表示计划生成时页面已命中敏感 → 首步执行前
// 强制一次 SensitivePaused 批准（即便逐动作检测未命中）；只加闸门，不减闸门（FULL_AUTO 除外）。
// 步骤失败自动重试（共 1+STEP_RETRIES 次尝试），重试仍失败 → 中止后续依赖步骤并带原因收敛
// Stopped：失败详情经工具结果回传模型自行调整（低危新计划免确认直接执行，形成「确认一次执行
// 到底」的自愈闭环）；同一动作敏感命中后批准放行即不再走分档确认，避免同一步双闸双问。
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// 确认模式（设置页三档，ConfigStore.confirmMode 持久化；PlanTool 从设置读取后传入 submit）
enum class ConfirmMode { FULL_AUTO, SMART, STRICT }

// 分档确认判定（单一事实源，审查 W3 收口）：PlanTool.predictPause（提交时预测、决定拉不拉前台）
// 与 WritePlanExecutor.runPlan（执行时闸门）共用本谓词；改档位规则只动这里，防预测/执行两处漂移。
// flagged = 模型在 plan 里标记 confirm 的动作（执行器 actions 下标，已含 OpenApp 头偏移）。
// 纯 JVM 无 Android 依赖，ConfirmTiersTest 表驱动直测。
object ConfirmTiers {
    fun requiresConfirm(mode: ConfirmMode, action: WriteAction, flagged: Boolean): Boolean = when {
        mode == ConfirmMode.FULL_AUTO -> false
        action is WriteAction.Input &&
            (mode == ConfirmMode.STRICT || ScreenGuard.hasSensitive(action.text)) -> true
        mode == ConfirmMode.SMART && flagged -> true
        else -> false
    }
}

sealed class PlanEvent {
    data class Submitted(val planId: String, val labels: List<String>) : PlanEvent()
    data class StepEcho(val planId: String, val index: Int, val label: String) : PlanEvent()
    data class SensitivePaused(val planId: String, val index: Int, val reason: String) : PlanEvent()
    data class SendConfirmNeeded(val planId: String, val index: Int) : PlanEvent()
    data class Done(val planId: String, val executed: Int) : PlanEvent()
    data class Stopped(val planId: String, val executed: Int, val reason: String = "") : PlanEvent()
}

class WritePlanExecutor(
    private val scope: CoroutineScope,
    private val sensitiveChecker: suspend (WriteAction) -> String?,
    private val executor: suspend (WriteAction) -> Boolean,
    // 重试间隔：生产 800ms 等页面稳定；测试注入 0（本仓 coroutines-test 的 backgroundScope
    // delay 不被 advanceUntilIdle 虚拟时间推进，实测 ScratchDelayTest 实锤，规避之）
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) {
    sealed class PlanState {
        data class Executing(val index: Int) : PlanState()           // 正在执行第 index 步（0 起）
        data class SensitivePaused(val index: Int) : PlanState()
        data class SendConfirmPaused(val index: Int) : PlanState()    // 需确认动作（分档判定命中）
        data class Done(val executed: Int) : PlanState()
        data class Stopped(val executed: Int, val reason: String = "") : PlanState()
    }

    private val _state = MutableStateFlow<PlanState?>(null)
    val state: StateFlow<PlanState?> = _state.asStateFlow()

    // replay=0 + 64 缓冲：订阅方先挂上再提交即可收全事件；无订阅时事件丢弃（终态仍可从 state 读到）
    private val _events = MutableSharedFlow<PlanEvent>(extraBufferCapacity = 64)
    val events: MutableSharedFlow<PlanEvent> = _events

    // 最近一次 submit 的步骤文案快照（UI 计划卡读它渲染步骤列表；执行中 state 只带 index）。
    // 写发生在 submit 的 synchronized 块内、置 Executing 之前；读在 Compose 主线程，@Volatile 保证可见性。
    @Volatile var lastLabels: List<String> = emptyList()
        private set

    // 最近一次 submit 的 planId：approve/approveSend/cancel 都要按 planId 校验，
    // 而 planId 不在 PlanState 里（state 只有 index），计划卡按钮需要它 → 与 lastLabels 同源暴露。
    @Volatile var lastPlanId: String = ""
        private set

    // 安全审计（run-1 F-3）批准绑定：与 lastLabels 同源暴露完整参数对象与计划级元数据，
    // 计划卡据此渲染每步真实动作（坐标/输入文本/目标应用）——用户确认的必须是参数本身而非标签。
    @Volatile var lastActions: List<WriteAction> = emptyList()
        private set
    @Volatile var lastSummary: String = ""
        private set
    @Volatile var lastTargetPkg: String = ""
        private set

    private val lock = Any()
    private var planId: String = ""
    private var actions: List<WriteAction> = emptyList()
    private var labels: List<String> = emptyList()
    private var gate: CompletableDeferred<Unit>? = null
    @Volatile private var cancelRequested: Boolean = false
    @Volatile private var sensitiveHint: Boolean = false
    @Volatile private var mode: ConfirmMode = ConfirmMode.SMART
    @Volatile private var modelConfirm: Set<Int> = emptySet()

    // 提交计划：直接开始执行并发 Submitted；已有活跃计划时返回 false。
    // 执行循环在 scope 上起独立 Job；确认分档参数由工具层（PlanTool）按用户设置传入。
    // summary/targetPkg（安全审计 run-1 F-3）：计划级元数据与动作对象同源落快照供 UI 渲染，
    // 旧调用方不传时退化为空串（计划卡自行降级为只显示标签）。
    fun submit(
        planId: String,
        actions: List<WriteAction>,
        labels: List<String>,
        sensitiveHint: Boolean,
        summary: String = "",
        targetPkg: String = "",
        mode: ConfirmMode = ConfirmMode.SMART,
        modelConfirm: Set<Int> = emptySet(),
    ): Boolean {
        synchronized(lock) {
            if (isActive(_state.value)) return false
            this.planId = planId
            this.actions = actions
            this.labels = labels
            this.lastLabels = labels
            this.lastPlanId = planId
            this.lastActions = actions
            this.lastSummary = summary
            this.lastTargetPkg = targetPkg
            this.sensitiveHint = sensitiveHint
            this.mode = mode
            this.modelConfirm = modelConfirm
            this.cancelRequested = false
            this.gate = CompletableDeferred()
            _state.value = PlanState.Executing(0)
        }
        _events.tryEmit(PlanEvent.Submitted(planId, labels))
        scope.launch { runPlan() }
        return true
    }

    // SensitivePaused → 继续（批准后本步照常执行，不重复敏感检测）
    fun approve(planId: String) {
        val d = synchronized(lock) {
            if (this.planId != planId) return
            if (_state.value !is PlanState.SensitivePaused) return
            gate
        } ?: return
        d.complete(Unit)
    }

    // SendConfirmPaused → 继续（确认动作放行）
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
            val id = planId
            var i = 0
            while (i < actions.size) {
                if (cancelRequested) { stop(id, executed); return }
                _state.value = PlanState.Executing(i)
                val action = actions[i]
                val label = labels.getOrElse(i) { "步骤 ${i + 1}" }

                if (mode != ConfirmMode.FULL_AUTO) {
                    // ① 提交时的敏感提示（可选的额外闸门）
                    if (sensitiveHint && i == 0) {
                        pause(PlanState.SensitivePaused(0), PlanEvent.SensitivePaused(id, 0, HINT_REASON))
                        if (cancelRequested) { stop(id, executed); return }
                    }
                    // ② 敏感页急停：命中即挂起（含外发文本敏感词与 fail-closed），approve 后放行
                    val reason = sensitiveChecker(action)
                    if (reason != null) {
                        pause(PlanState.SensitivePaused(i), PlanEvent.SensitivePaused(id, i, reason))
                        if (cancelRequested) { stop(id, executed); return }
                    } else if (ConfirmTiers.requiresConfirm(mode, action, flagged = i in modelConfirm)) {
                        // ③ 分档确认（与 ② 互斥，防同一步双闸双问）：STRICT 所有输入；
                        //    SMART 敏感词输入或模型标记动作；低危点按/滑动/返回/桌面不问
                        pause(PlanState.SendConfirmPaused(i), PlanEvent.SendConfirmNeeded(id, i))
                        if (cancelRequested) { stop(id, executed); return }
                    }
                }

                // ④ 执行前回显；失败自动重试（重试前留间隔等页面稳定），仍失败 → 带原因收敛 Stopped
                _events.tryEmit(PlanEvent.StepEcho(id, i, label))
                var ok = executor(action)
                var attempt = 0
                while (!ok && attempt < STEP_RETRIES && !cancelRequested) {
                    attempt++
                    delay(retryDelayMs)
                    ok = executor(action)
                }
                if (ok) {
                    executed++
                } else {
                    _events.tryEmit(PlanEvent.StepEcho(id, i, FAIL_PREFIX + label))
                    stop(id, executed, "第 ${i + 1} 步「$label」执行失败（已重试 $STEP_RETRIES 次），后续步骤已中止")
                    return
                }
                i++
            }
            finish(PlanState.Done(executed), PlanEvent.Done(id, executed))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // 注入实现抛意外异常时不悬挂状态机：带原因收敛为 Stopped
            stop(planId, executed, "执行器异常：${e.message ?: "未知错误"}")
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

    private fun stop(id: String, executed: Int, reason: String = "") {
        finish(PlanState.Stopped(executed, reason), PlanEvent.Stopped(id, executed, reason))
    }

    private fun finish(terminal: PlanState, event: PlanEvent) {
        _state.value = terminal
        _events.tryEmit(event)
    }

    private fun isActive(s: PlanState?): Boolean =
        s is PlanState.Executing || s is PlanState.SensitivePaused || s is PlanState.SendConfirmPaused

    private companion object {
        const val FAIL_PREFIX = "[失败] "
        const val HINT_REASON = "计划生成时页面已命中敏感关键词"
        const val STEP_RETRIES = 2
        const val RETRY_DELAY_MS = 800L
    }
}
