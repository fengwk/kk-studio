package fun.fengwk.kkstudio.core.ai.environment.registry;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-safe server-memory Environment registry keyed by canonical {@link EnvironmentId}.
 *
 * <p>First connected id wins: a later HELLO for an occupied id is rejected without displacing the
 * original entry. Entries are removed on disconnect. Display names are metadata only: the same name
 * may be bound to different ids, and routing never falls back to a name. Nothing is persisted.
 */
@Component
public class LiveEnvironmentRegistry {

  private final Map<EnvironmentId, LiveEnvironment> byId = new LinkedHashMap<>();

  public LiveEnvironmentRegistry() {}

  /**
   * Attempts to bind {@code environmentId} (with display {@code environmentName}) to {@code
   * connection} after HELLO authentication.
   *
   * @return true when this connection becomes the first occupant of the id
   */
  public synchronized boolean tryBind(
      EnvironmentId environmentId,
      String environmentName,
      EnvironmentDaemonConnection connection,
      Instant now) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(now, "now");
    LiveEnvironment existing = byId.get(environmentId);
    if (existing != null) {
      return existing.connection().connectionId().equals(connection.connectionId());
    }
    byId.put(
        environmentId,
        new LiveEnvironment(
            environmentId,
            requireNonBlank(environmentName, "environmentName"),
            LiveEnvironmentStatus.CONNECTING,
            connection,
            List.of(),
            now));
    return true;
  }

  /** Replaces the daemon's advertised skills for a bound id owned by {@code connection}. */
  public synchronized void updateSkills(
      EnvironmentId environmentId,
      EnvironmentDaemonConnection connection,
      List<DaemonSkillDescriptor> skills,
      Instant now) {
    LiveEnvironment current = requireOwned(environmentId, connection);
    byId.put(
        current.id(),
        new LiveEnvironment(
            current.id(),
            current.name(),
            current.status(),
            current.connection(),
            List.copyOf(Objects.requireNonNull(skills, "skills")),
            Objects.requireNonNull(now, "now")));
  }

  /**
   * Marks the bound id READY so gateway workers may dispatch against it. Callers own the
   * skills-before-READY transition; an empty skills list remains valid.
   */
  public synchronized void markReady(
      EnvironmentId environmentId, EnvironmentDaemonConnection connection, Instant now) {
    LiveEnvironment current = requireOwned(environmentId, connection);
    byId.put(
        current.id(),
        new LiveEnvironment(
            current.id(),
            current.name(),
            LiveEnvironmentStatus.READY,
            current.connection(),
            current.skills(),
            Objects.requireNonNull(now, "now")));
  }

  /** Refreshes last-seen for a bound id owned by {@code connection}. */
  public synchronized void heartbeat(
      EnvironmentId environmentId, EnvironmentDaemonConnection connection, Instant now) {
    LiveEnvironment current = requireOwned(environmentId, connection);
    byId.put(
        current.id(),
        new LiveEnvironment(
            current.id(),
            current.name(),
            current.status(),
            current.connection(),
            current.skills(),
            Objects.requireNonNull(now, "now")));
  }

  /**
   * Removes the entry only when {@code connection} still owns {@code environmentId}. Safe to call
   * on every disconnect path.
   */
  public synchronized void unregister(
      EnvironmentId environmentId, EnvironmentDaemonConnection connection) {
    if (environmentId == null || connection == null) {
      return;
    }
    LiveEnvironment current = byId.get(environmentId);
    if (current != null && current.connection().connectionId().equals(connection.connectionId())) {
      byId.remove(environmentId);
    }
  }

  public synchronized Optional<LiveEnvironment> find(EnvironmentId environmentId) {
    if (environmentId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(byId.get(environmentId));
  }

  public synchronized boolean isReady(EnvironmentId environmentId) {
    return find(environmentId).map(LiveEnvironment::isReady).orElse(false);
  }

  /** Compact snapshot list for read-only API, insertion order preserved. */
  public synchronized List<LiveEnvironment> list() {
    return List.copyOf(new ArrayList<>(byId.values()));
  }

  public synchronized List<LiveEnvironment> listReady() {
    return byId.values().stream().filter(LiveEnvironment::isReady).toList();
  }

  private LiveEnvironment requireOwned(
      EnvironmentId environmentId, EnvironmentDaemonConnection connection) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(connection, "connection");
    LiveEnvironment current = byId.get(environmentId);
    if (current == null || !current.connection().connectionId().equals(connection.connectionId())) {
      throw new IllegalStateException(
          "environment is not bound to this connection: " + environmentId);
    }
    return current;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
