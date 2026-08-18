package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.util.Objects;

/**
 * 一次 Tool invocation 的冻结请求：已完成的 ToolCall 及其 binding。
 *
 * <p>arguments JSON 与 Environment 路由在创建时冻结；retry 仅 replay 原始请求。binding 只在 immediate FAILED
 * 槽位（unknown tool / 输出截断）可空，READY 及 dispatch 路径的 binding 非空由 {@link
 * fun.fengwk.kkstudio.harness.runtime.processor.ModelResponsePlanner} 与 {@link ToolInvocation}
 * 状态不变量 保证；Tool schema 校验在 planner 完成，构造器不再重复校验。
 */
public record ToolInvocationRequest(ToolCall call, ToolBinding binding) {

  public ToolInvocationRequest {
    call = Objects.requireNonNull(call, "call");
  }
}
