package fun.fengwk.kkstudio.platform.environment.query;

/** 跨节点只读 Environment 目录查询生命周期。 */
public enum EnvironmentQueryStatus {
  /** 查询任务已创建，等待调度执行。 */
  PENDING,

  /** 正在执行目录查询。 */
  RUNNING,

  /** 目录查询成功完成并返回清单。 */
  SUCCEEDED,

  /** 目录查询执行失败。 */
  FAILED
}
