package fun.fengwk.kkstudio.harness.runtime.thread;

/** ThreadInput 状态机：QUEUED -> APPLIED | CANCELLED。 */
public enum ThreadInputStatus {
  QUEUED("queued"),
  APPLIED("applied"),
  CANCELLED("cancelled");

  private final String value;

  ThreadInputStatus(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  public static ThreadInputStatus fromValue(String value) {
    for (ThreadInputStatus status : values()) {
      if (status.value.equals(value) || status.name().equalsIgnoreCase(value)) {
        return status;
      }
    }
    throw new IllegalArgumentException("unknown thread input status: " + value);
  }
}
