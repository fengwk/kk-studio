package fun.fengwk.kkstudio.platform.environment.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
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

  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
    SingleConnectionDataSource dataSource =
        new SingleConnectionDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    this.jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.update(
        "insert into environment (id, name, registration_token, version) values (?, 'cluster-env', 'token', 0)",
        ENV.value());
  }

  private JdbcClusterNodeRegistry registry(UUID localNodeId, String secret) {
    ClusterProperties properties = new ClusterProperties();
    properties.setInternalUrl("http://node-a.internal:8080");
    properties.setSecret(secret);
    return new JdbcClusterNodeRegistry(jdbcTemplate, localNodeId, Clock.systemUTC(), properties);
  }

  private void seedConnection(UUID owner, String status, String runtimeInfo, Instant leaseUntil) {
    jdbcTemplate.update(
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
        """,
        ENV.value(),
        owner,
        UUID.randomUUID(),
        status,
        runtimeInfo,
        Timestamp.from(leaseUntil.minusSeconds(5)),
        Timestamp.from(leaseUntil));
  }

  private void seedNode(UUID nodeInstanceId, String internalUrl, Instant leaseUntil) {
    jdbcTemplate.update(
        "insert into cluster_node (instance_id, internal_url, lease_until, last_seen) values (?, ?, ?, ?)",
        nodeInstanceId,
        internalUrl,
        Timestamp.from(leaseUntil),
        Timestamp.from(leaseUntil.minusSeconds(5)));
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
  void registrationAndHeartbeatRefreshTheLocalNodeLease() {
    JdbcClusterNodeRegistry registry = registry(NODE_A, SECRET);
    registry.registerLocalNode(URI.create("http://node-a.internal:8080"), Duration.ofSeconds(60));

    Integer registered =
        jdbcTemplate.queryForObject(
            "select count(*) from cluster_node where instance_id = ? and internal_url = ?",
            Integer.class,
            NODE_A,
            "http://node-a.internal:8080");
    assertEquals(1, registered);
    assertTrue(registry.heartbeatLocalNode(Duration.ofSeconds(60)));

    jdbcTemplate.update("delete from cluster_node where instance_id = ?", NODE_A);
    assertFalse(registry.heartbeatLocalNode(Duration.ofSeconds(60)));
  }
}
