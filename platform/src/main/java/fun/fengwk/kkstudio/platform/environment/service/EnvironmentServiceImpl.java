package fun.fengwk.kkstudio.platform.environment.service;

import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCardDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentRegistrationTokenDTO;
import fun.fengwk.kkstudio.share.ai.environment.LiveEnvironmentCapabilityDTO;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 稳定 Environment Card 业务实现。 */
@AllArgsConstructor
@Service
public class EnvironmentServiceImpl implements EnvironmentService {

  private static final String RESOURCE = "environment";

  private final EnvironmentRepository environmentRepository;
  private final EnvironmentRegistry environmentRegistry;
  private final JdbcTemplate jdbcTemplate;
  private final SystemSettingsSnapshot snapshot;
  private final Clock clock;

  @Override
  @Transactional
  public EnvironmentCardDTO create(EnvironmentCreateDTO dto) {
    if (dto == null) {
      throw new AiValidationException(RESOURCE, "request body must not be null");
    }
    String name = validateName(dto.getName());
    if (environmentRepository.existsByName(name)) {
      throw new AiDuplicateException(RESOURCE, "environment name already exists: " + name);
    }
    Environment env = new Environment();
    env.setId(UUID.randomUUID());
    env.setName(name);
    env.setRegistrationToken(UUID.randomUUID().toString());
    try {
      if (!environmentRepository.create(env)) {
        throw new IllegalStateException("create environment failed");
      }
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(RESOURCE, "environment name already exists: " + name, error);
    }
    Environment created = environmentRepository.getById(env.getId());
    return toCardDto(created, true);
  }

  @Override
  public EnvironmentCardDTO get(EnvironmentId id) {
    Objects.requireNonNull(id, "id");
    Environment env = environmentRepository.getById(id.value());
    if (env == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    return toCardDto(env, false);
  }

  @Override
  public List<EnvironmentCardDTO> list() {
    List<Environment> envs = environmentRepository.listNewestFirst();
    List<EnvironmentCardDTO> results = new ArrayList<>(envs.size());
    for (Environment env : envs) {
      results.add(toCardDto(env, false));
    }
    return List.copyOf(results);
  }

  @Override
  public EnvironmentRegistrationTokenDTO getRegistrationToken(EnvironmentId id) {
    Objects.requireNonNull(id, "id");
    Environment env = environmentRepository.getById(id.value());
    if (env == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    EnvironmentRegistrationTokenDTO dto = new EnvironmentRegistrationTokenDTO();
    dto.setId(env.getId().toString());
    dto.setRegistrationToken(env.getRegistrationToken());
    dto.setVersion(CatalogVersions.format(env.getVersion()));
    return dto;
  }

  @Override
  @Transactional
  public EnvironmentCardDTO rotateToken(EnvironmentId id, String expectedVersion) {
    Objects.requireNonNull(id, "id");
    long expected = CatalogVersions.parse(expectedVersion, "expectedVersion");
    Environment env = environmentRepository.lockById(id.value());
    if (env == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    if (env.getVersion() != expected) {
      throw new AiVersionConflictException(
          RESOURCE, expectedVersion, CatalogVersions.format(env.getVersion()));
    }
    env.setRegistrationToken(UUID.randomUUID().toString());
    if (!environmentRepository.updateById(env, expected)) {
      Environment reread = environmentRepository.getById(id.value());
      if (reread == null) {
        throw new AiResourceNotFoundException(RESOURCE);
      }
      throw new AiVersionConflictException(
          RESOURCE, expectedVersion, CatalogVersions.format(reread.getVersion()));
    }
    Environment updated = environmentRepository.getById(id.value());
    return toCardDto(updated, true);
  }

  @Override
  @Transactional
  public void delete(EnvironmentId id, String expectedVersion) {
    Objects.requireNonNull(id, "id");
    long expected = CatalogVersions.parse(expectedVersion, "expectedVersion");
    Environment env = environmentRepository.lockById(id.value());
    if (env == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    if (env.getVersion() != expected) {
      throw new AiVersionConflictException(
          RESOURCE, expectedVersion, CatalogVersions.format(env.getVersion()));
    }
    if (environmentRegistry.hasActiveLease(id)) {
      throw new AiInUseException(
          RESOURCE, "cannot delete environment with an active connection lease");
    }
    if (hasActiveWork(id)) {
      throw new AiInUseException(
          RESOURCE, "cannot delete environment required by active harness work");
    }
    try {
      if (!environmentRepository.deleteById(id.value(), expected)) {
        Environment reread = environmentRepository.getById(id.value());
        if (reread == null) {
          throw new AiResourceNotFoundException(RESOURCE);
        }
        throw new AiVersionConflictException(
            RESOURCE, expectedVersion, CatalogVersions.format(reread.getVersion()));
      }
    } catch (DataIntegrityViolationException error) {
      throw new AiInUseException(
          RESOURCE, "cannot delete environment due to existing references", error);
    }
  }

  private boolean hasActiveWork(EnvironmentId id) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(1) from harness_work where required_environment_id = ?",
            Integer.class,
            id.value());
    return count != null && count > 0;
  }

  private String validateName(String raw) {
    if (raw == null) {
      throw new AiValidationException(RESOURCE, "environment name must not be blank");
    }
    String trimmed = raw.strip();
    if (trimmed.isEmpty()) {
      throw new AiValidationException(RESOURCE, "environment name must not be blank");
    }
    if (!raw.equals(trimmed)) {
      throw new AiValidationException(
          RESOURCE, "environment name must not contain surrounding whitespace");
    }
    if (trimmed.indexOf('/') >= 0) {
      throw new AiValidationException(RESOURCE, "environment name must not contain '/'");
    }
    if (trimmed.length() > 64) {
      throw new AiValidationException(RESOURCE, "environment name must be <= 64 characters");
    }
    return trimmed;
  }

  private EnvironmentCardDTO toCardDto(Environment env, boolean exposeToken) {
    EnvironmentCardDTO dto = new EnvironmentCardDTO();
    dto.setId(env.getId().toString());
    dto.setName(env.getName());
    if (exposeToken) {
      dto.setRegistrationToken(env.getRegistrationToken());
    }
    dto.setVersion(CatalogVersions.format(env.getVersion()));
    dto.setCreateTime(env.getCreateTime());
    dto.setUpdateTime(env.getUpdateTime());

    EnvironmentId envId = EnvironmentId.of(env.getId());
    Optional<EnvironmentConnection> connOpt = environmentRegistry.find(envId);
    if (connOpt.isEmpty()) {
      // 无连接行：从未连接过，无任何已保留的宿主 metadata。
      dto.setStatus("OFFLINE");
      dto.setReady(false);
      dto.setCapabilities(List.of());
      return dto;
    }
    // 连接状态、就绪判定、能力列表与 lastSeen 都是 live 事实；宿主 metadata 是连接行保留的最近一次 READY 事实。
    EnvironmentConnection conn = connOpt.get();
    dto.setStatus(conn.status().name());
    dto.setReady(
        conn.isReady(
            clock.instant(),
            Duration.ofMillis(snapshot.get().environment().heartbeatTimeoutMillis())));
    dto.setLastSeen(conn.lastSeenAt());
    DaemonEnvironmentInfo host =
        conn.daemonCapabilities() == null ? null : conn.daemonCapabilities().environment();
    dto.setOperatingSystem(host == null ? null : host.operatingSystem().wireValue());
    dto.setTimeZone(host == null ? null : host.timeZone());
    dto.setNote(host == null ? null : host.note());
    dto.setRootPath(host == null ? null : host.rootPath());
    dto.setCapabilities(
        conn.capabilities().stream()
            .map(
                c -> {
                  LiveEnvironmentCapabilityDTO cdto = new LiveEnvironmentCapabilityDTO();
                  cdto.setId(c.id().value());
                  cdto.setVersion(c.version());
                  return cdto;
                })
            .toList());
    return dto;
  }
}
