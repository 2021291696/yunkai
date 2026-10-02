package com.zhuolin.yunkai.screen

import com.zhuolin.yunkai.service.screen.ConfirmMode
import com.zhuolin.yunkai.service.screen.PlanEvent
import com.zhuolin.yunkai.service.screen.WriteAction
import com.zhuolin.yunkai.service.screen.WritePlanExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 写操作计划状态机单测（2026-10-02 确认分级改造后语义）：
// 提交即执行（无整计划批准门）/ 三档确认判定表 / 敏感急停 / 取消 / 失败重试后带原因中止。
// 执行器与敏感检测均为 fake：executor 直接返回 true/false，敏感检测按动作特征命中。
class WritePlanExecutorTest {

    // 订阅必须早于 submit：用 Unconfined 收集器让订阅在 launch 处同步建立。
    private fun TestScope.executor(
        events: MutableList<PlanEvent>,
        sensitive: suspend (WriteAction) -> String? = { null },
        exec: suspend (WriteAction) -> Boolean = { true },
    ): WritePlanExecutor {
        // retryDelayMs=0：backgroundScope 的 delay 不被虚拟时间推进（版本实测），测试直接消掉间隔
        val e = WritePlanExecutor(backgroundScope, sensitive, exec, retryDelayMs = 0L)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { e.events.collect { events += it } }
        return e
    }

    // 推进挂起的执行循环。注意 runCurrent 必须在前：advanceUntilIdle 只清前台任务，
    // 队列里只剩 backgroundScope（执行循环所在）任务时会直接返回，计划的推进全靠 runCurrent。
    // 重试路径里的 delay(RETRY_DELAY_MS) 由虚拟时间直接消化。
    private fun TestScope.drain() {
        runCurrent()
        advanceUntilIdle()
    }

    @Test fun submit_entersExecutingAndEmitsSubmitted() = runTest {
        val events = mutableListOf<PlanEvent>()
        val release = CompletableDeferred<Unit>()
        val ex = executor(events, exec = { release.await(); true })
        assertTrue(ex.submit("p1", listOf(WriteAction.Tap(1, 2)), listOf("点搜索"), sensitiveHint = false))
        assertEquals(WritePlanExecutor.PlanState.Executing(0), ex.state.value)
        assertEquals(listOf(PlanEvent.Submitted("p1", listOf("点搜索"))), events)
        release.complete(Unit)
        drain()
        assertEquals(WritePlanExecutor.PlanState.Done(1), ex.state.value)
    }

    @Test fun submit_executesAllSteps_thenDone() = runTest {
        val events = mutableListOf<PlanEvent>()
        val ex = executor(events)
        ex.submit("p1", listOf(WriteAction.Tap(1, 2), WriteAction.Back, WriteAction.Home), listOf("点", "返回", "回桌面"), false)
        drain()
        assertEquals(WritePlanExecutor.PlanState.Done(3), ex.state.value)
        val echoes = events.filterIsInstance<PlanEvent.StepEcho>()
        assertEquals(3, echoes.size)
        assertEquals(listOf(0, 1, 2), echoes.map { it.index })
        assertEquals(listOf("点", "返回", "回桌面"), echoes.map { it.label })
        assertEquals(PlanEvent.Done("p1", 3), events.last())
    }

    @Test fun sensitiveChecker_pausesBeforeStep2_thenApproveContinues() = runTest {
        val events = mutableListOf<PlanEvent>()
        val done = mutableListOf<WriteAction>()
        val marker = WriteAction.Tap(9, 9)
        val ex = executor(
            events,
            sensitive = { if (it == marker) "命中支付关键词" else null },
            exec = { done += it; true },
        )
        ex.submit("p1", listOf(WriteAction.Tap(1, 2), marker, WriteAction.Back), listOf("点", "点支付", "返回"), false)
        drain()
        assertEquals(WritePlanExecutor.PlanState.SensitivePaused(1), ex.state.value)
        assertEquals(PlanEvent.SensitivePaused("p1", 1, "命中支付关键词"), events.last())
        assertEquals(listOf(WriteAction.Tap(1, 2)), done)          // 第 2 步未执行
        ex.approve("p1")
        drain()
        assertEquals(WritePlanExecutor.PlanState.Done(3), ex.state.value)
        assertEquals(listOf(WriteAction.Tap(1, 2), marker, WriteAction.Back), done)
    }

    @Test fun cancel_duringExecuting_stopsAfterCurrentStep() = runTest {
        val events = mutableListOf<PlanEvent>()
        val release = CompletableDeferred<Unit>()
        var firstStepEntered = false
        val ex = executor(
            events,
            exec = { if (!firstStepEntered) { firstStepEntered = true; release.await() }; true },
        )
        ex.submit("p1", listOf(WriteAction.Tap(1, 2), WriteAction.Back), listOf("点", "返回"), false)
        drain()
        assertEquals(WritePlanExecutor.PlanState.Executing(0), ex.state.value)
        ex.cancel("p1")                                            // 执行中取消
        release.complete(Unit)                                     // 当前步收尾
        drain()
        assertEquals(WritePlanExecutor.PlanState.Stopped(1), ex.state.value)
        assertEquals(PlanEvent.Stopped("p1", 1), events.last())   // 用户取消：无 reason
        assertEquals(1, events.filterIsInstance<PlanEvent.StepEcho>().size)
    }

    @Test fun cancel_immediatelyAfterSubmit_goesStopped() = runTest {
        val events = mutableListOf<PlanEvent>()
        val ex = executor(events)
        ex.submit("p1", listOf(WriteAction.Back), listOf("返回"), false)
        ex.cancel("p1")
        drain()
        assertEquals(WritePlanExecutor.PlanState.Stopped(0), ex.state.value)
        assertEquals(PlanEvent.Stopped("p1", 0), events.last())
    }

    // ── 三档确认判定表 ──

    @Test fun smart_plainInput_runsWithoutConfirm() = runTest {
        val events = mutableListOf<PlanEvent>()
        var calls = 0
        val ex = executor(events, exec = { calls++; true })
        ex.submit("p1", listOf(WriteAction.Input("你好")), listOf("输入文本"), false, mode = ConfirmMode.SMART)
        drain()
        assertEquals(WritePlanExecutor.PlanState.Done(1), ex.state.value)
        assertTrue(events.none { it is PlanEvent.SendConfirmNeeded })
        assertEquals(1, calls)                                     // 无任何暂停直接执行
    }

    @Test fun smart_sensitiveInput_pausesForConfirm() = runTest {
        val events = mutableListOf<PlanEvent>()
        val ex = executor(events)
        ex.submit("p1", listOf(WriteAction.Input("请填写支付密码")), listOf("输入密码"), false, mode = ConfirmMode.SMART)
        drain()
        assertEquals(WritePlanExecutor.PlanState.SendConfirmPaused(0), ex.state.value)
        assertEquals(PlanEvent.SendConfirmNeeded("p1", 0), events.last())
        ex.approveSend("p1")
        drain()
        assertEquals(WritePlanExecutor.PlanState.Done(1), ex.state.value)
    }

    @Test fun strict_anyInput_pausesForConfirm() = runTest {
        val events = mutableListOf<PlanEvent>()
        val ex = executor(events)
        ex.submit("p1", listOf(WriteAction.Input("你好")), listOf("输入文本"), false, mode = ConfirmMode.STRICT)
        drain()
        assertEquals(WritePlanExecutor.PlanState.SendConfirmPaused(0), ex.state.value)
        ex.approveSend("p1")
        drain()
        assertEquals(WritePlanExecutor.PlanState.Done(1), ex.state.value)
    }

    @Test fun strict_lowRiskTap_runsFree() = runTest {
        val events = mutableListOf<PlanEvent>()
        val ex = executor(events)
        ex.submit("p1", listOf(WriteAction.Tap(3, 4), WriteAction.Back), listOf("点", "返回"), false, mode = ConfirmMode.STRICT)
        drain()
        assertEquals(WritePlanExecutor.PlanState.Done(2), ex.state.value)
        assertTrue(events.none { it is PlanEvent.SendConfirmNeeded })
    }

    @Test fun smart_modelConfirmedTap_pausesForConfirm() = runTest {
        val events = mutableListOf<PlanEvent>()
        val ex = executor(events)
        ex.submit(
            "p1", listOf(WriteAction.Tap(5, 6)), listOf("点删除"), false,
            mode = ConfirmMode.SMART, modelConfirm = setOf(0),
        )
        drain()
        assertEquals(WritePlanExecutor.PlanState.SendConfirmPaused(0), ex.state.value)
        ex.approveSend("p1")
        drain()
        assertEquals(WritePlanExecutor.PlanState.Done(1), ex.state.value)
    }

    @Test fun fullAuto_skipsAllGates_evenSensitive() = runTest {
        val events = mutableListOf<PlanEvent>()
        var calls = 0
        val ex = executor(
            events,
            sensitive = { "任何动作都报敏感" },
            exec = { calls++; true },
        )
        ex.submit(
            "p1", listOf(WriteAction.Input("转账给张三"), WriteAction.Tap(1, 2)), listOf("输入", "点"), false,
            mode = ConfirmMode.FULL_AUTO,
        )
        drain()
        assertEquals(WritePlanExecutor.PlanState.Done(2), ex.state.value)
        assertTrue(events.none { it is PlanEvent.SensitivePaused })
        assertTrue(events.none { it is PlanEvent.SendConfirmNeeded })
        assertEquals(2, calls)
    }

    @Test fun sensitiveHit_takesPriority_overConfirmGate_singleAsk() = runTest {
        val events = mutableListOf<PlanEvent>()
        val ex = executor(events, sensitive = { "页面命中敏感词" })
        ex.submit("p1", listOf(WriteAction.Input("转账给张三")), listOf("输入"), false, mode = ConfirmMode.SMART)
        drain()
        assertEquals(WritePlanExecutor.PlanState.SensitivePaused(0), ex.state.value)
        ex.approve("p1")                                           // 敏感放行后同一步不再走确认门（防双闸双问）
        drain()
        assertEquals(WritePlanExecutor.PlanState.Done(1), ex.state.value)
        assertEquals(1, events.filterIsInstance<PlanEvent.SensitivePaused>().size)
        assertEquals(0, events.filterIsInstance<PlanEvent.SendConfirmNeeded>().size)
    }

    // ── 失败重试语义 ──

    @Test fun executorFailure_retriesThenStopsWithReason_remainingStepsAborted() = runTest {
        val events = mutableListOf<PlanEvent>()
        val done = mutableListOf<WriteAction>()
        val marker = WriteAction.Tap(1, 2)
        var markerCalls = 0
        val ex = executor(
            events,
            exec = { a -> if (a == marker) { markerCalls++; false } else { done += a; true } },
        )
        ex.submit("p1", listOf(marker, WriteAction.Back), listOf("点我", "返回"), false)
        drain()
        val st = ex.state.value
        assertTrue(st is WritePlanExecutor.PlanState.Stopped && st.executed == 0)
        assertTrue((st as WritePlanExecutor.PlanState.Stopped).reason.contains("已重试"))
        assertEquals(3, markerCalls)                               // 1 次原始 + 2 次重试
        assertTrue(done.isEmpty())                                 // 后续依赖步骤全部中止
        val last = events.last()
        assertTrue(last is PlanEvent.Stopped)
        assertTrue((last as PlanEvent.Stopped).reason.contains("已重试"))
        val echoes = events.filterIsInstance<PlanEvent.StepEcho>()
        assertEquals(listOf("点我", "[失败] 点我"), echoes.map { it.label })
    }

    @Test fun transientFailure_recoversOnRetry_thenContinues() = runTest {
        val events = mutableListOf<PlanEvent>()
        var markerCalls = 0
        val marker = WriteAction.Tap(7, 8)
        val ex = executor(
            events,
            exec = { a -> if (a == marker) { markerCalls++; markerCalls >= 2 } else true },
        )
        ex.submit("p1", listOf(marker, WriteAction.Back), listOf("点我", "返回"), false)
        drain()
        assertEquals(WritePlanExecutor.PlanState.Done(2), ex.state.value)
        assertEquals(2, markerCalls)                               // 第 2 次尝试成功
    }

    @Test fun duplicateSubmit_rejected() = runTest {
        val events = mutableListOf<PlanEvent>()
        val release = CompletableDeferred<Unit>()
        val ex = executor(events, exec = { release.await(); true })
        assertTrue(ex.submit("p1", listOf(WriteAction.Back), listOf("返回"), false))
        assertFalse(ex.submit("p2", listOf(WriteAction.Home), listOf("回桌面"), false))
        assertEquals(WritePlanExecutor.PlanState.Executing(0), ex.state.value)   // 原计划不受影响
        assertEquals(1, events.filterIsInstance<PlanEvent.Submitted>().size)
        ex.cancel("p2")                                             // planId 不匹配的取消被忽略
        release.complete(Unit)
        drain()
        assertEquals(WritePlanExecutor.PlanState.Done(1), ex.state.value)
    }
}
