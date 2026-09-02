package fun.fengwk.kkstudio.platform.environment.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 基于 PostgreSQL 路由租约表 environment_connection 的原子认领、冲突隔离、租约接管与状态围栏测试。 */
class EnvironmentRegistryTest extends PostgresSchemaSupport {

  private static final Duration LEASE_DURATION = Duration.ofSeconds(60);
  private static final EnvironmentId DEV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final EnvironmentId PROD =
      EnvironmentId.parse("22222222-2222-2222-2222-222222222222");
  private static final DaemonCapabilities CAPABILITIES =
      new DaemonCapabilities(
          DaemonCapabilities.VERSION,
          new DaemonEnvironmentInfo(
              DaemonOperatingSystem.LINUX, "UTC", "Linux environment.", "/home/dev"),
          List.of());

  private final UUID node1 = UUID.randomUUID();
  private final UUID node2 = UUID.randomUUID();
  private JdbcTemplate jdbcTemplate;
  private EnvironmentRegistry registry1;
  private EnvironmentRegistry registry2;

  @BeforeEach
  void setUp() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
    SingleConnectionDataSource ds =
        new SingleConnectionDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    this.jdbcTemplate = new JdbcTemplate(ds);
    this.registry1 = new EnvironmentRegistry(jdbcTemplate, node1);
    this.registry2 = new EnvironmentRegistry(jdbcTemplate, node2);

    // 播种底层 environment 卡片行
    jdbcTemplate.update(
        "insert into environment (id, name, registration_token, version) values (?, 'dev', 'token-1', 0)",
        DEV.value());
    jdbcTemplate.update(
        "insert into environment (id, name, registration_token, version) values (?, 'prod', 'token-2', 0)",
        PROD.value());
  }

  @Test
  void tryAcquireNewEnvironmentRoute() {
    BindResult result = registry1.tryAcquire(DEV, LEASE_DURATION);
    BindResult.Acquired acquired = assertInstanceOf(BindResult.Acquired.class, result);
    assertNotNull(acquired.leaseToken());

    EnvironmentConnection env = registry1.find(DEV).orElseThrow();
    assertEquals(DEV, env.environmentId());
    assertEquals(node1, env.ownerNodeId());
    assertEquals(acquired.leaseToken(), env.leaseToken());
    assertEquals(EnvironmentConnectionStatus.CONNECTING, env.status());
    assertNull(env.daemonCapabilities());
    assertFalse(env.isReady(Instant.now(), LEASE_DURATION));
  }

  @Test
  void activeRouteReturnsRetryLater() {
    BindResult r1 = registry1.tryAcquire(DEV, LEASE_DURATION);
    BindResult.Acquired acquired = assertInstanceOf(BindResult.Acquired.class, r1);

    // 活跃租约期内再次尝试抢占 -> RETRY_LATER，且现有路由不变
    BindResult r2 = registry2.tryAcquire(DEV, LEASE_DURATION);
    assertInstanceOf(BindResult.RetryLater.class, r2);

    EnvironmentConnection env = registry1.find(DEV).orElseThrow();
    assertEquals(acquired.leaseToken(), env.leaseToken());
    assertEquals(node1, env.ownerNodeId());
  }

  @Test
  void expiredRouteAtomicallyTakenOver() {
    BindResult r1 = registry1.tryAcquire(DEV, LEASE_DURATION);
    assertInstanceOf(BindResult.Acquired.class, r1);

    // 将数据库中该行的租约到期时间手动调整为过去（模拟租约超时）
    jdbcTemplate.update(
        "update environment_connection set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_id = ?",
        DEV.value());

    // 另一个节点再次尝试绑定 -> 成功接管
    BindResult r2 = registry2.tryAcquire(DEV, LEASE_DURATION);
    BindResult.Acquired acquired2 = assertInstanceOf(BindResult.Acquired.class, r2);

    EnvironmentConnection env = registry2.find(DEV).orElseThrow();
    assertEquals(node2, env.ownerNodeId());
    assertEquals(acquired2.leaseToken(), env.leaseToken());
    assertEquals(EnvironmentConnectionStatus.CONNECTING, env.status());
  }

  @Test
  void markReadyFencedUpdate() {
    BindResult r1 = registry1.tryAcquire(DEV, LEASE_DURATION);
    UUID token1 = ((BindResult.Acquired) r1).leaseToken();

    // 错误的 leaseToken 无法标记 READY
    assertFalse(registry1.markReady(DEV, UUID.randomUUID(), CAPABILITIES, LEASE_DURATION));
    EnvironmentConnection connecting = registry1.find(DEV).orElseThrow();
    assertEquals(EnvironmentConnectionStatus.CONNECTING, connecting.status());

    // 正确的 (node1, token1) 成功标记 READY
    assertTrue(registry1.markReady(DEV, token1, CAPABILITIES, LEASE_DURATION));
    EnvironmentConnection ready = registry1.find(DEV).orElseThrow();
    assertEquals(EnvironmentConnectionStatus.READY, ready.status());
    assertNotNull(ready.daemonCapabilities());
    assertTrue(ready.isReady(Instant.now(), LEASE_DURATION));
  }

  @Test
  void heartbeatFencedUpdate() {
    BindResult r1 = registry1.tryAcquire(DEV, LEASE_DURATION);
    UUID token1 = ((BindResult.Acquired) r1).leaseToken();
    assertTrue(registry1.markReady(DEV, token1, CAPABILITIES, LEASE_DURATION));

    // 非持有节点无法刷新心跳
    assertFalse(registry2.heartbeat(DEV, token1, LEASE_DURATION));
    // 错误 token 无法刷新心跳
    assertFalse(registry1.heartbeat(DEV, UUID.randomUUID(), LEASE_DURATION));

    // 正确的持有者成功刷新心跳
    assertTrue(registry1.heartbeat(DEV, token1, LEASE_DURATION));
  }

  @Test
  void disconnectFencedUpdatePreservesGraceLease() {
    BindResult r1 = registry1.tryAcquire(DEV, LEASE_DURATION);
    UUID token1 = ((BindResult.Acquired) r1).leaseToken();
    assertTrue(registry1.markReady(DEV, token1, CAPABILITIES, LEASE_DURATION));

    // 错误 token 的断开不会影响现有行
    assertFalse(registry1.disconnect(DEV, UUID.randomUUID(), LEASE_DURATION));
    assertEquals(EnvironmentConnectionStatus.READY, registry1.find(DEV).orElseThrow().status());

    // 正确 token 断开 -> 状态回退为 CONNECTING，capabilities 置空，但行仍然保留
    assertTrue(registry1.disconnect(DEV, token1, LEASE_DURATION));
    EnvironmentConnection env = registry1.find(DEV).orElseThrow();
    assertEquals(EnvironmentConnectionStatus.CONNECTING, env.status());
    assertNull(env.daemonCapabilities());
    assertEquals(token1, env.leaseToken());
  }

  @Test
  void listAndDistinctEnvironments() {
    BindResult r1 = registry1.tryAcquire(DEV, LEASE_DURATION);
    BindResult r2 = registry2.tryAcquire(PROD, LEASE_DURATION);
    assertInstanceOf(BindResult.Acquired.class, r1);
    assertInstanceOf(BindResult.Acquired.class, r2);

    List<EnvironmentConnection> list = registry1.list();
    assertEquals(2, list.size());
    assertEquals(DEV, list.get(0).environmentId());
    assertEquals(PROD, list.get(1).environmentId());
  }
}
