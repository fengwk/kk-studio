package fun.fengwk.kkstudio.platform.environment.query;

/** 跨节点只读 Environment 查询生命周期。 */
public enum EnvironmentQueryStatus {
  PENDING,
  RUNNING,
  COMPLETED,
  FAILED,
  EXPIRED
}
