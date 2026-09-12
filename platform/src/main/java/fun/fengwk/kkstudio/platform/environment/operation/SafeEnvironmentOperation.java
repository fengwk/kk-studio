package fun.fengwk.kkstudio.platform.environment.operation;

import java.time.Instant;
import java.util.UUID;

/** 安全的公开/非内部操作投影，完全不含私有 arguments。 */
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
    UUID ownerNodeId,
    UUID leaseToken,
    Instant startedAt,
    Instant finishedAt,
    String resultSummary,
    String failureCode,
    String failureMessage,
    Instant createdAt,
    Instant updatedAt) {}
