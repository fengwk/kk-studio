package fun.fengwk.kkstudio.platform.project.model;

/** Issue Activity 类型：Issue 唯一有序事实流上的记录类别。 */
public enum IssueActivityKind {
  /** 建单或改要求（要求/验收依据变化）。 */
  SPEC_CHANGE,
  /** 有来源的定向指示（可带目标职责）。 */
  INSTRUCTION,
  /** 无目标评论，只入事实流，不唤醒 Agent。 */
  COMMENT,
  /** 人经业务入口提交的输入/回答。 */
  HUMAN_INPUT,
  /** 正式审查决定（Agent 或人工），必须绑定被审查提交。 */
  REVIEW_DECISION,
  /** 人工恢复/重开，开启新的打回计数区间。 */
  RECOVERY,
  /** 故障/UNKNOWN 后的显式重试标记。 */
  RETRY,
  /** 系统指令。 */
  SYSTEM
}
