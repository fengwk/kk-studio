package fun.fengwk.kkstudio.platform.environment.operation;

import java.time.Instant;
import java.util.UUID;

/**
 * 环境操作的公开与安全历史投影。
 *
 * <p>物理移除了私有执行参数 {@code arguments}、内部认领代币 {@code leaseToken} 与内部执行节点标识 {@code ownerNodeId}， 确保对外
 * API 及查询历史无法访问任何私有凭据或内部路由拓扑。
 */
public record SafeEnvironmentOperation(
    UUID id,
    UUID environmentId,
    UUID sourceId,
    EnvironmentOperationType operationType,
    EnvironmentOperationStatus status,
    long sourceVersion,
    long sourceSetVersion,
    String parameterSummary,
    Instant deadlineAt,
    Instant startedAt,
    Instant finishedAt,
    String resultSummary,
    String failureCode,
    String failureMessage,
    Instant createdAt,
    Instant updatedAt) {

  public boolean isTerminal() {
    return status.isTerminal();
  }
}
