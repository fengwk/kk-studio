package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

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
          tx.confirmExecution(stored, ExecutionExitState.STUDIO_EXITED);
          return null;
        });
    runtime.acceptCommands(resume, AcceptancePreflight.IDENTITY);
    assertEquals(
        ThreadExecutionControl.RUNNABLE,
        runtime.getThreadSnapshot(baseline.threadId()).thread().executionControl());
  }
}
