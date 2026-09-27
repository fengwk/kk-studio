package fun.fengwk.kkstudio.platform.project.model;

/**
 * Issue 控制暂停原因。
 *
 * <p>控制暂停与业务 BLOCKED 分开：BLOCKED 表示业务无法继续并记录恢复目标，控制暂停只阻止新派发并要求显式恢复，两者可以并存但语义不同。
 */
public enum PauseReason {
  /** 人工停止/暂停。 */
  USER,

  /** 执行失败。 */
  ERROR,

  /** 在途副作用不明，必须人工核查后才能清理或重试。 */
  UNKNOWN
}
