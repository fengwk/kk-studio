package fun.fengwk.kkstudio.canvas;

/** 画布函数执行的生命周期状态。 */
public enum CanvasFunctionRunStatus {
  /** 函数执行已就绪，等待分派执行。 */
  READY,

  /** 函数执行正在运行中。 */
  RUNNING,

  /** 函数执行成功完成并产出结果。 */
  SUCCEEDED,

  /** 函数执行因错误终止并记录失败信息。 */
  FAILED,

  /** 函数执行已被主动取消。 */
  CANCELLED,

  /**
   * 外部提交结果不明，自动调度已退出。
   *
   * <p>保留本次 pin 与冻结计划，只能由人工核查事实后解除：继续查询原任务或确认失败/取消，不得当作新请求重新提交。
   */
  UNKNOWN
}
