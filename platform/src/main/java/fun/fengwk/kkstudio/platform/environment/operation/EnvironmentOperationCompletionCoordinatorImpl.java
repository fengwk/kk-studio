package fun.fengwk.kkstudio.platform.environment.operation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfig;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfigCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshot;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshotCodec;

import java.util.Objects;
import java.util.UUID;

/**
 * {@link EnvironmentOperationCompletionCoordinator} 默认实现（包内私有）。
 *
 * <p>安全边界：绝不将 Daemon 原始 stdout/stderr、错误文本或异常栈记录至日志或持久化至数据库。
 */
@Slf4j
@Service
class EnvironmentOperationCompletionCoordinatorImpl
    implements EnvironmentOperationCompletionCoordinator {

  private final EnvironmentOperationResultPublisher publisher;
  private final McpDiscoveryResultPublisher mcpDiscoveryResultPublisher;
  private final EnvironmentOperationRepository repository;
  private final ObjectMapper objectMapper;
  private final DaemonSkillSourceSnapshotCodec snapshotCodec;
  private final DaemonSkillSourceConfigCodec configCodec;

  EnvironmentOperationCompletionCoordinatorImpl(
      EnvironmentOperationResultPublisher publisher,
      McpDiscoveryResultPublisher mcpDiscoveryResultPublisher,
      EnvironmentOperationRepository repository,
      ObjectMapper objectMapper) {
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.mcpDiscoveryResultPublisher =
        Objects.requireNonNull(mcpDiscoveryResultPublisher, "mcpDiscoveryResultPublisher");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.snapshotCodec = new DaemonSkillSourceSnapshotCodec();
    this.configCodec = new DaemonSkillSourceConfigCodec();
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
    if (operationType == EnvironmentOperationType.MCP_SERVER_DISCOVER) {
      return coordinateMcpDiscoverResult(
          environmentId, operationId, ownerNodeId, leaseToken, resourceId, resourceVersion, result);
    }
    return coordinateSkillSourceResult(
        environmentId,
        operationId,
        ownerNodeId,
        leaseToken,
        resourceId,
        resourceVersion,
        arguments,
        result);
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
    if (operationType == EnvironmentOperationType.MCP_SERVER_DISCOVER) {
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

    log.info("Coordinating execution failure for operation {}", operationId);
    DaemonSkillSourceConfig config;
    try {
      config = configCodec.decode(arguments);
    } catch (RuntimeException e) {
      log.warn("Failed to decode skill source arguments on failure for operation {}", operationId);
      boolean marked =
          repository.markFailed(
              operationId,
              ownerNodeId,
              leaseToken,
              EnvironmentOperationFailureCodes.OPERATION_FAILED,
              EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE);
      return marked ? OperationPublishOutcome.APPLIED : OperationPublishOutcome.LEASE_LOST;
    }

    if (config.sourceSetVersion() < 0
        || !resourceId.equals(config.sourceId())
        || resourceVersion != config.sourceVersion()) {
      log.warn("Skill source arguments mismatch on failure for operation {}", operationId);
      boolean marked =
          repository.markFailed(
              operationId,
              ownerNodeId,
              leaseToken,
              EnvironmentOperationFailureCodes.OPERATION_FAILED,
              EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE);
      return marked ? OperationPublishOutcome.APPLIED : OperationPublishOutcome.LEASE_LOST;
    }

    return publisher.publishExecutionFailure(
        environmentId,
        operationId,
        ownerNodeId,
        leaseToken,
        config.sourceSetVersion(),
        resourceId,
        resourceVersion);
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

  private OperationPublishOutcome coordinateSkillSourceResult(
      UUID environmentId,
      UUID operationId,
      UUID ownerNodeId,
      UUID leaseToken,
      UUID resourceId,
      long resourceVersion,
      String arguments,
      EnvironmentCapabilityResult result) {
    DaemonSkillSourceConfig config;
    try {
      config = configCodec.decode(arguments);
    } catch (RuntimeException e) {
      log.warn("Failed to decode skill source config arguments for operation {}", operationId);
      boolean marked =
          repository.markFailed(
              operationId,
              ownerNodeId,
              leaseToken,
              EnvironmentOperationFailureCodes.INVALID_RESULT,
              EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE);
      return marked ? OperationPublishOutcome.APPLIED : OperationPublishOutcome.LEASE_LOST;
    }

    if (config.sourceSetVersion() < 0
        || !resourceId.equals(config.sourceId())
        || resourceVersion != config.sourceVersion()) {
      log.warn(
          "Skill source arguments mismatch or invalid sourceSetVersion for operation {}",
          operationId);
      boolean marked =
          repository.markFailed(
              operationId,
              ownerNodeId,
              leaseToken,
              EnvironmentOperationFailureCodes.INVALID_RESULT,
              EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE);
      return marked ? OperationPublishOutcome.APPLIED : OperationPublishOutcome.LEASE_LOST;
    }

    long sourceSetVersion = config.sourceSetVersion();

    if (result == null) {
      log.warn("Capability returned null result for operation {}", operationId);
      return publisher.publishInvalidResult(
          environmentId,
          operationId,
          ownerNodeId,
          leaseToken,
          sourceSetVersion,
          resourceId,
          resourceVersion);
    }

    if (result.callId() == null || !result.callId().equals(operationId.toString())) {
      log.warn("Capability result callId mismatch for operation {}", operationId);
      return publisher.publishInvalidResult(
          environmentId,
          operationId,
          ownerNodeId,
          leaseToken,
          sourceSetVersion,
          resourceId,
          resourceVersion);
    }

    if (result.error()) {
      log.info("Capability returned error for operation {}", operationId);
      return publisher.publishExecutionFailure(
          environmentId,
          operationId,
          ownerNodeId,
          leaseToken,
          sourceSetVersion,
          resourceId,
          resourceVersion);
    }

    if (result.contents().size() != 1
        || !(result.contents().getFirst() instanceof JsonResultContent jsonContent)) {
      log.warn("Capability returned invalid result contents for operation {}", operationId);
      return publisher.publishInvalidResult(
          environmentId,
          operationId,
          ownerNodeId,
          leaseToken,
          sourceSetVersion,
          resourceId,
          resourceVersion);
    }

    DaemonSkillSourceSnapshot snapshot;
    try {
      JsonNode node = objectMapper.readTree(jsonContent.json());
      snapshot = snapshotCodec.decodeNode(node, "skillSourceSnapshot");
    } catch (RuntimeException | JsonProcessingException e) {
      log.warn("Failed to decode skill source snapshot for operation {}", operationId);
      return publisher.publishInvalidResult(
          environmentId,
          operationId,
          ownerNodeId,
          leaseToken,
          sourceSetVersion,
          resourceId,
          resourceVersion);
    }

    if (!resourceId.equals(snapshot.sourceId()) || resourceVersion != snapshot.sourceVersion()) {
      log.warn("Snapshot source identity mismatch for operation {}", operationId);
      return publisher.publishInvalidResult(
          environmentId,
          operationId,
          ownerNodeId,
          leaseToken,
          sourceSetVersion,
          resourceId,
          resourceVersion);
    }

    log.info("Publishing success for operation {}", operationId);
    return publisher.publishOperationSuccess(
        environmentId, operationId, ownerNodeId, leaseToken, sourceSetVersion, snapshot);
  }

  private OperationPublishOutcome coordinateMcpDiscoverResult(
      UUID environmentId,
      UUID operationId,
      UUID ownerNodeId,
      UUID leaseToken,
      UUID resourceId,
      long resourceVersion,
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
}
