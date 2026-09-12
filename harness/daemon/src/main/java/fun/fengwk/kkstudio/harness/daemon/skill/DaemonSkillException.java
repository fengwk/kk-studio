package fun.fengwk.kkstudio.harness.daemon.skill;

/** Skill 来源操作失败的类型化信号：{@code message} 是可回传的结构性原因，绝不包含 URL/ref/命令 argv。 */
final class DaemonSkillException extends RuntimeException {

  DaemonSkillException(String message) {
    super(message);
  }

  DaemonSkillException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * 中断检查：已取消的操作必须尽快停止，且绝不能继续到发布提交点。
   *
   * <p>取消是调用级事实（Future 中断），因此这里只读取当前线程的中断状态，不做任何全局登记；抛出的是结构性取消信号。
   */
  static void requireNotInterrupted() {
    if (Thread.currentThread().isInterrupted()) {
      throw new DaemonSkillException("skill operation was interrupted");
    }
  }
}
