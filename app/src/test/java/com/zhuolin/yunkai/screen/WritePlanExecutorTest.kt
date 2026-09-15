package com.zhuolin.yunkai.screen

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

// 写操作计划状态机单测（M2a-T5）：提交/批准/敏感急停/外发二次确认/取消/失败跳过。
// 执行器与敏感检测均为 fake：executor 直接返回 true/false，敏感检测按动作特征命中。
class WritePlanExecutorTest {

    // 订阅必须早于 submit：用 Unconfined 收集器让订阅在 launch 处同步建立。
    private fun TestScope.executor(
        events: MutableList<PlanEvent>,
        sensitive: suspend (WriteAction) -> String? = { null },
        exec: suspend (WriteAction) -> Boolean = { true },
    ): WritePlanExecutor {
        val e = WritePlanExecutor(backgroundScope, sensitive, exec)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { e.events.collect { events += it } }
        return e
    }

    // 推进挂起的执行循环。注意 runCurrent 必须在前：advanceUntilIdle 只清前台任务，
    // 队列里只剩 backgroundScope（执行循环所在）任务时会直接返回，计划的推进全靠 runCurrent。
    private fun TestScope.drain() {
        runCurrent()
        advanceUntilIdle()
    }

    @Test fun submit_pendingAndSubmittedEvent() = runTest {
        val events = mutableListOf<PlanEvent>()
        val ex = executor(events)
        assertTrue(ex.submit("p1", listOf(WriteAction.Tap(1, 2)), listOf("点搜索"), sensitiveHint = false))
        assertEquals(WritePlanExecutor.PlanState.Pending, ex.state.value)
        assertEquals(listOf(PlanEvent.Submitted("p1", listOf("点搜索"))), events)
    }

    @Test fun approve_executesAllSteps_thenDone() = runTest {
        val events = mutableListOf<PlanEvent>()
        val ex = executor(events)
        ex.submit("p1", listOf(WriteAction.Tap(1, 2), WriteAction.Back, WriteAction.Home), listOf("点", "返回", "回桌面"), false)
        ex.approve("p1")
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
        ex.approve("p1")
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
        ex.approve("p1")
        drain()
        assertEquals(WritePlanExecutor.PlanState.Executing(0), ex.state.value)
        ex.cancel("p1")                                            // 执行中取消
        release.complete(Unit)                                     // 当前步收尾
        drain()
        assertEquals(WritePlanExecutor.PlanState.Stopped(1), ex.state.value)
        assertEquals(PlanEvent.Stopped("p1", 1), events.last())
        assertEquals(1, events.filterIsInstance<PlanEvent.StepEcho>().size)
    }

    @Test fun cancel_whilePending_goesStopped() = runTest {
        val events = mutableListOf<PlanEvent>()
        val ex = executor(events)
        ex.submit("p1", listOf(WriteAction.Back), listOf("返回"), false)
        ex.cancel("p1")
        drain()
        assertEquals(WritePlanExecutor.PlanState.Stopped(0), ex.state.value)
        assertEquals(PlanEvent.Stopped("p1", 0), events.last())
    }

    @Test fun input_needsSendConfirm_beforeExecute() = runTest {
        val events = mutableListOf<PlanEvent>()
        var calls = 0
        val ex = executor(events, exec = { calls++; true })
        ex.submit("p1", listOf(WriteAction.Input("你好")), listOf("输入文本"), false)
        ex.approve("p1")
        drain()
        assertEquals(WritePlanExecutor.PlanState.SendConfirmPaused(0), ex.state.value)
        assertEquals(PlanEvent.SendConfirmNeeded("p1", 0), events.last())
        assertTrue(events.none { it is PlanEvent.StepEcho })
        assertEquals(0, calls)                                     // 未 approveSend 不执行
        ex.approveSend("p1")
        drain()
        assertEquals(WritePlanExecutor.PlanState.Done(1), ex.state.value)
        assertEquals(1, calls)
    }

    @Test fun executorFailure_marksEchoAndContinues() = runTest {
        val events = mutableListOf<PlanEvent>()
        val ex = executor(events, exec = { it != WriteAction.Tap(1, 2) })
        ex.submit("p1", listOf(WriteAction.Tap(1, 2), WriteAction.Back), listOf("点我", "返回"), false)
        ex.approve("p1")
        drain()
        assertEquals(WritePlanExecutor.PlanState.Done(1), ex.state.value)   // 失败步不计入 executed
        val echoes = events.filterIsInstance<PlanEvent.StepEcho>()
        assertEquals(listOf("点我", "[失败] 点我", "返回"), echoes.map { it.label })
        assertEquals(PlanEvent.Done("p1", 1), events.last())
    }

    @Test fun duplicateSubmit_rejected() = runTest {
        val events = mutableListOf<PlanEvent>()
        val ex = executor(events)
        assertTrue(ex.submit("p1", listOf(WriteAction.Back), listOf("返回"), false))
        assertFalse(ex.submit("p2", listOf(WriteAction.Home), listOf("回桌面"), false))
        assertEquals(WritePlanExecutor.PlanState.Pending, ex.state.value)   // 原计划不受影响
        assertEquals(1, events.filterIsInstance<PlanEvent.Submitted>().size)
        ex.approve("p2")                                            // planId 不匹配的批准被忽略
        drain()
        assertEquals(WritePlanExecutor.PlanState.Pending, ex.state.value)
    }
}
