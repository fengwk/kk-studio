package fun.fengwk.kkstudio.canvas.function;

/**
 * Run 的外部提交事实，由 Runtime 在事务内持久化，adapter 只读取。
 *
 * <p>{@code PENDING} 是尚未提交的新 Run；{@code SUBMITTING} 表示提交意图已落库但外部提交结果不明，自动恢复不得再次提交，只能等待人工核查； {@code
 * SUBMITTED} 表示外部任务身份已可查询，恢复时只查询原任务。
 */
public enum CanvasFunctionSubmitState {
  PENDING,
  SUBMITTING,
  SUBMITTED
}
