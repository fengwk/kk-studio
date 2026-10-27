package fun.fengwk.kkstudio.platform.environment.service;

import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.platform.environment.RegistrationTokens;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentEvent;
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
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentEventDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallCodeDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallConfigDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallConfigUpdateDTO;
import fun.fengwk.kkstudio.share.ai.environment.LiveEnvironmentCapabilityDTO;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

  /** 无有效连接（从未连接或连接已失效）时对外派生的状态。 */
  private static final String OFFLINE = "OFFLINE";

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
    String token = RegistrationTokens.generate();
    env.setRegistrationTokenHash(RegistrationTokens.hash(token));
    try {
      if (!environmentRepository.create(env)) {
        throw new IllegalStateException("create environment failed");
      }
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(RESOURCE, "environment name already exists: " + name, error);
    }
    Environment created = environmentRepository.getById(env.getId());
    return toCardDto(created, token);
  }

  @Override
  public EnvironmentCardDTO get(EnvironmentId id) {
    Objects.requireNonNull(id, "id");
    Environment env = environmentRepository.getById(id.value());
    if (env == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    return toCardDto(env, null);
  }

  @Override
  public List<EnvironmentCardDTO> list() {
    List<Environment> envs = environmentRepository.listNewestFirst();
    List<EnvironmentCardDTO> results = new ArrayList<>(envs.size());
    for (Environment env : envs) {
      results.add(toCardDto(env, null));
    }
    return List.copyOf(results);
  }

  @Override
  @Transactional
  public EnvironmentCardDTO importEnvironment(
      String name, String registrationTokenHash, EnvironmentInstallConfigDTO installConfig) {
    String canonicalName = validateName(name);
    String tokenHash = validateRegistrationTokenHash(registrationTokenHash);
    EnvironmentInstallConfigDTO config =
        installConfig == null ? null : EnvironmentInstallConfigs.validate(installConfig);
    if (environmentRepository.existsByName(canonicalName)) {
      throw new AiDuplicateException(RESOURCE, "environment name already exists: " + canonicalName);
    }
    if (environmentRepository.getByRegistrationTokenHash(tokenHash) != null) {
      throw new AiDuplicateException(RESOURCE, "environment registrationToken already in use");
    }
    Environment env = new Environment();
    env.setId(UUID.randomUUID());
    env.setName(canonicalName);
    env.setRegistrationTokenHash(tokenHash);
    env.setInstallConfig(config);
    try {
      if (!environmentRepository.create(env)) {
        throw new IllegalStateException("create environment failed");
      }
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(
          RESOURCE, "environment name or registrationToken already exists", error);
    }
    Environment created = environmentRepository.getById(env.getId());
    return toCardDto(created, null);
  }

  @Override
  @Transactional
  public EnvironmentCardDTO updateImportedEnvironment(
      EnvironmentId id,
      String registrationTokenHash,
      EnvironmentInstallConfigDTO installConfig,
      String expectedVersion) {
    Objects.requireNonNull(id, "id");
    String tokenHash = validateRegistrationTokenHash(registrationTokenHash);
    EnvironmentInstallConfigDTO config =
        installConfig == null ? null : EnvironmentInstallConfigs.validate(installConfig);
    long expected = CatalogVersions.parse(expectedVersion, "expectedVersion");
    Environment env = environmentRepository.lockById(id.value());
    if (env == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    if (env.getVersion() != expected) {
      throw new AiVersionConflictException(
          RESOURCE, expectedVersion, CatalogVersions.format(env.getVersion()));
    }
    if (tokenHash.equals(env.getRegistrationTokenHash())
        && Objects.equals(config, env.getInstallConfig())) {
      return toCardDto(env, null);
    }
    env.setRegistrationTokenHash(tokenHash);
    env.setInstallConfig(config);
    try {
      if (!environmentRepository.updateById(env, expected)) {
        Environment reread = environmentRepository.getById(id.value());
        if (reread == null) {
          throw new AiResourceNotFoundException(RESOURCE);
        }
        throw new AiVersionConflictException(
            RESOURCE, expectedVersion, CatalogVersions.format(reread.getVersion()));
      }
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(
          RESOURCE, "environment registrationToken already in use", error);
    }
    Environment updated = environmentRepository.getById(id.value());
    return toCardDto(updated, null);
  }

  @Override
  @Transactional
  public EnvironmentCardDTO updateInstallConfig(
      EnvironmentId id, EnvironmentInstallConfigUpdateDTO request) {
    Objects.requireNonNull(id, "id");
    if (request == null) {
      throw new AiValidationException(RESOURCE, "request body must not be null");
    }
    EnvironmentInstallConfigDTO config =
        EnvironmentInstallConfigs.validate(request.getInstallConfig());
    long expected = CatalogVersions.parse(request.getExpectedVersion(), "expectedVersion");
    Environment env = environmentRepository.lockById(id.value());
    if (env == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    if (env.getVersion() != expected) {
      throw new AiVersionConflictException(
          RESOURCE, request.getExpectedVersion(), CatalogVersions.format(env.getVersion()));
    }
    if (Objects.equals(config, env.getInstallConfig())) {
      return toCardDto(env, null);
    }
    env.setInstallConfig(config);
    if (!environmentRepository.updateById(env, expected)) {
      Environment reread = environmentRepository.getById(id.value());
      if (reread == null) {
        throw new AiResourceNotFoundException(RESOURCE);
      }
      throw new AiVersionConflictException(
          RESOURCE, request.getExpectedVersion(), CatalogVersions.format(reread.getVersion()));
    }
    return toCardDto(environmentRepository.getById(id.value()), null);
  }

  @Override
  public List<EnvironmentEventDTO> listEvents(EnvironmentId id) {
    Objects.requireNonNull(id, "id");
    if (environmentRepository.getById(id.value()) == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    // 事件是连接行保留的可重建投影：从未连接的 Environment 没有事件。
    EnvironmentConnection connection = environmentRegistry.find(id).orElse(null);
    if (connection == null) {
      return List.of();
    }
    List<EnvironmentEventDTO> events = new ArrayList<>(connection.recentEvents().size());
    for (EnvironmentEvent event : connection.recentEvents()) {
      events.add(toEventDto(event));
    }
    return List.copyOf(events);
  }

  @Override
  @Transactional
  public EnvironmentInstallCodeDTO issueInstallCode(EnvironmentId id, String expectedVersion) {
    Environment env = requireInstallable(id, expectedVersion);
    String code = EnvironmentInstallCodes.generate();
    Instant expiresAt = clock.instant().plus(Duration.ofMinutes(5));
    if (!environmentRepository.saveInstallCode(
        env.getId(), env.getVersion(), EnvironmentInstallCodes.hash(code), expiresAt)) {
      throw new IllegalStateException("locked environment changed while issuing an install code");
    }
    EnvironmentInstallCodeDTO result = new EnvironmentInstallCodeDTO();
    result.setCode(code);
    result.setExpiresAt(expiresAt);
    return result;
  }

  /**
   * 兑换安装 code：为本次安装签发新凭证并写入脚本。
   *
   * <p>锁定环境后由仓库在数据库时间下原子校验并清空 code，同时轮换凭证、递增版本并生成脚本；任一步失败均回滚。旧凭证的 Daemon 下次握手被拒绝。
   */
  @Override
  @Transactional
  public String installationScript(EnvironmentId id, String code) {
    Objects.requireNonNull(id, "id");
    Environment env = environmentRepository.lockById(id.value());
    if (env == null || env.getInstallConfig() == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    if (code == null) {
      throw new AiValidationException(RESOURCE, "invalid or expired install code");
    }
    String token = RegistrationTokens.generate();
    if (!environmentRepository.redeemInstallCode(
        env.getId(),
        env.getVersion(),
        EnvironmentInstallCodes.hash(code),
        RegistrationTokens.hash(token))) {
      throw new AiValidationException(RESOURCE, "invalid or expired install code");
    }
    return EnvironmentInstallScripts.install(env.getInstallConfig(), token);
  }

  /** 签发必须校验期望版本；兑换只校验 code，不接收版本。 */
  private Environment requireInstallable(EnvironmentId id, String expectedVersion) {
    Objects.requireNonNull(id, "id");
    Environment env = environmentRepository.lockById(id.value());
    if (env == null || env.getInstallConfig() == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    if (env.getVersion() != CatalogVersions.parse(expectedVersion, "expectedVersion")) {
      throw new AiVersionConflictException(
          RESOURCE, expectedVersion, CatalogVersions.format(env.getVersion()));
    }
    return env;
  }

  @Override
  public String uninstallationScript(String operatingSystem) {
    return EnvironmentInstallScripts.uninstall(operatingSystem);
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
    String token = RegistrationTokens.generate();
    env.setRegistrationTokenHash(RegistrationTokens.hash(token));
    if (!environmentRepository.updateById(env, expected)) {
      Environment reread = environmentRepository.getById(id.value());
      if (reread == null) {
        throw new AiResourceNotFoundException(RESOURCE);
      }
      throw new AiVersionConflictException(
          RESOURCE, expectedVersion, CatalogVersions.format(reread.getVersion()));
    }
    Environment updated = environmentRepository.getById(id.value());
    return toCardDto(updated, token);
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
            "select count(1) from durable_work where required_environment_id = ?",
            Integer.class,
            id.value());
    return count != null && count > 0;
  }

  private static EnvironmentEventDTO toEventDto(EnvironmentEvent event) {
    EnvironmentEventDTO dto = new EnvironmentEventDTO();
    dto.setTime(event.time());
    dto.setLevel(event.level());
    dto.setType(event.type());
    dto.setMessage(event.message());
    return dto;
  }

  /** CRUD 与配置同步共用的纯输入校验。 */
  public static String validateName(String raw) {
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

  /** 导入的凭证摘要必须是规范 SHA-256（64 位小写十六进制）。 */
  public static String validateRegistrationTokenHash(String raw) {
    if (!RegistrationTokens.isHash(raw)) {
      throw new AiValidationException(
          RESOURCE, "registrationTokenHash must be a lowercase hex SHA-256 digest");
    }
    return raw;
  }

  /** {@code token} 只在刚签发明文凭证的响应中非空。 */
  private EnvironmentCardDTO toCardDto(Environment env, String token) {
    EnvironmentCardDTO dto = new EnvironmentCardDTO();
    dto.setId(env.getId().toString());
    dto.setName(env.getName());
    dto.setInstallConfig(env.getInstallConfig());
    dto.setRegistrationToken(token);
    dto.setVersion(CatalogVersions.format(env.getVersion()));
    dto.setCreateTime(env.getCreateTime());
    dto.setUpdateTime(env.getUpdateTime());

    EnvironmentId envId = EnvironmentId.of(env.getId());
    Optional<EnvironmentConnection> connOpt = environmentRegistry.find(envId);
    if (connOpt.isEmpty()) {
      // 无连接行：从未连接过，无任何已保留的宿主 metadata。
      dto.setStatus(OFFLINE);
      dto.setReady(false);
      dto.setCapabilities(List.of());
      return dto;
    }
    // 连接状态、就绪判定、能力列表与 lastSeen 都是 live 事实；宿主 metadata 是连接行保留的最近一次 READY 事实。
    EnvironmentConnection conn = connOpt.get();
    Instant now = clock.instant();
    // 连接行只在状态截止时间之前仍是有效路由事实：租约与心跳窗口取更早者作为唯一上界，
    // 过期后同一时钟派生出 OFFLINE，并且不再把该连接的能力当作可用。
    Instant statusDeadline =
        statusDeadline(
            conn, Duration.ofMillis(snapshot.get().environment().heartbeatTimeoutMillis()));
    boolean statusValid = statusDeadline.isAfter(now);
    dto.setStatus(statusValid ? conn.status().name() : OFFLINE);
    dto.setStatusExpiresAt(statusValid ? statusDeadline : null);
    dto.setReady(
        conn.isReady(
            now, Duration.ofMillis(snapshot.get().environment().heartbeatTimeoutMillis())));
    dto.setLastSeen(conn.lastSeenAt());
    DaemonEnvironmentInfo host =
        conn.daemonCapabilities() == null ? null : conn.daemonCapabilities().environment();
    dto.setOperatingSystem(host == null ? null : host.operatingSystem().wireValue());
    dto.setTimeZone(host == null ? null : host.timeZone());
    dto.setNote(host == null ? null : host.note());
    dto.setUserName(host == null ? null : host.userName());
    dto.setHomeDirectory(host == null ? null : host.homeDirectory());
    dto.setDaemonVersion(conn.daemonVersion());
    dto.setCapabilities(
        statusValid
            ? conn.capabilities().stream()
                .map(
                    c -> {
                      LiveEnvironmentCapabilityDTO cdto = new LiveEnvironmentCapabilityDTO();
                      cdto.setId(c.id().value());
                      cdto.setVersion(c.version());
                      return cdto;
                    })
                .toList()
            : List.of());
    return dto;
  }

  /** 有效连接的状态截止时间：租约到期与心跳窗口到期两个上界取更早者，二者都由同一连接行事实给出。 */
  private static Instant statusDeadline(EnvironmentConnection conn, Duration heartbeatTimeout) {
    Instant heartbeatDeadline = conn.lastSeenAt().plus(heartbeatTimeout);
    return conn.leaseUntil().isBefore(heartbeatDeadline) ? conn.leaseUntil() : heartbeatDeadline;
  }
}
