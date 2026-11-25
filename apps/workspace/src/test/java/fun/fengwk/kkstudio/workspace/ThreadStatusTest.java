package fun.fengwk.kkstudio.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.thread.ExecutionRootFacts;

import java.time.Instant;
import java.util.UUID;

class ThreadStatusTest {
  /** Mixed descendant facts must not hide human intervention or runnable work. */
  @Test
  void obligationsTakePriorityOverTheHead() {
    assertEquals(
        ThreadStatus.State.NEEDS_YOU,
        project(true, true, true, false, TurnEndOutcome.FAILED).state());
    assertEquals(
        ThreadStatus.State.RUNNING,
        project(false, true, true, false, TurnEndOutcome.FAILED).state());
    assertEquals(
        ThreadStatus.State.WAITING,
        project(false, false, true, true, TurnEndOutcome.FAILED).state());
  }

  /** Only the current failed head counts, and stopping is not a failure. */
  @Test
  void settlesFromTheHeadAndStopMarker() {
    assertEquals(
        new ThreadStatus(ThreadStatus.State.FAILED, null),
        project(false, false, false, false, TurnEndOutcome.FAILED));
    assertEquals(
        new ThreadStatus(ThreadStatus.State.IDLE, ThreadStatus.Reason.STOPPED),
        project(false, false, false, true, TurnEndOutcome.FAILED));
    for (TurnEndOutcome outcome :
        new TurnEndOutcome[] {
          null, TurnEndOutcome.COMPLETED, TurnEndOutcome.STOPPED, TurnEndOutcome.CANCELLED
        }) {
      assertEquals(
          new ThreadStatus(ThreadStatus.State.IDLE, null),
          project(false, false, false, false, outcome));
    }
  }

  private static ThreadStatus project(
      boolean human, boolean work, boolean blocked, boolean stopped, TurnEndOutcome outcome) {
    return ThreadStatus.project(
        new ExecutionRootFacts(
            new UUID(0, 1), human, work, blocked, stopped, outcome, Instant.EPOCH));
  }
}
