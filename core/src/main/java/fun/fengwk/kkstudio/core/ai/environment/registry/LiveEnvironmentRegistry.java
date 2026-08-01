package fun.fengwk.kkstudio.core.ai.environment.registry;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-safe server-memory Environment registry keyed by non-blank {@code environmentName}.
 *
 * <p>First connected name wins: a later HELLO for an occupied name is rejected without displacing
 * the original entry. Entries are removed on disconnect. Nothing is persisted.
 */
@Component
public class LiveEnvironmentRegistry {

  private final Map<String, LiveEnvironment> byName = new LinkedHashMap<>();

  public LiveEnvironmentRegistry() {}

  /**
   * Attempts to bind {@code environmentName} to {@code connection} after HELLO authentication.
   *
   * @return true when this connection becomes the first occupant of the name
   */
  public synchronized boolean tryBind(
      String environmentName, EnvironmentDaemonConnection connection, Instant now) {
    String name = requireNonBlank(environmentName, "environmentName");
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(now, "now");
    LiveEnvironment existing = byName.get(name);
    if (existing != null) {
      return existing.connection().connectionId().equals(connection.connectionId());
    }
    byName.put(
        name,
        new LiveEnvironment(name, LiveEnvironmentStatus.CONNECTING, connection, List.of(), now));
    return true;
  }

  /** Replaces the daemon's advertised skills for a bound name owned by {@code connection}. */
  public synchronized void updateSkills(
      String environmentName,
      EnvironmentDaemonConnection connection,
      List<DaemonSkillDescriptor> skills,
      Instant now) {
    LiveEnvironment current = requireOwned(environmentName, connection);
    byName.put(
        current.environmentName(),
        new LiveEnvironment(
            current.environmentName(),
            current.status(),
            current.connection(),
            List.copyOf(Objects.requireNonNull(skills, "skills")),
            Objects.requireNonNull(now, "now")));
  }

  /**
   * Marks the bound name READY so gateway workers may dispatch against it. Callers own the
   * skills-before-READY transition; an empty skills list remains valid.
   */
  public synchronized void markReady(
      String environmentName, EnvironmentDaemonConnection connection, Instant now) {
    LiveEnvironment current = requireOwned(environmentName, connection);
    byName.put(
        current.environmentName(),
        new LiveEnvironment(
            current.environmentName(),
            LiveEnvironmentStatus.READY,
            current.connection(),
            current.skills(),
            Objects.requireNonNull(now, "now")));
  }

  /** Refreshes last-seen for a bound name owned by {@code connection}. */
  public synchronized void heartbeat(
      String environmentName, EnvironmentDaemonConnection connection, Instant now) {
    LiveEnvironment current = requireOwned(environmentName, connection);
    byName.put(
        current.environmentName(),
        new LiveEnvironment(
            current.environmentName(),
            current.status(),
            current.connection(),
            current.skills(),
            Objects.requireNonNull(now, "now")));
  }

  /**
   * Removes the entry only when {@code connection} still owns {@code environmentName}. Safe to call
   * on every disconnect path.
   */
  public synchronized void unregister(
      String environmentName, EnvironmentDaemonConnection connection) {
    if (environmentName == null || environmentName.isBlank() || connection == null) {
      return;
    }
    LiveEnvironment current = byName.get(environmentName);
    if (current != null && current.connection().connectionId().equals(connection.connectionId())) {
      byName.remove(environmentName);
    }
  }

  public synchronized Optional<LiveEnvironment> find(String environmentName) {
    if (environmentName == null || environmentName.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(byName.get(environmentName));
  }

  public synchronized boolean isReady(String environmentName) {
    return find(environmentName).map(LiveEnvironment::isReady).orElse(false);
  }

  /** Compact snapshot list for read-only API, insertion order preserved. */
  public synchronized List<LiveEnvironment> list() {
    return List.copyOf(new ArrayList<>(byName.values()));
  }

  public synchronized List<LiveEnvironment> listReady() {
    return byName.values().stream().filter(LiveEnvironment::isReady).toList();
  }

  private LiveEnvironment requireOwned(
      String environmentName, EnvironmentDaemonConnection connection) {
    String name = requireNonBlank(environmentName, "environmentName");
    Objects.requireNonNull(connection, "connection");
    LiveEnvironment current = byName.get(name);
    if (current == null || !current.connection().connectionId().equals(connection.connectionId())) {
      throw new IllegalStateException("environment is not bound to this connection: " + name);
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
