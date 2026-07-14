package fun.fengwk.kkstudio.harness.runtime.tool;

import java.util.Objects;

/** beforeToolCall 可替换绑定 descriptor 或参数 JSON。 */
public record BeforeToolCallResult(ToolBinding binding, String argumentsJson) {
  public BeforeToolCallResult {
    binding = Objects.requireNonNull(binding, "binding");
    if (argumentsJson == null) {
      throw new IllegalArgumentException("argumentsJson must not be null");
    }
  }
}
