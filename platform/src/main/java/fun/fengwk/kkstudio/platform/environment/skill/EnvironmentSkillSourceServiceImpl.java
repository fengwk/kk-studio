package fun.fengwk.kkstudio.platform.environment.skill;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDiagnostic;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceType;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.environment.skill.SkillSourceConfigValidator.NormalizedConfig;
import fun.fengwk.kkstudio.platform.environment.skill.model.EnvironmentInventory;
import fun.fengwk.kkstudio.platform.environment.skill.model.SkillSource;
import fun.fengwk.kkstudio.platform.environment.skill.repo.SkillSourceRepository;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillDiagnosticDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceUpdateDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Skill 来源配置 CRUD 实现。
 *
 * <p>写事务按固定顺序取锁：{@code environment} 行（{@code for key share}）→ {@code environment_inventory}
 * 行（{@code for update}） → 该 Environment 的全部 {@code environment_skill_source} 行（按 {@code source_id}
 * 升序 {@code for update}）。 key share 允许并发的来源读取与其它 key share 事务共存，但会阻塞正在删除该 Environment 的冲突事务； 与
 * inventory 的排他锁协同，保证来源变更、版本推进、Agent 技能引用校验与删除操作按全局一致的锁顺序互斥，杜绝死锁与并发悬空引用。 只读查询（get/list）完全不取锁。
 */
@AllArgsConstructor
@Service
public class EnvironmentSkillSourceServiceImpl implements EnvironmentSkillSourceService {

  private final EnvironmentRepository environmentRepository;
  private final SkillSourceRepository skillSourceRepository;
  private final AgentDefinitionRepository agentDefinitionRepository;

  @Override
  public List<EnvironmentSkillSourceDTO> list(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    requireEnvironment(environmentId);
    return skillSourceRepository.listSources(environmentId.value()).stream()
        .map(EnvironmentSkillSourceServiceImpl::toDto)
        .toList();
  }

  @Override
  public EnvironmentSkillSourceDTO get(EnvironmentId environmentId, UUID sourceId) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(sourceId, "sourceId");
    requireEnvironment(environmentId);
    SkillSource source = skillSourceRepository.getSource(environmentId.value(), sourceId);
    if (source == null) {
      throw notFound(environmentId, sourceId);
    }
    return toDto(source);
  }

  @Override
  @Transactional
  public EnvironmentSkillSourceDTO create(
      EnvironmentId environmentId, EnvironmentSkillSourceCreateDTO request) {
    Objects.requireNonNull(environmentId, "environmentId");
    if (request == null) {
      throw new AiValidationException(
          SkillSourceConfigValidator.RESOURCE, "request body must not be null");
    }
    NormalizedConfig config =
        SkillSourceConfigValidator.normalize(
            request.getType(),
            request.getPath(),
            request.getGitUrl(),
            request.getGitRef(),
            request.getScanPath());
    lockMutationScope(environmentId);
    SkillSource source = new SkillSource();
    source.setSourceId(UUID.randomUUID());
    source.setEnvironmentId(environmentId.value());
    source.setType(config.type());
    source.setPath(config.path());
    source.setGitUrl(config.gitUrl());
    source.setGitRef(config.gitRef());
    source.setScanPath(config.scanPath());
    source.setDefaultSource(false);
    if (!skillSourceRepository.createSource(source)) {
      throw new IllegalStateException("create skill source failed");
    }
    requireSourceSetIncrement(environmentId);
    return toDto(requireSource(environmentId, source.getSourceId()));
  }

  @Override
  @Transactional
  public EnvironmentSkillSourceDTO update(
      EnvironmentId environmentId, UUID sourceId, EnvironmentSkillSourceUpdateDTO request) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(sourceId, "sourceId");
    if (request == null) {
      throw new AiValidationException(
          SkillSourceConfigValidator.RESOURCE, "request body must not be null");
    }
    long expected = CatalogVersions.parse(request.getExpectedVersion(), "expectedVersion");
    NormalizedConfig config =
        SkillSourceConfigValidator.normalize(
            request.getType(),
            request.getPath(),
            request.getGitUrl(),
            request.getGitRef(),
            request.getScanPath());
    MutationScope scope = lockMutationScope(environmentId);
    SkillSource current = requireLockedSource(environmentId, scope.sources(), sourceId);
    if (current.getVersion() != expected) {
      throw new AiVersionConflictException(
          SkillSourceConfigValidator.RESOURCE,
          request.getExpectedVersion(),
          CatalogVersions.format(current.getVersion()));
    }
    if (current.isDefaultSource() && config.type() != DaemonSkillSourceType.PATH) {
      throw new AiValidationException(
          SkillSourceConfigValidator.RESOURCE, "default skill source cannot be changed to git");
    }
    SkillSource replacement = new SkillSource();
    replacement.setSourceId(sourceId);
    replacement.setEnvironmentId(environmentId.value());
    replacement.setType(config.type());
    replacement.setPath(config.path());
    replacement.setGitUrl(config.gitUrl());
    replacement.setGitRef(config.gitRef());
    replacement.setScanPath(config.scanPath());
    replacement.setDefaultSource(current.isDefaultSource());
    replacement.setDiagnostics(List.of());
    if (!skillSourceRepository.updateSourceByVersion(replacement, expected)) {
      throw versionConflict(environmentId, sourceId, request.getExpectedVersion());
    }
    return toDto(requireSource(environmentId, sourceId));
  }

  @Override
  @Transactional
  public void delete(EnvironmentId environmentId, UUID sourceId, String expectedVersion) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(sourceId, "sourceId");
    long expected = CatalogVersions.parse(expectedVersion, "expectedVersion");
    MutationScope scope = lockMutationScope(environmentId);
    SkillSource current = requireLockedSource(environmentId, scope.sources(), sourceId);
    if (current.getVersion() != expected) {
      throw new AiVersionConflictException(
          SkillSourceConfigValidator.RESOURCE,
          expectedVersion,
          CatalogVersions.format(current.getVersion()));
    }
    if (agentDefinitionRepository.existsReferencingSkillSource(environmentId.value(), sourceId)) {
      throw new AiInUseException(
          SkillSourceConfigValidator.RESOURCE,
          "skill source is referenced by an agent: " + sourceId);
    }
    if (!skillSourceRepository.deleteSourceByVersion(environmentId.value(), sourceId, expected)) {
      throw versionConflict(environmentId, sourceId, expectedVersion);
    }
    // 删除改变的是集合成员关系（而不是配置内容），因此推进期望代际；已删除的来源绝不被自动补回。
    requireSourceSetIncrement(environmentId);
  }

  private Environment requireEnvironment(EnvironmentId environmentId) {
    Environment environment = environmentRepository.getById(environmentId.value());
    if (environment == null) {
      throw environmentNotFound(environmentId);
    }
    return environment;
  }

  private record MutationScope(EnvironmentInventory inventory, List<SkillSource> sources) {}

  /**
   * 取写路径的全部行锁，并返回按 {@code source_id} 升序的来源快照与已锁定的 inventory 行。
   *
   * <p>锁顺序固定为：{@code environment}（{@code for key share}，与并发删除 Environment 互斥）→ {@code
   * environment_inventory}（{@code for update}）→ 该 Environment 的全部 {@code environment_skill_source}
   * 行（{@code for update}，{@code source_id} 升序）。集合版本推进与 READY 发布使用同一顺序，因此并发写路径按同一顺序等待而不形成死锁。
   */
  private MutationScope lockMutationScope(EnvironmentId environmentId) {
    if (environmentRepository.lockForKeyShare(environmentId.value()) == null) {
      throw environmentNotFound(environmentId);
    }
    EnvironmentInventory inventory = skillSourceRepository.lockInventory(environmentId.value());
    if (inventory == null) {
      throw environmentNotFound(environmentId);
    }
    List<SkillSource> sources = skillSourceRepository.lockAllSources(environmentId.value());
    return new MutationScope(inventory, sources);
  }

  private SkillSource requireLockedSource(
      EnvironmentId environmentId, List<SkillSource> locked, UUID sourceId) {
    for (SkillSource source : locked) {
      if (source.getSourceId().equals(sourceId)) {
        return source;
      }
    }
    throw notFound(environmentId, sourceId);
  }

  private SkillSource requireSource(EnvironmentId environmentId, UUID sourceId) {
    SkillSource source = skillSourceRepository.getSource(environmentId.value(), sourceId);
    if (source == null) {
      throw notFound(environmentId, sourceId);
    }
    return source;
  }

  private void requireSourceSetIncrement(EnvironmentId environmentId) {
    if (!skillSourceRepository.incrementSourceSetVersion(environmentId.value())) {
      throw new IllegalStateException("increment skill source set version failed");
    }
  }

  /** CAS 未命中时按最新权威版本重读一次，让 409 携带真实 actual 版本而不是猜测值。 */
  private AiVersionConflictException versionConflict(
      EnvironmentId environmentId, UUID sourceId, String rawExpected) {
    SkillSource reread = skillSourceRepository.getSource(environmentId.value(), sourceId);
    if (reread == null) {
      return new AiVersionConflictException(
          SkillSourceConfigValidator.RESOURCE, rawExpected, rawExpected);
    }
    return new AiVersionConflictException(
        SkillSourceConfigValidator.RESOURCE,
        rawExpected,
        CatalogVersions.format(reread.getVersion()));
  }

  private static AiResourceNotFoundException environmentNotFound(EnvironmentId environmentId) {
    return new AiResourceNotFoundException("environment");
  }

  private static AiResourceNotFoundException notFound(EnvironmentId environmentId, UUID sourceId) {
    return new AiResourceNotFoundException(SkillSourceConfigValidator.RESOURCE);
  }

  static EnvironmentSkillSourceDTO toDto(SkillSource source) {
    EnvironmentSkillSourceDTO dto = new EnvironmentSkillSourceDTO();
    dto.setSourceId(source.getSourceId().toString());
    dto.setEnvironmentId(source.getEnvironmentId().toString());
    dto.setType(source.getType().wireValue());
    dto.setPath(source.getPath());
    dto.setGitUrl(source.getGitUrl());
    dto.setGitRef(source.getGitRef());
    dto.setScanPath(source.getScanPath());
    dto.setDefaultSource(source.isDefaultSource());
    dto.setVersion(CatalogVersions.format(source.getVersion()));
    dto.setStatus(source.getStatus().wireValue());
    dto.setAppliedVersion(
        source.getAppliedVersion() == null
            ? null
            : CatalogVersions.format(source.getAppliedVersion()));
    dto.setAppliedRevision(source.getAppliedRevision());
    List<EnvironmentSkillDiagnosticDTO> diagnostics = new ArrayList<>();
    for (DaemonSkillDiagnostic diagnostic : source.getDiagnostics()) {
      EnvironmentSkillDiagnosticDTO diagnosticDto = new EnvironmentSkillDiagnosticDTO();
      diagnosticDto.setLocation(diagnostic.location());
      diagnosticDto.setMessage(diagnostic.message());
      diagnostics.add(diagnosticDto);
    }
    dto.setDiagnostics(List.copyOf(diagnostics));
    dto.setLastErrorCode(source.getLastErrorCode());
    dto.setLastErrorMessage(source.getLastErrorMessage());
    dto.setLastAppliedAt(source.getLastAppliedAt());
    dto.setCreateTime(source.getCreateTime());
    dto.setUpdateTime(source.getUpdateTime());
    return dto;
  }
}
