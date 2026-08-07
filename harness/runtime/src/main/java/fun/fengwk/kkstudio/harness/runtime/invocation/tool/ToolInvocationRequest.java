package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.util.Objects;

/**
 * 一次 Tool invocation 的冻结请求：已完成的 ToolCall 及其 binding。
 *
 * <p>arguments JSON 与 Environment 路由在创建时冻结；retry 仅 replay 原始请求。
 */
public record ToolInvocationRequest(ToolCall call, ToolBinding binding) {

  public ToolInvocationRequest {
    call = Objects.requireNonNull(call, "call");
    binding = Objects.requireNonNull(binding, "binding");
    call.validateFor(binding.descriptor());
  }
}
