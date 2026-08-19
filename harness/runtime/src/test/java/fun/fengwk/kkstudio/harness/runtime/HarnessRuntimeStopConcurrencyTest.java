package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.assertStopped;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.setWaitingApproval;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException.Reason;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Stop 控制面竞态：Thread 加锁与 revision CAS 在 Approval 和 MOVE_HEAD 之间只允许一种线性化， 而确定性的赢家顺序测试保留 Approval 的
 * replay 语义。
 */
class HarnessRuntimeStopConcurrencyTest {

  @Test
  void approvalThenStopDeletesTheToolRowSoFurtherReplayIsNotApplicable() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntime runtime = runtime(store);
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    ToolApprovalCommand approval = allow(baseline);
    runtime.decideToolApproval(approval);

    StopResult stopped = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 2));

    assertStopped(stopped);
    // stopTools 在同一事务删除 tool 行；后续 decision replay 因找不到 durable row 而业务冲突拒绝。
    assertTrue(store.transaction(tx -> tx.findToolInvocation(baseline.toolId())).isEmpty());
    HarnessRuntimeConflictException replayError =
        assertThrows(
            HarnessRuntimeConflictException.class, () -> runtime.decideToolApproval(approval));
    assertEquals(Reason.APPROVAL_NOT_APPLICABLE, replayError.reason());
  }

  @Test
  void stopThenApprovalCannotReopenAnUndecidedTool() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntime runtime = runtime(store);
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);

    runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 1));
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.decideToolApproval(allow(baseline)));

    assertEquals(Reason.APPROVAL_NOT_APPLICABLE, error.reason());
    // Stop 已删除 tool 行；decision 不再能 undecided WAITING_APPROVAL → READY 转换。
    assertTrue(store.transaction(tx -> tx.findToolInvocation(baseline.toolId())).isEmpty());
  }

  @Test
  void concurrentStopAndApprovalHaveExactlyOneBusinessWinner() throws Exception {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntime runtime = runtime(store);
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Attempt> stop =
          pool.submit(
              attempt(
                  barrier,
                  () -> runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 1))));
      Future<Attempt> approval =
          pool.submit(attempt(barrier, () -> runtime.decideToolApproval(allow(baseline))));
      Attempt stopAttempt = stop.get();
      Attempt approvalAttempt = approval.get();
      assertEquals(1, successCount(stopAttempt, approvalAttempt));

      if (stopAttempt.error() == null) {
        // Stop 胜出：tool 行在同一事务被 stopTools 物理删除，approval 因 TOOL_ACTIVE 不再属于当前
        // 上下文而被业务冲突拒绝。
        assertTrue(store.transaction(tx -> tx.findToolInvocation(baseline.toolId())).isEmpty());
        assertConflict(approvalAttempt, Reason.APPROVAL_NOT_APPLICABLE);
      } else {
        assertConflict(stopAttempt, Reason.STALE_REVISION);
        ToolInvocation tool =
            store.transaction(tx -> tx.findToolInvocation(baseline.toolId()).orElseThrow());
        assertEquals(ToolInvocationStatus.READY, tool.status());
        assertEquals(ToolApprovalDecision.ALLOWED, tool.approval().decision());
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * 同 Thread 上的两个并发 acceptCommands（相同 cursor、不同 client id）：Thread 行锁串行化后恰好一个赢得 cursor
   * admission，败者必然 STALE_COMMAND_CURSOR，最终 nextCommandSequence 精确推进一次。
   */
  @Test
  void concurrentAcceptsOnSameThreadHaveExactlyOneCursorWinner() throws Exception {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntime runtime = runtime(store);
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Attempt> first =
          pool.submit(
              attempt(
                  barrier,
                  () ->
                      runtime.acceptCommands(
                          new AcceptCommandsCommand(
                              new AcceptCommandsTarget.Thread(
                                  baseline.threadId(), baseline.rootEntryId(), 1),
                              List.of(userMessageCommand(TestIds.id(1), "a"))),
                          AcceptancePreflight.IDENTITY)));
      Future<Attempt> second =
          pool.submit(
              attempt(
                  barrier,
                  () ->
                      runtime.acceptCommands(
                          new AcceptCommandsCommand(
                              new AcceptCommandsTarget.Thread(
                                  baseline.threadId(), baseline.rootEntryId(), 1),
                              List.of(userMessageCommand(TestIds.id(2), "b"))),
                          AcceptancePreflight.IDENTITY)));
      Attempt one = first.get();
      Attempt two = second.get();
      assertEquals(1, successCount(one, two));
      Attempt winner = one.error() == null ? one : two;
      Attempt loser = one.error() == null ? two : one;
      assertConflict(loser, Reason.STALE_COMMAND_CURSOR);
      assertEquals(1, ((AcceptedCommands) winner.value()).acceptedCommands().size());
      ThreadState thread =
          store.transaction(tx -> tx.lockThread(baseline.threadId()).orElseThrow());
      assertEquals(2L, thread.nextCommandSequence());
      assertEquals(1L, thread.revision());
    } finally {
      pool.shutdownNow();
    }
  }

  private static HarnessRuntime runtime(InMemoryHarnessStore store) {
    return new HarnessRuntime(store, Clock.fixed(T5, ZoneOffset.UTC));
  }

  private static ToolApprovalCommand allow(HarnessRuntimeTestSupport.ToolBaseline baseline) {
    return new ToolApprovalCommand(
        baseline.threadId(),
        baseline.toolId(),
        ToolApprovalDecision.ALLOWED,
        TestIds.id(1),
        "alice",
        null);
  }

  private static Callable<Attempt> attempt(CyclicBarrier barrier, Callable<?> action) {
    return () -> {
      barrier.await();
      try {
        return new Attempt(action.call(), null);
      } catch (Throwable error) {
        return new Attempt(null, error);
      }
    };
  }

  private static int successCount(Attempt first, Attempt second) {
    return (first.error() == null ? 1 : 0) + (second.error() == null ? 1 : 0);
  }

  private static void assertConflict(Attempt attempt, Reason reason) {
    assertTrue(attempt.error() instanceof HarnessRuntimeConflictException);
    assertEquals(reason, ((HarnessRuntimeConflictException) attempt.error()).reason());
  }

  private record Attempt(Object value, Throwable error) {}
}
