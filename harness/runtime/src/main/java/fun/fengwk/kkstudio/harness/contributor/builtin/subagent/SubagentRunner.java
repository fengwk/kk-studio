package fun.fengwk.kkstudio.harness.contributor.builtin.subagent;

import java.util.function.Consumer;

/**
 * Subagent 委派的运行时端口。接受必须持久化子 Session/Thread 的源命令与 Join；同步调用继续等待冻结结果，异步调用只返回受理回执。
 *
 * <p>等待由工具执行线程完成，不在事务或调度线程中等待。实现必须保证幂等：同一个 {@link SubagentTaskRequest#invocationId()} 重试返回同一子
 * Thread。
 */
@FunctionalInterface
public interface SubagentRunner {

  /**
   * 接受一次 Subagent 委派，并按内部模式同步等待 Join 或返回异步受理回执。
   *
   * @param request 解析出的 Subagent 任务参数
   * @param async 异步模式；模型调用固定传 {@code false}
   * @param accepted 在持久受理后调用，供前端展示子代理运行中
   * @return 子 Session/Thread 身份及同步模式下冻结的 Join 结果
   */
  SubagentTaskResult run(
      SubagentTaskRequest request, boolean async, Consumer<SubagentTaskAcceptance> accepted);
}
