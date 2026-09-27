package fun.fengwk.kkstudio.platform.project.model;

/** Issue 时间线活动类型。 */
public enum IssueActivityKind {
  /** 人或 Agent 的普通评论，不自动唤醒 Agent。 */
  COMMENT,

  /** Run 呈现，只引用 Run 而不复制正文，由 SYSTEM 生成。 */
  RUN,

  /** 投递给明确当前 Run 的指示。 */
  INSTRUCTION,

  /** 规格变更事件，使用 typed data。 */
  SPEC_CHANGE,

  /** 状态流转事件，使用 typed data。 */
  STATE_CHANGE,

  /** 控制事件（阻塞/恢复/暂停/额度），使用 typed data。 */
  CONTROL
}
