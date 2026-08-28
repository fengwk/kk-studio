package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityDescriptor;

import java.util.Objects;

/** 冻结后的 ENVIRONMENT_CAPABILITY Tool 贡献。 */
public record EnvironmentCapabilityToolContribution(
    ContributionId id,
    AgentToolDefinition definition,
    EnvironmentCapabilityDescriptor capability,
    int priority)
    implements ToolContribution {

  public EnvironmentCapabilityToolContribution {
    id = Objects.requireNonNull(id, "id");
    definition = Objects.requireNonNull(definition, "definition");
    capability = Objects.requireNonNull(capability, "capability");
    if (definition.backend() != AgentToolBackend.ENVIRONMENT_CAPABILITY) {
      throw new IllegalArgumentException(
          "environment capability tool definition must use ENVIRONMENT_CAPABILITY backend");
    }
    if (!definition.descriptor().inputSchema().equals(capability.inputSchema())
        || !definition.descriptor().timeout().equals(capability.timeout())) {
      throw new IllegalArgumentException(
          "environment capability tool descriptor schema and timeout must match capability");
    }
  }
}
