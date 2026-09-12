package fun.fengwk.kkstudio.platform.environment.operation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * environment_operation 完整持久领域模型（包内私有）。
 *
 * <p>包含内部 dispatcher 专用的私有 {@code arguments} 与内部租约代币 {@code leaseToken}。
 *
 * <p>安全边界：{@link #toString()} 严格排除 {@code arguments} 与 {@code leaseToken}，避免日志与调试信息泄露凭证或租约。 公开投影请使用
 * {@link #toSafeProjection()} 或 {@link SafeEnvironmentOperation}。
 */
@EqualsAndHashCode
public final class EnvironmentOperation {

  private final UUID id;
  private final UUID environmentId;
  private final UUID sourceId;
  private final EnvironmentOperationType operationType;
  private final EnvironmentOperationStatus status;
  private final long sourceVersion;
  private final long sourceSetVersion;

  @JsonIgnore private final String arguments;

  private final String parameterSummary;
  private final Instant deadlineAt;
  private final UUID ownerNodeId;
  @JsonIgnore private final UUID leaseToken;
  private final Instant startedAt;
  private final Instant finishedAt;
  private final String resultSummary;
  private final String failureCode;
  private final String failureMessage;
  private final Instant createdAt;
  private final Instant updatedAt;

  public EnvironmentOperation(
      UUID id,
      UUID environmentId,
      UUID sourceId,
      EnvironmentOperationType operationType,
      EnvironmentOperationStatus status,
      long sourceVersion,
      long sourceSetVersion,
      String arguments,
      String parameterSummary,
      Instant deadlineAt,
      UUID ownerNodeId,
      UUID leaseToken,
      Instant startedAt,
      Instant finishedAt,
      String resultSummary,
      String failureCode,
      String failureMessage,
      Instant createdAt,
      Instant updatedAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.environmentId = Objects.requireNonNull(environmentId, "environmentId");
    this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
    this.operationType = Objects.requireNonNull(operationType, "operationType");
    this.status = Objects.requireNonNull(status, "status");
    this.sourceVersion = sourceVersion;
    this.sourceSetVersion = sourceSetVersion;
    this.arguments = Objects.requireNonNull(arguments, "arguments");
    this.parameterSummary = Objects.requireNonNull(parameterSummary, "parameterSummary");
    this.deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
    this.ownerNodeId = ownerNodeId;
    this.leaseToken = leaseToken;
    this.startedAt = startedAt;
    this.finishedAt = finishedAt;
    this.resultSummary = resultSummary;
    this.failureCode = failureCode;
    this.failureMessage = failureMessage;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
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
  public EnvironmentOperationStatus status() {
    return status;
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

  @JsonProperty
  public UUID ownerNodeId() {
    return ownerNodeId;
  }

  @JsonIgnore
  public UUID leaseToken() {
    return leaseToken;
  }

  @JsonProperty
  public Instant startedAt() {
    return startedAt;
  }

  @JsonProperty
  public Instant finishedAt() {
    return finishedAt;
  }

  @JsonProperty
  public String resultSummary() {
    return resultSummary;
  }

  @JsonProperty
  public String failureCode() {
    return failureCode;
  }

  @JsonProperty
  public String failureMessage() {
    return failureMessage;
  }

  @JsonProperty
  public Instant createdAt() {
    return createdAt;
  }

  @JsonProperty
  public Instant updatedAt() {
    return updatedAt;
  }

  public boolean isTerminal() {
    return status.isTerminal();
  }

  @Override
  public String toString() {
    return "EnvironmentOperation["
        + "id="
        + id
        + ", environmentId="
        + environmentId
        + ", sourceId="
        + sourceId
        + ", operationType="
        + operationType
        + ", status="
        + status
        + ", sourceVersion="
        + sourceVersion
        + ", sourceSetVersion="
        + sourceSetVersion
        + ", parameterSummary="
        + parameterSummary
        + ", deadlineAt="
        + deadlineAt
        + ", ownerNodeId="
        + ownerNodeId
        + ", startedAt="
        + startedAt
        + ", finishedAt="
        + finishedAt
        + ", resultSummary="
        + resultSummary
        + ", failureCode="
        + failureCode
        + ", failureMessage="
        + failureMessage
        + ", createdAt="
        + createdAt
        + ", updatedAt="
        + updatedAt
        + "]";
  }

  /** 返回不包含私有 arguments 与内部 leaseToken / ownerNodeId 的安全投影。 */
  public SafeEnvironmentOperation toSafeProjection() {
    return new SafeEnvironmentOperation(
        id,
        environmentId,
        sourceId,
        operationType,
        status,
        sourceVersion,
        sourceSetVersion,
        parameterSummary,
        deadlineAt,
        startedAt,
        finishedAt,
        resultSummary,
        failureCode,
        failureMessage,
        createdAt,
        updatedAt);
  }
}
