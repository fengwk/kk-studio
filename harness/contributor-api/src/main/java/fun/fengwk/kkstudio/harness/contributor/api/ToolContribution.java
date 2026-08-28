package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;

/** 冻结后的 Tool 贡献契约。 */
public sealed interface ToolContribution
    permits HostToolContribution,
        DeclarativeToolContribution,
        EnvironmentCapabilityToolContribution {

  ContributionId id();

  AgentToolDefinition definition();

  int priority();
}
