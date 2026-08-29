package fun.fengwk.kkstudio.harness.tool;

import java.util.Objects;

/** Agent tool 的最小统一公共定义。 */
public record AgentToolDefinition(
    AgentToolId id, ToolDescriptor descriptor, ToolVisibility visibility) {

  public AgentToolDefinition {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(visibility, "visibility");
  }
}
