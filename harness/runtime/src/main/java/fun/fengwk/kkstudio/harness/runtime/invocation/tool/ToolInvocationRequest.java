package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.util.Objects;

/**
 * 一次 Tool invocation 的 transient executable request：冻结的 ToolCall 与其 binding，只在 READY Tool 的
 * ToolProcessor/Gateway 边界构造，绝不持久化。
 *
 * <p>arguments JSON 与 Environment 路由在创建时冻结；retry 仅 replay 原始请求。构造器要求 call 与 binding 均非空： 可空 binding
 * 只属于 durable immediate FAILED {@link ToolInvocation} 槽位（unknown tool / 输出截断），永远不会进入 executable
 * request；Tool schema 校验在 planner 完成（{@link
 * fun.fengwk.kkstudio.harness.runtime.processor.ModelResponsePlanner}），构造器不再重复校验。
 */
public record ToolInvocationRequest(ToolCall call, ToolBinding binding) {

  public ToolInvocationRequest {
    call = Objects.requireNonNull(call, "call");
    binding = Objects.requireNonNull(binding, "binding");
  }
}
