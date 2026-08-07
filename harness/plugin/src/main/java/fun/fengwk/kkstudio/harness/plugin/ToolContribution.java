package fun.fengwk.kkstudio.harness.plugin;

import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactory;

import java.util.Objects;

/** 冻结后的 Tool 贡献：scoped 身份、factory 与可见性。 */
public record ToolContribution(ContributionId id, ToolFactory factory, ToolVisibility visibility) {

  public ToolContribution {
    id = Objects.requireNonNull(id, "id");
    factory = Objects.requireNonNull(factory, "factory");
    visibility = Objects.requireNonNull(visibility, "visibility");
  }
}
