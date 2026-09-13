package fun.fengwk.kkstudio.platform.environment.operation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.util.Objects;
import java.util.UUID;

/**
 * 未接入 MCP Server 目录持久化切片前的 fail-closed 默认实现。
 *
 * <p>由于本切片尚不存在原子消费 discovery 结果并持久化工具目录的能力，成功结果一律收敛为 FAILED (INVALID_RESULT)，绝不写入
 * SUCCEEDED，避免产生“操作成功但目录未落库”的不一致状态。后续 MCP 切片应提供 原子实现替换该 Bean。
 */
@Slf4j
@Component
class FailClosedMcpDiscoveryResultPublisher implements McpDiscoveryResultPublisher {

  private final EnvironmentOperationRepository repository;

  FailClosedMcpDiscoveryResultPublisher(EnvironmentOperationRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  @Override
  public OperationPublishOutcome publishDiscoverySuccess(
      UUID environmentId,
      UUID operationId,
      UUID ownerNodeId,
      UUID leaseToken,
      UUID resourceId,
      long resourceVersion,
      EnvironmentCapabilityResult result) {
    log.warn(
        "MCP discovery result accepted but no directory persistence is wired; failing closed for"
            + " operation {}",
        operationId);
    return markInvalidResult(operationId, ownerNodeId, leaseToken);
  }

  @Override
  public OperationPublishOutcome publishDiscoveryFailure(
      UUID environmentId,
      UUID operationId,
      UUID ownerNodeId,
      UUID leaseToken,
      UUID resourceId,
      long resourceVersion,
      String failureCode,
      String failureMessage) {
    Objects.requireNonNull(failureMessage, "failureMessage");
    boolean updated =
        repository.markFailed(
            operationId,
            ownerNodeId,
            leaseToken,
            Objects.requireNonNull(failureCode, "failureCode"),
            failureMessage);
    return updated ? OperationPublishOutcome.APPLIED : OperationPublishOutcome.LEASE_LOST;
  }

  private OperationPublishOutcome markInvalidResult(
      UUID operationId, UUID ownerNodeId, UUID leaseToken) {
    boolean updated =
        repository.markFailed(
            operationId,
            ownerNodeId,
            leaseToken,
            EnvironmentOperationFailureCodes.INVALID_RESULT,
            EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE);
    return updated ? OperationPublishOutcome.APPLIED : OperationPublishOutcome.LEASE_LOST;
  }
}
