package fun.fengwk.kkstudio.platform.environment.registry;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Environment 当前连接路由行只读快照。
 *
 * <p>除了连接与最近一次被接受的 READY 宿主 metadata，本快照还携带同一行的两个可重建投影：Skill Package 同步状态与最近运维事件。
 * 它们与连接事实同行保存，因此离开的持有者留下的写入不可能覆盖新持有者的行（写回始终按 owner + lease token 围栏）。
 */
public record EnvironmentConnection(
    EnvironmentId environmentId,
    UUID ownerNodeId,
    UUID leaseToken,
    LiveEnvironmentStatus status,
    DaemonCapabilities daemonCapabilities,
    List<EnvironmentSkillState> skillState,
    List<EnvironmentEvent> recentEvents,
    Instant lastSeenAt,
    Instant leaseUntil) {

  /** 运维事件投影的容量上限：最近的 200 条，最旧的先被淘汰。 */
  public static final int MAX_RECENT_EVENTS = 200;

  public EnvironmentConnection {
    environmentId = Objects.requireNonNull(environmentId, "environmentId");
    ownerNodeId = Objects.requireNonNull(ownerNodeId, "ownerNodeId");
    leaseToken = Objects.requireNonNull(leaseToken, "leaseToken");
    status = Objects.requireNonNull(status, "status");
    if (status == LiveEnvironmentStatus.READY) {
      daemonCapabilities = Objects.requireNonNull(daemonCapabilities, "READY daemonCapabilities");
    }
    skillState = List.copyOf(Objects.requireNonNull(skillState, "skillState"));
    recentEvents = List.copyOf(Objects.requireNonNull(recentEvents, "recentEvents"));
    lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt");
    leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
  }

  public List<EnvironmentCapabilityDescriptor> capabilities() {
    return EnvironmentCapabilityCatalog.descriptors();
  }

  /** 连接行保留的最近一次 READY 宿主用户名；从未 READY 时为 null。 */
  public String userName() {
    return daemonCapabilities == null ? null : daemonCapabilities.environment().userName();
  }

  /** 连接行保留的最近一次 READY 宿主 canonical home 目录；从未 READY 时为 null。 */
  public String homeDirectory() {
    return daemonCapabilities == null ? null : daemonCapabilities.environment().homeDirectory();
  }

  /** 指定 Package 的同步投影；从未同步过时为空。 */
  public Optional<EnvironmentSkillState> skillStateOf(String packageName) {
    return skillState.stream().filter(state -> state.packageName().equals(packageName)).findFirst();
  }

  /**
   * 指定 Package 在本地可用的稳定安装根：只有状态为 installed 且已安装 commit 与 {@code currentCommit} 完全一致时才返回。
   *
   * <p>该判定是「精确安装事实」的唯一入口：任何失败、过期或缺失的投影都必须让调用方回退 platform URI。
   */
  public Optional<String> installedSkillRoot(String packageName, String currentCommit) {
    return skillStateOf(packageName)
        .filter(state -> state.isInstalledAt(currentCommit))
        .map(EnvironmentSkillState::localPath);
  }

  /** 最近一条 WARN/ERROR 运维事件；没有此类事件时为空。 */
  public Optional<EnvironmentEvent> lastAlert() {
    for (int index = recentEvents.size() - 1; index >= 0; index--) {
      EnvironmentEvent event = recentEvents.get(index);
      if (event.isAlert()) {
        return Optional.of(event);
      }
    }
    return Optional.empty();
  }

  public boolean isReady(Instant now, Duration heartbeatTimeout) {
    Instant current = now != null ? now : Instant.now();
    return status == LiveEnvironmentStatus.READY
        && leaseUntil.isAfter(current)
        && (heartbeatTimeout == null || lastSeenAt.isAfter(current.minus(heartbeatTimeout)));
  }

  public boolean isOnline(Instant now) {
    Instant current = now != null ? now : Instant.now();
    return leaseUntil.isAfter(current);
  }
}
