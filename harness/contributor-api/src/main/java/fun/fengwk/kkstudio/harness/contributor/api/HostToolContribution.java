package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;

import java.util.Objects;

/** 冻结后的 HOST Tool 贡献。 */
public record HostToolContribution(
    ContributionId id, AgentToolDefinition definition, Tool tool, int priority)
    implements ToolContribution {

  public HostToolContribution {
    id = Objects.requireNonNull(id, "id");
    definition = Objects.requireNonNull(definition, "definition");
    tool = Objects.requireNonNull(tool, "tool");
    if (definition.backend() != AgentToolBackend.HOST) {
      throw new IllegalArgumentException("host tool definition must use HOST backend");
    }
    if (!definition.descriptor().equals(tool.descriptor())) {
      throw new IllegalArgumentException(
          "host tool definition descriptor must match tool descriptor");
    }
  }
}
