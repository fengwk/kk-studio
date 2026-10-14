package fun.fengwk.kkstudio.platform.environment.update;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次受管更新操作的持久事实：唯一 operationId、目标版本、阶段、可选有界错误与时间戳。
 *
 * <p>它是「一个 Environment 同一时刻至多一次更新」的权威事实：活动行由数据库部分唯一索引约束，跨平台重启后仍可从行恢复。
 */
public record EnvironmentUpdateOperation(
    String operationId,
    EnvironmentId environmentId,
    String targetVersion,
    EnvironmentUpdatePhase phase,
    String error,
    Instant createdAt,
    Instant updatedAt) {

  public EnvironmentUpdateOperation {
    operationId = Objects.requireNonNull(operationId, "operationId");
    environmentId = Objects.requireNonNull(environmentId, "environmentId");
    targetVersion = Objects.requireNonNull(targetVersion, "targetVersion");
    phase = Objects.requireNonNull(phase, "phase");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
  }
}
