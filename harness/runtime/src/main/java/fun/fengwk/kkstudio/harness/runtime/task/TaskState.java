package fun.fengwk.kkstudio.harness.runtime.task;

/** Durable subagent task lifecycle, independent from the child thread lifecycle. */
public enum TaskState {
  RUNNING,
  SUCCEEDED,
  FAILED,
  CANCELLED;

  public boolean terminal() {
    return this != RUNNING;
  }
}
