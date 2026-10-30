package fun.fengwk.kkstudio.platform.environment.cluster;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本节点 {@code cluster_node} 注册行与 realtime 订阅租约的生命周期：启动时按配置注册内部 origin，之后按心跳间隔续约。
 *
 * <p>只有同时配置内部 origin 与共享秘密时才会注册并参与跨节点路由；任一缺失即明确不可用（记录一次警告并跳过注册），绝不从请求 Host 推断内部
 * 地址。续约发现注册行缺失时立即补偿注册，保证节点在数据库重置或注册行过期后自愈。节点心跳同时续租本机活跃的 Thread 订阅；正常退出时删除本节点全部 realtime
 * 订阅，进程异常退出由租约到期兜底。停止后不再调度。
 */
@Slf4j
public final class ClusterNodeLifecycle implements SmartLifecycle {

  private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

  private final JdbcClusterNodeRegistry registry;
  private final ClusterProperties properties;
  private final RealtimeSubscriptionService realtimeSubscriptions;
  private final AtomicBoolean running = new AtomicBoolean();
  private ScheduledExecutorService executor;

  public ClusterNodeLifecycle(
      JdbcClusterNodeRegistry registry,
      ClusterProperties properties,
      RealtimeSubscriptionService realtimeSubscriptions) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.realtimeSubscriptions =
        Objects.requireNonNull(realtimeSubscriptions, "realtimeSubscriptions");
  }

  @Override
  public void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    Optional<URI> origin = properties.internalOrigin();
    if (origin.isEmpty()) {
      return;
    }
    if (!properties.secretConfigured()) {
      log.warn(
          "kk-studio.cluster.secret is not configured; cross-node IDE routing stays unavailable"
              + " and this node will not register");
      return;
    }
    try {
      registry.registerLocalNode(origin.get(), properties.getNodeLease());
    } catch (RuntimeException error) {
      log.warn("cluster node registration failed; retrying on heartbeat", error);
    }
    Duration interval = properties.getHeartbeatInterval();
    executor =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("cluster-node-heartbeat").factory());
    executor.scheduleAtFixedRate(
        this::heartbeat, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** 单次心跳：先续约节点注册行（缺失时补偿注册），再续租本机活跃的 realtime 订阅。测试直接驱动该入口。 */
  void heartbeat() {
    URI origin = properties.internalOrigin().orElse(null);
    if (origin == null) {
      return;
    }
    try {
      if (!registry.heartbeatLocalNode(properties.getNodeLease())) {
        registry.registerLocalNode(origin, properties.getNodeLease());
      }
    } catch (RuntimeException error) {
      log.warn("cluster node heartbeat failed", error);
      return;
    }
    realtimeSubscriptions.renewLocalSubscriptions();
  }

  @Override
  public void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    ScheduledExecutorService current = executor;
    executor = null;
    if (current == null) {
      return;
    }
    current.shutdown();
    try {
      if (!current.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        current.shutdownNow();
      }
    } catch (InterruptedException error) {
      current.shutdownNow();
      Thread.currentThread().interrupt();
    } finally {
      realtimeSubscriptions.releaseLocalSubscriptions();
    }
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  /** 注册相位排在最后，使 JdbcTemplate 与相关 bean 全部就绪后才注册。 */
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }
}
