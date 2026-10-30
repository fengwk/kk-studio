package fun.fengwk.kkstudio.platform.environment.cluster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

/**
 * 真实 PostgreSQL：订阅租约、cluster_node 租约、注销与节点退出共同决定其它节点能否为该 Thread 发布 realtime。
 *
 * <p>每个查询都使用新的服务实例，避免一秒缓存掩盖租约状态变化；核心断言是“只有订阅行与节点行同时新鲜时才存在远端订阅”。
 */
class RealtimeSubscriptionServiceIntegrationTest extends PostgresSchemaSupport {

  private static final UUID NODE_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID NODE_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final UUID SESSION = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID ENTRY = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID THREAD = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final String SECRET = "test-cluster-secret-0123456789abcdef";

  private JdbcClient jdbc;

  @BeforeEach
  void setUp() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
    jdbc =
        JdbcClient.create(
            new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true));
    seedThread();
    seedNode(NODE_A, Duration.ofSeconds(45).toMillis());
  }

  /** 无订阅行时，其它节点不得为该 Thread 发布 realtime。 */
  @Test
  void noSubscriptionHasNoRemoteSubscription() {
    assertFalse(remoteView().hasRemoteSubscription(THREAD));
  }

  /** 登记后其它节点看到有效订阅；本节点自己的订阅不算远端。 */
  @Test
  void registeredSubscriptionIsRemoteOnlyForAnotherNode() {
    RealtimeSubscriptionService owner = localService();
    owner.register(THREAD, () -> {});

    assertTrue(subscriptionLeaseIsFresh());
    assertTrue(remoteView().hasRemoteSubscription(THREAD));
    assertFalse(localService().hasRemoteSubscription(THREAD));
  }

  /** 订阅租约过期后，即使节点仍存活也不再跨节点发布。 */
  @Test
  void expiredSubscriptionLeaseHasNoRemoteSubscription() {
    localService().register(THREAD, () -> {});
    expireSubscriptionLease();

    assertFalse(remoteView().hasRemoteSubscription(THREAD));
  }

  /** 节点租约过期后，即使订阅行仍存在也不再跨节点发布。 */
  @Test
  void expiredNodeLeaseHasNoRemoteSubscription() {
    localService().register(THREAD, () -> {});
    expireNodeLease();

    assertFalse(remoteView().hasRemoteSubscription(THREAD));
  }

  /** 末个订阅释放后立即删除远端可见的租约行。 */
  @Test
  void unregisterRemovesRemoteSubscription() {
    RealtimeSubscriptionService owner = localService();
    owner.register(THREAD, () -> {});
    assertTrue(remoteView().hasRemoteSubscription(THREAD));

    owner.unregister(THREAD);

    assertFalse(remoteView().hasRemoteSubscription(THREAD));
  }

  /** 节点正常退出清理本节点全部订阅；之后心跳式续租或迟到的新登记都不得复活已释放的 Thread。 */
  @Test
  void releaseRemovesEveryLocalSubscriptionAndStopsRenewal() {
    RealtimeSubscriptionService owner = localService();
    owner.register(THREAD, () -> {});
    assertTrue(remoteView().hasRemoteSubscription(THREAD));

    owner.releaseLocalSubscriptions();
    owner.register(THREAD, () -> {});
    owner.renewLocalSubscriptions();

    assertFalse(remoteView().hasRemoteSubscription(THREAD));
  }

  /** 心跳续租恢复过期或缺失的租约行，使远端重新可见。 */
  @Test
  void renewalRefreshesExpiredAndRecreatesMissingRows() {
    RealtimeSubscriptionService owner = localService();
    owner.register(THREAD, () -> {});
    expireSubscriptionLease();
    assertFalse(remoteView().hasRemoteSubscription(THREAD));

    owner.renewLocalSubscriptions();
    assertTrue(remoteView().hasRemoteSubscription(THREAD));

    jdbc.sql("delete from realtime_subscription where thread_id = ? and node_instance_id = ?")
        .params(THREAD, NODE_A)
        .update();
    owner.renewLocalSubscriptions();
    assertTrue(remoteView().hasRemoteSubscription(THREAD));
  }

  private RealtimeSubscriptionService localService() {
    return service(NODE_A);
  }

  private RealtimeSubscriptionService remoteView() {
    return service(NODE_B);
  }

  private RealtimeSubscriptionService service(UUID localNodeId) {
    ClusterProperties properties = new ClusterProperties();
    properties.setInternalUrl("http://node-a.internal:8080");
    properties.setSecret(SECRET);
    properties.setNodeLease(Duration.ofSeconds(45));
    properties.setHeartbeatInterval(Duration.ofSeconds(15));
    return new RealtimeSubscriptionService(jdbc, localNodeId, properties);
  }

  private boolean subscriptionLeaseIsFresh() {
    Boolean fresh =
        jdbc.sql(
                """
                select lease_until > statement_timestamp() + interval '40 seconds'
                    and lease_until < statement_timestamp() + interval '50 seconds'
                from realtime_subscription
                where thread_id = ? and node_instance_id = ?
                """)
            .params(THREAD, NODE_A)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(fresh);
  }

  private void expireSubscriptionLease() {
    jdbc.sql(
            "update realtime_subscription set lease_until = statement_timestamp() - interval '1 second'"
                + " where thread_id = ? and node_instance_id = ?")
        .params(THREAD, NODE_A)
        .update();
  }

  private void expireNodeLease() {
    jdbc.sql(
            "update cluster_node set last_seen = statement_timestamp() - interval '2 minutes',"
                + " lease_until = statement_timestamp() - interval '1 second' where instance_id = ?")
        .param(NODE_A)
        .update();
  }

  private void seedNode(UUID nodeInstanceId, long leaseMillis) {
    jdbc.sql(
            """
            insert into cluster_node (instance_id, internal_url, lease_until, last_seen)
            values (?, ?,
                statement_timestamp() + (? * interval '1 millisecond'),
                statement_timestamp())
            """)
        .params(nodeInstanceId, "http://" + nodeInstanceId + ".internal:8080", leaseMillis)
        .update();
  }

  private void seedThread() {
    jdbc.sql("insert into harness_session (id, name, created_at) values (?, 'realtime', now())")
        .param(SESSION)
        .update();
    jdbc.sql(
            """
            insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)
            values (?, ?, null, 'ROOT', '{}'::jsonb, now())
            """)
        .params(ENTRY, SESSION)
        .update();
    jdbc.sql(
            """
            insert into harness_thread
                (id, session_id, head_entry_id, creation_request_hash, name, root_thread_id,
                 yolo_enabled, spawn_trigger, constraints, execution_control,
                 input_through_sequence, next_command_sequence, version, created_at, updated_at)
            values (?, ?, ?, repeat('a', 64), 'realtime', ?, true, 'USER',
                '{"maxOutputTokens":null,"promptCacheDisabled":false,"allowAutoCompaction":true}',
                'RUNNABLE', 0, 1, 0, now(), now())
            """)
        .params(THREAD, SESSION, ENTRY, THREAD)
        .update();
  }
}
