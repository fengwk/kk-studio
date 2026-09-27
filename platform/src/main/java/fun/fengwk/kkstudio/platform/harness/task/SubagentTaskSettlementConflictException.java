package fun.fengwk.kkstudio.platform.harness.task;

/**
 * 结算/提醒事务因持久事实已推进而必须回滚的信号。
 *
 * <p>抛出它表示"本事务写入的命令不得落地"：例如结果已由并发方交付、提醒阈值已推进、或父 Thread 在事务内被确认处于显式停止态。调用方必须让当前事务回滚，
 * 并在下一轮扫描按最新持久事实收敛，绝不能把命令当作已交付。
 */
public class SubagentTaskSettlementConflictException extends RuntimeException {

  public SubagentTaskSettlementConflictException(String message) {
    super(message);
  }
}
