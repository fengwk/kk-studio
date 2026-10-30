package fun.fengwk.kkstudio.platform.environment.cluster;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

/** ClusterNodeLifecycle：复用节点心跳续租订阅、节点续约失败不误续租、退出清理订阅。 */
class ClusterNodeLifecycleTest {

  private static final URI ORIGIN = URI.create("http://node-a.internal:8080");
  private static final Duration LEASE = Duration.ofSeconds(45);

  private final JdbcClusterNodeRegistry registry = mock(JdbcClusterNodeRegistry.class);
  private final RealtimeSubscriptionService subscriptions = mock(RealtimeSubscriptionService.class);

  /** 节点心跳成功时先续约 cluster_node，再用同一节拍续租本机订阅。 */
  @Test
  void heartbeatRenewsNodeThenSubscriptions() {
    when(registry.heartbeatLocalNode(LEASE)).thenReturn(true);
    ClusterNodeLifecycle lifecycle = lifecycle(properties());

    lifecycle.heartbeat();

    verify(registry).heartbeatLocalNode(LEASE);
    verify(registry, never()).registerLocalNode(any(), any());
    verify(subscriptions).renewLocalSubscriptions();
  }

  /** cluster_node 行缺失时在同一心跳内补偿注册，然后仍续租订阅。 */
  @Test
  void heartbeatReRegistersMissingNodeThenRenewsSubscriptions() {
    when(registry.heartbeatLocalNode(LEASE)).thenReturn(false);
    ClusterNodeLifecycle lifecycle = lifecycle(properties());

    lifecycle.heartbeat();

    verify(registry).registerLocalNode(ORIGIN, LEASE);
    verify(subscriptions).renewLocalSubscriptions();
  }

  /** 节点续约失败时不续租订阅，避免远端看到“有效订阅 + 无有效节点”的假象。 */
  @Test
  void failedNodeHeartbeatSkipsSubscriptionRenewal() {
    when(registry.heartbeatLocalNode(LEASE))
        .thenThrow(new IllegalStateException("database unavailable"));
    ClusterNodeLifecycle lifecycle = lifecycle(properties());

    lifecycle.heartbeat();

    verify(subscriptions, never()).renewLocalSubscriptions();
  }

  /** 正常停止释放订阅，即使调度线程已经结束。 */
  @Test
  void stopReleasesLocalSubscriptions() {
    ClusterProperties properties = properties();
    properties.setHeartbeatInterval(Duration.ofHours(1));
    ClusterNodeLifecycle lifecycle = lifecycle(properties);
    lifecycle.start();
    try {
      verify(registry).registerLocalNode(ORIGIN, LEASE);
    } finally {
      lifecycle.stop();
    }

    verify(subscriptions).releaseLocalSubscriptions();
  }

  /** 未配置内部 origin 时不注册节点，也不触碰订阅服务。 */
  @Test
  void disabledLifecycleDoesNotTouchClusterOrSubscriptions() {
    ClusterNodeLifecycle lifecycle = lifecycle(new ClusterProperties());

    lifecycle.start();
    lifecycle.stop();

    verifyNoInteractions(registry);
    verifyNoInteractions(subscriptions);
  }

  private ClusterNodeLifecycle lifecycle(ClusterProperties properties) {
    return new ClusterNodeLifecycle(registry, properties, subscriptions);
  }

  private static ClusterProperties properties() {
    ClusterProperties properties = new ClusterProperties();
    properties.setInternalUrl(ORIGIN.toString());
    properties.setSecret("test-cluster-secret-0123456789abcdef");
    properties.setNodeLease(LEASE);
    return properties;
  }
}
