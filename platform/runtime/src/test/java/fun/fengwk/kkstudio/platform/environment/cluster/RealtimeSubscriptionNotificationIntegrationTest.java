package fun.fengwk.kkstudio.platform.environment.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSink;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSource;
import fun.fengwk.kkstudio.harness.infra.postgresql.RealtimeNotificationCodec;
import fun.fengwk.kkstudio.harness.infra.realtime.RealtimeBus;
import fun.fengwk.kkstudio.harness.infra.realtime.RealtimePayloadBuffer;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * D2 跨层闭环：真实订阅服务、RealtimeBus 与 PostgreSQL NOTIFY 组合后，只有目标 Thread 在其它节点存在同时新鲜的订阅租约与 {@code
 * cluster_node} 租约时才发送通知。
 *
 * <p>每次状态变化后换用新的发布侧服务实例，避免一秒判定缓存掩盖租约的过期、注销与节点退出。
 */
class RealtimeSubscriptionNotificationIntegrationTest extends PostgresSchemaSupport {

  private static final UUID NODE_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID NODE_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final UUID SESSION = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID ENTRY = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID THREAD = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final UUID MESSAGE = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final String SECRET = "test-cluster-secret-0123456789abcdef";
  private static final Duration LEASE = Duration.ofSeconds(45);
  private static final Duration NOTIFICATION_QUIET_WINDOW = Duration.ofMillis(300);
  private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(10);
  private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

  private final RealtimeNotificationCodec codec = new RealtimeNotificationCodec();
  private final RealtimePayloadBuffer payloadBuffer = new RealtimePayloadBuffer();
  private final AtomicReference<RealtimeSubscriptionService> publisher = new AtomicReference<>();
  private JdbcClient jdbcClient;
  private JdbcTemplate jdbcTemplate;
  private Connection listener;
  private PGConnection listenerNotifications;
  private RealtimeBus bus;
  private List<RealtimeEvent> received;

  @BeforeEach
  void setUp() throws Exception {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
    SingleConnectionDataSource dataSource =
        new SingleConnectionDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    jdbcClient = JdbcClient.create(dataSource);
    jdbcTemplate = new JdbcTemplate(dataSource);
    seedThread();
    seedNode(NODE_A);
    seedNode(NODE_B);
    publisher.set(service(NODE_A));

    listener = newConnection();
    listener.setAutoCommit(true);
    listenerNotifications = listener.unwrap(PGConnection.class);
    try (Statement statement = listener.createStatement()) {
      statement.execute("LISTEN " + PostgresqlRealtimeEventSource.CHANNEL);
    }
    drainNotifications();

    received = new ArrayList<>();
    bus =
        new RealtimeBus(
            NODE_A,
            codec,
            new PostgresqlRealtimeEventSource(codec),
            new PostgresqlRealtimeEventSink(jdbcTemplate, codec, NODE_A, payloadBuffer),
            threadId -> publisher.get().hasRemoteSubscription(threadId),
            (origin, threadId, payloadId) -> {
              throw new AssertionError("small event must not fetch an oversize payload");
            },
            payloadBuffer,
            Executors.newSingleThreadExecutor());
    bus.subscribe(THREAD, received::add, () -> {});
  }

  @AfterEach
  void tearDown() throws SQLException {
    if (bus != null) {
      bus.close();
    }
    if (listener != null) {
      listener.close();
    }
  }

  /** 无订阅、注销、订阅过期、节点租约过期与节点退出都不得 NOTIFY；只有真实有效订阅存在时才发出一条。 */
  @Test
  void notifyFollowsActualSubscriptionAndLeaseLiveness() throws Exception {
    RealtimeEvent localOnly = modelDelta("local-only", 1);
    bus.append(localOnly);
    assertEquals(List.of(localOnly), received);
    assertNoNotification("no remote subscription must produce zero NOTIFY");

    RealtimeSubscriptionService remote = service(NODE_B);
    remote.register(THREAD, () -> {});
    useFreshPublisher();
    RealtimeEvent remoteWanted = modelDelta("remote-wanted", 2);
    bus.append(remoteWanted);
    assertEquals(List.of(localOnly, remoteWanted), received);
    RealtimeNotificationCodec.Envelope.Event notification =
        assertInstanceOf(
            RealtimeNotificationCodec.Envelope.Event.class, awaitNotifications(1).getFirst());
    assertEquals(remoteWanted, notification.event());

    remote.unregister(THREAD);
    useFreshPublisher();
    RealtimeEvent afterUnregister = modelDelta("after-unregister", 3);
    bus.append(afterUnregister);
    assertEquals(List.of(localOnly, remoteWanted, afterUnregister), received);
    assertNoNotification("unregister must stop cross-node NOTIFY");

    clearRemoteSubscriptions();
    RealtimeSubscriptionService expiring = service(NODE_B);
    expiring.register(THREAD, () -> {});
    expireSubscriptionLease();
    useFreshPublisher();
    RealtimeEvent expiredSubscription = modelDelta("expired-subscription", 4);
    bus.append(expiredSubscription);
    assertEquals(List.of(localOnly, remoteWanted, afterUnregister, expiredSubscription), received);
    assertNoNotification("expired subscription lease must stop cross-node NOTIFY");

    clearRemoteSubscriptions();
    seedNode(NODE_B);
    RealtimeSubscriptionService nodeLeaseExpiring = service(NODE_B);
    nodeLeaseExpiring.register(THREAD, () -> {});
    expireNodeLease();
    useFreshPublisher();
    RealtimeEvent expiredNode = modelDelta("expired-node", 5);
    bus.append(expiredNode);
    assertEquals(
        List.of(localOnly, remoteWanted, afterUnregister, expiredSubscription, expiredNode),
        received);
    assertNoNotification("expired node lease must stop cross-node NOTIFY");

    clearRemoteSubscriptions();
    seedNode(NODE_B);
    RealtimeSubscriptionService exiting = service(NODE_B);
    exiting.register(THREAD, () -> {});
    exiting.releaseLocalSubscriptions();
    useFreshPublisher();
    RealtimeEvent afterNodeExit = modelDelta("after-node-exit", 6);
    bus.append(afterNodeExit);
    assertEquals(
        List.of(
            localOnly,
            remoteWanted,
            afterUnregister,
            expiredSubscription,
            expiredNode,
            afterNodeExit),
        received);
    assertNoNotification("node exit must remove its remote subscription");
  }

  private void useFreshPublisher() {
    publisher.set(service(NODE_A));
  }

  private RealtimeSubscriptionService service(UUID nodeInstanceId) {
    ClusterProperties properties = new ClusterProperties();
    properties.setInternalUrl("http://node.internal:8080");
    properties.setSecret(SECRET);
    properties.setNodeLease(LEASE);
    properties.setHeartbeatInterval(Duration.ofSeconds(15));
    return new RealtimeSubscriptionService(jdbcClient, nodeInstanceId, properties);
  }

  private void clearRemoteSubscriptions() {
    jdbcClient
        .sql("delete from realtime_subscription where node_instance_id = ?")
        .param(NODE_B)
        .update();
  }

  private void expireSubscriptionLease() {
    jdbcClient
        .sql(
            "update realtime_subscription set lease_until = statement_timestamp() - interval '1 second'"
                + " where thread_id = ? and node_instance_id = ?")
        .params(THREAD, NODE_B)
        .update();
  }

  private void expireNodeLease() {
    jdbcClient
        .sql(
            "update cluster_node set last_seen = statement_timestamp() - interval '2 minutes',"
                + " lease_until = statement_timestamp() - interval '1 second' where instance_id = ?")
        .param(NODE_B)
        .update();
  }

  private void seedNode(UUID nodeInstanceId) {
    jdbcClient
        .sql(
            """
            insert into cluster_node (instance_id, internal_url, lease_until, last_seen)
            values (?, ?,
                statement_timestamp() + (? * interval '1 millisecond'),
                statement_timestamp())
            on conflict (instance_id) do update
            set internal_url = excluded.internal_url,
                lease_until = excluded.lease_until,
                last_seen = excluded.last_seen
            """)
        .params(nodeInstanceId, "http://" + nodeInstanceId + ".internal:8080", LEASE.toMillis())
        .update();
  }

  private void seedThread() {
    jdbcClient
        .sql("insert into harness_session (id, name, created_at) values (?, 'realtime', now())")
        .param(SESSION)
        .update();
    jdbcClient
        .sql(
            """
            insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)
            values (?, ?, null, 'ROOT', '{}'::jsonb, now())
            """)
        .params(ENTRY, SESSION)
        .update();
    jdbcClient
        .sql(
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

  private static RealtimeEvent.ModelDelta modelDelta(String text, long sequence) {
    return new RealtimeEvent.ModelDelta(
        THREAD, MESSAGE, 1, sequence, new ProviderStreamEvent.TextDelta(text), NOW);
  }

  private void assertNoNotification(String message) throws Exception {
    assertEquals(0, countNotificationsWithin(NOTIFICATION_QUIET_WINDOW), message);
  }

  private List<RealtimeNotificationCodec.Envelope> awaitNotifications(int expected)
      throws Exception {
    List<RealtimeNotificationCodec.Envelope> notifications = new ArrayList<>();
    long deadline = System.nanoTime() + DELIVERY_TIMEOUT.toNanos();
    while (notifications.size() < expected) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError(
            "expected " + expected + " notifications but received " + notifications.size());
      }
      for (PGNotification notification : pollNotifications(200)) {
        assertEquals(PostgresqlRealtimeEventSource.CHANNEL, notification.getName());
        notifications.add(codec.decode(notification.getParameter()));
      }
    }
    assertNoNotification("no extra notification must follow the expected one");
    return notifications;
  }

  private int countNotificationsWithin(Duration window) throws Exception {
    int count = 0;
    long deadline = System.nanoTime() + window.toNanos();
    while (System.nanoTime() < deadline) {
      count += pollNotifications(50).length;
    }
    return count;
  }

  private PGNotification[] pollNotifications(int pollMillis) throws Exception {
    PGNotification[] notifications = listenerNotifications.getNotifications(pollMillis);
    return notifications == null ? new PGNotification[0] : notifications;
  }

  private void drainNotifications() throws Exception {
    while (pollNotifications(200).length > 0) {
      // 清空旧连接可能遗留的通知。
    }
  }
}
