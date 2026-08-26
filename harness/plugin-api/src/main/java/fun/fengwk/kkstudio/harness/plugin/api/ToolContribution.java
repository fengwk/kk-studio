package fun.fengwk.kkstudio.harness.plugin.api;

import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;

import java.util.List;
import java.util.Objects;

/** 冻结后的 Tool 贡献：scoped 身份、同步纯工具、统一定义与 state accesses。 */
public record ToolContribution(
    ContributionId id,
    PluginTool tool,
    AgentToolDefinition definition,
    List<PluginStateDeclaration> stateAccesses,
    int priority) {

  public ToolContribution {
    id = Objects.requireNonNull(id, "id");
    tool = Objects.requireNonNull(tool, "tool");
    definition = Objects.requireNonNull(definition, "definition");
    if (definition.backend() != AgentToolBackend.PLUGIN) {
      throw new IllegalArgumentException("plugin tool definition must use PLUGIN backend");
    }
    stateAccesses = List.copyOf(Objects.requireNonNull(stateAccesses, "stateAccesses"));
  }
}
