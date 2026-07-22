package fun.fengwk.kkstudio.harness.kernel.thread;

/**
 * ThreadInput 的生命周期状态枚举。
 *
 * <p>状态机：{@code QUEUED -> APPLIED | CANCELLED}；进入 terminal 后不可修改。
 */
public enum InputStatus {
  /** 已 enqueue，等待 Reconciler 在下一个 turn boundary 应用。 */
  QUEUED,
  /** 已成功应用为对应 Entry 或配置变更。 */
  APPLIED,
  /** 被 Stop 或失效 Thread 主动取消；不会产生 Entry。 */
  CANCELLED;

  /** 是否为 terminal（APPLIED / CANCELLED）。 */
  public boolean isTerminal() {
    return this == APPLIED || this == CANCELLED;
  }
}
