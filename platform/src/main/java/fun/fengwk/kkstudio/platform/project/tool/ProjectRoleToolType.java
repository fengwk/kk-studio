package fun.fengwk.kkstudio.platform.project.tool;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 12 个 Project/Issue 角色工具的类型定义与描述符。 */
public enum ProjectRoleToolType {
  PROJECT_READ("project_read", ProjectRole.COORDINATOR, ToolSideEffect.READ_ONLY),
  ISSUE_READ("issue_read", ProjectRole.COORDINATOR, ToolSideEffect.READ_ONLY),
  ISSUE_LIST("issue_list", ProjectRole.COORDINATOR, ToolSideEffect.READ_ONLY),
  ISSUE_CREATE("issue_create", ProjectRole.COORDINATOR, ToolSideEffect.NON_IDEMPOTENT),
  ISSUE_UPDATE("issue_update", ProjectRole.COORDINATOR, ToolSideEffect.NON_IDEMPOTENT),
  ISSUE_ADD_DEPENDENCY(
      "issue_add_dependency", ProjectRole.COORDINATOR, ToolSideEffect.NON_IDEMPOTENT),
  ISSUE_REMOVE_DEPENDENCY(
      "issue_remove_dependency", ProjectRole.COORDINATOR, ToolSideEffect.NON_IDEMPOTENT),
  ISSUE_SET_STATUS("issue_set_status", ProjectRole.COORDINATOR, ToolSideEffect.NON_IDEMPOTENT),
  ISSUE_CANCEL("issue_cancel", ProjectRole.COORDINATOR, ToolSideEffect.NON_IDEMPOTENT),
  ISSUE_SUBMIT("issue_submit", ProjectRole.EXECUTOR, ToolSideEffect.IDEMPOTENT),
  ISSUE_REQUEST_INPUT("issue_request_input", ProjectRole.EXECUTOR, ToolSideEffect.NON_IDEMPOTENT),
  ISSUE_REVIEW("issue_review", ProjectRole.REVIEWER, ToolSideEffect.IDEMPOTENT);

  private final String modelName;
  private final ProjectRole requiredRole;
  private final ToolSideEffect sideEffect;
  private final ToolDescriptor descriptor;

  ProjectRoleToolType(String modelName, ProjectRole requiredRole, ToolSideEffect sideEffect) {
    this.modelName = Objects.requireNonNull(modelName, "modelName");
    this.requiredRole = Objects.requireNonNull(requiredRole, "requiredRole");
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

  public ProjectRole requiredRole() {
    return requiredRole;
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
    return Arrays.stream(values()).filter(t -> t.requiredRole == role).toList();
  }

  /** 返回该角色可用的模型可见工具名列表（声明顺序）。 */
  public static List<String> namesForRole(ProjectRole role) {
    return forRole(role).stream().map(ProjectRoleToolType::modelName).toList();
  }
}
