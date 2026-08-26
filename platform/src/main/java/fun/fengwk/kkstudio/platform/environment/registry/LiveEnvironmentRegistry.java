package fun.fengwk.kkstudio.platform.environment.registry;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.platform.environment.gateway.EnvironmentDaemonConnection;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 以 canonical {@link EnvironmentName} 为键的线程安全服务端内存 Environment registry。
 *
 * <p>名称是唯一路由身份，不持久化任何内容。占用语义：持有者连接打开且心跳未过期时，同名 HELLO 被拒绝（{@link
 * BindResult.Rejected}）；持有者连接已关闭或心跳超过配置超时（lastSeen 租约过期）时，registry 原子切换到新持有者 （{@link
 * BindResult.Replaced}，旧连接交还调用方恰好清理一次）。断开连接时只移除恰好拥有该名称的连接条目。
 */
@Component
public class LiveEnvironmentRegistry {

  private final Map<EnvironmentName, LiveEnvironment> byName = new LinkedHashMap<>();

  public LiveEnvironmentRegistry() {}

  /**
   * 在 HELLO 认证后，尝试把 {@code environmentName} 绑定到 {@code connection}。
   *
   * <p>原子判定：无现有条目或同连接幂等重绑 → {@link BindResult.Accepted}；现有持有者连接仍打开且心跳未过期 → {@link
   * BindResult.Rejected}（typed 冲突，绝不挤占）；现有持有者连接已关闭或心跳超过 {@code heartbeatTimeout} 过期 →
   * 条目原子替换为新持有者并返回 {@link BindResult.Replaced}（携带被替换的旧连接，调用方恰好清理一次）。 CONNECTING 的新鲜声明（打开 + 心跳未过期）与
   * READY 同等受保护。
   */
  public synchronized BindResult tryBind(
      EnvironmentName environmentName,
      EnvironmentDaemonConnection connection,
      Instant now,
      Duration heartbeatTimeout) {
    Objects.requireNonNull(environmentName, "environmentName");
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(heartbeatTimeout, "heartbeatTimeout");
    LiveEnvironment existing = byName.get(environmentName);
    if (existing == null) {
      byName.put(
          environmentName,
          new LiveEnvironment(
              environmentName, LiveEnvironmentStatus.CONNECTING, connection, null, now));
      return BindResult.accepted();
    }
    if (existing.connection().connectionId().equals(connection.connectionId())) {
      // 同连接幂等重绑：继续持有，不挤占自己。
      return BindResult.accepted();
    }
    if (existing.connection().isOpen()
        && !existing.lastSeenAt().isBefore(now.minus(heartbeatTimeout))) {
      // fresh/open holder：租约未过期，冲突。
      return BindResult.rejected();
    }
    // 持有者已死（连接关闭或租约过期）：原子接管，旧连接交还调用方恰好清理一次。
    byName.put(
        environmentName,
        new LiveEnvironment(
            environmentName, LiveEnvironmentStatus.CONNECTING, connection, null, now));
    return BindResult.replaced(existing.connection());
  }

  /** 为 {@code connection} 拥有的已绑定名称替换 daemon 通告的版本化能力对象（skills + MCP server 摘要）。 */
  public synchronized void updateCapabilities(
      EnvironmentName environmentName,
      EnvironmentDaemonConnection connection,
      DaemonCapabilities capabilities,
      Instant now) {
    LiveEnvironment current = requireOwned(environmentName, connection);
    byName.put(
        current.name(),
        new LiveEnvironment(
            current.name(),
            current.status(),
            current.connection(),
            Objects.requireNonNull(capabilities, "capabilities"),
            Objects.requireNonNull(now, "now")));
  }

  /** 把已绑定名称标记为 READY，使 gateway worker 可以对其派发。capabilities-before-READY 的转换由调用方负责；空能力仍然有效。 */
  public synchronized void markReady(
      EnvironmentName environmentName, EnvironmentDaemonConnection connection, Instant now) {
    LiveEnvironment current = requireOwned(environmentName, connection);
    if (current.daemonCapabilities() == null) {
      throw new IllegalStateException("environment cannot become READY before capabilities");
    }
    byName.put(
        current.name(),
        new LiveEnvironment(
            current.name(),
            LiveEnvironmentStatus.READY,
            current.connection(),
            current.daemonCapabilities(),
            Objects.requireNonNull(now, "now")));
  }

  /** 刷新 {@code connection} 拥有的已绑定名称的 last-seen。 */
  public synchronized void heartbeat(
      EnvironmentName environmentName, EnvironmentDaemonConnection connection, Instant now) {
    LiveEnvironment current = requireOwned(environmentName, connection);
    byName.put(
        current.name(),
        new LiveEnvironment(
            current.name(),
            current.status(),
            current.connection(),
            current.daemonCapabilities(),
            Objects.requireNonNull(now, "now")));
  }

  /** 仅当 {@code connection} 仍拥有 {@code environmentName} 时移除条目。可在任何断开路径安全调用。 */
  public synchronized void unregister(
      EnvironmentName environmentName, EnvironmentDaemonConnection connection) {
    if (environmentName == null || connection == null) {
      return;
    }
    LiveEnvironment current = byName.get(environmentName);
    if (current != null && current.connection().connectionId().equals(connection.connectionId())) {
      byName.remove(environmentName);
    }
  }

  public synchronized Optional<LiveEnvironment> find(EnvironmentName environmentName) {
    if (environmentName == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(byName.get(environmentName));
  }

  public synchronized boolean isReady(
      EnvironmentName environmentName, Instant now, Duration heartbeatTimeout) {
    return find(environmentName)
        .map(environment -> environment.isReady(now, heartbeatTimeout))
        .orElse(false);
  }

  /** 供只读 API 使用的紧凑快照列表，保持插入顺序。 */
  public synchronized List<LiveEnvironment> list() {
    return List.copyOf(new ArrayList<>(byName.values()));
  }

  private LiveEnvironment requireOwned(
      EnvironmentName environmentName, EnvironmentDaemonConnection connection) {
    Objects.requireNonNull(environmentName, "environmentName");
    Objects.requireNonNull(connection, "connection");
    LiveEnvironment current = byName.get(environmentName);
    if (current == null || !current.connection().connectionId().equals(connection.connectionId())) {
      throw new IllegalStateException(
          "environment is not bound to this connection: " + environmentName);
    }
    return current;
  }
}
