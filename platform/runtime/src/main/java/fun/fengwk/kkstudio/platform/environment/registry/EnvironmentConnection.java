package fun.fengwk.kkstudio.platform.environment.registry;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.platform.daemon.DaemonReadyMetadata;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Environment 当前连接路由行只读快照。
 *
 * <p>除了连接与最近一次被接受的 READY 宿主 metadata，本快照还携带最近运维事件。 它们与连接事实同行保存，因此离开的持有者留下的写入不可能覆盖新持有者的行（写回始终按 owner
 * + lease token 围栏）。
 */
public record EnvironmentConnection(
    EnvironmentId environmentId,
    UUID ownerNodeId,
    UUID leaseToken,
    LiveEnvironmentStatus status,
    DaemonReadyMetadata readyMetadata,
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
      readyMetadata = Objects.requireNonNull(readyMetadata, "READY readyMetadata");
    }
    recentEvents = List.copyOf(Objects.requireNonNull(recentEvents, "recentEvents"));
    lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt");
    leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
  }

  public List<EnvironmentCapabilityDescriptor> capabilities() {
    return EnvironmentCapabilityCatalog.descriptors();
  }

  /** 连接行保留的最近一次 READY 宿主用户名；从未 READY 时为 null。 */
  public String userName() {
    return readyMetadata == null ? null : readyMetadata.hostInfo().userName();
  }

  /** 连接行保留的最近一次 READY 宿主 canonical home 目录；从未 READY 时为 null。 */
  public String homeDirectory() {
    return readyMetadata == null ? null : readyMetadata.hostInfo().homeDirectory();
  }

  /** 连接行保留的最近一次 READY 上报的实际 Daemon 构建版本；从未 READY 时为 null。 */
  public String daemonVersion() {
    return readyMetadata == null ? null : readyMetadata.daemonVersion();
  }

  public boolean isReady(Instant now, Duration heartbeatTimeout) {
    Instant current = now != null ? now : Instant.now();
    return status == LiveEnvironmentStatus.READY
        && leaseUntil.isAfter(current)
        && (heartbeatTimeout == null || lastSeenAt.isAfter(current.minus(heartbeatTimeout)));
  }
}
