package fun.fengwk.kkstudio.platform.harness.thread.command;

/**
 * 内部确定性拒绝信号：只在显式拒绝点抛出，由 {@link DatabaseTurnResolver} 转换为 typed {@link
 * fun.fengwk.kkstudio.harness.builtin.port.TurnResolver.Result.Rejected}。
 *
 * <p>它绝不包装外部基础设施异常，因此可以安全地与目录快照异常一起被集中捕获。
 */
final class TurnRejection extends RuntimeException {

  private TurnRejection(String message) {
    super(message);
  }

  static TurnRejection of(String message) {
    return new TurnRejection(message);
  }
}
