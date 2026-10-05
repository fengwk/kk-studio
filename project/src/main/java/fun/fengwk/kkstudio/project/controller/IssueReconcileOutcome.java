package fun.fengwk.kkstudio.project.controller;

/** Reconcile 单次确定性动作的结果类型。 */
public enum IssueReconcileOutcome {
  /** Issue 已归档、DONE 或不再存在：没有待推进事实，结束本次 claim。 */
  CONVERGED_ARCHIVED,

  /** 当前阶段是保留阶段、停用阶段或人工阶段：不产生 Run，结束本次 claim。 */
  CONVERGED_NO_AGENT,

  /** 阶段额度已用尽：只展示待人工授权，不继续调用模型，结束本次 claim。 */
  CONVERGED_BUDGET_EXHAUSTED,

  /** 存在无法派发的确定性拒绝（暂停门禁、缺失 Agent 等）：结束本次 claim，等待显式恢复。 */
  CONVERGED_UNDISPATCHABLE,

  /** 正接受一次新的 Run，随后重新检查。 */
  RUN_ACCEPTED,

  /** 在途执行（queued command、模型或工具调用）尚未静止：按 activeDelay 重新检查。 */
  DEFERRED_PROCESSING,

  /** 已到达安全点但门禁仍关闭：Run 置 WAITING 并停止活动计时，gate 打开后恢复同一 Run。 */
  WAITING_FOR_GATE,

  /** WAITING 的 Run 门禁已打开：恢复同一 Run 的活动计时，不消耗新的阶段额度。 */
  RUN_RESUMED,

  /** 未处理的定向指示已投递给明确的当前 Run，游标随之推进。 */
  INSTRUCTION_DELIVERED,

  /** 已接受交接且执行完全静止：Run 正常收尾并提交目标阶段。 */
  RUN_HANDED_OFF,

  /** 正常收尾但没有交接：Node 保持当前阶段，后续按额度新建 Run。 */
  RUN_COMPLETED,

  /** 目标执行以失败终态结束（Join outcome=ERROR）：Run 失败收尾，绝不借此推进阶段。 */
  RUN_FAILED,

  /** 目标执行被取消终态结束（Join outcome=CANCELLED，含显式 Stop）：Run 取消收尾，绝不借此推进阶段。 */
  RUN_CANCELLED,

  /** 活动时长额度耗尽：Run 失败收尾并与 ERROR 暂停门禁原子写入。 */
  RUN_BUDGET_EXHAUSTED,

  /** 迟到的旧 Run（阶段或归属已不匹配）：只做安全收尾，绝不推进阶段。 */
  STALE_RUN_CLOSED,

  /** 执行静止但仍未产出可收尾区间：按 activeDelay 重新检查。 */
  DEFERRED_IDLE,

  /** Run 终态且门禁打开但阶段额度已用尽：以 blockedDelay 重新检查，保留最后一次恢复能力。 */
  DEFERRED_AWAITING_AUTHORIZATION
}
