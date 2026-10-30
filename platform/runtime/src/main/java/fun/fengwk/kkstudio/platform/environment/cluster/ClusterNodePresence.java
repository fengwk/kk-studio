package fun.fengwk.kkstudio.platform.environment.cluster;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 其它活跃集群节点是否存在的一秒级缓存快照。
 *
 * <p>Realtime 发布路径会按事件频率（约几十毫秒一次）询问该判定；每次事件都查 {@code cluster_node} 会让数据库往返淹没 实时增量。这里以数据库现在时的 {@code
 * lease_until} 为准做短期缓存，缓存窗口内复用同一布尔结果，过期后重新查询。查询失败 不伪装成“单节点”，而是向上抛出，让调用方在完成本地投递后报告远端不可用。
 */
public final class ClusterNodePresence {

  static final String HAS_OTHER_ACTIVE_NODES_SQL =
      """
      select exists(
          select 1
          from cluster_node
          where instance_id <> :localNodeId
            and lease_until > statement_timestamp())
      """;

  private static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(1);

  private final JdbcClient jdbcClient;
  private final UUID localNodeId;
  private final long cacheTtlNanos;
  private final LongSupplier nanoClock;

  private boolean cached;
  private boolean initialized;
  private long refreshedAtNanos;

  public ClusterNodePresence(JdbcClient jdbcClient, UUID localNodeId) {
    this(jdbcClient, localNodeId, DEFAULT_CACHE_TTL, System::nanoTime);
  }

  ClusterNodePresence(
      JdbcClient jdbcClient, UUID localNodeId, Duration cacheTtl, LongSupplier nanoClock) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
    this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    Objects.requireNonNull(cacheTtl, "cacheTtl");
    long ttlNanos = cacheTtl.toNanos();
    if (ttlNanos <= 0) {
      throw new IllegalArgumentException("cacheTtl must be positive");
    }
    this.cacheTtlNanos = ttlNanos;
  }

  /** 依据缓存快照判断除本节点外是否还有未过期注册行。 */
  public synchronized boolean hasOtherActiveNodes() {
    long now = nanoClock.getAsLong();
    // nanoTime 的起点可以为负数，并且会回绕；只比较经过的时间，不把 0 当作尚未查询的 deadline。
    if (initialized && now - refreshedAtNanos < cacheTtlNanos) {
      return cached;
    }
    Boolean active =
        jdbcClient
            .sql(HAS_OTHER_ACTIVE_NODES_SQL)
            .param("localNodeId", localNodeId)
            .query(Boolean.class)
            .single();
    cached = Boolean.TRUE.equals(active);
    refreshedAtNanos = now;
    initialized = true;
    return cached;
  }
}
