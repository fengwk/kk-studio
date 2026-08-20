package fun.fengwk.kkstudio.harness.runtime;

/** Thread 当前是否允许手动压缩；disabledReason 为 null 当且仅当可用。 */
public record ManualCompactionAvailability(DisabledReason disabledReason) {

  public static ManualCompactionAvailability enabled() {
    return new ManualCompactionAvailability(null);
  }

  public static ManualCompactionAvailability disabled(DisabledReason reason) {
    if (reason == null) {
      throw new NullPointerException("reason");
    }
    return new ManualCompactionAvailability(reason);
  }

  public boolean available() {
    return disabledReason == null;
  }

  /** 稳定的 UI/API 禁用原因。 */
  public enum DisabledReason {
    THREAD_BUSY,
    OWNERSHIP_BARRIER,
    NO_RESOLVED_CONTEXT,
    MODEL_CHANGED,
    BELOW_MINIMUM,
    NOTHING_TO_COMPACT
  }
}
