package fun.fengwk.kkstudio.platform.environment.operation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResultCodes;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshot;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshotCodec;
import fun.fengwk.kkstudio.platform.environment.skill.OperationPublishOutcome;
import fun.fengwk.kkstudio.platform.environment.skill.SkillInventoryPublisher;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link EnvironmentOperationCompletionCoordinator} 默认实现。
 *
 * <p>安全边界：绝不将 Daemon 原始 stdout/stderr、错误文本或异常栈记录至日志或持久化至数据库。
 */
@Slf4j
@Service
public class EnvironmentOperationCompletionCoordinatorImpl
    implements EnvironmentOperationCompletionCoordinator {

  private final SkillInventoryPublisher publisher;
  private final EnvironmentOperationRepository repository;
  private final ObjectMapper objectMapper;
  private final DaemonSkillSourceSnapshotCodec snapshotCodec;

  public EnvironmentOperationCompletionCoordinatorImpl(
      SkillInventoryPublisher publisher,
      EnvironmentOperationRepository repository,
      ObjectMapper objectMapper) {
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.snapshotCodec = new DaemonSkillSourceSnapshotCodec();
  }

  @Override
  public OperationPublishOutcome coordinateResult(
      UUID environmentId,
      UUID operationId,
      UUID ownerNodeId,
      UUID leaseToken,
      long sourceSetVersion,
      UUID sourceId,
      long sourceVersion,
      EnvironmentCapabilityResult result) {
    Objects.requireNonNull(result, "result");

    if (result.error()) {
      String code = extractErrorCode(result.detailsJson());
      log.info("Capability returned error for operation {}", operationId);
      return publisher.publishOperationFailure(
          environmentId,
          operationId,
          ownerNodeId,
          leaseToken,
          sourceSetVersion,
          sourceId,
          sourceVersion,
          code,
          EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE,
          "{}");
    }

    if (result.contents().size() != 1
        || !(result.contents().getFirst() instanceof JsonResultContent jsonContent)) {
      log.warn("Capability returned invalid result contents for operation {}", operationId);
      return publisher.publishOperationFailure(
          environmentId,
          operationId,
          ownerNodeId,
          leaseToken,
          sourceSetVersion,
          sourceId,
          sourceVersion,
          EnvironmentOperationFailureCodes.INVALID_RESULT,
          EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE,
          "{}");
    }

    DaemonSkillSourceSnapshot snapshot;
    try {
      JsonNode node = objectMapper.readTree(jsonContent.json());
      snapshot = snapshotCodec.decodeNode(node, "skillSourceSnapshot");
    } catch (RuntimeException | JsonProcessingException e) {
      log.warn("Failed to decode skill source snapshot for operation {}", operationId);
      return publisher.publishOperationFailure(
          environmentId,
          operationId,
          ownerNodeId,
          leaseToken,
          sourceSetVersion,
          sourceId,
          sourceVersion,
          EnvironmentOperationFailureCodes.INVALID_RESULT,
          EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE,
          "{}");
    }

    if (!sourceId.equals(snapshot.sourceId()) || sourceVersion != snapshot.sourceVersion()) {
      log.warn("Snapshot source identity mismatch for operation {}", operationId);
      return publisher.publishOperationFailure(
          environmentId,
          operationId,
          ownerNodeId,
          leaseToken,
          sourceSetVersion,
          sourceId,
          sourceVersion,
          EnvironmentOperationFailureCodes.INVALID_RESULT,
          EnvironmentOperationFailureCodes.INVALID_RESULT_MESSAGE,
          "{}");
    }

    String resultSummaryJson = buildSuccessSummary(snapshot);
    log.info("Publishing success for operation {}", operationId);
    return publisher.publishOperationSuccess(
        environmentId,
        operationId,
        ownerNodeId,
        leaseToken,
        sourceSetVersion,
        snapshot,
        resultSummaryJson);
  }

  @Override
  public OperationPublishOutcome coordinateFailure(
      UUID environmentId,
      UUID operationId,
      UUID ownerNodeId,
      UUID leaseToken,
      long sourceSetVersion,
      UUID sourceId,
      long sourceVersion,
      String failureCode,
      String failureMessage) {
    String code =
        (failureCode == null || failureCode.isBlank())
            ? EnvironmentOperationFailureCodes.OPERATION_FAILED
            : failureCode;
    String message =
        (failureMessage == null || failureMessage.isBlank())
            ? EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE
            : failureMessage;
    log.info("Coordinating failure for operation {}", operationId);
    return publisher.publishOperationFailure(
        environmentId,
        operationId,
        ownerNodeId,
        leaseToken,
        sourceSetVersion,
        sourceId,
        sourceVersion,
        code,
        message,
        "{}");
  }

  @Override
  public boolean coordinateUnknown(
      UUID operationId,
      UUID ownerNodeId,
      UUID leaseToken,
      String failureCode,
      String failureMessage) {
    String code =
        (failureCode == null || failureCode.isBlank())
            ? EnvironmentOperationFailureCodes.TRANSPORT_ERROR
            : failureCode;
    String message =
        (failureMessage == null || failureMessage.isBlank())
            ? EnvironmentOperationFailureCodes.TRANSPORT_ERROR_MESSAGE
            : failureMessage;
    log.info("Coordinating unknown state for operation {}", operationId);
    return repository.markUnknown(operationId, ownerNodeId, leaseToken, code, message);
  }

  private String extractErrorCode(String detailsJson) {
    if (detailsJson == null || detailsJson.isBlank() || "{}".equals(detailsJson.trim())) {
      return EnvironmentOperationFailureCodes.OPERATION_FAILED;
    }
    try {
      JsonNode node = objectMapper.readTree(detailsJson);
      if (node != null && node.isObject() && node.has("code")) {
        JsonNode codeNode = node.get("code");
        if (codeNode.isTextual() && !codeNode.asText().isBlank()) {
          return EnvironmentCapabilityResultCodes.requireCode(codeNode.asText());
        }
      }
    } catch (RuntimeException | JsonProcessingException e) {
      // 忽略解析错误，回退到标准码
    }
    return EnvironmentOperationFailureCodes.OPERATION_FAILED;
  }

  private String buildSuccessSummary(DaemonSkillSourceSnapshot snapshot) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("skillCount", snapshot.skills().size());
    summary.put("diagnosticCount", snapshot.diagnostics().size());
    if (snapshot.sourceRevision() != null && !snapshot.sourceRevision().isBlank()) {
      summary.put("sourceRevision", snapshot.sourceRevision());
    }
    try {
      return objectMapper.writeValueAsString(summary);
    } catch (JsonProcessingException e) {
      return "{}";
    }
  }
}
