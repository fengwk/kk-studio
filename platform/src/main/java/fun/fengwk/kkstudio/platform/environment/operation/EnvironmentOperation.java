package fun.fengwk.kkstudio.platform.environment.operation;

import java.time.Instant;
import java.util.UUID;

/**
 * environment_operation 完整持久领域模型。
 *
 * <p>包含内部 dispatcher 专用的私有 {@code arguments}。
 *
 * <p>安全边界：{@link #toString()} 严格排除 {@code arguments}，避免日志与调试信息泄露凭证。
 */
public record EnvironmentOperation(
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
        + ", leaseToken="
        + leaseToken
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

  /** 返回不包含私有 arguments 的安全投影。 */
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
        ownerNodeId,
        leaseToken,
        startedAt,
        finishedAt,
        resultSummary,
        failureCode,
        failureMessage,
        createdAt,
        updatedAt);
  }
}
