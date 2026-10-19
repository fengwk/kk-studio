package fun.fengwk.kkstudio.platform.environment.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 意图：跨节点路由必须严格依据数据库现在时的 owner/lease 快照与 {@code cluster_node} 注册表，陈旧 owner、无 READY、过期注册、缺失注册
 * 或本节点缺少共享秘密都不得被路由；本地路由也须由连接行 READY + 未过期租约确认。
 */
class JdbcClusterNodeRegistryIntegrationTest extends PostgresSchemaSupport {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID NODE_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID NODE_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final String SECRET = "test-cluster-secret-0123456789abcdef";
  private static final String NODE_B_URL = "http://node-b.internal:8080";
  private static final Instant NOW = Instant.now();

  private JdbcClient jdbcClient;

  @BeforeEach
  void setUp() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
    SingleConnectionDataSource dataSource =
        new SingleConnectionDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    this.jdbcClient = JdbcClient.create(dataSource);
    jdbcClient
        .sql(
            "insert into environment (id, name, registration_token_hash, version)"
                + " values (?, 'cluster-env', repeat('a', 64), 0)")
        .params(ENV.value())
        .update();
  }

  private JdbcClusterNodeRegistry registry(UUID localNodeId, String secret) {
    ClusterProperties properties = new ClusterProperties();
    properties.setInternalUrl("http://node-a.internal:8080");
    properties.setSecret(secret);
    return new JdbcClusterNodeRegistry(jdbcClient, localNodeId, properties);
  }

  private void seedConnection(UUID owner, String status, String runtimeInfo, Instant leaseUntil) {
    jdbcClient
        .sql(
            """
            insert into environment_connection
                (environment_id, owner_node_id, lease_token, status, runtime_info, skill_state,
                 recent_events, last_seen_at, lease_until)
            values (?, ?, ?, ?, ?::jsonb, '[]'::jsonb, '[]'::jsonb, ?, ?)
            on conflict (environment_id) do update
            set owner_node_id = excluded.owner_node_id,
                lease_token = excluded.lease_token,
                status = excluded.status,
                runtime_info = excluded.runtime_info,
                last_seen_at = excluded.last_seen_at,
                lease_until = excluded.lease_until
            """)
        .params(
            ENV.value(),
            owner,
            UUID.randomUUID(),
            status,
            runtimeInfo,
            Timestamp.from(leaseUntil.minusSeconds(5)),
            Timestamp.from(leaseUntil))
        .update();
  }

  private void seedNode(UUID nodeInstanceId, String internalUrl, Instant leaseUntil) {
    jdbcClient
        .sql(
            "insert into cluster_node (instance_id, internal_url, lease_until, last_seen)"
                + " values (?, ?, ?, ?)")
        .params(
            nodeInstanceId,
            internalUrl,
            Timestamp.from(leaseUntil),
            Timestamp.from(leaseUntil.minusSeconds(5)))
        .update();
  }

  @Test
  void localReadyOwnerRoutesLocalButStillRequiresTheLease() {
    seedConnection(NODE_A, "READY", "{}", NOW.plusSeconds(60));

    Optional<ClusterIdeOwnerRoute> route = registry(NODE_A, SECRET).route(ENV);

    assertTrue(route.isPresent());
    assertTrue(route.get().local());
    assertEquals(NODE_A, route.get().nodeInstanceId());
  }

  @Test
  void connectingOwnerHasNoRoute() {
    seedConnection(NODE_A, "CONNECTING", null, NOW.plusSeconds(60));

    assertTrue(registry(NODE_A, SECRET).route(ENV).isEmpty());
  }

  @Test
  void expiredLeaseHasNoRoute() {
    seedConnection(NODE_A, "READY", "{}", NOW.minusSeconds(60));

    assertTrue(registry(NODE_A, SECRET).route(ENV).isEmpty());
  }

  @Test
  void missingConnectionHasNoRoute() {
    assertTrue(registry(NODE_A, SECRET).route(ENV).isEmpty());
  }

  @Test
  void remoteOwnerWithFreshRegistryRoutesRemote() {
    seedConnection(NODE_B, "READY", "{}", NOW.plusSeconds(60));
    seedNode(NODE_B, NODE_B_URL, NOW.plusSeconds(60));

    Optional<ClusterIdeOwnerRoute> route = registry(NODE_A, SECRET).route(ENV);

    assertTrue(route.isPresent());
    assertFalse(route.get().local());
    assertEquals(NODE_B, route.get().nodeInstanceId());
    assertEquals(URI.create(NODE_B_URL), route.get().internalUrl());
  }

  @Test
  void remoteOwnerWithStaleRegistryHasNoRoute() {
    seedConnection(NODE_B, "READY", "{}", NOW.plusSeconds(60));
    seedNode(NODE_B, NODE_B_URL, NOW.minusSeconds(60));

    assertTrue(registry(NODE_A, SECRET).route(ENV).isEmpty());
  }

  @Test
  void remoteOwnerWithMissingRegistryRowHasNoRoute() {
    seedConnection(NODE_B, "READY", "{}", NOW.plusSeconds(60));

    assertTrue(registry(NODE_A, SECRET).route(ENV).isEmpty());
  }

  @Test
  void remoteOwnerWithoutSharedSecretHasNoRoute() {
    seedConnection(NODE_B, "READY", "{}", NOW.plusSeconds(60));
    seedNode(NODE_B, NODE_B_URL, NOW.plusSeconds(60));

    assertTrue(registry(NODE_A, null).route(ENV).isEmpty());
  }

  @Test
  void remoteOwnerWithMalformedRegistryOriginHasNoRoute() {
    seedConnection(NODE_B, "READY", "{}", NOW.plusSeconds(60));
    seedNode(NODE_B, "ftp://not-http.internal", NOW.plusSeconds(60));

    assertTrue(registry(NODE_A, SECRET).route(ENV).isEmpty());
  }

  @Test
  void remoteRoutingUsesDatabaseNowInsteadOfProcessClock() {
    jdbcClient
        .sql(
            """
            insert into environment_connection
                (environment_id, owner_node_id, lease_token, status, runtime_info, skill_state,
                 recent_events, last_seen_at, lease_until)
            values (?, ?, ?, 'READY', '{}'::jsonb, '[]'::jsonb, '[]'::jsonb,
                statement_timestamp(), statement_timestamp() + interval '1 hour')
            """)
        .params(ENV.value(), NODE_B, UUID.randomUUID())
        .update();
    jdbcClient
        .sql(
            """
            insert into cluster_node (instance_id, internal_url, lease_until, last_seen)
            values (?, ?, statement_timestamp() + interval '1 hour', statement_timestamp())
            """)
        .params(NODE_B, NODE_B_URL)
        .update();

    Optional<ClusterIdeOwnerRoute> route = registry(NODE_A, SECRET).route(ENV);
    assertTrue(route.isPresent());
    assertFalse(route.get().local());

    // 注册行由数据库现在时判定过期：last_seen 同步后移以满足 lease_until > last_seen 约束。
    jdbcClient
        .sql(
            "update cluster_node set lease_until = statement_timestamp() - interval '1 hour',"
                + " last_seen = statement_timestamp() - interval '2 hour' where instance_id = ?")
        .params(NODE_B)
        .update();
    assertTrue(registry(NODE_A, SECRET).route(ENV).isEmpty());
  }

  @Test
  void registrationAndHeartbeatRefreshTheLocalNodeLease() {
    JdbcClusterNodeRegistry registry = registry(NODE_A, SECRET);
    registry.registerLocalNode(URI.create("http://node-a.internal:8080"), Duration.ofSeconds(60));

    Integer registered =
        jdbcClient
            .sql("select count(*) from cluster_node where instance_id = ? and internal_url = ?")
            .params(NODE_A, "http://node-a.internal:8080")
            .query(Integer.class)
            .single();
    assertEquals(1, registered);
    assertTrue(registry.heartbeatLocalNode(Duration.ofSeconds(60)));

    jdbcClient.sql("delete from cluster_node where instance_id = ?").params(NODE_A).update();
    assertFalse(registry.heartbeatLocalNode(Duration.ofSeconds(60)));
  }
}
