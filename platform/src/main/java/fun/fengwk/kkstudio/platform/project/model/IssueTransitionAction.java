package fun.fengwk.kkstudio.platform.project.model;

/** Issue 状态迁移动作。 */
public enum IssueTransitionAction {
  /** 人工标记就绪：BACKLOG -> TODO。 */
  READY,
  /** 暂缓：TODO -> BACKLOG。 */
  DEFER,
  /** 派发执行 Run：TODO -> IN_PROGRESS。 */
  START_EXECUTION,
  /** 合格最终报告提交审查：IN_PROGRESS -> IN_REVIEW。 */
  SUBMIT,
  /** 正式审查打回：IN_REVIEW -> TODO（未达阈值）或 BLOCKED（达到阈值）。 */
  REQUEST_CHANGES,
  /** 人工恢复（要求不变）：BLOCKED -> TODO。 */
  RECOVER,
  /** 人工恢复（先改要求）：BLOCKED -> BACKLOG。 */
  RECOVER_TO_BACKLOG,
  /** 明确批准：IN_REVIEW -> DONE。 */
  APPROVE,
  /** 授权取消：非终态（含 BLOCKED）-> CANCELED。 */
  CANCEL,
  /** 授权重开：DONE/CANCELED -> TODO。 */
  REOPEN
}
