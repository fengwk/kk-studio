package fun.fengwk.kkstudio.platform.environment.operation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 创建处于 PENDING 状态的操作信令命令（包内私有）。
 *
 * <p>内部持有用于执行的原始私有 {@code arguments}。该字段仅包内可见，且明确被 Jackson 忽略并从 {@link #toString()} 中排除，防止凭据泄露。
 */
@EqualsAndHashCode
final class CreatePendingOperationCommand {

  private final UUID id;
  private final UUID environmentId;
  private final UUID sourceId;
  private final EnvironmentOperationType operationType;
  private final long sourceVersion;
  private final long sourceSetVersion;

  @JsonIgnore private final String arguments;

  private final String parameterSummary;
  private final Instant deadlineAt;

  CreatePendingOperationCommand(
      UUID id,
      UUID environmentId,
      UUID sourceId,
      EnvironmentOperationType operationType,
      long sourceVersion,
      long sourceSetVersion,
      String arguments,
      String parameterSummary,
      Instant deadlineAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.environmentId = Objects.requireNonNull(environmentId, "environmentId");
    this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
    this.operationType = Objects.requireNonNull(operationType, "operationType");
    this.sourceVersion = sourceVersion;
    this.sourceSetVersion = sourceSetVersion;
    this.arguments = Objects.requireNonNull(arguments, "arguments");
    this.parameterSummary = Objects.requireNonNull(parameterSummary, "parameterSummary");
    this.deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
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
  public UUID sourceId() {
    return sourceId;
  }

  @JsonProperty
  public EnvironmentOperationType operationType() {
    return operationType;
  }

  @JsonProperty
  public long sourceVersion() {
    return sourceVersion;
  }

  @JsonProperty
  public long sourceSetVersion() {
    return sourceSetVersion;
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
  public Instant deadlineAt() {
    return deadlineAt;
  }

  @Override
  public String toString() {
    return "CreatePendingOperationCommand["
        + "id="
        + id
        + ", environmentId="
        + environmentId
        + ", sourceId="
        + sourceId
        + ", operationType="
        + operationType
        + ", sourceVersion="
        + sourceVersion
        + ", sourceSetVersion="
        + sourceSetVersion
        + ", parameterSummary="
        + parameterSummary
        + ", deadlineAt="
        + deadlineAt
        + "]";
  }
}
