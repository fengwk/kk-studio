package fun.fengwk.kkstudio.harness.runtime.tool;

import fun.fengwk.kkstudio.harness.tool.ToolCall;
import java.util.Objects;

/** beforeToolCall 当前不可变输入。 */
public record BeforeToolCallContext(ToolBinding binding, ToolCall call) {
  public BeforeToolCallContext {
    binding = Objects.requireNonNull(binding, "binding");
    call = Objects.requireNonNull(call, "call");
  }
}
