package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userCustomMessageCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** STOPPED 且退出尚未确认时拒绝恢复输入；确认后的同一 Thread 可以恢复。 */
class ExecutionExitResumeTest {

  @Test
  void stoppedThreadRejectsResumeUntilItsExecutionIsConfirmed() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntime runtime =
        HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T0, ZoneOffset.UTC));
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    long version = runtime.getThreadSnapshot(baseline.threadId()).thread().version();
    runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), version));
    store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.registerExecution(
              new ExecutionExitFact(
                  TestIds.id(2),
                  baseline.threadId(),
                  TestIds.id(3),
                  1,
                  "lease-1",
                  ExecutionExitKind.TOOL,
                  null,
                  ExecutionExitState.UNCONFIRMED,
                  T0,
                  null,
                  null));
          return null;
        });
    AcceptCommandsCommand resume =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(baseline.threadId()),
            List.of(userMessageCommand(TestIds.id(4), "resume")));
    HarnessRuntimeConflictException rejected =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.acceptCommands(resume, AcceptancePreflight.IDENTITY));
    assertEquals(HarnessRuntimeConflictException.Reason.EXIT_UNCONFIRMED, rejected.reason());

    store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId());
          ExecutionExitFact stored = tx.findExecution(TestIds.id(2)).orElseThrow();
          tx.confirmExecution(stored, ExecutionExitState.STUDIO_EXITED, T0.plusMillis(1));
          return null;
        });
    runtime.acceptCommands(resume, AcceptancePreflight.IDENTITY);
    assertEquals(
        ThreadExecutionControl.RUNNABLE,
        runtime.getThreadSnapshot(baseline.threadId()).thread().executionControl());
  }

  @Test
  void stoppedThreadRejectsTrustedCustomAndGoalWhileItsExecutionIsUnconfirmed() {
    // 测试意图：CUSTOM 与 GOAL 都是可信 user-like 输入，STOPPED + unconfirmed 时同样拒绝恢复且零写入。
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntime runtime =
        HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T0, ZoneOffset.UTC));
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    stop(runtime, baseline.threadId(), TestIds.id(1));
    registerUnconfirmed(store, baseline.threadId(), TestIds.id(2));
    ThreadState stopped = runtime.getThreadSnapshot(baseline.threadId()).thread();

    assertExitUnconfirmed(
        runtime,
        threadCommand(
            baseline.threadId(),
            stopped.headEntryId(),
            stopped.nextCommandSequence(),
            userCustomMessageCommand(TestIds.id(10), "custom steer")));
    assertExitUnconfirmed(
        runtime,
        threadCommand(
            baseline.threadId(),
            stopped.headEntryId(),
            stopped.nextCommandSequence(),
            new NewThreadCommand(new GoalCommandPayload("finish"), TestIds.id(11))));

    assertTrue(
        store.<Boolean>transaction(tx -> tx.loadCommandsByThread(baseline.threadId()).isEmpty()));
    assertEquals(
        ThreadExecutionControl.STOPPED,
        runtime.getThreadSnapshot(baseline.threadId()).thread().executionControl());
  }

  @Test
  void exactReplayOfAcceptedTrustedInputTakesPrecedenceOverExitCheckAndKeepsStopped() {
    // 测试意图：已接收的 CUSTOM/GOAL 原样重放先于恢复准入，replayed=true 且不改变 STOPPED/version。
    assertReplayKeepsStopped(
        userCustomMessageCommand(TestIds.id(20), "custom steer"), TestIds.id(21));
    assertReplayKeepsStopped(
        new NewThreadCommand(new GoalCommandPayload("finish"), TestIds.id(22)), TestIds.id(23));
  }

  private static void assertReplayKeepsStopped(
      NewThreadCommand original, UUID unconfirmedExecutionId) {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntime runtime =
        HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T0, ZoneOffset.UTC));
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    ThreadState initial = runtime.getThreadSnapshot(baseline.threadId()).thread();
    AcceptCommandsCommand accept =
        threadCommand(
            baseline.threadId(), initial.headEntryId(), initial.nextCommandSequence(), original);
    AcceptedCommands accepted = runtime.acceptCommands(accept, AcceptancePreflight.IDENTITY);
    assertFalse(accepted.replayed());
    long sourceSequence = accepted.acceptedCommands().getFirst().sequence();

    stop(runtime, baseline.threadId(), TestIds.id(1));
    registerUnconfirmed(store, baseline.threadId(), unconfirmedExecutionId);
    ThreadState stopped = runtime.getThreadSnapshot(baseline.threadId()).thread();

    AcceptedCommands replay =
        runtime.acceptCommands(
            threadCommand(baseline.threadId(), stopped.headEntryId(), sourceSequence, original),
            AcceptancePreflight.IDENTITY);
    assertTrue(replay.replayed());
    assertEquals(original.idempotencyKey(), replay.acceptedCommands().getFirst().idempotencyKey());
    assertEquals(original.requestHash(), replay.acceptedCommands().getFirst().requestHash());
    assertEquals(sourceSequence, replay.acceptedCommands().getFirst().sequence());
    ThreadState after = runtime.getThreadSnapshot(baseline.threadId()).thread();
    assertEquals(ThreadExecutionControl.STOPPED, after.executionControl());
    assertEquals(stopped.version(), after.version());
  }

  private static void stop(HarnessRuntime runtime, UUID threadId, UUID stopRequestId) {
    long version = runtime.getThreadSnapshot(threadId).thread().version();
    runtime.stop(new StopCommand(threadId, stopRequestId, version));
  }

  private static void registerUnconfirmed(
      InMemoryHarnessStore store, UUID threadId, UUID executionId) {
    store.transaction(
        tx -> {
          tx.lockThread(threadId);
          tx.registerExecution(
              new ExecutionExitFact(
                  executionId,
                  threadId,
                  TestIds.id(3),
                  1,
                  "lease-1",
                  ExecutionExitKind.TOOL,
                  null,
                  ExecutionExitState.UNCONFIRMED,
                  T0,
                  null,
                  null));
          return null;
        });
  }

  private static AcceptCommandsCommand threadCommand(
      UUID threadId, UUID expectedHead, long expectedNext, NewThreadCommand command) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.Thread(threadId, expectedHead, expectedNext), List.of(command));
  }

  private static void assertExitUnconfirmed(HarnessRuntime runtime, AcceptCommandsCommand command) {
    HarnessRuntimeConflictException rejected =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.acceptCommands(command, AcceptancePreflight.IDENTITY));
    assertEquals(HarnessRuntimeConflictException.Reason.EXIT_UNCONFIRMED, rejected.reason());
  }
}
