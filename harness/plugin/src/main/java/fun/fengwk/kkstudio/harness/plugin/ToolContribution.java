package fun.fengwk.kkstudio.harness.plugin;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.util.List;
import java.util.Objects;

/** 冻结后的 Tool 贡献：scoped 身份、同步纯工具、descriptor、state accesses 与可见性。 */
public record ToolContribution(
    ContributionId id,
    PluginTool tool,
    ToolDescriptor descriptor,
    List<PluginStateDeclaration> stateAccesses,
    ToolVisibility visibility) {

  public ToolContribution {
    id = Objects.requireNonNull(id, "id");
    tool = Objects.requireNonNull(tool, "tool");
    descriptor = Objects.requireNonNull(descriptor, "descriptor");
    stateAccesses = List.copyOf(Objects.requireNonNull(stateAccesses, "stateAccesses"));
    visibility = Objects.requireNonNull(visibility, "visibility");
  }
}
