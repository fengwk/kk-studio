package fun.fengwk.kkstudio.harness.contributor.builtin.subagent;

import fun.fengwk.kkstudio.harness.api.join.SubagentTaskRequest;

import java.util.function.Consumer;

/**
 * Subagent 委派运行时端口。接受必须持久化子 Session/Thread 的源命令与 Join；结果由 Join 匹配后驱动父 TOOL Work 交付。
 *
 * <p>实现必须保证幂等：同一个 {@link SubagentTaskRequest#invocationId()} 重试返回同一子 Thread。
 */
@FunctionalInterface
public interface SubagentRunner {

  /** 接受一次 Subagent 委派；持久接受后调用 accepted，调用方随后立即释放执行线程。 */
  SubagentTaskAcceptance accept(
      SubagentTaskRequest request, Consumer<SubagentTaskAcceptance> accepted);
}
