package fun.fengwk.kkstudio.platform.environment.operation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentOperationDTO;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link EnvironmentOperationService} 默认实现。
 *
 * <p>遵循全局锁顺序：{@code environment} (key share) → {@code environment_operation}（插入）。
 */
@Slf4j
@Service
public class EnvironmentOperationServiceImpl implements EnvironmentOperationService {

  private static final String RESOURCE = "environment_operation";

  private final EnvironmentRepository environmentRepository;
  private final EnvironmentOperationRepository environmentOperationRepository;
  private final ObjectMapper objectMapper;

  public EnvironmentOperationServiceImpl(
      EnvironmentRepository environmentRepository,
      EnvironmentOperationRepository environmentOperationRepository,
      ObjectMapper objectMapper) {
    this.environmentRepository =
        Objects.requireNonNull(environmentRepository, "environmentRepository");
    this.environmentOperationRepository =
        Objects.requireNonNull(environmentOperationRepository, "environmentOperationRepository");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  @Override
  @Transactional
  public EnvironmentOperationDTO createOperation(
      EnvironmentId environmentId,
      EnvironmentOperationType operationType,
      EnvironmentOperationResourceType resourceType,
      UUID resourceId,
      long resourceVersion,
      String arguments,
      String parameterSummary,
      long timeoutMillis) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(operationType, "operationType");
    Objects.requireNonNull(resourceType, "resourceType");
    Objects.requireNonNull(resourceId, "resourceId");
    if (operationType.resourceType() != resourceType) {
      throw new AiValidationException(
          RESOURCE,
          "operationType " + operationType + " is incompatible with resourceType " + resourceType);
    }
    if (resourceVersion < 0) {
      throw new AiValidationException(RESOURCE, "resourceVersion must be non-negative");
    }
    if (timeoutMillis <= 0) {
      throw new AiValidationException(RESOURCE, "timeoutMillis must be strictly positive");
    }

    EnvironmentCapabilityId capId = capabilityIdFor(operationType);
    // fail closed：只有已在 harness capability catalog 注册 descriptor 的操作类型才允许创建，
    // 避免产生永远无法被分发、或结果无法被原子消费的悬挂操作（MCP 目录切片注册 mcp.local.discover 后自动放开）。
    EnvironmentCapabilityDescriptor descriptor =
        EnvironmentCapabilityCatalog.find(capId)
            .orElseThrow(
                () ->
                    new AiValidationException(
                        RESOURCE,
                        "capability descriptor for operationType "
                            + operationType
                            + " is not registered"));
    long maxTimeoutMillis = descriptor.timeout().toMillis();
    if (timeoutMillis > maxTimeoutMillis) {
      throw new AiValidationException(
          RESOURCE, "timeoutMillis must not exceed " + maxTimeoutMillis + " ms");
    }

    UUID envId = environmentId.value();
    if (environmentRepository.lockForKeyShare(envId) == null) {
      throw new AiResourceNotFoundException("environment");
    }

    String safeSummary =
        (parameterSummary != null && !parameterSummary.isBlank()) ? parameterSummary : "{}";
    UUID operationId = UUID.randomUUID();
    CreatePendingOperationWithTimeoutCommand command =
        new CreatePendingOperationWithTimeoutCommand(
            operationId,
            envId,
            resourceType,
            resourceId,
            operationType,
            resourceVersion,
            arguments,
            safeSummary,
            timeoutMillis);

    environmentOperationRepository.createPendingWithTimeout(command);
    log.info("Created pending operation {} for environment {}", operationId, envId);
    return toDto(environmentOperationRepository.getSafe(envId, operationId));
  }

  @Override
  public EnvironmentOperationDTO get(EnvironmentId environmentId, UUID operationId) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(operationId, "operationId");
    SafeEnvironmentOperation safe =
        environmentOperationRepository.getSafe(environmentId.value(), operationId);
    return toDto(safe);
  }

  @Override
  public List<EnvironmentOperationDTO> list(EnvironmentId environmentId, int limit) {
    Objects.requireNonNull(environmentId, "environmentId");
    if (limit <= 0) {
      throw new AiValidationException(RESOURCE, "limit must be positive");
    }
    int boundedLimit = Math.min(limit, 100);
    UUID envId = environmentId.value();
    if (environmentRepository.getById(envId) == null) {
      throw new AiResourceNotFoundException("environment");
    }
    List<SafeEnvironmentOperation> safeList =
        environmentOperationRepository.listSafeByEnvironment(envId, boundedLimit);
    return safeList.stream().map(this::toDto).toList();
  }

  @Override
  @Transactional
  public EnvironmentOperationDTO cancel(EnvironmentId environmentId, UUID operationId) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(operationId, "operationId");
    UUID envId = environmentId.value();

    SafeEnvironmentOperation safe = environmentOperationRepository.getSafe(envId, operationId);
    if (safe.status() != EnvironmentOperationStatus.PENDING) {
      throw new AiValidationException(
          RESOURCE, "operation cannot be cancelled because it is in status " + safe.status());
    }

    boolean cancelled = environmentOperationRepository.cancelPending(operationId);
    if (!cancelled) {
      safe = environmentOperationRepository.getSafe(envId, operationId);
      if (safe.status() != EnvironmentOperationStatus.CANCELLED) {
        throw new AiValidationException(
            RESOURCE, "operation cannot be cancelled because it is in status " + safe.status());
      }
    }
    log.info("Cancelled operation {} for environment {}", operationId, envId);
    return toDto(environmentOperationRepository.getSafe(envId, operationId));
  }

  private EnvironmentOperationDTO toDto(SafeEnvironmentOperation safe) {
    EnvironmentOperationDTO dto = new EnvironmentOperationDTO();
    dto.setId(safe.id().toString());
    dto.setEnvironmentId(safe.environmentId().toString());
    dto.setResourceType(safe.resourceType().name());
    dto.setResourceId(safe.resourceId().toString());
    dto.setOperationType(safe.operationType().name());
    dto.setStatus(safe.status().name());
    dto.setResourceVersion(CatalogVersions.format(safe.resourceVersion()));
    dto.setParameterSummary(parseJsonObject(safe.parameterSummary()));
    dto.setDeadlineAt(safe.deadlineAt());
    dto.setStartedAt(safe.startedAt());
    dto.setFinishedAt(safe.finishedAt());
    dto.setResultSummary(parseJsonObject(safe.resultSummary()));
    dto.setFailureCode(safe.failureCode());
    dto.setFailureMessage(safe.failureMessage());
    dto.setCreatedAt(safe.createdAt());
    dto.setUpdatedAt(safe.updatedAt());
    return dto;
  }

  private Map<String, Object> parseJsonObject(String json) {
    if (json == null || json.isBlank() || "{}".equals(json.trim())) {
      return null;
    }
    try {
      return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private static EnvironmentCapabilityId capabilityIdFor(EnvironmentOperationType type) {
    return switch (type) {
      case MCP_SERVER_DISCOVER -> EnvironmentCapabilityIds.MCP_LOCAL_DISCOVER;
    };
  }
}
