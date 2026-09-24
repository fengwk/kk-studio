package fun.fengwk.kkstudio.platform.project.controller;

/** Reconcile 单次确定性动作的执行结果类型。 */
public enum IssueReconcileOutcome {
  /** BACKLOG/DONE/CANCELED/archived 等状态已收敛，完成 work。 */
  SKIPPED_CONVERGED,

  /** Project 已归档，禁止启动新 run，以 blockedDelay 延后。 */
  DEFERRED_ARCHIVED,

  /** Issue 依赖的前序 Issue 未处于 DONE 状态，以 blockedDelay 延后。 */
  DEFERRED_BLOCKED,

  /** TODO Issue 无 assignee，无法启动执行，完成 work。 */
  NO_ASSIGNEE,

  /** 创建 Executor run，Issue 迁移至 IN_PROGRESS。 */
  EXECUTOR_STARTED,

  /** IN_REVIEW Issue 无有效 reviewer 或与 assignee 相同，等待人工审查，完成 work。 */
  WAITING_HUMAN_REVIEW,

  /** BLOCKED Issue 等待人工恢复，完成 work。 */
  WAITING_HUMAN_RECOVERY,

  /** 尚无 Reviewer run 且最近 Executor 已 SUBMITTED，创建 Reviewer run。 */
  REVIEWER_STARTED,

  /** FAILED/UNKNOWN Reviewer 观察到新 RETRY fact，创建同角色新 run。 */
  REVIEWER_RETRY_STARTED,

  /** 活跃 run 缺少 Agent Session 关联，同事务补建 Session 与初始命令。 */
  SESSION_BOOTSTRAPPED,

  /** 活跃 run 已过 deadline，标记为 FAILED 并立即重新排队。 */
  RUN_DEADLINE_EXCEEDED,

  /** 观察到 Harness 进入 UNKNOWN（model/tool/metadata），活跃 run 标记为 UNKNOWN 并立即重新排队。 */
  RUN_UNKNOWN_HARNESS,

  /** Harness 处于处理中（non-idle 或 queued commands），以 activeDelay 短延后。 */
  RUN_PROCESSING,

  /** 对齐工作 Branch 的 YOLO 设置以匹配 Project。 */
  YOLO_ALIGNED,

  /** 识别到 Executor 的合规提交 Turn，完成 Executor run 并将 Issue 迁移至 IN_REVIEW。 */
  EXECUTOR_SUBMITTED,

  /** 静止状态下观察到新 IssueActivity，发送消息并递增游标。 */
  ACTIVITY_DELIVERED,

  /** 有界扫描窗口已读完但还没到事实流末端：已推进游标并立即重新排队继续扫描。 */
  ACTIVITY_SCAN_RESCHEDULED,

  /** WAITING_HUMAN 状态下且无新 activity，有 deadline 时调度到该时刻，无 deadline 完成 work。 */
  WAITING_FOR_HUMAN,

  /** 静止且无新 activity，且 continuation 预算可用，发送 SYSTEM steering 并递增 continuationCount。 */
  SYSTEM_CONTINUATION_SENT,

  /** 静止且 continuation 预算耗尽，活跃 run 标记为 FAILED 并立即重新排队。 */
  BUDGET_EXHAUSTED,

  /** 终态 FAILED/UNKNOWN 观察到新 RETRY fact，创建同角色新 run。 */
  RETRY_RUN_STARTED,

  /** 终态 FAILED/UNKNOWN 无新 RETRY fact，完成 work。 */
  CONVERGED_FAILED
}
