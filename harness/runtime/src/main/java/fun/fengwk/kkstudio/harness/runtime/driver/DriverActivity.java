package fun.fengwk.kkstudio.harness.runtime.driver;

/** 公共 Runtime 可见的 Driver 当前活动类别；不暴露任何 Driver 私有状态。 */
public enum DriverActivity {
  /** 当前 open Turn 没有该 Driver 的待推进状态。 */
  NONE,
  /** Generation 正在执行，Runtime 只需等待。 */
  ACTIVE,
  /** Generation 已终止且等待 Runtime 触发结果物化。 */
  RESPONSE_PENDING
}
