package fun.fengwk.kkstudio.platform.environment.operation;

import java.util.Objects;

/** environment_operation 操作类型。 */
public enum EnvironmentOperationType {
  SKILL_REFRESH(EnvironmentOperationResourceType.SKILL_SOURCE),
  SKILL_INSTALL(EnvironmentOperationResourceType.SKILL_SOURCE),
  SKILL_UPDATE(EnvironmentOperationResourceType.SKILL_SOURCE),
  MCP_SERVER_DISCOVER(EnvironmentOperationResourceType.MCP_SERVER);

  private final EnvironmentOperationResourceType resourceType;

  EnvironmentOperationType(EnvironmentOperationResourceType resourceType) {
    this.resourceType = Objects.requireNonNull(resourceType, "resourceType");
  }

  public EnvironmentOperationResourceType resourceType() {
    return resourceType;
  }
}
