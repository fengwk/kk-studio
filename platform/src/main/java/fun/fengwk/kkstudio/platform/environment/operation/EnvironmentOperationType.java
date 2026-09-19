package fun.fengwk.kkstudio.platform.environment.operation;

import java.util.Objects;

/** environment_operation 操作类型。 */
public enum EnvironmentOperationType {
  MCP_SERVER_DISCOVER(EnvironmentOperationResourceType.MCP_SERVER);

  private final EnvironmentOperationResourceType resourceType;

  EnvironmentOperationType(EnvironmentOperationResourceType resourceType) {
    this.resourceType = Objects.requireNonNull(resourceType, "resourceType");
  }

  public EnvironmentOperationResourceType resourceType() {
    return resourceType;
  }
}
