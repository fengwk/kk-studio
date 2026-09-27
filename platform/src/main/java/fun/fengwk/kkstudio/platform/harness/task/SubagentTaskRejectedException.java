package fun.fengwk.kkstudio.platform.harness.task;

/**
 * 确定性的 task 委派拒绝：参数、归属、静止态或并发额度不满足时抛出，由 task Tool 收敛为错误结果。
 *
 * <p>拒绝发生在任何持久写入之前或与写入同事务回滚，绝不留下半接受状态。
 */
public class SubagentTaskRejectedException extends RuntimeException {

  public SubagentTaskRejectedException(String message) {
    super(message);
  }

  public SubagentTaskRejectedException(String message, Throwable cause) {
    super(message, cause);
  }
}
