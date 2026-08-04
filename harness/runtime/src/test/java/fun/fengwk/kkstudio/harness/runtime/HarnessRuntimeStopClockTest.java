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

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

/**
 * Stop reads its Clock only after the whole Work pre-lock: a store proxy that advances the mutable
 * clock the moment any Work row is locked must be reflected in every timestamp the transaction
 * writes (Thread, Model, Command and Entries).
 */
class HarnessRuntimeStopClockTest {

  @Test
  void timestampIsCapturedAfterTheWorkLocks() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.ModelBaseline baseline =
        seedModel(store, ModelInvocationStatus.READY);
    seedQueuedCommand(store, baseline.threadId(), 1L, 1L, userMessagePayload("hi"), "cid-1");
    seedThreadWork(store, baseline.threadId());
    seedModelWork(store, baseline.modelId());
    TestClock clock = new TestClock(T0);
    HarnessRuntime runtime =
        new HarnessRuntime(storeAdvancingClockOnWorkLock(store, clock, T3), clock);

    runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 0));

    ThreadState thread = store.transaction(tx -> tx.lockThread(baseline.threadId()).orElseThrow());
    assertEquals(T3, thread.updatedAt());
    ModelInvocation model =
        store.transaction(tx -> tx.findModelInvocation(baseline.modelId()).orElseThrow());
    assertEquals(T3, model.updatedAt());
    ThreadCommand command =
        store.transaction(
            tx -> tx.findCommandByClientId(baseline.threadId(), "cid-1").orElseThrow());
    assertEquals(T3, command.cancelledAt());
    // Stop 追加的 barrier 与 TURN_END（path 最后两个 Entry）必须使用 Work 锁后的时间。
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(thread.headEntryId()));
    assertEquals(5, path.entries().size());
    assertEquals(T3, path.entries().get(3).createdAt());
    assertEquals(T3, path.entries().get(4).createdAt());
  }
}
