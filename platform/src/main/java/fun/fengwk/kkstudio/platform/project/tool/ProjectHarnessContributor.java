package fun.fengwk.kkstudio.platform.project.tool;

import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessRegistrar;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 注册 12 个 Project/Issue 角色 INTERNAL 工具的 Harness Contributor。 */
public final class ProjectHarnessContributor implements HarnessContributor {

  public static final ContributorId ID = new ContributorId("project");
  public static final String NAME = "Project";
  public static final String VERSION = "1";

  private static final ContributorDescriptor DESCRIPTOR =
      new ContributorDescriptor(ID, NAME, VERSION, Set.of());

  private final Map<ProjectRoleToolType, Tool> tools;

  public ProjectHarnessContributor(List<ProjectRoleTool> tools) {
    Objects.requireNonNull(tools, "tools");
    if (tools.size() != ProjectRoleToolType.values().length) {
      throw new IllegalArgumentException("Invalid project tools count");
    }
    Set<ProjectRoleToolType> seen = EnumSet.noneOf(ProjectRoleToolType.class);
    Map<ProjectRoleToolType, Tool> map = new EnumMap<>(ProjectRoleToolType.class);
    for (ProjectRoleTool tool : tools) {
      if (tool == null || !seen.add(tool.type())) {
        throw new IllegalArgumentException("Duplicate or null project tool provided");
      }
      map.put(tool.type(), tool);
    }
    if (seen.size() != ProjectRoleToolType.values().length) {
      throw new IllegalArgumentException("Missing required project tools");
    }
    this.tools = map;
  }

  @Override
  public ContributorDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public void contribute(HarnessRegistrar registrar) {
    Objects.requireNonNull(registrar, "registrar");

    for (ProjectRoleToolType type : ProjectRoleToolType.values()) {
      Tool tool = tools.get(type);
      registrar.registerTool(
          type.localName(), type.agentToolId(), tool, ToolVisibility.INTERNAL, 0);
    }
  }
}
