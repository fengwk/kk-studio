package fun.fengwk.kkstudio.harness.builtin.subagent;

/**
 * 持久接受 Subagent 委派的运行时端口。
 *
 * <p>本端口只有「接受」没有「等待」：实现必须在一个事务内完成子 Session/Thread 的命令接受与本次委派的持久记录，然后立即返回子 Thread 身份。
 * 子执行的终态结果不经过本端口回传，而是由运行时在结清后作为父 Thread 的一条独立消息交付。
 *
 * <p>实现必须保证幂等：同一个 {@link SubagentTaskRequest#invocationId()} 重试返回同一个子 Thread。参数被拒或接受失败以异常表达，由调用方收敛为
 * 错误结果。
 */
@FunctionalInterface
public interface SubagentRunner {

  /**
   * 持久接受一次 Subagent 委派。
   *
   * @param request 解析出的 Subagent 任务参数
   * @return 持久接受结果（子 Session/Thread 身份）
   */
  SubagentTaskAcceptance accept(SubagentTaskRequest request);
}
