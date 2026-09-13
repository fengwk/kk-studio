package fun.fengwk.kkstudio.platform.environment.operation;

import fun.fengwk.kkstudio.platform.error.AiDuplicateException;

import java.util.Objects;
import java.util.UUID;

/**
 * 同一 (environment_id, resource_type, resource_id) 已存在未终结操作冲突；映射为 HTTP 409。
 *
 * <p>本异常绝不回显任何调用参数或凭证，也不保留底层包含完整行数据的 JDBC 异常原因链。
 */
public class DuplicateActiveOperationException extends AiDuplicateException {

  private final UUID environmentId;
  private final EnvironmentOperationResourceType resourceType;
  private final UUID resourceId;

  public DuplicateActiveOperationException(
      UUID environmentId, EnvironmentOperationResourceType resourceType, UUID resourceId) {
    super(
        "environment_operation",
        "active operation already exists for environment "
            + Objects.requireNonNull(environmentId, "environmentId")
            + ", resourceType "
            + Objects.requireNonNull(resourceType, "resourceType")
            + " and resourceId "
            + Objects.requireNonNull(resourceId, "resourceId"));
    this.environmentId = environmentId;
    this.resourceType = resourceType;
    this.resourceId = resourceId;
  }

  public UUID getEnvironmentId() {
    return environmentId;
  }

  public EnvironmentOperationResourceType getResourceType() {
    return resourceType;
  }

  public UUID getResourceId() {
    return resourceId;
  }
}
