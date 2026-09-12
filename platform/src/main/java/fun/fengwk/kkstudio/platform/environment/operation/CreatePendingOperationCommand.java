package fun.fengwk.kkstudio.platform.environment.operation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 创建 PENDING 操作的输入命令。
 *
 * <p>注意：{@link #toString()} 严格排除私有 {@code arguments}。
 */
public record CreatePendingOperationCommand(
    UUID id,
    UUID environmentId,
    UUID sourceId,
    EnvironmentOperationType operationType,
    long sourceVersion,
    long sourceSetVersion,
    String arguments,
    String parameterSummary,
    Instant deadlineAt) {

  public CreatePendingOperationCommand {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(sourceId, "sourceId");
    Objects.requireNonNull(operationType, "operationType");
    Objects.requireNonNull(arguments, "arguments");
    Objects.requireNonNull(parameterSummary, "parameterSummary");
    Objects.requireNonNull(deadlineAt, "deadlineAt");
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
