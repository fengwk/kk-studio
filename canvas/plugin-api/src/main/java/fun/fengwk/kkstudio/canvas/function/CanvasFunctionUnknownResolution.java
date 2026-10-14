package fun.fengwk.kkstudio.canvas.function;

/** 人工核查 UNKNOWN Run 后的唯一处置：继续查询原任务，或确认失败/取消。 */
public enum CanvasFunctionUnknownResolution {
  /** 人工已确认外部任务存在且可查询：Run 回到 READY，只允许查询原任务，不会重新提交。 */
  RESUME,

  /** 人工已确认外部任务不存在或已失败：收敛为 FAILED。 */
  FAILED,

  /** 人工已确认外部任务已取消或应终止：收敛为 CANCELLED。 */
  CANCELLED
}
