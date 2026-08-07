package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedQueuedCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;

/**
 * 空闲 Stop：不创建任何 Entry，queued Command 在同一 now 被取消，revision 被精确推进一次， 仅删除 Work 不会触发 revision
 * bump，任何删除失败都会让整个事务回滚。
 */
class HarnessRuntimeStopIdleTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = new HarnessRuntime(store, Clock.fixed(T3, ZoneOffset.UTC));
  }

  @Test
  void idleWithoutCommandsOrWorkIsZeroEffect() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 0));
    assertEquals(StopResult.Status.IDLE, result.status());
    assertEquals(0, result.cancelledCommandCount());
    assertEquals(0L, result.thread().revision());
    assertEquals(baseline.rootEntryId(), result.thread().headEntryId());
  }

  @Test
  void idleWithQueuedCommandsCancelsAllAtOneNowAndBumpsRevisionExactlyOnce() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    seedQueuedCommand(store, baseline.threadId(), 1L, 1L, userMessagePayload("a"), "cid-1");
    seedQueuedCommand(store, baseline.threadId(), 2L, 2L, userMessagePayload("b"), "cid-2");
    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 0));
    assertEquals(StopResult.Status.IDLE, result.status());
    assertEquals(2, result.cancelledCommandCount());
    assertEquals(1L, result.thread().revision());
    assertEquals(1L, result.thread().nextCommandSequence());
    List<ThreadCommand> commands =
        store.transaction(
            tx -> {
              tx.lockThread(baseline.threadId());
              return tx.loadQueuedCommands(baseline.threadId());
            });
    assertTrue(commands.isEmpty());
    for (long id : List.of(1L, 2L)) {
      ThreadCommand command =
          store.transaction(
              tx ->
                  tx.findCommandByClientId(baseline.threadId(), id == 1 ? "cid-1" : "cid-2")
                      .orElseThrow());
      assertEquals(ThreadCommandState.CANCELLED, command.state());
      assertEquals(T3, command.cancelledAt());
    }
  }

  @Test
  void idleWorkOnlyDeletionDoesNotBumpRevision() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    seedThreadWork(store, baseline.threadId());
    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 0));
    assertEquals(StopResult.Status.IDLE, result.status());
    assertEquals(0, result.cancelledCommandCount());
    assertEquals(0L, result.thread().revision());
    assertFalse(
        store
            .transaction(
                tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId())))
            .isPresent());
  }

  @Test
  void idleWithCommandsAndWorkBumpsRevisionAndDeletesTheWorkRow() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    seedQueuedCommand(store, baseline.threadId(), 1L, 1L, userMessagePayload("a"), "cid-1");
    seedThreadWork(store, baseline.threadId());
    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), "stop-1", 0));
    assertEquals(1, result.cancelledCommandCount());
    assertEquals(1L, result.thread().revision());
    assertFalse(
        store
            .transaction(
                tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId())))
            .isPresent());
    ThreadCommand command =
        store.transaction(
            tx -> tx.findCommandByClientId(baseline.threadId(), "cid-1").orElseThrow());
    assertEquals(ThreadCommandState.CANCELLED, command.state());
  }

  /** deleteWork 是 final mutation：失败时整个事务回滚，Command 取消与 revision bump 都不落盘。 */
  @Test
  void deleteFailureRollsBackCommandsRevisionAndWorkDeletion() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    seedQueuedCommand(store, baseline.threadId(), 1L, 1L, userMessagePayload("a"), "cid-1");
    seedThreadWork(store, baseline.threadId());
    HarnessRuntime failingRuntime =
        new HarnessRuntime(storeFailingDeleteWork(store), Clock.fixed(T3, ZoneOffset.UTC));
    assertThrows(
        IllegalStateException.class,
        () -> failingRuntime.stop(new StopCommand(baseline.threadId(), "stop-1", 0)));
    ThreadState thread = store.transaction(tx -> tx.lockThread(baseline.threadId()).orElseThrow());
    assertEquals(0L, thread.revision());
    ThreadCommand command =
        store.transaction(
            tx -> tx.findCommandByClientId(baseline.threadId(), "cid-1").orElseThrow());
    assertEquals(ThreadCommandState.QUEUED, command.state());
    assertTrue(
        store
            .transaction(
                tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId())))
            .isPresent());
  }

  /** 仅测试用的委托 store：其事务使每次 deleteWork 调用都失败。 */
  private static HarnessStore storeFailingDeleteWork(InMemoryHarnessStore delegate) {
    return (HarnessStore)
        Proxy.newProxyInstance(
            HarnessStore.class.getClassLoader(),
            new Class<?>[] {HarnessStore.class},
            (proxy, method, args) -> {
              if (method.getName().equals("transaction")) {
                @SuppressWarnings("unchecked")
                Function<HarnessStore.Transaction, Object> callback =
                    (Function<HarnessStore.Transaction, Object>) args[0];
                return delegate.transaction(
                    tx -> {
                      HarnessStore.Transaction wrapped =
                          (HarnessStore.Transaction)
                              Proxy.newProxyInstance(
                                  HarnessStore.Transaction.class.getClassLoader(),
                                  new Class<?>[] {HarnessStore.Transaction.class},
                                  (transactionProxy, transactionMethod, transactionArgs) -> {
                                    if (transactionMethod.getName().equals("deleteWork")) {
                                      throw new IllegalStateException("fenced delete failure");
                                    }
                                    return transactionMethod.invoke(tx, transactionArgs);
                                  });
                      return callback.apply(wrapped);
                    });
              }
              return method.invoke(delegate, args);
            });
  }
}
