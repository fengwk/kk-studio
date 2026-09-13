package fun.fengwk.kkstudio.platform.environment.operation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;

import java.util.Objects;
import java.util.UUID;

/**
 * 以相对超时毫秒数创建 PENDING 状态操作信令的命令（包内私有）。
 *
 * <p>底层 SQL 在插入时由 PostgreSQL {@code statement_timestamp()} 计算绝对截止时间 {@code deadline_at}，杜绝 JVM
 * 与数据库的时钟竞争。私有执行参数 {@code arguments} 被 Jackson 忽略并从 {@link #toString()} 中物理排除。
 */
@EqualsAndHashCode
final class CreatePendingOperationWithTimeoutCommand {

  private final UUID id;
  private final UUID environmentId;
  private final EnvironmentOperationResourceType resourceType;
  private final UUID resourceId;
  private final EnvironmentOperationType operationType;
  private final long resourceVersion;

  @JsonIgnore private final String arguments;

  private final String parameterSummary;
  private final long timeoutMillis;

  CreatePendingOperationWithTimeoutCommand(
      UUID id,
      UUID environmentId,
      EnvironmentOperationResourceType resourceType,
      UUID resourceId,
      EnvironmentOperationType operationType,
      long resourceVersion,
      String arguments,
      String parameterSummary,
      long timeoutMillis) {
    this.id = Objects.requireNonNull(id, "id");
    this.environmentId = Objects.requireNonNull(environmentId, "environmentId");
    this.resourceType = Objects.requireNonNull(resourceType, "resourceType");
    this.resourceId = Objects.requireNonNull(resourceId, "resourceId");
    this.operationType = Objects.requireNonNull(operationType, "operationType");
    this.resourceVersion = resourceVersion;
    this.arguments = Objects.requireNonNull(arguments, "arguments");
    this.parameterSummary = Objects.requireNonNull(parameterSummary, "parameterSummary");
    this.timeoutMillis = timeoutMillis;
  }

  @JsonProperty
  public UUID id() {
    return id;
  }

  @JsonProperty
  public UUID environmentId() {
    return environmentId;
  }

  @JsonProperty
  public EnvironmentOperationResourceType resourceType() {
    return resourceType;
  }

  @JsonProperty
  public UUID resourceId() {
    return resourceId;
  }

  @JsonProperty
  public EnvironmentOperationType operationType() {
    return operationType;
  }

  @JsonProperty
  public long resourceVersion() {
    return resourceVersion;
  }

  @JsonIgnore
  String arguments() {
    return arguments;
  }

  @JsonProperty
  public String parameterSummary() {
    return parameterSummary;
  }

  @JsonProperty
  public long timeoutMillis() {
    return timeoutMillis;
  }

  @Override
  public String toString() {
    return "CreatePendingOperationWithTimeoutCommand["
        + "id="
        + id
        + ", environmentId="
        + environmentId
        + ", resourceType="
        + resourceType
        + ", resourceId="
        + resourceId
        + ", operationType="
        + operationType
        + ", resourceVersion="
        + resourceVersion
        + ", parameterSummary="
        + parameterSummary
        + ", timeoutMillis="
        + timeoutMillis
        + "]";
  }
}
