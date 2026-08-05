package fun.fengwk.kkstudio.core.ai.environment.registry;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Snapshot of one server-memory Environment entry keyed by canonical {@link EnvironmentId}.
 *
 * <p>{@code name} is display-only metadata bound at HELLO; it may be reused across different ids
 * and never participates in routing. Environment tools are fixed by {@link EnvironmentToolCatalog};
 * READY only publishes the daemon's available skills.
 */
public record LiveEnvironment(
    EnvironmentId id,
    String name,
    LiveEnvironmentStatus status,
    EnvironmentDaemonConnection connection,
    List<DaemonSkillDescriptor> skills,
    Instant lastSeenAt) {

  public LiveEnvironment {
    id = Objects.requireNonNull(id, "id");
    name = requireNonBlank(name, "name");
    status = Objects.requireNonNull(status, "status");
    connection = Objects.requireNonNull(connection, "connection");
    skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
    lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt");
  }

  public List<ToolDescriptor> tools() {
    return EnvironmentToolCatalog.descriptors();
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
