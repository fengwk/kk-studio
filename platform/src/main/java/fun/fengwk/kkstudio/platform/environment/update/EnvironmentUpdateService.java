package fun.fengwk.kkstudio.platform.environment.update;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateCommand;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateResult;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentUpdateListener;
import fun.fengwk.kkstudio.platform.environment.gateway.EnvironmentDaemonGateway;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentUpdateDTO;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 受管 Daemon 更新的单一产品路径：准入、持久阶段推进与最终成功判定。
 *
 * <p>不变量：
 *
 * <ul>
 *   <li>一个 Environment 同一时刻至多一次更新：内存准入（{@code EnvironmentDaemonServer}）与持久 operation 行双重保证；任一在途普通调用
 *       （含 Skill 同步）或活动 harness work 都忙拒绝。
 *   <li>更新期间普通 capability 调用在起点被拒绝：{@code EnvironmentDaemonServer.beginUpdate} 登记 operationId 后，
 *       core 对该 Environment 的普通调用 fail-closed。
 *   <li>最终成功只由「更新后 Daemon 以目标版本重新 READY」确认；Daemon 的阶段回执只表达准备进度，连接断开映射为派生 UNKNOWN（等待重连），
 *       绝不据此判定失败或重试。
 *   <li>失败保留旧二进制：命令不可达、准入被拒或 Daemon 预检失败都只终结 operation 并解除准入。
 * </ul>
 *
 * <p>是否「受管安装」由 Daemon 侧布局检查最终裁定，Platform 只下发固定官方发布目标。
 */
@Slf4j
@Service
public class EnvironmentUpdateService implements EnvironmentUpdateListener {

  private static final String RESOURCE = "environment";

  /** 允许推进为 RUNNING 的来源。 */
  private static final Set<EnvironmentUpdatePhase> RUNNING_FROM =
      EnumSet.of(EnvironmentUpdatePhase.PENDING);

  /** 允许推进为 PREPARED 的来源（ACCEPTED 回执可能丢失，因此也接受 PENDING）。 */
  private static final Set<EnvironmentUpdatePhase> PREPARED_FROM =
      EnumSet.of(EnvironmentUpdatePhase.PENDING, EnvironmentUpdatePhase.RUNNING);

  /** 允许终结或成功的来源。 */
  private static final Set<EnvironmentUpdatePhase> ACTIVE_FROM =
      EnumSet.of(
          EnvironmentUpdatePhase.PENDING,
          EnvironmentUpdatePhase.RUNNING,
          EnvironmentUpdatePhase.PREPARED);

  private static final int MAX_ERROR_CHARS = 500;

  private static final String ACTIVE_WORK_SQL =
      "select count(1) from harness_work where required_environment_id = ?";

  private final EnvironmentDaemonGateway gateway;
  private final EnvironmentRegistry registry;
  private final EnvironmentRepository environmentRepository;
  private final EnvironmentUpdateRepository updateRepository;
  private final DaemonReleaseProvider releaseProvider;
  private final SystemSettingsSnapshot snapshot;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  public EnvironmentUpdateService(
      EnvironmentDaemonGateway gateway,
      EnvironmentRegistry registry,
      EnvironmentRepository environmentRepository,
      EnvironmentUpdateRepository updateRepository,
      DaemonReleaseProvider releaseProvider,
      SystemSettingsSnapshot snapshot,
      JdbcTemplate jdbcTemplate,
      Clock clock) {
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.environmentRepository =
        Objects.requireNonNull(environmentRepository, "environmentRepository");
    this.updateRepository = Objects.requireNonNull(updateRepository, "updateRepository");
    this.releaseProvider = Objects.requireNonNull(releaseProvider, "releaseProvider");
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * 对目标 Environment 发起一次受管更新。
   *
   * @throws AiResourceNotFoundException 目标不存在
   * @throws AiInUseException 目标不可用、已有更新在途、存在在途调用/工作，或官方发布不可用
   */
  public EnvironmentUpdateDTO startUpdate(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    Environment environment = environmentRepository.getById(environmentId.value());
    if (environment == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    if (updateRepository.findActive(environmentId).isPresent()) {
      throw new AiInUseException(RESOURCE, "an update is already in progress for this environment");
    }
    if (!isReadyNow(environmentId)) {
      throw new AiInUseException(RESOURCE, "environment is not ready for update");
    }
    if (gateway.server().activeInvocationCount(environmentId) > 0) {
      throw new AiInUseException(RESOURCE, "cannot update environment with in-flight invocations");
    }
    if (hasActiveWork(environmentId)) {
      throw new AiInUseException(
          RESOURCE, "cannot update environment required by active harness work");
    }
    DaemonReleaseTarget target =
        releaseProvider
            .resolveTarget()
            .orElseThrow(
                () ->
                    new AiInUseException(RESOURCE, "the official daemon release is not available"));

    String operationId = UUID.randomUUID().toString();
    Instant now = clock.instant();
    EnvironmentUpdateOperation operation =
        new EnvironmentUpdateOperation(
            operationId,
            environmentId,
            target.targetVersion(),
            EnvironmentUpdatePhase.PENDING,
            null,
            now,
            now);
    if (!updateRepository.insertPending(operation)) {
      throw new AiInUseException(RESOURCE, "an update is already in progress for this environment");
    }
    try {
      gateway.server().beginUpdate(environmentId, operationId);
    } catch (RuntimeException error) {
      updateRepository.advance(
          operationId, EnvironmentUpdatePhase.FAILED, bounded(error.getMessage()), ACTIVE_FROM);
      throw error;
    }
    try {
      gateway
          .server()
          .sendUpdate(
              environmentId,
              new DaemonUpdateCommand(
                  operationId,
                  target.targetVersion(),
                  target.artifactUrl(),
                  target.artifactSha256()));
    } catch (RuntimeException error) {
      gateway.server().endUpdate(environmentId, operationId);
      updateRepository.advance(
          operationId,
          EnvironmentUpdatePhase.FAILED,
          bounded("update command could not be delivered"),
          ACTIVE_FROM);
      throw error;
    }
    updateRepository.advance(operationId, EnvironmentUpdatePhase.RUNNING, null, RUNNING_FROM);
    return describe(environmentId).orElseThrow();
  }

  /** 读取该 Environment 最近一次更新操作投影；从未更新时为 empty。 */
  public Optional<EnvironmentUpdateDTO> describe(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    return updateRepository.findLatest(environmentId).map(this::toDto);
  }

  @Override
  public void onUpdateResult(EnvironmentId environmentId, DaemonUpdateResult result) {
    Optional<EnvironmentUpdateOperation> found = updateRepository.find(result.operationId());
    if (found.isEmpty() || !found.get().environmentId().equals(environmentId)) {
      // 迟到的回执或不属于该 Environment 的 operation：不产生任何状态变更。
      return;
    }
    switch (result.phase()) {
      case ACCEPTED -> updateRepository.advance(
          result.operationId(), EnvironmentUpdatePhase.RUNNING, null, RUNNING_FROM);
      case PREPARED -> updateRepository.advance(
          result.operationId(), EnvironmentUpdatePhase.PREPARED, null, PREPARED_FROM);
      case FAILED -> {
        updateRepository.advance(
            result.operationId(),
            EnvironmentUpdatePhase.FAILED,
            bounded(result.message()),
            ACTIVE_FROM);
        gateway.server().endUpdate(environmentId, result.operationId());
      }
    }
  }

  /** READY 会话事件驱动的最终成功判定：只有目标版本与运行版本一致才终结为 SUCCEEDED 并解除准入；版本不一致只记录，不清除准入。 */
  public void onEnvironmentReady(EnvironmentId environmentId) {
    Optional<EnvironmentUpdateOperation> active = updateRepository.findActive(environmentId);
    if (active.isEmpty()) {
      return;
    }
    EnvironmentUpdateOperation operation = active.get();
    String actualVersion =
        registry.find(environmentId).map(EnvironmentConnection::daemonVersion).orElse(null);
    if (operation.targetVersion().equals(actualVersion)) {
      updateRepository.advance(
          operation.operationId(), EnvironmentUpdatePhase.SUCCEEDED, null, ACTIVE_FROM);
      gateway.server().endUpdate(environmentId, operation.operationId());
      return;
    }
    log.info(
        "environment {} is ready with daemon version {} while update {} targets {}",
        environmentId,
        actualVersion,
        operation.operationId(),
        operation.targetVersion());
  }

  private EnvironmentUpdateDTO toDto(EnvironmentUpdateOperation operation) {
    EnvironmentUpdateDTO dto = new EnvironmentUpdateDTO();
    dto.setOperationId(operation.operationId());
    dto.setTargetVersion(operation.targetVersion());
    dto.setPhase(derivePhase(operation).name());
    dto.setError(operation.error());
    dto.setCreatedAt(operation.createdAt());
    dto.setUpdatedAt(operation.updatedAt());
    return dto;
  }

  /** 推进中的操作在宿主不可达时派生 UNKNOWN：断开不等于失败，等待重连确认。 */
  private EnvironmentUpdatePhase derivePhase(EnvironmentUpdateOperation operation) {
    if (operation.phase().isActive() && !isReadyNow(operation.environmentId())) {
      return EnvironmentUpdatePhase.UNKNOWN;
    }
    return operation.phase();
  }

  private boolean isReadyNow(EnvironmentId environmentId) {
    return registry
        .find(environmentId)
        .map(
            connection ->
                connection.isReady(
                    clock.instant(),
                    Duration.ofMillis(snapshot.get().environment().heartbeatTimeoutMillis())))
        .orElse(false);
  }

  private boolean hasActiveWork(EnvironmentId environmentId) {
    Integer count =
        jdbcTemplate.queryForObject(ACTIVE_WORK_SQL, Integer.class, environmentId.value());
    return count != null && count > 0;
  }

  private static String bounded(String message) {
    if (message == null || message.isBlank()) {
      return null;
    }
    String sanitized = message.replaceAll("\\p{Cntrl}", " ").trim();
    return sanitized.length() > MAX_ERROR_CHARS
        ? sanitized.substring(0, MAX_ERROR_CHARS)
        : sanitized;
  }
}
