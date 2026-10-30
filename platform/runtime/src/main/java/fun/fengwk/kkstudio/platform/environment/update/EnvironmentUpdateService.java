package fun.fengwk.kkstudio.platform.environment.update;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonArchitecture;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonRuntimeSnapshot;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateArtifact;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateCommand;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdatePhase;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateProgress;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateResult;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentUpdateListener;
import fun.fengwk.kkstudio.platform.environment.Environment;
import fun.fengwk.kkstudio.platform.environment.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.gateway.EnvironmentDaemonGateway;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentEvent;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.error.AiEnvironmentUnavailableException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentRuntimeDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentRuntimeStatusDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentUpdateProgressDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentUpdateStartDTO;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * 受管 Daemon 更新的单一产品路径：当前运行快照读取、准入与命令下发。
 *
 * <p>不变量：
 *
 * <ul>
 *   <li>当前版本、更新进度和更新是否在途都来自 Daemon 的 runtimeStatus；Server 不持久化更新任务、阶段或历史。
 *   <li>一个 Environment 同一时刻至多一次更新最终由 Daemon 的本机准入门闩保证；Server 只在命令下发的短暂窗口内登记准入， 发送返回后立即释放，丢失回执不会永久锁住
 *       Platform。
 *   <li>UPDATE_RESULT 只是有界接收回执，不推进任务状态机；FAILED 只追加一条固定的去敏运维事件，不保留任何 Server 侧更新锁。
 *   <li>最终成功只由目标版本 Daemon 重新 READY 后的 runtimeStatus 证明，不由下载完成或回执推断。
 * </ul>
 *
 * <p>是否「受管安装」由 Daemon 侧布局检查最终裁定，Platform 只下发固定官方发布目标。
 */
@Slf4j
@Service
public class EnvironmentUpdateService implements EnvironmentUpdateListener {

  private static final String RESOURCE = "environment";

  private final EnvironmentDaemonGateway gateway;
  private final EnvironmentRegistry registry;
  private final EnvironmentRepository environmentRepository;
  private final DaemonReleaseProvider releaseProvider;
  private final SystemSettingsSnapshot snapshot;
  private final Clock clock;

  public EnvironmentUpdateService(
      EnvironmentDaemonGateway gateway,
      EnvironmentRegistry registry,
      EnvironmentRepository environmentRepository,
      DaemonReleaseProvider releaseProvider,
      SystemSettingsSnapshot snapshot,
      Clock clock) {
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.environmentRepository =
        Objects.requireNonNull(environmentRepository, "environmentRepository");
    this.releaseProvider = Objects.requireNonNull(releaseProvider, "releaseProvider");
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** 读取本节点当前 READY 连接代际缓存；没有可用快照时返回明确的 unavailable 错误。 */
  public EnvironmentRuntimeStatusDTO runtimeStatus(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    return gateway
        .server()
        .runtimeStatus(environmentId)
        .map(EnvironmentUpdateService::toDto)
        .orElseThrow(EnvironmentUpdateService::unavailable);
  }

  /**
   * 对目标 Environment 发起一次受管更新。
   *
   * <p>当前已是目标版本或同一目标正在更新时返回当前快照，不重复下发；不同目标、在途调用或 durable work 明确 busy。
   *
   * @throws AiResourceNotFoundException 目标不存在
   * @throws AiEnvironmentUnavailableException 目标没有当前 READY 运行快照
   * @throws AiInUseException 目标已有其它更新、存在在途调用/工作，或官方发布不可用
   */
  public EnvironmentUpdateStartDTO startUpdate(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    Environment environment = environmentRepository.getById(environmentId.value());
    if (environment == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    EnvironmentConnection connection = requireReadyConnection(environmentId);
    EnvironmentRuntimeStatusDTO current = runtimeStatus(environmentId);

    DaemonEnvironmentInfo host = connection.daemonCapabilities().environment();
    DaemonOperatingSystem operatingSystem = host.operatingSystem();
    DaemonArchitecture architecture = canonicalArchitecture(host.architecture());
    if (!DaemonUpdateArtifact.isSupported(operatingSystem, architecture)) {
      throw new AiInUseException(
          RESOURCE,
          "no official daemon release for "
              + DaemonUpdateArtifact.osSegment(operatingSystem)
              + "-"
              + architecture.wireValue());
    }
    DaemonReleaseTarget target =
        releaseProvider
            .resolveTarget(operatingSystem, architecture)
            .orElseThrow(
                () ->
                    new AiInUseException(RESOURCE, "the official daemon release is not available"));

    if (target.targetVersion().equals(current.getStatus().getDaemonVersion())) {
      return startResult(EnvironmentUpdateStartDTO.CURRENT, current);
    }
    DaemonUpdateProgress progress = toProgress(current.getStatus().getUpdate());
    if (progress != null) {
      if (target.targetVersion().equals(progress.targetVersion())) {
        return startResult(EnvironmentUpdateStartDTO.IN_PROGRESS, current);
      }
      throw new AiInUseException(
          RESOURCE, "an update to " + progress.targetVersion() + " is already in progress");
    }
    if (gateway.server().activeInvocationCount(environmentId) > 0) {
      throw new AiInUseException(RESOURCE, "cannot update environment with in-flight invocations");
    }
    if (hasActiveWork(environmentId)) {
      throw new AiInUseException(
          RESOURCE, "cannot update environment required by active harness work");
    }

    String operationId = UUID.randomUUID().toString();
    try {
      gateway.server().beginUpdate(environmentId, operationId);
    } catch (EnvironmentCapabilityBusyException error) {
      throw new AiInUseException(RESOURCE, "an update is already in progress for this environment");
    } catch (EnvironmentCapabilityUnavailableException error) {
      throw unavailable();
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
      return startResult(
          EnvironmentUpdateStartDTO.SENT_UNCONFIRMED, runtimeStatusOr(environmentId, current));
    } catch (EnvironmentCapabilityBusyException error) {
      recordUpdateFailure(environmentId);
      throw new AiInUseException(RESOURCE, "daemon is not ready to accept the update command");
    } catch (EnvironmentCapabilityUnavailableException error) {
      recordUpdateFailure(environmentId);
      throw unavailable();
    } catch (RuntimeException error) {
      recordUpdateFailure(environmentId);
      throw new AiInUseException(
          RESOURCE, "the update command could not be delivered with a confirmed outcome");
    } finally {
      // Daemon 持有最终本机互斥；Server 的准入只覆盖命令下发的瞬时窗口。
      gateway.server().endUpdate(environmentId, operationId);
    }
  }

  @Override
  public void onUpdateResult(EnvironmentId environmentId, DaemonUpdateResult result) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(result, "result");
    if (result.phase() != DaemonUpdatePhase.FAILED) {
      return;
    }
    // 回执只是接收事实：失败统一收敛为一条去敏、有界运维事件，不建立任务状态。
    recordUpdateFailure(environmentId);
  }

  private EnvironmentConnection requireReadyConnection(EnvironmentId environmentId) {
    Duration timeout = Duration.ofMillis(snapshot.get().environment().heartbeatTimeoutMillis());
    return registry
        .find(environmentId)
        .filter(candidate -> candidate.isReady(clock.instant(), timeout))
        .orElseThrow(EnvironmentUpdateService::unavailable);
  }

  private EnvironmentRuntimeStatusDTO runtimeStatusOr(
      EnvironmentId environmentId, EnvironmentRuntimeStatusDTO fallback) {
    return gateway
        .server()
        .runtimeStatus(environmentId)
        .map(EnvironmentUpdateService::toDto)
        .orElse(fallback);
  }

  private void recordUpdateFailure(EnvironmentId environmentId) {
    try {
      registry
          .find(environmentId)
          .filter(connection -> connection.ownerNodeId().equals(registry.ownerNodeId()))
          .ifPresent(
              connection ->
                  registry.recordEvent(
                      environmentId,
                      connection.leaseToken(),
                      new EnvironmentEvent(
                          clock.instant(),
                          EnvironmentEvent.LEVEL_WARN,
                          EnvironmentEvent.TYPE_UPDATE_FAILED,
                          "daemon update failed")));
    } catch (RuntimeException error) {
      log.warn(
          "failed to record update failure for environment {}: {}",
          environmentId,
          error.getClass().getSimpleName());
    }
  }

  private static EnvironmentRuntimeStatusDTO toDto(DaemonRuntimeSnapshot snapshot) {
    EnvironmentRuntimeDTO status = new EnvironmentRuntimeDTO();
    status.setDaemonVersion(snapshot.status().daemonVersion());
    status.setUpdate(toProgressDto(snapshot.status().update()));
    EnvironmentRuntimeStatusDTO dto = new EnvironmentRuntimeStatusDTO();
    dto.setRevision(Long.toString(snapshot.revision()));
    dto.setStatus(status);
    return dto;
  }

  private static EnvironmentUpdateStartDTO startResult(
      String commandStatus, EnvironmentRuntimeStatusDTO runtimeStatus) {
    EnvironmentUpdateStartDTO dto = new EnvironmentUpdateStartDTO();
    dto.setCommandStatus(commandStatus);
    dto.setRuntimeStatus(runtimeStatus);
    return dto;
  }

  private static EnvironmentUpdateProgressDTO toProgressDto(DaemonUpdateProgress progress) {
    if (progress == null) {
      return null;
    }
    EnvironmentUpdateProgressDTO dto = new EnvironmentUpdateProgressDTO();
    dto.setTargetVersion(progress.targetVersion());
    dto.setDownloadedBytes(progress.downloadedBytes());
    dto.setTotalBytes(progress.totalBytes());
    return dto;
  }

  private static DaemonUpdateProgress toProgress(EnvironmentUpdateProgressDTO progress) {
    if (progress == null) {
      return null;
    }
    return new DaemonUpdateProgress(
        progress.getTargetVersion(), progress.getDownloadedBytes(), progress.getTotalBytes());
  }

  /**
   * READY 上报的宿主架构解析为发布选择用的 canonical 架构：{@code null}（未上报）或未知取值一律拒绝，绝不猜测替代归档。
   *
   * <p>错误只说明拒绝，不回显上报取值。
   */
  private static DaemonArchitecture canonicalArchitecture(String architecture) {
    if (architecture == null) {
      throw new AiInUseException(RESOURCE, "host architecture is unknown for update");
    }
    try {
      return DaemonArchitecture.fromWireValue(architecture);
    } catch (IllegalArgumentException error) {
      throw new AiInUseException(RESOURCE, "unsupported host architecture for update");
    }
  }

  private boolean hasActiveWork(EnvironmentId environmentId) {
    return environmentRepository.hasActiveWork(environmentId.value());
  }

  private static AiEnvironmentUnavailableException unavailable() {
    return new AiEnvironmentUnavailableException(
        "environment runtime snapshot is unavailable; wait for READY or reconnect");
  }
}
