package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.util.Objects;

/**
 * Frozen request of one Tool invocation: the completed ToolCall plus its binding.
 *
 * <p>The arguments JSON and the Environment route are frozen at creation; retry replays the
 * original request only.
 */
public record ToolInvocationRequest(ToolCall call, ToolBinding binding) {

  public ToolInvocationRequest {
    call = Objects.requireNonNull(call, "call");
    binding = Objects.requireNonNull(binding, "binding");
    call.validateFor(binding.descriptor());
  }
}
