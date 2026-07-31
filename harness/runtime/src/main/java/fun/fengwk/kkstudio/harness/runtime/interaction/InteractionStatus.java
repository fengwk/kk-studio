package fun.fengwk.kkstudio.harness.runtime.interaction;

/** Durable Interaction lifecycle. Every state except {@link #OPEN} is terminal and immutable. */
public enum InteractionStatus {
  OPEN,
  RESOLVED;

  /** Returns whether no further response or lifecycle transition is permitted. */
  public boolean isTerminal() {
    return this != OPEN;
  }
}
