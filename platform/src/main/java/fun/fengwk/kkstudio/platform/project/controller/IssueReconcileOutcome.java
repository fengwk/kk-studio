package fun.fengwk.kkstudio.platform.project.controller;

/** Reconcile 单次确定性动作的执行结果类型。 */
public enum IssueReconcileOutcome {
  /** BACKLOG/DONE/CANCELED/archived 等状态已收敛，完成 controller work。 */
  SKIPPED_CONVERGED,

  /** Project 已归档，禁止启动新 run，以 blockedDelay 延后。 */
  DEFERRED_ARCHIVED,

  /** Issue 依赖的前序 Issue 未处于 DONE 状态，以 blockedDelay 延后。 */
  DEFERRED_BLOCKED,

  /** TODO Issue 无 assignee，无法启动执行，完成 controller work。 */
  NO_ASSIGNEE,

  /** 创建 Executor run、Session 与初始命令，Issue 迁移至 IN_PROGRESS。 */
  EXECUTOR_STARTED,

  /** IN_REVIEW Issue 无 reviewer，等待人工介入，完成 controller work。 */
  WAITING_HUMAN_REVIEW,

  /** 尚无 Reviewer run 且最近 Executor 已 SUBMITTED，创建 Reviewer run 与 Session。 */
  REVIEWER_STARTED,

  /** FAILED/UNKNOWN Reviewer 观察到新 RETRY input，创建同角色新 run 与 Session。 */
  REVIEWER_RETRY_STARTED,

  /** 活跃 AGENT run 缺少 Session 关联，同事务补建 Session 与初始命令。 */
  SESSION_BOOTSTRAPPED,

  /** 活跃 run 已过 deadline，标记为 FAILED 并立即重新排队。 */
  RUN_DEADLINE_EXCEEDED,

  /** 观察到 Harness 进入 UNKNOWN（model/tool/metadata），活跃 run 标记为 UNKNOWN 并立即重新排队。 */
  RUN_UNKNOWN_HARNESS,

  /** Harness 处于处理中（non-idle 或 queued commands），以 activeDelay 短延后。 */
  RUN_PROCESSING,

  /** 静止状态下观察到新 issue_input，发送 USER continuation，恢复 WAITING_HUMAN 为 RUNNING。 */
  USER_CONTINUATION_SENT,

  /** WAITING_HUMAN 状态下且无新 input，通知 Coordinator；有 deadline 时调度到该时刻。 */
  WAITING_FOR_HUMAN,

  /** 静止且无新 input，且 continuation 预算可用，发送 SYSTEM steering 并递增 continuation_count。 */
  SYSTEM_CONTINUATION_SENT,

  /** 静止且 continuation 预算耗尽，活跃 run 标记为 FAILED 并立即重新排队。 */
  BUDGET_EXHAUSTED,

  /** 终态 FAILED/UNKNOWN 观察到新 RETRY input，创建同角色新 run 与 Session。 */
  RETRY_RUN_STARTED,

  /** 终态 FAILED/UNKNOWN 无新 RETRY input，仅尝试 coordinator attention 并完成 controller work。 */
  CONVERGED_FAILED
}
