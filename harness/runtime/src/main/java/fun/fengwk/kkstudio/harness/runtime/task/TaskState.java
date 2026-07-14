package fun.fengwk.kkstudio.harness.runtime.task;

/** Durable subagent task lifecycle, independent from the child run lifecycle. */
public enum TaskState {
  RUNNING,
  SUCCEEDED,
  FAILED,
  CANCELLED;

  public boolean terminal() {
    return this != RUNNING;
  }
}
