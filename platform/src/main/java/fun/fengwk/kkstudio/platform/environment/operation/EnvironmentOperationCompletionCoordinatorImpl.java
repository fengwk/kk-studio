package fun.fengwk.kkstudio.platform.environment.operation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.util.Objects;
import java.util.UUID;

/**
 * {@link EnvironmentOperationCompletionCoordinator} 默认实现（包内私有）。
 *
 * <p>当前唯一的操作族是 Local MCP 发现：结果由 {@link McpDiscoveryResultPublisher} 在推进操作终态的同一围栏/事务内消费。安全边界：绝不将
 * Daemon 原始 stdout/stderr、错误文本或异常栈记录至日志或持久化至数据库。
 */
@Slf4j
@Service
class EnvironmentOperationCompletionCoordinatorImpl
    implements EnvironmentOperationCompletionCoordinator {

  private final McpDiscoveryResultPublisher mcpDiscoveryResultPublisher;
  private final EnvironmentOperationRepository repository;

  EnvironmentOperationCompletionCoordinatorImpl(
      McpDiscoveryResultPublisher mcpDiscoveryResultPublisher,
      EnvironmentOperationRepository repository) {
    this.mcpDiscoveryResultPublisher =
        Objects.requireNonNull(mcpDiscoveryResultPublisher, "mcpDiscoveryResultPublisher");
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  @Override
  public OperationPublishOutcome coordinateResult(
      UUID environmentId,
      UUID operationId,
      UUID ownerNodeId,
      UUID leaseToken,
      EnvironmentOperationType operationType,
      EnvironmentOperationResourceType resourceType,
      UUID resourceId,
      long resourceVersion,
      String arguments,
      EnvironmentCapabilityResult result) {
    if (result == null
        || result.callId() == null
        || !result.callId().equals(operationId.toString())) {
      log.warn("MCP discover returned null or invalid result callId for operation {}", operationId);
      return mcpDiscoveryResultPublisher.publishDiscoveryFailure(
          environmentId,
          operationId,
          ownerNodeId,
          leaseToken,
          resourceId,
          resourceVersion,
          EnvironmentOperationFailureCodes.INVALID_RESULT,
          EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE);
    }

    if (result.error()) {
      log.info("MCP discover capability returned error for operation {}", operationId);
      return mcpDiscoveryResultPublisher.publishDiscoveryFailure(
          environmentId,
          operationId,
          ownerNodeId,
          leaseToken,
          resourceId,
          resourceVersion,
          EnvironmentOperationFailureCodes.OPERATION_FAILED,
          EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE);
    }

    log.info("MCP discover succeeded for operation {}", operationId);
    return mcpDiscoveryResultPublisher.publishDiscoverySuccess(
        environmentId, operationId, ownerNodeId, leaseToken, resourceId, resourceVersion, result);
  }

  @Override
  public OperationPublishOutcome coordinateExecutionFailure(
      UUID environmentId,
      UUID operationId,
      UUID ownerNodeId,
      UUID leaseToken,
      EnvironmentOperationType operationType,
      EnvironmentOperationResourceType resourceType,
      UUID resourceId,
      long resourceVersion,
      String arguments) {
    log.info("Coordinating MCP discover execution failure for operation {}", operationId);
    return mcpDiscoveryResultPublisher.publishDiscoveryFailure(
        environmentId,
        operationId,
        ownerNodeId,
        leaseToken,
        resourceId,
        resourceVersion,
        EnvironmentOperationFailureCodes.OPERATION_FAILED,
        EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE);
  }

  @Override
  public boolean coordinateTransportUnknown(UUID operationId, UUID ownerNodeId, UUID leaseToken) {
    log.info("Coordinating transport unknown state for operation {}", operationId);
    return repository.markUnknown(
        operationId,
        ownerNodeId,
        leaseToken,
        EnvironmentOperationFailureCodes.TRANSPORT_ERROR,
        EnvironmentOperationFailureCodes.TRANSPORT_ERROR_MESSAGE);
  }
}
