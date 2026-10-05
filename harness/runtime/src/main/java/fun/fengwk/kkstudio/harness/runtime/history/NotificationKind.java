package fun.fengwk.kkstudio.harness.runtime.history;

/**
 * 系统通知的类型：由 Runtime 物化为历史，对模型是上下文，不是更高权限指令。
 *
 * <p>普通客户端不能伪造内部通知；用户写出的 {@code <system-reminder>} 仍然是用户输入。
 */
public enum NotificationKind {
  /** 子 Thread 委派执行结果（subagent completion）。 */
  SUBAGENT_RESULT,
  /** 委派任务短期 turn 预算提醒。 */
  TASK_BUDGET
}
