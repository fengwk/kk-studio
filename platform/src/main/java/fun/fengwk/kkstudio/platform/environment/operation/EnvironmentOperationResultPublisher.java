package fun.fengwk.kkstudio.platform.environment.operation;

import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshot;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.skill.model.EnvironmentInventory;
import fun.fengwk.kkstudio.platform.environment.skill.model.SkillInventoryEntry;
import fun.fengwk.kkstudio.platform.environment.skill.model.SkillSource;
import fun.fengwk.kkstudio.platform.environment.skill.repo.SkillSourceRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 协调 Skill 操作执行结果并在全局锁与代际/租约围栏下原子持久化。
 *
 * <p>锁顺序：{@code environment} (key share) → {@code environment_connection} (for share) → {@code
 * environment_inventory} (for update) → {@code environment_skill_source} 全部行按 source_id 升序 (for
 * update) → {@code environment_operation} (update)。
 */
@AllArgsConstructor
@Service
class EnvironmentOperationResultPublisher {

  private static final String FENCE_SQL =
      """
      select environment_id, runtime_info
      from environment_connection
      where environment_id = ?
        and owner_node_id = ?
        and lease_token = ?
        and status = 'READY'
        and lease_until > statement_timestamp()
      for share
      """;

  private final EnvironmentRepository environmentRepository;
  private final SkillSourceRepository skillSourceRepository;
  private final EnvironmentOperationRepository environmentOperationRepository;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;
  private final DaemonCapabilitiesCodec capabilitiesCodec = new DaemonCapabilitiesCodec();

  @Transactional
  OperationPublishOutcome publishOperationSuccess(
      UUID environmentId,
      UUID operationId,
      UUID ownerNodeId,
      UUID leaseToken,
      long expectedSourceSetVersion,
      DaemonSkillSourceSnapshot snapshot,
      String resultSummaryJson) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(ownerNodeId, "ownerNodeId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(snapshot, "snapshot");

    if (environmentRepository.lockForKeyShare(environmentId) == null) {
      return OperationPublishOutcome.LEASE_LOST;
    }

    List<DaemonCapabilities> fencedRows =
        jdbcTemplate.query(
            FENCE_SQL,
            (rs, rowNum) -> {
              String json = rs.getString("runtime_info");
              return json != null ? capabilitiesCodec.decode(json) : null;
            },
            environmentId,
            ownerNodeId,
            leaseToken);
    if (fencedRows.isEmpty() || fencedRows.getFirst() == null) {
      return OperationPublishOutcome.LEASE_LOST;
    }
    DaemonCapabilities capabilities = fencedRows.getFirst();

    EnvironmentInventory inventory = skillSourceRepository.lockInventory(environmentId);
    if (inventory == null || inventory.getSourceSetVersion() != expectedSourceSetVersion) {
      environmentOperationRepository.markFailed(
          operationId,
          ownerNodeId,
          leaseToken,
          EnvironmentOperationFailureCodes.RESOURCE_CHANGED,
          EnvironmentOperationFailureCodes.RESOURCE_CHANGED_MESSAGE);
      return OperationPublishOutcome.RESOURCE_CHANGED;
    }

    List<SkillSource> lockedSources = skillSourceRepository.lockAllSources(environmentId);
    SkillSource source =
        lockedSources.stream()
            .filter(s -> s.getSourceId().equals(snapshot.sourceId()))
            .findFirst()
            .orElse(null);
    if (source == null || source.getVersion() != snapshot.sourceVersion()) {
      environmentOperationRepository.markFailed(
          operationId,
          ownerNodeId,
          leaseToken,
          EnvironmentOperationFailureCodes.RESOURCE_CHANGED,
          EnvironmentOperationFailureCodes.RESOURCE_CHANGED_MESSAGE);
      return OperationPublishOutcome.RESOURCE_CHANGED;
    }

    skillSourceRepository.deleteSourceSkills(environmentId, snapshot.sourceId());
    List<SkillInventoryEntry> entries = toEntries(environmentId, snapshot);
    skillSourceRepository.insertSkills(entries);
    if (!skillSourceRepository.markSourceReady(
        environmentId,
        snapshot.sourceId(),
        snapshot.sourceVersion(),
        snapshot.sourceRevision(),
        snapshot.diagnostics())) {
      throw new IllegalStateException("mark skill source READY failed: " + snapshot.sourceId());
    }

    if (!skillSourceRepository.applyReadyReport(
        environmentId,
        expectedSourceSetVersion,
        capabilities.version(),
        capabilities.environment().operatingSystem().wireValue(),
        capabilities.environment().timeZone(),
        capabilities.environment().note(),
        capabilities.environment().rootPath(),
        ownerNodeId,
        leaseToken)) {
      throw new IllegalStateException("apply skill inventory report failed: " + environmentId);
    }

    String summary =
        (resultSummaryJson == null || resultSummaryJson.isBlank()) ? "{}" : resultSummaryJson;
    if (!environmentOperationRepository.markSucceeded(
        operationId, ownerNodeId, leaseToken, summary)) {
      throw new IllegalStateException(
          "failed to mark operation succeeded under valid claim fence: " + operationId);
    }
    return OperationPublishOutcome.APPLIED;
  }

  @Transactional
  OperationPublishOutcome publishOperationFailure(
      UUID environmentId,
      UUID operationId,
      UUID ownerNodeId,
      UUID leaseToken,
      long expectedSourceSetVersion,
      UUID sourceId,
      long expectedSourceVersion,
      String failureCode,
      String failureMessage) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(ownerNodeId, "ownerNodeId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(sourceId, "sourceId");

    if (environmentRepository.lockForKeyShare(environmentId) == null) {
      return OperationPublishOutcome.LEASE_LOST;
    }

    List<UUID> fencedRows =
        jdbcTemplate.query(
            FENCE_SQL,
            (rs, rowNum) -> (UUID) rs.getObject("environment_id"),
            environmentId,
            ownerNodeId,
            leaseToken);
    if (fencedRows.isEmpty()) {
      return OperationPublishOutcome.LEASE_LOST;
    }

    EnvironmentInventory inventory = skillSourceRepository.lockInventory(environmentId);
    if (inventory == null || inventory.getSourceSetVersion() != expectedSourceSetVersion) {
      environmentOperationRepository.markFailed(
          operationId,
          ownerNodeId,
          leaseToken,
          EnvironmentOperationFailureCodes.RESOURCE_CHANGED,
          EnvironmentOperationFailureCodes.RESOURCE_CHANGED_MESSAGE);
      return OperationPublishOutcome.RESOURCE_CHANGED;
    }

    List<SkillSource> lockedSources = skillSourceRepository.lockAllSources(environmentId);
    SkillSource source =
        lockedSources.stream()
            .filter(s -> s.getSourceId().equals(sourceId))
            .findFirst()
            .orElse(null);
    if (source == null || source.getVersion() != expectedSourceVersion) {
      environmentOperationRepository.markFailed(
          operationId,
          ownerNodeId,
          leaseToken,
          EnvironmentOperationFailureCodes.RESOURCE_CHANGED,
          EnvironmentOperationFailureCodes.RESOURCE_CHANGED_MESSAGE);
      return OperationPublishOutcome.RESOURCE_CHANGED;
    }

    String code =
        (failureCode == null || failureCode.isBlank())
            ? EnvironmentOperationFailureCodes.OPERATION_FAILED
            : failureCode;
    String message =
        (failureMessage == null || failureMessage.isBlank())
            ? EnvironmentOperationFailureCodes.OPERATION_FAILED_MESSAGE
            : failureMessage;

    if (!skillSourceRepository.markSourceFailed(
        environmentId, sourceId, expectedSourceVersion, code, message)) {
      throw new IllegalStateException("mark skill source FAILED failed: " + sourceId);
    }

    if (!environmentOperationRepository.markFailed(
        operationId, ownerNodeId, leaseToken, code, message)) {
      throw new IllegalStateException(
          "failed to mark operation failed under valid claim fence: " + operationId);
    }
    return OperationPublishOutcome.APPLIED;
  }

  private List<SkillInventoryEntry> toEntries(
      UUID environmentId, DaemonSkillSourceSnapshot snapshot) {
    List<SkillInventoryEntry> entries = new ArrayList<>(snapshot.skills().size());
    Instant discoveredAt = clock.instant();
    for (DaemonSkillDescriptor skill : snapshot.skills()) {
      SkillInventoryEntry entry = new SkillInventoryEntry();
      entry.setEnvironmentId(environmentId);
      entry.setSourceId(snapshot.sourceId());
      entry.setName(skill.name());
      entry.setSourceVersion(skill.sourceVersion());
      entry.setDescription(skill.description());
      entry.setBaseDirectory(skill.baseDirectory());
      entry.setContentRevision(skill.contentRevision());
      entry.setDiscoveredAt(discoveredAt);
      entries.add(entry);
    }
    return List.copyOf(entries);
  }
}
