package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.util.Objects;

/**
 * 一次 Tool invocation 的 transient executable request：冻结的 ToolCall 与其 binding，只在 READY Tool 的
 * ToolProcessor/Gateway 边界构造，绝不持久化。
 *
 * <p>构造时把 durable raw canonical call 确定性归一化为 transient executable call，使权限 preflight、审批预览与实际执行
 * 观察到同一参数；retry 仅 replay 该冻结请求。构造器要求 call 与 binding 均非空：可空 binding 只属于 durable immediate FAILED
 * {@link ToolInvocation} 槽位（unknown tool / 输出截断），永远不会进入 executable request。Planner 已提前做同一
 * 归一化兼容校验；这里仍在 transient 边界固化返回值。
 */
public record ToolInvocationRequest(ToolCall call, ToolBinding binding) {

  public ToolInvocationRequest {
    binding = Objects.requireNonNull(binding, "binding");
    call = Objects.requireNonNull(call, "call").validateFor(binding.descriptor());
  }
}
