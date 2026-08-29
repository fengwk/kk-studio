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

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 基于 PostgreSQL 路由租约表的原子认领、冲突隔离、租约接管与状态围栏测试。 */
class LiveEnvironmentRegistryTest extends PostgresSchemaSupport {

  private static final Duration LEASE_DURATION = Duration.ofSeconds(60);
  private static final EnvironmentName DEV = new EnvironmentName("dev");
  private static final EnvironmentName PROD = new EnvironmentName("prod");
  private static final DaemonCapabilities CAPABILITIES =
      new DaemonCapabilities(
          DaemonCapabilities.VERSION,
          new DaemonEnvironmentInfo(
              DaemonOperatingSystem.LINUX, "UTC", "Linux environment.", "/home/dev"),
          List.of(),
          List.of());

  private final UUID node1 = UUID.randomUUID();
  private final UUID node2 = UUID.randomUUID();
  private JdbcTemplate jdbcTemplate;
  private LiveEnvironmentRegistry registry1;
  private LiveEnvironmentRegistry registry2;

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
    this.registry1 = new LiveEnvironmentRegistry(jdbcTemplate, node1);
    this.registry2 = new LiveEnvironmentRegistry(jdbcTemplate, node2);
  }

  @Test
  void tryAcquireNewEnvironmentRoute() {
    BindResult result = registry1.tryAcquire(DEV, "daemon-1", LEASE_DURATION);
    BindResult.Acquired acquired = assertInstanceOf(BindResult.Acquired.class, result);
    assertNotNull(acquired.routeToken());

    LiveEnvironment env = registry1.find(DEV).orElseThrow();
    assertEquals(DEV, env.name());
    assertEquals("daemon-1", env.daemonId());
    assertEquals(node1, env.ownerNodeId());
    assertEquals(acquired.routeToken(), env.routeToken());
    assertEquals(LiveEnvironmentStatus.CONNECTING, env.status());
    assertNull(env.daemonCapabilities());
    assertFalse(env.isReady(Instant.now()));
  }

  @Test
  void sameDaemonIdActiveReturnsRetryLater() {
    BindResult r1 = registry1.tryAcquire(DEV, "daemon-1", LEASE_DURATION);
    BindResult.Acquired acquired = assertInstanceOf(BindResult.Acquired.class, r1);

    // 同一 daemon 实例在活跃租约期内重连 -> RETRY_LATER，且现有路由不变
    BindResult r2 = registry1.tryAcquire(DEV, "daemon-1", LEASE_DURATION);
    assertInstanceOf(BindResult.RetryLater.class, r2);

    LiveEnvironment env = registry1.find(DEV).orElseThrow();
    assertEquals(acquired.routeToken(), env.routeToken());
    assertEquals(node1, env.ownerNodeId());
  }

  @Test
  void differentDaemonIdActiveReturnsConflict() {
    BindResult r1 = registry1.tryAcquire(DEV, "daemon-1", LEASE_DURATION);
    BindResult.Acquired acquired = assertInstanceOf(BindResult.Acquired.class, r1);

    // 不同 daemon 实例尝试抢占活跃路由 -> 终态 CONFLICT
    BindResult r2 = registry2.tryAcquire(DEV, "daemon-2", LEASE_DURATION);
    assertInstanceOf(BindResult.Conflict.class, r2);

    LiveEnvironment env = registry1.find(DEV).orElseThrow();
    assertEquals(acquired.routeToken(), env.routeToken());
    assertEquals("daemon-1", env.daemonId());
    assertEquals(node1, env.ownerNodeId());
  }

  @Test
  void expiredRouteAtomicallyTakenOver() {
    BindResult r1 = registry1.tryAcquire(DEV, "daemon-1", LEASE_DURATION);
    assertInstanceOf(BindResult.Acquired.class, r1);

    // 将数据库中该行的租约到期时间手动调整为过去（模拟租约超时）
    jdbcTemplate.update(
        "update live_environment set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_name = 'dev'");

    // 另一个节点/daemon 再次尝试绑定 -> 成功接管
    BindResult r2 = registry2.tryAcquire(DEV, "daemon-2", LEASE_DURATION);
    BindResult.Acquired acquired2 = assertInstanceOf(BindResult.Acquired.class, r2);

    LiveEnvironment env = registry2.find(DEV).orElseThrow();
    assertEquals("daemon-2", env.daemonId());
    assertEquals(node2, env.ownerNodeId());
    assertEquals(acquired2.routeToken(), env.routeToken());
    assertEquals(LiveEnvironmentStatus.CONNECTING, env.status());
  }

  @Test
  void markReadyFencedUpdate() {
    BindResult r1 = registry1.tryAcquire(DEV, "daemon-1", LEASE_DURATION);
    UUID token1 = ((BindResult.Acquired) r1).routeToken();

    // 错误的 routeToken 无法标记 READY
    assertFalse(registry1.markReady(DEV, UUID.randomUUID(), CAPABILITIES, LEASE_DURATION));
    LiveEnvironment connecting = registry1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.CONNECTING, connecting.status());

    // 正确的 (node1, token1) 成功标记 READY
    assertTrue(registry1.markReady(DEV, token1, CAPABILITIES, LEASE_DURATION));
    LiveEnvironment ready = registry1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.READY, ready.status());
    assertNotNull(ready.daemonCapabilities());
    assertTrue(ready.isReady(Instant.now()));
  }

  @Test
  void heartbeatFencedUpdate() {
    BindResult r1 = registry1.tryAcquire(DEV, "daemon-1", LEASE_DURATION);
    UUID token1 = ((BindResult.Acquired) r1).routeToken();
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
    BindResult r1 = registry1.tryAcquire(DEV, "daemon-1", LEASE_DURATION);
    UUID token1 = ((BindResult.Acquired) r1).routeToken();
    assertTrue(registry1.markReady(DEV, token1, CAPABILITIES, LEASE_DURATION));

    // 错误 token 的断开不会影响现有行
    assertFalse(registry1.disconnect(DEV, UUID.randomUUID(), LEASE_DURATION));
    assertEquals(LiveEnvironmentStatus.READY, registry1.find(DEV).orElseThrow().status());

    // 正确 token 断开 -> 状态回退为 CONNECTING，capabilities 置空，但行仍然保留
    assertTrue(registry1.disconnect(DEV, token1, LEASE_DURATION));
    LiveEnvironment env = registry1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.CONNECTING, env.status());
    assertNull(env.daemonCapabilities());
    assertEquals(token1, env.routeToken());
  }

  @Test
  void listAndDistinctEnvironments() {
    BindResult r1 = registry1.tryAcquire(DEV, "daemon-1", LEASE_DURATION);
    BindResult r2 = registry2.tryAcquire(PROD, "daemon-2", LEASE_DURATION);
    assertInstanceOf(BindResult.Acquired.class, r1);
    assertInstanceOf(BindResult.Acquired.class, r2);

    List<LiveEnvironment> list = registry1.list();
    assertEquals(2, list.size());
    assertEquals(DEV, list.get(0).name());
    assertEquals(PROD, list.get(1).name());
  }
}
