package fun.fengwk.kkstudio.platform.project.tool;

import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 12 个 Project/Issue 角色工具的类型定义与描述符。 */
public enum ProjectRoleToolType {
  PROJECT_READ(
      "project_read",
      ProjectRoleToolIds.PROJECT_READ,
      ProjectRole.COORDINATOR,
      ToolSideEffect.READ_ONLY),
  ISSUE_READ(
      "issue_read",
      ProjectRoleToolIds.ISSUE_READ,
      ProjectRole.COORDINATOR,
      ToolSideEffect.READ_ONLY),
  ISSUE_LIST(
      "issue_list",
      ProjectRoleToolIds.ISSUE_LIST,
      ProjectRole.COORDINATOR,
      ToolSideEffect.READ_ONLY),
  ISSUE_CREATE(
      "issue_create",
      ProjectRoleToolIds.ISSUE_CREATE,
      ProjectRole.COORDINATOR,
      ToolSideEffect.NON_IDEMPOTENT),
  ISSUE_UPDATE(
      "issue_update",
      ProjectRoleToolIds.ISSUE_UPDATE,
      ProjectRole.COORDINATOR,
      ToolSideEffect.NON_IDEMPOTENT),
  ISSUE_ADD_DEPENDENCY(
      "issue_add_dependency",
      ProjectRoleToolIds.ISSUE_ADD_DEPENDENCY,
      ProjectRole.COORDINATOR,
      ToolSideEffect.NON_IDEMPOTENT),
  ISSUE_REMOVE_DEPENDENCY(
      "issue_remove_dependency",
      ProjectRoleToolIds.ISSUE_REMOVE_DEPENDENCY,
      ProjectRole.COORDINATOR,
      ToolSideEffect.NON_IDEMPOTENT),
  ISSUE_SET_STATUS(
      "issue_set_status",
      ProjectRoleToolIds.ISSUE_SET_STATUS,
      ProjectRole.COORDINATOR,
      ToolSideEffect.NON_IDEMPOTENT),
  ISSUE_CANCEL(
      "issue_cancel",
      ProjectRoleToolIds.ISSUE_CANCEL,
      ProjectRole.COORDINATOR,
      ToolSideEffect.NON_IDEMPOTENT),
  ISSUE_SUBMIT(
      "issue_submit",
      ProjectRoleToolIds.ISSUE_SUBMIT,
      ProjectRole.EXECUTOR,
      ToolSideEffect.IDEMPOTENT),
  ISSUE_REQUEST_INPUT(
      "issue_request_input",
      ProjectRoleToolIds.ISSUE_REQUEST_INPUT,
      ProjectRole.EXECUTOR,
      ToolSideEffect.NON_IDEMPOTENT),
  ISSUE_REVIEW(
      "issue_review",
      ProjectRoleToolIds.ISSUE_REVIEW,
      ProjectRole.REVIEWER,
      ToolSideEffect.IDEMPOTENT);

  private final String modelName;
  private final AgentToolId agentToolId;
  private final ProjectRole requiredRole;
  private final ToolSideEffect sideEffect;
  private final ToolDescriptor descriptor;

  ProjectRoleToolType(
      String modelName,
      AgentToolId agentToolId,
      ProjectRole requiredRole,
      ToolSideEffect sideEffect) {
    this.modelName = Objects.requireNonNull(modelName, "modelName");
    this.agentToolId = Objects.requireNonNull(agentToolId, "agentToolId");
    this.requiredRole = Objects.requireNonNull(requiredRole, "requiredRole");
    this.sideEffect = Objects.requireNonNull(sideEffect, "sideEffect");
    this.descriptor =
        new ToolDescriptor(
            modelName,
            "1",
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
    return agentToolId.value();
  }

  public AgentToolId agentToolId() {
    return agentToolId;
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

  public static Optional<ProjectRoleToolType> findByAgentToolId(AgentToolId agentToolId) {
    if (agentToolId == null) {
      return Optional.empty();
    }
    return Arrays.stream(values()).filter(t -> t.agentToolId.equals(agentToolId)).findFirst();
  }

  public static List<ProjectRoleToolType> forRole(ProjectRole role) {
    Objects.requireNonNull(role, "role");
    return Arrays.stream(values()).filter(t -> t.requiredRole == role).toList();
  }
}
