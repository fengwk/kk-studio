package fun.fengwk.kkstudio.workspace;

import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.thread.ExecutionRootFacts;

/** Directory status is derived from one snapshot of the Harness execution tree. */
record ThreadStatus(State state, Reason reason) {
  enum State {
    NEEDS_YOU,
    RUNNING,
    WAITING,
    FAILED,
    IDLE
  }

  enum Reason {
    STOPPED
  }

  static ThreadStatus project(ExecutionRootFacts facts) {
    if (facts.waitingForHuman()) {
      return new ThreadStatus(State.NEEDS_YOU, null);
    }
    if (facts.hasWork()) {
      return new ThreadStatus(State.RUNNING, null);
    }
    if (facts.blocked()) {
      return new ThreadStatus(State.WAITING, null);
    }
    if (facts.stopped()) {
      return new ThreadStatus(State.IDLE, Reason.STOPPED);
    }
    if (facts.headOutcome() == TurnEndOutcome.FAILED) {
      return new ThreadStatus(State.FAILED, null);
    }
    return new ThreadStatus(State.IDLE, null);
  }
}
