package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.TestClock;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedModel;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedModelWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedQueuedCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.storeAdvancingClockOnWorkLock;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

/**
 * Stop 仅在整个 Work 预锁之后才读取其 Clock：store 代理会在任意 Work 行被锁定那一刻推进可变
 * clock，这一推进必须反映到事务写入的每一个时间戳（Thread、Model、Command 与 Entries）。
 */
class HarnessRuntimeStopClockTest {

  @Test
  void timestampIsCapturedAfterTheWorkLocks() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        seedModel(store, ModelInvocationStatus.READY);
    seedQueuedCommand(store, baseline.threadId(), 1L, userMessagePayload("hi"), TestIds.id(1));
    seedThreadWork(store, baseline.threadId());
    seedModelWork(store, baseline.modelId());
    TestClock clock = new TestClock(T0);
    HarnessRuntime runtime =
        HarnessRuntimeTestSupport.runtime(storeAdvancingClockOnWorkLock(store, clock, T3), clock);

    runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));

    ThreadState thread = store.transaction(tx -> tx.lockThread(baseline.threadId()).orElseThrow());
    assertEquals(T3, thread.updatedAt());
    // Model 行在 Stop 关闭 turn 时被物理删除（closed turn 不保留 Invocation）；post-lock 时间戳由
    // barrier/TURN_END Entry 与 cancelled command 承接。
    assertTrue(store.transaction(tx -> tx.findModelInvocation(baseline.modelId())).isEmpty());
    ThreadCommand command =
        store.transaction(
            tx -> tx.findCommandByIdempotencyKey(baseline.threadId(), TestIds.id(1)).orElseThrow());
    assertEquals(T3, command.cancelledAt());
    // Stop 追加的 barrier 与 TURN_END（path 最后两个 Entry）必须使用 Work 锁后的时间。
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(thread.headEntryId()));
    assertEquals(5, path.entries().size());
    assertEquals(T3, path.entries().get(3).createdAt());
    assertEquals(T3, path.entries().get(4).createdAt());
  }
}
