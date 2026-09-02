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

  public List<DaemonSkillDescriptor> skills() {
    return daemonCapabilities == null ? List.of() : daemonCapabilities.skills();
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
