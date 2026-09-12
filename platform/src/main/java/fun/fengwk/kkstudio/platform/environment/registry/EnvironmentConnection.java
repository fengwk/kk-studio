package fun.fengwk.kkstudio.platform.environment.registry;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Environment 当前连接路由行只读快照。 */
public record EnvironmentConnection(
    EnvironmentId environmentId,
    UUID ownerNodeId,
    UUID leaseToken,
    LiveEnvironmentStatus status,
    DaemonCapabilities daemonCapabilities,
    Instant lastSeenAt,
    Instant leaseUntil) {

  public EnvironmentConnection {
    environmentId = Objects.requireNonNull(environmentId, "environmentId");
    ownerNodeId = Objects.requireNonNull(ownerNodeId, "ownerNodeId");
    leaseToken = Objects.requireNonNull(leaseToken, "leaseToken");
    status = Objects.requireNonNull(status, "status");
    if (status == LiveEnvironmentStatus.READY) {
      daemonCapabilities = Objects.requireNonNull(daemonCapabilities, "READY daemonCapabilities");
    }
    lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt");
    leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
  }

  public List<EnvironmentCapabilityDescriptor> capabilities() {
    return EnvironmentCapabilityCatalog.descriptors();
  }

  /**
   * 展平 READY 来源快照中的全部 skill 描述。
   *
   * <p>这是 B2 之前的临时实现边界：Platform 尚未持久化权威 inventory，因此只从当前 READY 快照读取；READY 已在构造期保证 sourceId 唯一与
   * skill 名称全局唯一，因此展平结果没有隐式优先级。
   */
  public List<DaemonSkillDescriptor> skills() {
    return daemonCapabilities == null ? List.of() : daemonCapabilities.flattenSkills();
  }

  public String rootPath() {
    return daemonCapabilities == null ? null : daemonCapabilities.environment().rootPath();
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
