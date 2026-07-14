package fun.fengwk.kkstudio.harness.runtime.tool;

import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import java.util.Objects;

/** afterToolCall 扩展输入；T07/T09 执行器完成后调用。 */
public record AfterToolCallContext(
    long invocationId, ToolBinding binding, ToolCall call, ToolResult result) {
  public AfterToolCallContext {
    if (invocationId <= 0) {
      throw new IllegalArgumentException("invocationId must be positive");
    }
    binding = Objects.requireNonNull(binding, "binding");
    call = Objects.requireNonNull(call, "call");
    result = Objects.requireNonNull(result, "result");
  }
}
