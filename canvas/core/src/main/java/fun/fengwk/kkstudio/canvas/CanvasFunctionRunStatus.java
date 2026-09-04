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
  CANCELLED
}
