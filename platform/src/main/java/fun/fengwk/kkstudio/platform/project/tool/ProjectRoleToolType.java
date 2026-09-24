package fun.fengwk.kkstudio.platform.project.tool;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Issue 角色工具的类型定义与描述符（3 个内部 Agent 工具）。 */
public enum ProjectRoleToolType {
  ISSUE_READ(
      "issue_read", Set.of(ProjectRole.EXECUTOR, ProjectRole.REVIEWER), ToolSideEffect.READ_ONLY),
  ISSUE_REQUEST_INPUT(
      "issue_request_input",
      Set.of(ProjectRole.EXECUTOR, ProjectRole.REVIEWER),
      ToolSideEffect.NON_IDEMPOTENT),
  ISSUE_REVIEW("issue_review", Set.of(ProjectRole.REVIEWER), ToolSideEffect.IDEMPOTENT);

  private final String modelName;
  private final Set<ProjectRole> allowedRoles;
  private final ToolSideEffect sideEffect;
  private final ToolDescriptor descriptor;

  ProjectRoleToolType(String modelName, Set<ProjectRole> allowedRoles, ToolSideEffect sideEffect) {
    this.modelName = Objects.requireNonNull(modelName, "modelName");
    this.allowedRoles = Objects.requireNonNull(allowedRoles, "allowedRoles");
    this.sideEffect = Objects.requireNonNull(sideEffect, "sideEffect");
    this.descriptor =
        new ToolDescriptor(
            modelName,
            ProjectToolPrompts.prompt(modelName),
            modelName,
            ProjectToolPrompts.schema(modelName),
            sideEffect,
            Duration.ofSeconds(30));
  }

  public String modelName() {
    return modelName;
  }

  public String localName() {
    return modelName.replace('_', '.');
  }

  public Set<ProjectRole> allowedRoles() {
    return allowedRoles;
  }

  public boolean isAllowedFor(ProjectRole role) {
    return role != null && allowedRoles.contains(role);
  }

  public ToolSideEffect sideEffect() {
    return sideEffect;
  }

  public ToolDescriptor descriptor() {
    return descriptor;
  }

  public static Optional<ProjectRoleToolType> findByModelName(String modelName) {
    if (modelName == null) {
      return Optional.empty();
    }
    return Arrays.stream(values()).filter(t -> t.modelName.equals(modelName)).findFirst();
  }

  public static List<ProjectRoleToolType> forRole(ProjectRole role) {
    Objects.requireNonNull(role, "role");
    return Arrays.stream(values()).filter(t -> t.isAllowedFor(role)).toList();
  }

  /** 返回该角色可用的模型可见工具名列表（声明顺序）。 */
  public static List<String> namesForRole(ProjectRole role) {
    return forRole(role).stream().map(ProjectRoleToolType::modelName).toList();
  }
}
