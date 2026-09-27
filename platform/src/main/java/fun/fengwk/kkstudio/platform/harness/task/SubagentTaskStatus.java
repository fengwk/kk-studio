package fun.fengwk.kkstudio.platform.harness.task;

/** 异步 task 委派的三段状态机：把「子执行终结」与「父通知完成」显式分开，使停止的父既不占并发额度、又不丢失结果。 */
public enum SubagentTaskStatus {
  /** 子执行仍在进行（含其子树仍在进行）：占并发额度，等待子线程结清。 */
  OPEN,

  /** 子执行已终结，终态与报告已持久化在记录行上，父通知尚未入队：不占并发额度；父 Thread 处于停止态时保持本状态，恢复后再交付。 */
  SETTLED,

  /** 结果已作为父 Thread 的一条 CUSTOM_MESSAGE 入队，并与本状态更新同事务提交。 */
  DELIVERED
}
