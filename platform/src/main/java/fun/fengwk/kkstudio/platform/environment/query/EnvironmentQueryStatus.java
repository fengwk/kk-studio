package fun.fengwk.kkstudio.platform.environment.query;

/** 跨节点只读 Environment 目录查询生命周期。 */
public enum EnvironmentQueryStatus {
  PENDING,
  RUNNING,
  SUCCEEDED,
  FAILED
}
