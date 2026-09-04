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
    /** 线程非空闲、存在开放 Turn，或已有自动压缩待执行。 */
    THREAD_BUSY,

    /** 回溯候选历史时遇到归属其他 Thread 的已关闭 Turn。 */
    OWNERSHIP_BARRIER,

    /** 找不到带已解析上下文窗口与输出上限的可用历史 Turn。 */
    NO_RESOLVED_CONTEXT,

    /** 基础模型配置已变更，无法沿用历史上下文执行压缩。 */
    MODEL_CHANGED,

    /** 预计投影 token 数低于手动压缩的最小阈值。 */
    BELOW_MINIMUM,

    /** 当前历史无可压缩内容。 */
    NOTHING_TO_COMPACT
  }
}
