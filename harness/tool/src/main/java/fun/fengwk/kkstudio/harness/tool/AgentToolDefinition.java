package fun.fengwk.kkstudio.harness.tool;

import java.util.Objects;

/** Agent tool 的最小统一公共定义；{@link ToolDescriptor#name()} 即其唯一身份。 */
public record AgentToolDefinition(ToolDescriptor descriptor, ToolVisibility visibility) {

  public AgentToolDefinition {
    descriptor = Objects.requireNonNull(descriptor, "descriptor");
    visibility = Objects.requireNonNull(visibility, "visibility");
  }
}
