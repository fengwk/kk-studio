package fun.fengwk.kkstudio.harness.runtime.thread;

/** Thread 持久状态；与 processor lease 正交。 */
public enum ThreadStatus {
  IDLE("idle"),
  RUNNING("running"),
  WAITING("waiting"),
  FAILED("failed"),
  RETRYING("retrying");

  private final String value;

  ThreadStatus(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  public static ThreadStatus fromValue(String value) {
    for (ThreadStatus status : values()) {
      if (status.value.equals(value) || status.name().equalsIgnoreCase(value)) {
        return status;
      }
    }
    throw new IllegalArgumentException("unknown thread status: " + value);
  }

  public boolean isRunnable() {
    return this == RUNNING || this == WAITING || this == RETRYING;
  }
}
