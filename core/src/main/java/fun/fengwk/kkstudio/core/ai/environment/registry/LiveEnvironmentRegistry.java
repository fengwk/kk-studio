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
 * 以 canonical {@link EnvironmentId} 为键的线程安全服务端内存 Environment registry。
 *
 * <p>先连接者优先：已被占用的 id 上到达的后续 HELLO 会被拒绝，且不会挤掉原条目。断开连接时移除条目。 展示名只是元数据：同一名字可能绑定到不同
 * id，路由绝不回退到名字。不持久化任何内容。
 */
@Component
public class LiveEnvironmentRegistry {

  private final Map<EnvironmentId, LiveEnvironment> byId = new LinkedHashMap<>();

  public LiveEnvironmentRegistry() {}

  /**
   * 在 HELLO 认证后，尝试把 {@code environmentId}（连同展示名 {@code environmentName}）绑定到 {@code connection}。
   *
   * @return 该连接成为该 id 的第一个占用者时返回 true
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

  /** 为 {@code connection} 拥有的已绑定 id 替换 daemon 通告的 skills。 */
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

  /** 把已绑定 id 标记为 READY，使 gateway worker 可以对其派发。skills-before-READY 的转换由调用方负责； 空 skills 列表仍然有效。 */
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

  /** 刷新 {@code connection} 拥有的已绑定 id 的 last-seen。 */
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

  /** 仅当 {@code connection} 仍拥有 {@code environmentId} 时移除条目。可在任何断开路径安全调用。 */
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

  /** 供只读 API 使用的紧凑快照列表，保持插入顺序。 */
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
