package fun.fengwk.kkstudio.platform.environment.cluster;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 按 Thread 维护本节点 realtime 订阅租约，并判定其它节点是否存在有效订阅。
 *
 * <p>本节点只在首个浏览器订阅建立时登记一份 {@code (threadId, nodeInstanceId)} 租约，随后由 {@link ClusterNodeLifecycle}
 * 的现有心跳统一续租；末个订阅释放时删除租约，节点退出时清理本节点全部租约。租约时长与 {@code cluster_node} 相同（默认 45 秒，心跳 15
 * 秒），所有时间判断都下推到数据库现在时。节点 ID 不设外键：远端订阅只有同时满足订阅租约未过期且对应 {@code cluster_node} 租约未过期时才有效。
 *
 * <p>发布路径按 Thread 查询远端订阅，并复用一秒有界缓存，避免每个增量事件都执行 SQL。查询失败直接向上抛出，绝不退化为“没有远端订阅”。注册、续租、
 * 注销共用同一把服务锁，保证最后释放后不会被迟到的续租复活；续租失败或恢复时在锁外调用 resync 回调，避免与 Hub 资源锁形成锁序反转。
 */
@Slf4j
public class RealtimeSubscriptionService {

  static final String UPSERT_SQL =
      """
      insert into realtime_subscription (thread_id, node_instance_id, lease_until)
      values (:threadId, :nodeInstanceId,
          statement_timestamp() + (:leaseMillis * interval '1 millisecond'))
      on conflict (thread_id, node_instance_id) do update
      set lease_until = excluded.lease_until
      """;

  static final String DELETE_SQL =
      """
      delete from realtime_subscription
      where thread_id = :threadId
        and node_instance_id = :nodeInstanceId
      """;

  static final String DELETE_ALL_SQL =
      """
      delete from realtime_subscription
      where node_instance_id = :nodeInstanceId
      """;

  static final String HAS_REMOTE_SUBSCRIPTION_SQL =
      """
      select exists(
          select 1
          from realtime_subscription s
          join cluster_node n on n.instance_id = s.node_instance_id
          where s.thread_id = :threadId
            and s.node_instance_id <> :localNodeId
            and s.lease_until > statement_timestamp()
            and n.lease_until > statement_timestamp())
      """;

  static final int MAX_CACHE_ENTRIES = 4096;

  private static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(1);

  private final JdbcClient jdbcClient;
  private final UUID localNodeId;
  private final Duration lease;
  private final boolean enabled;
  private final long cacheTtlNanos;
  private final long leaseNanos;
  private final LongSupplier nanoClock;
  private final Object lock = new Object();

  /** 本节点当前活跃的 Thread 订阅；value 记录同 Thread 的 Hub 状态引用计数与 resync 回调。 */
  private final Map<UUID, LocalThread> localThreads = new HashMap<>();

  /** 按 Thread 的远端订阅快照，访问顺序 LRU 且容量有界；只在 {@link #lock} 内读写。 */
  private final LinkedHashMap<UUID, RemotePresence> remoteCache =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, RemotePresence> eldest) {
          return size() > MAX_CACHE_ENTRIES;
        }
      };

  /** 一旦释放本节点全部租约，注册与续租永久关闭，迟到的生命周期回调不得复活订阅。 */
  private boolean released;

  public RealtimeSubscriptionService(
      JdbcClient jdbcClient, UUID localNodeId, ClusterProperties properties) {
    this(jdbcClient, localNodeId, properties, DEFAULT_CACHE_TTL, System::nanoTime);
  }

  RealtimeSubscriptionService(
      JdbcClient jdbcClient,
      UUID localNodeId,
      ClusterProperties properties,
      Duration cacheTtl,
      LongSupplier nanoClock) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
    ClusterProperties clusterProperties = Objects.requireNonNull(properties, "properties");
    this.lease = clusterProperties.getNodeLease();
    this.leaseNanos = lease.toNanos();
    this.enabled = clusterProperties.remoteRoutingConfigured();
    this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    Objects.requireNonNull(cacheTtl, "cacheTtl");
    long ttlNanos = cacheTtl.toNanos();
    if (ttlNanos <= 0) {
      throw new IllegalArgumentException("cacheTtl must be positive");
    }
    this.cacheTtlNanos = ttlNanos;
  }

  /**
   * 登记本节点对 Thread 的订阅；重复调用只增加本地引用计数，不重复占租约行。
   *
   * <p>登记失败仍保留本地跟踪，使后续心跳可以重新 upsert；异常交给 Hub 记录并触发该 Thread 的 resync，本机投递不受影响。
   */
  public void register(UUID threadId, Runnable onResync) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(onResync, "onResync");
    if (!enabled) {
      return;
    }
    synchronized (lock) {
      if (released) {
        return;
      }
      long now = nanoClock.getAsLong();
      LocalThread local = localThreads.get(threadId);
      if (local == null) {
        local = new LocalThread(now);
        localThreads.put(threadId, local);
      }
      local.subscribers++;
      local.onResync = onResync;
      try {
        upsert(threadId);
        local.lastRenewedAtNanos = now;
        local.interrupted = false;
      } catch (RuntimeException error) {
        local.interrupted = true;
        throw error;
      }
    }
  }

  /** 释放一份本节点订阅；引用计数归零时删除租约行。 */
  public void unregister(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    if (!enabled) {
      return;
    }
    synchronized (lock) {
      if (released) {
        return;
      }
      LocalThread local = localThreads.get(threadId);
      if (local == null) {
        return;
      }
      local.subscribers--;
      if (local.subscribers > 0) {
        return;
      }
      localThreads.remove(threadId);
      delete(threadId);
    }
  }

  /**
   * 由节点心跳续租全部活跃订阅。
   *
   * <p>每个 Thread 独立续租与记录健康状态：某个 Thread 因外键等原因失败不会阻止后续 Thread 续租。首次失败与恢复各触发一次该 Thread 的
   * resync；任一相邻两次成功续租的单调时钟间隔达到租约时长，也视为可能发生通知空窗并在恢复续租后触发 resync，覆盖心跳提前返回或长停顿但不抛 SQL
   * 异常的路径。回调统一在服务锁外执行，失败不向心跳抛出且不做额外重试。
   */
  public void renewLocalSubscriptions() {
    if (!enabled) {
      return;
    }
    List<Runnable> resyncs = new ArrayList<>();
    synchronized (lock) {
      if (released || localThreads.isEmpty()) {
        return;
      }
      long now = nanoClock.getAsLong();
      for (Map.Entry<UUID, LocalThread> entry : localThreads.entrySet()) {
        UUID threadId = entry.getKey();
        LocalThread local = entry.getValue();
        try {
          upsert(threadId);
          long previousRenewalNanos = local.lastRenewedAtNanos;
          local.lastRenewedAtNanos = now;
          if (local.interrupted || now - previousRenewalNanos >= leaseNanos) {
            local.interrupted = false;
            resyncs.add(local.onResync);
          }
        } catch (RuntimeException error) {
          if (!local.interrupted) {
            local.interrupted = true;
            resyncs.add(local.onResync);
          }
          log.warn(
              "realtime subscription renewal failed; local delivery continues threadId={}"
                  + " activeThreads={} errorType={}",
              threadId,
              localThreads.size(),
              error.getClass().getSimpleName());
        }
      }
    }
    runResyncs(resyncs);
  }

  /** 节点正常退出时清理本节点全部订阅；失败只记录安全错误，残留行由租约到期兜底。 */
  public void releaseLocalSubscriptions() {
    if (!enabled) {
      return;
    }
    synchronized (lock) {
      if (released) {
        return;
      }
      // 先闭合生命周期边界，再清空本地状态：之后迟到的 register/renew 只能 no-op。
      released = true;
      localThreads.clear();
      remoteCache.clear();
      try {
        jdbcClient.sql(DELETE_ALL_SQL).param("nodeInstanceId", localNodeId).update();
      } catch (RuntimeException error) {
        log.warn(
            "cannot release realtime subscriptions on shutdown; relying on lease expiry"
                + " errorType={}",
            error.getClass().getSimpleName());
      }
    }
  }

  /**
   * 判断指定 Thread 是否在其它节点存在有效订阅。
   *
   * <p>结果按 Thread 缓存一秒且容量有界；缓存只保存“当前是否应跨节点发布”的布尔快照，不改变租约事实。数据库查询失败直接向上抛出，且不缓存失败结果。
   */
  public boolean hasRemoteSubscription(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    if (!enabled) {
      return false;
    }
    synchronized (lock) {
      long now = nanoClock.getAsLong();
      RemotePresence cached = remoteCache.get(threadId);
      if (cached != null && now - cached.checkedAtNanos() < cacheTtlNanos) {
        return cached.present();
      }
      remoteCache.remove(threadId);
      Boolean present =
          jdbcClient
              .sql(HAS_REMOTE_SUBSCRIPTION_SQL)
              .param("threadId", threadId)
              .param("localNodeId", localNodeId)
              .query(Boolean.class)
              .single();
      boolean value = Boolean.TRUE.equals(present);
      remoteCache.put(threadId, new RemotePresence(value, now));
      return value;
    }
  }

  /** 测试观测点：缓存条目数不得超过 {@link #MAX_CACHE_ENTRIES}。 */
  int cachedRemoteEntries() {
    synchronized (lock) {
      return remoteCache.size();
    }
  }

  private void upsert(UUID threadId) {
    jdbcClient
        .sql(UPSERT_SQL)
        .param("threadId", threadId)
        .param("nodeInstanceId", localNodeId)
        .param("leaseMillis", lease.toMillis())
        .update();
  }

  private void delete(UUID threadId) {
    jdbcClient
        .sql(DELETE_SQL)
        .param("threadId", threadId)
        .param("nodeInstanceId", localNodeId)
        .update();
  }

  private static void runResyncs(List<Runnable> resyncs) {
    for (Runnable resync : resyncs) {
      try {
        resync.run();
      } catch (RuntimeException error) {
        log.warn(
            "realtime subscription resync callback failed errorType={}",
            error.getClass().getSimpleName());
      }
    }
  }

  private static final class LocalThread {
    private int subscribers;
    private Runnable onResync;
    private boolean interrupted;
    private long lastRenewedAtNanos;

    private LocalThread(long lastRenewedAtNanos) {
      this.lastRenewedAtNanos = lastRenewedAtNanos;
    }
  }

  private record RemotePresence(boolean present, long checkedAtNanos) {}
}
