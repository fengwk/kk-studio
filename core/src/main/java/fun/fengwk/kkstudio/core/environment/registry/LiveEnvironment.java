package fun.fengwk.kkstudio.core.environment.registry;

import fun.fengwk.kkstudio.core.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolCapabilitiesCodec.DaemonToolCapabilities;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Snapshot of one server-memory Environment entry keyed by non-blank {@code environmentName}.
 *
 * <p>Capabilities are empty until CAPABILITIES is accepted; status becomes {@link
 * LiveEnvironmentStatus#READY} only after READY.
 */
public record LiveEnvironment(
    String environmentName,
    LiveEnvironmentStatus status,
    EnvironmentDaemonConnection connection,
    DaemonToolCapabilities capabilities,
    Instant lastSeenAt) {

  public LiveEnvironment {
    environmentName = requireNonBlank(environmentName, "environmentName");
    status = Objects.requireNonNull(status, "status");
    connection = Objects.requireNonNull(connection, "connection");
    capabilities = Objects.requireNonNull(capabilities, "capabilities");
    lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt");
  }

  public List<ToolDescriptor> tools() {
    return capabilities.tools();
  }

  public List<DaemonSkillDescriptor> skills() {
    return capabilities.skills();
  }

  public boolean isReady() {
    return status == LiveEnvironmentStatus.READY && connection.isOpen();
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
