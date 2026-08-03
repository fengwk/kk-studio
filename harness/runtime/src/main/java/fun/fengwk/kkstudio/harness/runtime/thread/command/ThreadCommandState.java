package fun.fengwk.kkstudio.harness.runtime.thread.command;

/** Derived lifecycle state of one durable Thread command. */
public enum ThreadCommandState {
  QUEUED,
  APPLIED,
  CANCELLED;

  /** Whether the command has reached a terminal state. */
  public boolean isTerminal() {
    return this == APPLIED || this == CANCELLED;
  }
}
