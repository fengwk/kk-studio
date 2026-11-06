package fun.fengwk.kkstudio.platform.environment.skill;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.daemon.DaemonSkillPackage;
import fun.fengwk.kkstudio.platform.daemon.DaemonSkillStatePackage;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentEvent;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentSkillState;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.platform.storage.dto.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Environment 技能期望集合的下发与状态收敛编排器。
 *
 * <p>触发源只有三个：READY 会话事件、PostgreSQL {@code skill_package_changed} 通知与用户点击「重新同步」。每次触发都下发一份**完整**
 * 期望集合（包名、归档摘要、大小与预签名下载地址），Daemon 据此对账并回报全部包状态；平台不在请求路径上等待安装，也不轮询。
 *
 * <p>同一 Environment 单飞：运行期间的新触发只保留一个标记，本轮结束后再跑一次；不同 Environment 互不阻塞。状态上报是完整事实，只在投影真正变化时以 owner +
 * lease token 围栏整体替换连接行。
 */
@Slf4j
public class EnvironmentSkillSyncOrchestrator {

  /** PostgreSQL 通知 channel：与 schema 的 {@code skill_package_changed} 触发器一致。 */
  public static final String CHANNEL = "skill_package_changed";

  private final EnvironmentRegistry environmentRegistry;
  private final SkillPackageRepository skillPackageRepository;
  private final StorageBlobManager storageBlobManager;
  private final EnvironmentSkillTransport transport;
  private final ExecutorService executor;
  private final Clock clock;
  private final Set<EnvironmentId> scheduled = ConcurrentHashMap.newKeySet();
  private final Set<EnvironmentId> pending = ConcurrentHashMap.newKeySet();

  public EnvironmentSkillSyncOrchestrator(
      EnvironmentRegistry environmentRegistry,
      SkillPackageRepository skillPackageRepository,
      StorageBlobManager storageBlobManager,
      EnvironmentSkillTransport transport,
      ExecutorService executor,
      Clock clock) {
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
    this.skillPackageRepository =
        Objects.requireNonNull(skillPackageRepository, "skillPackageRepository");
    this.storageBlobManager = Objects.requireNonNull(storageBlobManager, "storageBlobManager");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** READY 会话事件：向该 Environment 下发最新期望集合。 */
  public void onEnvironmentReady(EnvironmentId environmentId) {
    trigger(environmentId);
  }

  /** Package 变更通知：向本节点持有的全部 READY Environment 重新下发期望集合。 */
  public void onPackageChanged(String packageName) {
    if (packageName == null || packageName.isBlank()) {
      return;
    }
    for (EnvironmentConnection connection : readyOwnedEnvironments()) {
      trigger(connection.environmentId());
    }
  }

  /** 通知重连对账或用户重新同步：对全部 READY Environment 重新下发期望集合。 */
  public void reconcileReadyEnvironments() {
    for (EnvironmentConnection connection : readyOwnedEnvironments()) {
      trigger(connection.environmentId());
    }
  }

  /**
   * Daemon 上报一轮完整安装状态：按当前租约围栏整体替换 {@code skill_state} 投影。
   *
   * <p>上报与当前投影相同则不写数据库（不产生无意义的环境失效通知）；任何形状非法的条目都拒绝整轮上报。
   */
  public void onSkillState(
      EnvironmentId environmentId, List<DaemonSkillStatePackage> reportedPackages) {
    if (environmentId == null || reportedPackages == null) {
      return;
    }
    EnvironmentConnection connection = environmentRegistry.find(environmentId).orElse(null);
    if (connection == null || connection.status() != LiveEnvironmentStatus.READY) {
      return;
    }
    List<EnvironmentSkillState> states;
    try {
      states = toSkillStates(reportedPackages);
    } catch (IllegalArgumentException error) {
      log.warn(
          "environment skill state was rejected: environmentId={}, reason={}",
          environmentId,
          error.getMessage());
      return;
    }
    if (states.equals(connection.skillState())) {
      return;
    }
    try {
      environmentRegistry.replaceSkillState(
          environmentId,
          connection.leaseToken(),
          states,
          new EnvironmentEvent(
              clock.instant(),
              EnvironmentEvent.LEVEL_INFO,
              EnvironmentEvent.TYPE_SKILL_STATE_UPDATED,
              "skill state updated: " + states.size() + " packages"));
    } catch (RuntimeException error) {
      log.warn("environment skill state could not be recorded: environmentId={}", environmentId);
    }
  }

  private void trigger(EnvironmentId environmentId) {
    if (environmentId == null) {
      return;
    }
    pending.add(environmentId);
    schedule(environmentId);
  }

  /** 已排队的 Environment 不重复提交；任务结束时若又有触发则补跑一次。 */
  private void schedule(EnvironmentId environmentId) {
    if (!scheduled.add(environmentId)) {
      return;
    }
    try {
      executor.execute(() -> run(environmentId));
    } catch (RejectedExecutionException error) {
      scheduled.remove(environmentId);
      log.warn("environment skill sync was rejected: environmentId={}", environmentId);
    }
  }

  private void run(EnvironmentId environmentId) {
    try {
      do {
        pending.remove(environmentId);
        reconcile(environmentId);
      } while (pending.contains(environmentId));
    } finally {
      scheduled.remove(environmentId);
      if (pending.contains(environmentId)) {
        schedule(environmentId);
      }
    }
  }

  /** 下发一份完整期望集合；连接已不 READY 或队列满时放弃，下一次触发会重新收敛。 */
  private void reconcile(EnvironmentId environmentId) {
    EnvironmentConnection connection = environmentRegistry.find(environmentId).orElse(null);
    if (connection == null || connection.status() != LiveEnvironmentStatus.READY) {
      return;
    }
    try {
      List<DaemonSkillPackage> packages = desiredPackages();
      transport.sendSkillSync(environmentId, packages);
    } catch (RuntimeException error) {
      log.warn(
          "environment skill sync could not be sent: environmentId={}, reason={}",
          environmentId,
          error.getClass().getSimpleName());
    }
  }

  private List<DaemonSkillPackage> desiredPackages() {
    List<DaemonSkillPackage> packages = new ArrayList<>();
    for (SkillPackage skillPackage : skillPackageRepository.listPackages()) {
      try {
        StorageBlob artifact = storageBlobManager.getBlob(skillPackage.artifactBlobId());
        if (artifact == null || artifact.getState() != StorageBlobState.ACTIVE) {
          log.warn(
              "skill package artifact is unavailable for environment sync: {}",
              skillPackage.packageName());
          continue;
        }
        StoragePresignedUrlDTO download =
            storageBlobManager.presignOriginalUrl(skillPackage.artifactBlobId());
        packages.add(
            new DaemonSkillPackage(
                skillPackage.packageName(),
                artifact.getSha256(),
                artifact.getSizeBytes(),
                download.getUrl()));
      } catch (RuntimeException error) {
        log.warn(
            "skill package could not be prepared for environment sync: {}",
            skillPackage.packageName());
      }
    }
    return List.copyOf(packages);
  }

  private static List<EnvironmentSkillState> toSkillStates(
      List<DaemonSkillStatePackage> reportedPackages) {
    List<EnvironmentSkillState> states = new ArrayList<>(reportedPackages.size());
    Set<String> names = new HashSet<>();
    for (DaemonSkillStatePackage reported : reportedPackages) {
      Objects.requireNonNull(reported, "reported skill state");
      if (!names.add(reported.packageName())) {
        throw new IllegalArgumentException("duplicate skill package state");
      }
      states.add(
          new EnvironmentSkillState(
              reported.packageName(),
              reported.installedSha256(),
              reported.localPath(),
              reported.error()));
    }
    return List.copyOf(states);
  }

  private List<EnvironmentConnection> readyOwnedEnvironments() {
    try {
      return environmentRegistry.listReadyOwnedByNode();
    } catch (RuntimeException error) {
      log.warn("environment skill sync could not list ready owned environments", error);
      return List.of();
    }
  }
}
