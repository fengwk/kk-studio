package fun.fengwk.kkstudio.harness.runtime.execution;

/**
 * Model 与 Tool Invocation 的统一状态枚举。
 *
 * <p>状态机不变量：
 *
 * <ul>
 *   <li>{@link #SUCCEEDED}, {@link #FAILED}, {@link #CANCELLED}, {@link #UNKNOWN} 为 terminal。
 *   <li>{@link #RETRY_WAIT} 必须存在非空的 {@code nextAttemptAt}，用于 due scheduler 重投。
 *   <li>{@link #RUNNING} 必须存在有效 worker lease 与非空的 {@code startedAt}。
 *   <li>{@link #UNKNOWN} 仅用于 worker 在无法确认副作用结果时落库；不视为"成功"。
 * </ul>
 */
public enum InvocationStatus {
  QUEUED,
  RUNNING,
  RETRY_WAIT,
  SUCCEEDED,
  FAILED,
  CANCELLED,
  UNKNOWN;

  /** 该状态是否为 terminal（不再可被任何 worker/owner 推进）。 */
  public boolean isTerminal() {
    return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == UNKNOWN;
  }
}
