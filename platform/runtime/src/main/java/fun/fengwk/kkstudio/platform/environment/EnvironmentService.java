package fun.fengwk.kkstudio.platform.environment;

import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.environment.dto.DaemonConfigCodec;
import fun.fengwk.kkstudio.platform.environment.dto.DaemonConfigDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentEventDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentEventLevel;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentEventType;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentInstallCodeDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentStatus;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentUpdateDTO;
import fun.fengwk.kkstudio.platform.environment.dto.LiveEnvironmentCapabilityDTO;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentEvent;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.platform.environment.update.PlatformBuildInfo;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 稳定 Environment 卡片业务服务：完整配置 CAS、备注、安装码与安装脚本。 */
@AllArgsConstructor
@Service
public class EnvironmentService {

  private static final String RESOURCE = "environment";

  private final EnvironmentRepository environmentRepository;
  private final EnvironmentRegistry environmentRegistry;
  private final SystemSettingsSnapshot snapshot;
  private final Clock clock;

  @Transactional
  public EnvironmentDTO create(EnvironmentCreateDTO dto) {
    if (dto == null) {
      throw DomainException.validation(RESOURCE, "request body must not be null");
    }
    String name = validateName(dto.getName());
    String note = validateNote(dto.getNote());
    DaemonConfigDTO daemonConfig = validateDaemonConfig(dto.getDaemonConfig());
    if (environmentRepository.existsByName(name)) {
      throw DomainException.duplicate(RESOURCE, "environment name already exists: " + name);
    }
    Environment env = new Environment();
    env.setId(UUID.randomUUID());
    env.setName(name);
    env.setNote(note);
    env.setDaemonConfig(daemonConfig);
    String token = RegistrationTokens.generate();
    env.setRegistrationTokenHash(RegistrationTokens.hash(token));
    try {
      if (!environmentRepository.create(env)) {
        throw new IllegalStateException("create environment failed");
      }
    } catch (DuplicateKeyException error) {
      throw DomainException.duplicate(RESOURCE, "environment name already exists: " + name, error);
    }
    Environment created = environmentRepository.getById(env.getId());
    return toDto(created, token);
  }

  public EnvironmentDTO get(EnvironmentId id) {
    Objects.requireNonNull(id, "id");
    Environment env = environmentRepository.getById(id.value());
    if (env == null) {
      throw DomainException.notFound(RESOURCE);
    }
    return toDto(env, null);
  }

  /** Work 路由使用数据库时间判定 READY 租约，不使用展示卡片的 JVM 时钟。 */
  public Optional<Instant> findReadyLeaseUntil(EnvironmentId id) {
    return environmentRegistry.findReadyLeaseUntil(Objects.requireNonNull(id, "id"));
  }

  /** 沿 Work 冻结的环境身份取得展示名。 */
  public String requireName(EnvironmentId id) {
    Environment environment =
        environmentRepository.getById(Objects.requireNonNull(id, "id").value());
    if (environment == null) {
      throw new IllegalStateException("waiting environment registry entry does not exist: " + id);
    }
    return environment.getName();
  }

  public List<EnvironmentDTO> list() {
    List<Environment> envs = environmentRepository.listNewestFirst();
    List<EnvironmentDTO> results = new ArrayList<>(envs.size());
    for (Environment env : envs) {
      results.add(toDto(env, null));
    }
    return List.copyOf(results);
  }

  @Transactional
  public EnvironmentDTO importEnvironment(
      String name, String note, String registrationTokenHash, DaemonConfigDTO daemonConfig) {
    String canonicalName = validateName(name);
    String canonicalNote = validateNote(note);
    String tokenHash = validateRegistrationTokenHash(registrationTokenHash);
    DaemonConfigDTO config = validateDaemonConfig(daemonConfig);
    if (environmentRepository.existsByName(canonicalName)) {
      throw DomainException.duplicate(
          RESOURCE, "environment name already exists: " + canonicalName);
    }
    if (environmentRepository.getByRegistrationTokenHash(tokenHash) != null) {
      throw DomainException.duplicate(RESOURCE, "environment registrationToken already in use");
    }
    Environment env = new Environment();
    env.setId(UUID.randomUUID());
    env.setName(canonicalName);
    env.setNote(canonicalNote);
    env.setRegistrationTokenHash(tokenHash);
    env.setDaemonConfig(config);
    try {
      if (!environmentRepository.create(env)) {
        throw new IllegalStateException("create environment failed");
      }
    } catch (DuplicateKeyException error) {
      throw DomainException.duplicate(
          RESOURCE, "environment name or registrationToken already exists", error);
    }
    Environment created = environmentRepository.getById(env.getId());
    return toDto(created, null);
  }

  @Transactional
  public EnvironmentDTO updateImportedEnvironment(
      EnvironmentId id,
      String note,
      String registrationTokenHash,
      DaemonConfigDTO daemonConfig,
      long expectedVersion) {
    Objects.requireNonNull(id, "id");
    String canonicalNote = validateNote(note);
    String tokenHash = validateRegistrationTokenHash(registrationTokenHash);
    DaemonConfigDTO config = validateDaemonConfig(daemonConfig);
    Environment env = environmentRepository.lockById(id.value());
    if (env == null) {
      throw DomainException.notFound(RESOURCE);
    }
    if (env.getVersion() != expectedVersion) {
      throw DomainException.versionConflict(RESOURCE, expectedVersion, env.getVersion());
    }
    env.setNote(canonicalNote);
    env.setRegistrationTokenHash(tokenHash);
    env.setDaemonConfig(config);
    try {
      if (!environmentRepository.updateById(env, expectedVersion)) {
        Environment reread = environmentRepository.getById(id.value());
        if (reread == null) {
          throw DomainException.notFound(RESOURCE);
        }
        throw DomainException.versionConflict(RESOURCE, expectedVersion, reread.getVersion());
      }
    } catch (DuplicateKeyException error) {
      throw DomainException.duplicate(
          RESOURCE, "environment registrationToken already in use", error);
    }
    Environment updated = environmentRepository.getById(id.value());
    return toDto(updated, null);
  }

  /** 显式 PUT 整体替换可编辑属性；即使内容相同也推进版本并发布配置变更通知。 */
  @Transactional
  public EnvironmentDTO update(EnvironmentId id, EnvironmentUpdateDTO request) {
    Objects.requireNonNull(id, "id");
    if (request == null) {
      throw DomainException.validation(RESOURCE, "request body must not be null");
    }
    Long expectedVersion = request.getExpectedVersion();
    if (expectedVersion == null) {
      throw DomainException.validation(RESOURCE, "expectedVersion is required");
    }
    String note = validateNote(request.getNote());
    DaemonConfigDTO config = validateDaemonConfig(request.getDaemonConfig());
    Environment env = environmentRepository.lockById(id.value());
    if (env == null) {
      throw DomainException.notFound(RESOURCE);
    }
    if (env.getVersion() != expectedVersion) {
      throw DomainException.versionConflict(RESOURCE, expectedVersion, env.getVersion());
    }
    env.setNote(note);
    env.setDaemonConfig(config);
    if (!environmentRepository.updateById(env, expectedVersion)) {
      Environment reread = environmentRepository.getById(id.value());
      if (reread == null) {
        throw DomainException.notFound(RESOURCE);
      }
      throw DomainException.versionConflict(RESOURCE, expectedVersion, reread.getVersion());
    }
    return toDto(environmentRepository.getById(id.value()), null);
  }

  public List<EnvironmentEventDTO> listEvents(EnvironmentId id) {
    Objects.requireNonNull(id, "id");
    if (environmentRepository.getById(id.value()) == null) {
      throw DomainException.notFound(RESOURCE);
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

  @Transactional
  public EnvironmentInstallCodeDTO issueInstallCode(EnvironmentId id, long expectedVersion) {
    Environment env = requireInstallable(id, expectedVersion);
    String code = EnvironmentInstallCodes.generate();
    Optional<Instant> expiresAt =
        environmentRepository.saveInstallCode(
            env.getId(), env.getVersion(), EnvironmentInstallCodes.hash(code));
    if (expiresAt.isEmpty()) {
      throw new IllegalStateException("locked environment changed while issuing an install code");
    }
    EnvironmentInstallCodeDTO result = new EnvironmentInstallCodeDTO();
    result.setCode(code);
    result.setExpiresAt(expiresAt.get());
    return result;
  }

  /**
   * 兑换安装 code：为本次安装签发新凭证并写入脚本。
   *
   * <p>锁定环境后由仓库在数据库时间下原子校验并清空 code，同时轮换凭证、递增版本并返回兑换后的 CAS 版本；任一步失败均回滚。旧凭证的
   * Daemon 下次握手被拒绝。
   */
  public String installationScript(EnvironmentId id, String code, DaemonOperatingSystem operatingSystem) {
    return installationScript(id, code, operatingSystem, PlatformBuildInfo.version());
  }

  String installationScript(
      EnvironmentId id,
      String code,
      DaemonOperatingSystem operatingSystem,
      String releaseVersion) {
    Objects.requireNonNull(id, "id");
    EnvironmentInstallScripts.requireInstallableOperatingSystem(operatingSystem);
    Environment env = environmentRepository.lockById(id.value());
    if (env == null) {
      throw DomainException.notFound(RESOURCE);
    }
    if (code == null) {
      throw DomainException.validation(RESOURCE, "invalid or expired install code");
    }
    String token = RegistrationTokens.generate();
    Optional<Long> redeemedVersion =
        environmentRepository.redeemInstallCode(
            env.getId(),
            env.getVersion(),
            EnvironmentInstallCodes.hash(code),
            RegistrationTokens.hash(token));
    if (redeemedVersion.isEmpty()) {
      throw DomainException.validation(RESOURCE, "invalid or expired install code");
    }
    return EnvironmentInstallScripts.install(
        env.getId(),
        redeemedVersion.get(),
        env.getDaemonConfig(),
        token,
        operatingSystem,
        releaseVersion);
  }

  /** 签发必须校验期望版本；兑换只校验 code 与目标操作系统，不接收版本。 */
  private Environment requireInstallable(EnvironmentId id, long expectedVersion) {
    Objects.requireNonNull(id, "id");
    Environment env = environmentRepository.lockById(id.value());
    if (env == null) {
      throw DomainException.notFound(RESOURCE);
    }
    if (env.getVersion() != expectedVersion) {
      throw DomainException.versionConflict(RESOURCE, expectedVersion, env.getVersion());
    }
    return env;
  }

  public String uninstallationScript(EnvironmentId id, DaemonOperatingSystem operatingSystem) {
    return uninstallationScript(id, operatingSystem, PlatformBuildInfo.version());
  }

  String uninstallationScript(
      EnvironmentId id, DaemonOperatingSystem operatingSystem, String releaseVersion) {
    Objects.requireNonNull(id, "id");
    EnvironmentInstallScripts.requireInstallableOperatingSystem(operatingSystem);
    if (environmentRepository.getById(id.value()) == null) {
      throw DomainException.notFound(RESOURCE);
    }
    return EnvironmentInstallScripts.uninstall(id.value(), operatingSystem, releaseVersion);
  }

  @Transactional
  public EnvironmentDTO rotateToken(EnvironmentId id, long expectedVersion) {
    Objects.requireNonNull(id, "id");
    Environment env = environmentRepository.lockById(id.value());
    if (env == null) {
      throw DomainException.notFound(RESOURCE);
    }
    if (env.getVersion() != expectedVersion) {
      throw DomainException.versionConflict(RESOURCE, expectedVersion, env.getVersion());
    }
    String token = RegistrationTokens.generate();
    env.setRegistrationTokenHash(RegistrationTokens.hash(token));
    if (!environmentRepository.updateById(env, expectedVersion)) {
      Environment reread = environmentRepository.getById(id.value());
      if (reread == null) {
        throw DomainException.notFound(RESOURCE);
      }
      throw DomainException.versionConflict(RESOURCE, expectedVersion, reread.getVersion());
    }
    Environment updated = environmentRepository.getById(id.value());
    return toDto(updated, token);
  }

  @Transactional
  public void delete(EnvironmentId id, long expectedVersion) {
    Objects.requireNonNull(id, "id");
    Environment env = environmentRepository.lockById(id.value());
    if (env == null) {
      throw DomainException.notFound(RESOURCE);
    }
    if (env.getVersion() != expectedVersion) {
      throw DomainException.versionConflict(RESOURCE, expectedVersion, env.getVersion());
    }
    if (environmentRegistry.hasActiveLease(id)) {
      throw DomainException.inUse(
          RESOURCE, "cannot delete environment with an active connection lease");
    }
    if (hasActiveWork(id)) {
      throw DomainException.inUse(
          RESOURCE, "cannot delete environment required by active harness work");
    }
    try {
      if (!environmentRepository.deleteById(id.value(), expectedVersion)) {
        Environment reread = environmentRepository.getById(id.value());
        if (reread == null) {
          throw DomainException.notFound(RESOURCE);
        }
        throw DomainException.versionConflict(RESOURCE, expectedVersion, reread.getVersion());
      }
    } catch (DataIntegrityViolationException error) {
      throw DomainException.inUse(
          RESOURCE, "cannot delete environment due to existing references", error);
    }
  }

  private boolean hasActiveWork(EnvironmentId id) {
    return environmentRepository.hasActiveWork(id.value());
  }

  private static EnvironmentEventDTO toEventDto(EnvironmentEvent event) {
    EnvironmentEventDTO dto = new EnvironmentEventDTO();
    dto.setOccurredAt(event.time());
    dto.setLevel(EnvironmentEventLevel.valueOf(event.level()));
    dto.setType(EnvironmentEventType.valueOf(event.type()));
    dto.setMessage(event.message());
    return dto;
  }

  /** CRUD 与配置同步共用的纯输入校验。 */
  public static String validateName(String raw) {
    if (raw == null) {
      throw DomainException.validation(RESOURCE, "environment name must not be blank");
    }
    String trimmed = raw.strip();
    if (trimmed.isEmpty()) {
      throw DomainException.validation(RESOURCE, "environment name must not be blank");
    }
    if (!raw.equals(trimmed)) {
      throw DomainException.validation(
          RESOURCE, "environment name must not contain surrounding whitespace");
    }
    if (trimmed.indexOf('/') >= 0) {
      throw DomainException.validation(RESOURCE, "environment name must not contain '/'");
    }
    if (trimmed.length() > 64) {
      throw DomainException.validation(RESOURCE, "environment name must be <= 64 characters");
    }
    return trimmed;
  }

  /** 卡片备注可为空；保存时去除首尾空白，拒绝控制字符与超过 512 字符的文本。 */
  public static String validateNote(String raw) {
    if (raw == null) {
      return null;
    }
    if (raw.codePoints().anyMatch(Character::isISOControl)
        || raw.indexOf('\u2028') >= 0
        || raw.indexOf('\u2029') >= 0) {
      throw DomainException.validation(
          RESOURCE, "environment note must not contain control characters");
    }
    String note = raw.strip();
    if (note.isEmpty()) {
      return null;
    }
    if (note.length() > 512) {
      throw DomainException.validation(RESOURCE, "environment note must be <= 512 characters");
    }
    return note;
  }

  /** CRUD 与配置同步共用的完整 Daemon 配置校验；错误不携带提交值。 */
  public static DaemonConfigDTO validateDaemonConfig(DaemonConfigDTO config) {
    if (config == null) {
      throw DomainException.validation(RESOURCE, "daemonConfig must be an object");
    }
    try {
      return DaemonConfigCodec.validate(config);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(RESOURCE, error.getMessage());
    }
  }

  /** 导入的凭证摘要必须是规范 SHA-256（64 位小写十六进制）。 */
  public static String validateRegistrationTokenHash(String raw) {
    if (!RegistrationTokens.isHash(raw)) {
      throw DomainException.validation(
          RESOURCE, "registrationTokenHash must be a lowercase hex SHA-256 digest");
    }
    return raw;
  }

  /** {@code token} 只在刚签发明文凭证的响应中非空。 */
  private EnvironmentDTO toDto(Environment env, String token) {
    EnvironmentDTO dto = new EnvironmentDTO();
    dto.setId(env.getId().toString());
    dto.setName(env.getName());
    dto.setNote(env.getNote());
    dto.setDaemonConfig(env.getDaemonConfig());
    dto.setRegistrationToken(token);
    dto.setVersion(env.getVersion());
    dto.setCreatedAt(env.getCreatedAt());
    dto.setUpdatedAt(env.getUpdatedAt());

    EnvironmentId envId = EnvironmentId.of(env.getId());
    Optional<EnvironmentConnection> connOpt = environmentRegistry.find(envId);
    if (connOpt.isEmpty()) {
      // 无连接行：从未连接过，无任何已保留的宿主 metadata。
      dto.setStatus(EnvironmentStatus.OFFLINE);
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
    dto.setStatus(
        statusValid
            ? (conn.status() == LiveEnvironmentStatus.READY
                ? EnvironmentStatus.READY
                : EnvironmentStatus.CONNECTING)
            : EnvironmentStatus.OFFLINE);
    dto.setStatusExpiresAt(statusValid ? statusDeadline : null);
    dto.setReady(
        conn.isReady(
            now, Duration.ofMillis(snapshot.get().environment().heartbeatTimeoutMillis())));
    dto.setLastSeenAt(conn.lastSeenAt());
    DaemonEnvironmentInfo host =
        conn.daemonCapabilities() == null ? null : conn.daemonCapabilities().environment();
    dto.setOperatingSystem(host == null ? null : host.operatingSystem());
    dto.setTimeZone(host == null ? null : host.timeZone());
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
