package fun.fengwk.kkstudio.platform.environment.skill.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDiagnostic;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceType;
import fun.fengwk.kkstudio.platform.environment.skill.SkillDiagnosticsCodec;
import fun.fengwk.kkstudio.platform.environment.skill.SkillSourceStatus;
import fun.fengwk.kkstudio.platform.environment.skill.model.EnvironmentInventory;
import fun.fengwk.kkstudio.platform.environment.skill.model.SkillInventoryEntry;
import fun.fengwk.kkstudio.platform.environment.skill.model.SkillSource;
import fun.fengwk.kkstudio.platform.environment.skill.repo.SkillSourceRepository;
import fun.fengwk.kkstudio.platform.environment.skill.repo.impl.mapper.EnvironmentInventoryMapper;
import fun.fengwk.kkstudio.platform.environment.skill.repo.impl.mapper.EnvironmentSkillMapper;
import fun.fengwk.kkstudio.platform.environment.skill.repo.impl.mapper.EnvironmentSkillSourceMapper;
import fun.fengwk.kkstudio.platform.environment.skill.repo.impl.model.EnvironmentInventoryDO;
import fun.fengwk.kkstudio.platform.environment.skill.repo.impl.model.EnvironmentSkillDO;
import fun.fengwk.kkstudio.platform.environment.skill.repo.impl.model.EnvironmentSkillSourceDO;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 基于 PostgreSQL 的 Skill 来源配置与持久 inventory 仓库实现。 */
@AllArgsConstructor
@Repository
public class PostgresqlSkillSourceRepository implements SkillSourceRepository {

  private final EnvironmentInventoryMapper inventoryMapper;
  private final EnvironmentSkillSourceMapper sourceMapper;
  private final EnvironmentSkillMapper skillMapper;
  private final SkillDiagnosticsCodec diagnosticsCodec;

  @Override
  public EnvironmentInventory getInventory(UUID environmentId) {
    return toInventoryModel(inventoryMapper.get(environmentId));
  }

  @Override
  public EnvironmentInventory lockInventory(UUID environmentId) {
    return toInventoryModel(inventoryMapper.lock(environmentId));
  }

  @Override
  public boolean createInventory(UUID environmentId) {
    return inventoryMapper.insert(environmentId) == 1;
  }

  @Override
  public boolean incrementSourceSetVersion(UUID environmentId) {
    return inventoryMapper.incrementSourceSetVersion(environmentId) == 1;
  }

  @Override
  public boolean applyReadyReport(
      UUID environmentId,
      long sourceSetVersion,
      int capabilitiesVersion,
      String operatingSystem,
      String timeZone,
      String note,
      String rootPath,
      UUID ownerNodeId,
      UUID leaseToken) {
    return inventoryMapper.applyReadyReport(
            environmentId,
            sourceSetVersion,
            capabilitiesVersion,
            operatingSystem,
            timeZone,
            note,
            rootPath,
            ownerNodeId,
            leaseToken)
        == 1;
  }

  @Override
  public List<SkillSource> listSources(UUID environmentId) {
    return sourceMapper.listByEnvironment(environmentId).stream()
        .map(this::toSourceModel)
        .collect(Collectors.toList());
  }

  @Override
  public SkillSource getSource(UUID environmentId, UUID sourceId) {
    return toSourceModel(sourceMapper.get(environmentId, sourceId));
  }

  @Override
  public SkillSource lockSource(UUID environmentId, UUID sourceId) {
    return toSourceModel(sourceMapper.lock(environmentId, sourceId));
  }

  @Override
  public List<SkillSource> lockAllSources(UUID environmentId) {
    return sourceMapper.lockAllByEnvironment(environmentId).stream()
        .map(this::toSourceModel)
        .collect(Collectors.toList());
  }

  @Override
  public boolean createSource(SkillSource source) {
    return sourceMapper.insert(toSourceDO(source)) == 1;
  }

  @Override
  public boolean updateSourceByVersion(SkillSource source, long expectedVersion) {
    return sourceMapper.updateByVersion(toSourceDO(source), expectedVersion) == 1;
  }

  @Override
  public boolean deleteSourceByVersion(UUID environmentId, UUID sourceId, long expectedVersion) {
    return sourceMapper.deleteByVersion(environmentId, sourceId, expectedVersion) == 1;
  }

  @Override
  public boolean existsSource(UUID environmentId) {
    return sourceMapper.existsByEnvironment(environmentId);
  }

  @Override
  public boolean markSourceReady(
      UUID environmentId,
      UUID sourceId,
      long appliedVersion,
      String appliedRevision,
      List<DaemonSkillDiagnostic> diagnostics) {
    return sourceMapper.markReadyByVersion(
            environmentId,
            sourceId,
            appliedVersion,
            appliedRevision,
            diagnosticsCodec.encode(diagnostics))
        == 1;
  }

  private static final Pattern UPPER_SNAKE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
  private static final int MAX_ERROR_MESSAGE_CHARS = 2048;

  @Override
  public boolean markSourceFailed(
      UUID environmentId, UUID sourceId, long sourceVersion, String safeCode, String safeMessage) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(sourceId, "sourceId");
    if (sourceVersion < 0) {
      throw new IllegalArgumentException("sourceVersion must not be negative");
    }
    String code = validateErrorCode(safeCode);
    String message = validateErrorMessage(safeMessage);
    return sourceMapper.markFailedByVersion(environmentId, sourceId, sourceVersion, code, message)
        == 1;
  }

  private static String validateErrorCode(String code) {
    if (code == null
        || code.isBlank()
        || !code.equals(code.strip())
        || !UPPER_SNAKE.matcher(code).matches()) {
      throw new IllegalArgumentException("invalid error code");
    }
    return code;
  }

  private static String validateErrorMessage(String message) {
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("invalid error message");
    }
    String stripped = message.strip();
    if (stripped.length() > MAX_ERROR_MESSAGE_CHARS) {
      throw new IllegalArgumentException("invalid error message");
    }
    if (stripped.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("invalid error message");
    }
    return stripped;
  }

  @Override
  public List<SkillInventoryEntry> listSkills(UUID environmentId) {
    return skillMapper.listByEnvironment(environmentId).stream()
        .map(this::toSkillModel)
        .collect(Collectors.toList());
  }

  @Override
  public List<SkillInventoryEntry> listUsableSkills(UUID environmentId) {
    return skillMapper.listUsableByEnvironment(environmentId).stream()
        .map(this::toSkillModel)
        .collect(Collectors.toList());
  }

  @Override
  public void deleteSourceSkills(UUID environmentId, UUID sourceId) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(sourceId, "sourceId");
    skillMapper.deleteBySource(environmentId, sourceId);
  }

  @Override
  public void insertSkills(List<SkillInventoryEntry> skills) {
    Objects.requireNonNull(skills, "skills");
    if (!skills.isEmpty()) {
      skillMapper.insertAll(skills.stream().map(this::toSkillDO).toList());
    }
  }

  @Override
  public void replaceSourceSkills(
      UUID environmentId, UUID sourceId, List<SkillInventoryEntry> skills) {
    deleteSourceSkills(environmentId, sourceId);
    insertSkills(skills);
  }

  private EnvironmentInventory toInventoryModel(EnvironmentInventoryDO row) {
    if (row == null) {
      return null;
    }
    EnvironmentInventory target = new EnvironmentInventory();
    target.setEnvironmentId(row.getEnvironmentId());
    target.setSourceSetVersion(row.getSourceSetVersion());
    target.setAppliedSourceSetVersion(row.getAppliedSourceSetVersion());
    target.setCapabilitiesVersion(row.getCapabilitiesVersion());
    target.setOperatingSystem(row.getOperatingSystem());
    target.setTimeZone(row.getTimeZone());
    target.setNote(row.getNote());
    target.setRootPath(row.getRootPath());
    target.setOwnerNodeId(row.getOwnerNodeId());
    target.setLeaseToken(row.getLeaseToken());
    target.setReportedAt(row.getReportedAt());
    target.setCreateTime(row.getCreateTime());
    target.setUpdateTime(row.getUpdateTime());
    return target;
  }

  private SkillSource toSourceModel(EnvironmentSkillSourceDO row) {
    if (row == null) {
      return null;
    }
    SkillSource target = new SkillSource();
    target.setSourceId(row.getSourceId());
    target.setEnvironmentId(row.getEnvironmentId());
    target.setType(DaemonSkillSourceType.fromWireValue(row.getSourceType()));
    target.setPath(row.getPath());
    target.setDefaultSource(Boolean.TRUE.equals(row.getDefaultSource()));
    target.setGitUrl(row.getGitUrl());
    target.setGitRef(row.getGitRef());
    target.setScanPath(row.getScanPath());
    target.setVersion(row.getVersion());
    target.setStatus(SkillSourceStatus.require(row.getStatus()));
    target.setAppliedVersion(row.getAppliedVersion());
    target.setAppliedRevision(row.getAppliedRevision());
    target.setDiagnostics(diagnosticsCodec.decode(row.getDiagnosticsJson()));
    target.setLastErrorCode(row.getLastErrorCode());
    target.setLastErrorMessage(row.getLastErrorMessage());
    target.setLastAppliedAt(row.getLastAppliedAt());
    target.setCreateTime(row.getCreateTime());
    target.setUpdateTime(row.getUpdateTime());
    return target;
  }

  private EnvironmentSkillSourceDO toSourceDO(SkillSource model) {
    EnvironmentSkillSourceDO target = new EnvironmentSkillSourceDO();
    target.setSourceId(model.getSourceId());
    target.setEnvironmentId(model.getEnvironmentId());
    target.setSourceType(model.getType().wireValue());
    target.setPath(model.getPath());
    target.setDefaultSource(model.isDefaultSource());
    target.setGitUrl(model.getGitUrl());
    target.setGitRef(model.getGitRef());
    target.setScanPath(model.getScanPath());
    target.setVersion(model.getVersion());
    target.setStatus(
        model.getStatus() == null
            ? SkillSourceStatus.UNAPPLIED.wireValue()
            : model.getStatus().wireValue());
    target.setAppliedVersion(model.getAppliedVersion());
    target.setAppliedRevision(model.getAppliedRevision());
    target.setDiagnosticsJson(diagnosticsCodec.encode(model.getDiagnostics()));
    target.setLastErrorCode(model.getLastErrorCode());
    target.setLastErrorMessage(model.getLastErrorMessage());
    target.setLastAppliedAt(model.getLastAppliedAt());
    return target;
  }

  private SkillInventoryEntry toSkillModel(EnvironmentSkillDO row) {
    SkillInventoryEntry target = new SkillInventoryEntry();
    target.setEnvironmentId(row.getEnvironmentId());
    target.setSourceId(row.getSourceId());
    target.setName(row.getName());
    target.setSourceVersion(row.getSourceVersion());
    target.setDescription(row.getDescription());
    target.setBaseDirectory(row.getBaseDirectory());
    target.setContentRevision(row.getContentRevision());
    target.setDiscoveredAt(row.getDiscoveredAt());
    return target;
  }

  private EnvironmentSkillDO toSkillDO(SkillInventoryEntry model) {
    EnvironmentSkillDO target = new EnvironmentSkillDO();
    target.setEnvironmentId(model.getEnvironmentId());
    target.setSourceId(model.getSourceId());
    target.setName(model.getName());
    target.setSourceVersion(model.getSourceVersion());
    target.setDescription(model.getDescription());
    target.setBaseDirectory(model.getBaseDirectory());
    target.setContentRevision(model.getContentRevision());
    target.setDiscoveredAt(model.getDiscoveredAt());
    return target;
  }
}
