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
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfig;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfigCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceType;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.skill.model.EnvironmentInventory;
import fun.fengwk.kkstudio.platform.environment.skill.model.SkillSource;
import fun.fengwk.kkstudio.platform.environment.skill.repo.SkillSourceRepository;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentOperationCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentOperationDTO;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link EnvironmentOperationService} 默认实现。
 *
 * <p>遵循全局锁顺序：{@code environment} (key share) → {@code environment_inventory} (for update) → {@code
 * environment_skill_source} (for update)。
 */
@Slf4j
@Service
public class EnvironmentOperationServiceImpl implements EnvironmentOperationService {

  private static final String RESOURCE = "environment_operation";

  private final EnvironmentRepository environmentRepository;
  private final SkillSourceRepository skillSourceRepository;
  private final EnvironmentOperationRepository environmentOperationRepository;
  private final ObjectMapper objectMapper;
  private final DaemonSkillSourceConfigCodec configCodec;

  public EnvironmentOperationServiceImpl(
      EnvironmentRepository environmentRepository,
      SkillSourceRepository skillSourceRepository,
      EnvironmentOperationRepository environmentOperationRepository,
      ObjectMapper objectMapper) {
    this.environmentRepository =
        Objects.requireNonNull(environmentRepository, "environmentRepository");
    this.skillSourceRepository =
        Objects.requireNonNull(skillSourceRepository, "skillSourceRepository");
    this.environmentOperationRepository =
        Objects.requireNonNull(environmentOperationRepository, "environmentOperationRepository");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.configCodec = new DaemonSkillSourceConfigCodec();
  }

  @Override
  @Transactional
  public EnvironmentOperationDTO create(
      EnvironmentId environmentId,
      UUID sourceId,
      EnvironmentOperationType operationType,
      EnvironmentOperationCreateDTO request) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(sourceId, "sourceId");
    Objects.requireNonNull(operationType, "operationType");
    if (request == null || request.getTimeoutMillis() == null) {
      throw new AiValidationException(RESOURCE, "timeoutMillis must not be null");
    }
    long timeoutMillis = request.getTimeoutMillis();
    if (timeoutMillis <= 0) {
      throw new AiValidationException(RESOURCE, "timeoutMillis must be strictly positive");
    }

    EnvironmentCapabilityId capId = capabilityIdFor(operationType);
    EnvironmentCapabilityDescriptor descriptor = EnvironmentCapabilityCatalog.require(capId);
    long maxTimeoutMillis = descriptor.timeout().toMillis();
    if (timeoutMillis > maxTimeoutMillis) {
      throw new AiValidationException(
          RESOURCE, "timeoutMillis must not exceed " + maxTimeoutMillis + " ms");
    }

    UUID envId = environmentId.value();
    if (environmentRepository.lockForKeyShare(envId) == null) {
      throw new AiResourceNotFoundException("environment", envId.toString());
    }
    EnvironmentInventory inventory = skillSourceRepository.lockInventory(envId);
    if (inventory == null) {
      throw new AiResourceNotFoundException("environment", envId.toString());
    }
    List<SkillSource> lockedSources = skillSourceRepository.lockAllSources(envId);
    SkillSource source =
        lockedSources.stream()
            .filter(s -> s.getSourceId().equals(sourceId))
            .findFirst()
            .orElseThrow(
                () -> new AiResourceNotFoundException("skill_source", sourceId.toString()));

    validateOperationSemantics(source, operationType);

    Set<UUID> activeSourceIds =
        lockedSources.stream().map(SkillSource::getSourceId).collect(Collectors.toSet());

    String currentlyAppliedRevision;
    if (source.getType() == DaemonSkillSourceType.PATH) {
      currentlyAppliedRevision = null;
    } else if (source.getType() == DaemonSkillSourceType.GIT) {
      if (source.getAppliedVersion() != null && source.getAppliedVersion() == source.getVersion()) {
        currentlyAppliedRevision = source.getAppliedRevision();
      } else {
        currentlyAppliedRevision = null;
      }
    } else {
      currentlyAppliedRevision = null;
    }

    DaemonSkillSourceConfig config =
        new DaemonSkillSourceConfig(
            source.getSourceId(),
            source.getVersion(),
            inventory.getSourceSetVersion(),
            source.getType(),
            source.getPath(),
            source.isDefaultSource(),
            source.getGitUrl(),
            source.getGitRef(),
            source.getScanPath(),
            currentlyAppliedRevision,
            activeSourceIds);

    String arguments = configCodec.encode(config);
    String parameterSummary = buildParameterSummary(source);

    UUID operationId = UUID.randomUUID();
    CreatePendingOperationWithTimeoutCommand command =
        new CreatePendingOperationWithTimeoutCommand(
            operationId,
            envId,
            sourceId,
            operationType,
            source.getVersion(),
            inventory.getSourceSetVersion(),
            arguments,
            parameterSummary,
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
      throw new AiResourceNotFoundException("environment", envId.toString());
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

  private void validateOperationSemantics(
      SkillSource source, EnvironmentOperationType operationType) {
    if (operationType == EnvironmentOperationType.SKILL_INSTALL
        && source.getType() != DaemonSkillSourceType.GIT) {
      throw new AiValidationException(RESOURCE, "operation INSTALL is only valid for GIT sources");
    }
    if (operationType == EnvironmentOperationType.SKILL_UPDATE
        && source.getType() != DaemonSkillSourceType.GIT) {
      throw new AiValidationException(RESOURCE, "operation UPDATE is only valid for GIT sources");
    }
    if (operationType == EnvironmentOperationType.SKILL_REFRESH
        && source.getType() == DaemonSkillSourceType.GIT) {
      if (source.getAppliedVersion() == null
          || source.getAppliedVersion() != source.getVersion()
          || source.getAppliedRevision() == null
          || source.getAppliedRevision().isBlank()) {
        throw new AiValidationException(
            RESOURCE,
            "git skill source has no applied revision matching current version; install is required before refresh");
      }
    }
  }

  private String buildParameterSummary(SkillSource source) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("type", source.getType().wireValue());
    summary.put("defaultSource", source.isDefaultSource());
    try {
      return objectMapper.writeValueAsString(summary);
    } catch (JsonProcessingException e) {
      return "{}";
    }
  }

  private EnvironmentOperationDTO toDto(SafeEnvironmentOperation safe) {
    EnvironmentOperationDTO dto = new EnvironmentOperationDTO();
    dto.setId(safe.id().toString());
    dto.setEnvironmentId(safe.environmentId().toString());
    dto.setSourceId(safe.sourceId().toString());
    dto.setOperationType(safe.operationType().name());
    dto.setStatus(safe.status().name());
    dto.setSourceVersion(CatalogVersions.format(safe.sourceVersion()));
    dto.setSourceSetVersion(CatalogVersions.format(safe.sourceSetVersion()));
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
      case SKILL_REFRESH -> EnvironmentCapabilityIds.SKILL_SOURCE_REFRESH;
      case SKILL_INSTALL -> EnvironmentCapabilityIds.SKILL_SOURCE_INSTALL;
      case SKILL_UPDATE -> EnvironmentCapabilityIds.SKILL_SOURCE_UPDATE;
    };
  }
}
