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
import fun.fengwk.kkstudio.harness.environment.server.LeaseBindResult;
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
  private static final String DEV_TOKEN = "token-1";
  private static final String PROD_TOKEN = "token-2";
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
        "insert into environment (id, name, registration_token, version) values (?, 'dev', ?, 0)",
        DEV.value(),
        DEV_TOKEN);
    jdbcTemplate.update(
        "insert into environment (id, name, registration_token, version) values (?, 'prod', ?, 0)",
        PROD.value(),
        PROD_TOKEN);
  }

  /** 测试意图：验证正确 token 的新环境首次路由认领成功并生成有效 leaseToken 与 CONNECTING 状态。 */
  @Test
  void tryAcquireNewEnvironmentRoute() {
    LeaseBindResult result = registry1.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION);
    LeaseBindResult.Acquired acquired = assertInstanceOf(LeaseBindResult.Acquired.class, result);
    assertNotNull(acquired.leaseToken());

    EnvironmentConnection env = registry1.find(DEV).orElseThrow();
    assertEquals(DEV, env.environmentId());
    assertEquals(node1, env.ownerNodeId());
    assertEquals(acquired.leaseToken(), env.leaseToken());
    assertEquals(LiveEnvironmentStatus.CONNECTING, env.status());
    assertNull(env.daemonCapabilities());
    assertFalse(env.isReady(Instant.now(), LEASE_DURATION));
  }

  /** 测试意图：验证其他节点在活跃租约期内抢占返回 RETRY_LATER，且现有路由不被改变。 */
  @Test
  void activeRouteReturnsRetryLater() {
    LeaseBindResult r1 = registry1.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION);
    LeaseBindResult.Acquired acquired = assertInstanceOf(LeaseBindResult.Acquired.class, r1);

    // 活跃租约期内再次尝试抢占 -> RETRY_LATER，且现有路由不变
    LeaseBindResult r2 = registry2.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION);
    assertInstanceOf(LeaseBindResult.RetryLater.class, r2);

    EnvironmentConnection env = registry1.find(DEV).orElseThrow();
    assertEquals(acquired.leaseToken(), env.leaseToken());
    assertEquals(node1, env.ownerNodeId());
  }

  /** 测试意图：验证租约过期后，其他节点能通过原子 upsert 接管该路由。 */
  @Test
  void expiredRouteAtomicallyTakenOver() {
    LeaseBindResult r1 = registry1.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION);
    assertInstanceOf(LeaseBindResult.Acquired.class, r1);

    // 将数据库中该行的租约到期时间手动调整为过去（模拟租约超时）
    jdbcTemplate.update(
        "update environment_connection set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_id = ?",
        DEV.value());

    // 另一个节点再次尝试绑定 -> 成功接管
    LeaseBindResult r2 = registry2.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION);
    LeaseBindResult.Acquired acquired2 = assertInstanceOf(LeaseBindResult.Acquired.class, r2);

    EnvironmentConnection env = registry2.find(DEV).orElseThrow();
    assertEquals(node2, env.ownerNodeId());
    assertEquals(acquired2.leaseToken(), env.leaseToken());
    assertEquals(LiveEnvironmentStatus.CONNECTING, env.status());
  }

  /** 测试意图：验证 markReady 受到 (environmentId, ownerNodeId, leaseToken) 围栏保护。 */
  @Test
  void markReadyFencedUpdate() {
    LeaseBindResult r1 = registry1.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION);
    UUID token1 = ((LeaseBindResult.Acquired) r1).leaseToken();

    // 错误的 leaseToken 无法标记 READY
    assertFalse(registry1.markReady(DEV, UUID.randomUUID(), CAPABILITIES, LEASE_DURATION));
    EnvironmentConnection connecting = registry1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.CONNECTING, connecting.status());

    // 正确的 (node1, token1) 成功标记 READY
    assertTrue(registry1.markReady(DEV, token1, CAPABILITIES, LEASE_DURATION));
    EnvironmentConnection ready = registry1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.READY, ready.status());
    assertNotNull(ready.daemonCapabilities());
    assertTrue(ready.isReady(Instant.now(), LEASE_DURATION));
  }

  /** 测试意图：验证心跳刷新仅允许当前租约持有者在有效期内推进。 */
  @Test
  void heartbeatFencedUpdate() {
    LeaseBindResult r1 = registry1.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION);
    UUID token1 = ((LeaseBindResult.Acquired) r1).leaseToken();
    assertTrue(registry1.markReady(DEV, token1, CAPABILITIES, LEASE_DURATION));

    // 非持有节点无法刷新心跳
    assertFalse(registry2.heartbeat(DEV, token1, LEASE_DURATION));
    // 错误 token 无法刷新心跳
    assertFalse(registry1.heartbeat(DEV, UUID.randomUUID(), LEASE_DURATION));

    // 正确的持有者成功刷新心跳
    assertTrue(registry1.heartbeat(DEV, token1, LEASE_DURATION));
  }

  /** 测试意图：验证 disconnect 保留重连宽限期，将状态回退为 CONNECTING。 */
  @Test
  void disconnectFencedUpdatePreservesGraceLease() {
    LeaseBindResult r1 = registry1.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION);
    UUID token1 = ((LeaseBindResult.Acquired) r1).leaseToken();
    assertTrue(registry1.markReady(DEV, token1, CAPABILITIES, LEASE_DURATION));

    // 错误 token 的断开不会影响现有行
    assertFalse(registry1.disconnect(DEV, UUID.randomUUID(), LEASE_DURATION));
    assertEquals(LiveEnvironmentStatus.READY, registry1.find(DEV).orElseThrow().status());

    // 正确 token 断开 -> 状态回退为 CONNECTING，capabilities 置空，但行仍然保留
    assertTrue(registry1.disconnect(DEV, token1, LEASE_DURATION));
    EnvironmentConnection env = registry1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.CONNECTING, env.status());
    assertNull(env.daemonCapabilities());
    assertEquals(token1, env.leaseToken());
  }

  /** 测试意图：证明租约过期的持有者无法通过 markReady/heartbeat/disconnect 续期或恢复。 */
  @Test
  void oldExpiredHolderWithSameTokenCannotMarkReadyHeartbeatOrDisconnectRevive() {
    LeaseBindResult r1 = registry1.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION);
    UUID token1 = ((LeaseBindResult.Acquired) r1).leaseToken();
    assertTrue(registry1.markReady(DEV, token1, CAPABILITIES, LEASE_DURATION));

    // 手动调整租约到期时间为过去（同时保证 lease_until > last_seen_at 满足表约束）
    jdbcTemplate.update(
        "update environment_connection set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_id = ?",
        DEV.value());

    // 过期持有者尝试 markReady 必须被拒绝（返回 false）
    assertFalse(registry1.markReady(DEV, token1, CAPABILITIES, LEASE_DURATION));
    // 过期持有者尝试 heartbeat 必须被拒绝
    assertFalse(registry1.heartbeat(DEV, token1, LEASE_DURATION));
    // 过期持有者尝试 disconnect 必须被拒绝，不得恢复为活跃 CONNECTING 宽限期
    assertFalse(registry1.disconnect(DEV, token1, LEASE_DURATION));
  }

  /** 测试意图：证明 close 后同节点在宽限期内能直接重连并换发新 token，而其他节点不能抢占该宽限。 */
  @Test
  void gracePeriodAllowsSameNodeReconnectWithNewTokenWhileBlockingOtherNodes() {
    LeaseBindResult r1 = registry1.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION);
    UUID token1 = ((LeaseBindResult.Acquired) r1).leaseToken();
    assertTrue(registry1.markReady(DEV, token1, CAPABILITIES, LEASE_DURATION));

    // 断开连接，进入宽限期（status='CONNECTING' 且 lease_until > now）
    assertTrue(registry1.disconnect(DEV, token1, Duration.ofSeconds(30)));

    // 其他节点在宽限期内尝试抢占 -> 必须返回 RETRY_LATER
    LeaseBindResult r2 = registry2.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION);
    assertInstanceOf(LeaseBindResult.RetryLater.class, r2);

    // 同一节点在宽限期内重连 -> 允许接管并换发新 token
    LeaseBindResult rSame = registry1.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION);
    LeaseBindResult.Acquired acquiredSame = assertInstanceOf(LeaseBindResult.Acquired.class, rSame);
    assertNotNull(acquiredSame.leaseToken());

    EnvironmentConnection env = registry1.find(DEV).orElseThrow();
    assertEquals(node1, env.ownerNodeId());
    assertEquals(acquiredSame.leaseToken(), env.leaseToken());
    assertEquals(LiveEnvironmentStatus.CONNECTING, env.status());
  }

  /** 测试意图：证明活跃 READY 路由阻断包括原持有者在内的所有重复抢占，防止活动连接被意外重置。 */
  @Test
  void activeReadyRouteBlocksBothSameNodeAndOtherNode() {
    LeaseBindResult r1 = registry1.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION);
    UUID token1 = ((LeaseBindResult.Acquired) r1).leaseToken();
    assertTrue(registry1.markReady(DEV, token1, CAPABILITIES, LEASE_DURATION));

    // 同节点重复 acquire -> RETRY_LATER（READY 状态不准抢占）
    LeaseBindResult rSame = registry1.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION);
    assertInstanceOf(LeaseBindResult.RetryLater.class, rSame);

    // 异节点 acquire -> RETRY_LATER
    LeaseBindResult rOther = registry2.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION);
    assertInstanceOf(LeaseBindResult.RetryLater.class, rOther);
  }

  /** 测试意图：证明无效或已轮换的 registration token 会直接被拒绝，不会占用或创建连接。 */
  @Test
  void invalidOrRotatedTokenIsRejected() {
    // 错误 token -> REJECTED
    LeaseBindResult wrong = registry1.tryAcquire(DEV, "wrong-token", LEASE_DURATION);
    assertInstanceOf(LeaseBindResult.Rejected.class, wrong);

    // 不存在的环境 -> REJECTED
    LeaseBindResult missing =
        registry1.tryAcquire(
            EnvironmentId.parse("99999999-9999-9999-9999-999999999999"), DEV_TOKEN, LEASE_DURATION);
    assertInstanceOf(LeaseBindResult.Rejected.class, missing);

    // 没有 connection 行被创建
    assertTrue(registry1.find(DEV).isEmpty());
  }

  /**
   * 测试意图：证明 hasActiveLease、hasReadyLease、holdsReadyLease 与 hasActiveLeaseToken 严格依据 PostgreSQL
   * statement_timestamp() 判定租约活跃性；当数据库中租约过期时，无论外部传入的时钟如何滞后，各谓词均确定性返回 false。
   */
  @Test
  void dbAuthoritativePredicatesReflectExpiredLeaseRegardlessOfAppClock() {
    LeaseBindResult r1 = registry1.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION);
    UUID token1 = ((LeaseBindResult.Acquired) r1).leaseToken();

    // CONNECTING 阶段：activeLease 为 true，但 readyLease 均为 false
    assertTrue(registry1.hasActiveLease(DEV));
    assertTrue(registry1.hasActiveLeaseToken(DEV, token1));
    assertFalse(registry1.hasReadyLease(DEV));
    assertFalse(registry1.holdsReadyLease(DEV, token1));

    assertTrue(registry1.markReady(DEV, token1, CAPABILITIES, LEASE_DURATION));

    // READY 活跃状态下四个谓词均返回 true（hasReadyLease 跨节点均为 true）
    assertTrue(registry1.hasActiveLease(DEV));
    assertTrue(registry1.hasReadyLease(DEV));
    assertTrue(registry2.hasReadyLease(DEV));
    assertTrue(registry1.holdsReadyLease(DEV, token1));
    assertTrue(registry1.hasActiveLeaseToken(DEV, token1));

    // 非持有节点或错误 token 判定
    assertFalse(registry2.holdsReadyLease(DEV, token1));
    assertFalse(registry1.holdsReadyLease(DEV, UUID.randomUUID()));

    // 手动调整 DB 租约到期时间为过去
    jdbcTemplate.update(
        "update environment_connection set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_id = ?",
        DEV.value());

    // 此时即使外部认为时间未走，DB 现在时判定必须全部返回 false
    assertFalse(registry1.hasActiveLease(DEV));
    assertFalse(registry1.hasReadyLease(DEV));
    assertFalse(registry2.hasReadyLease(DEV));
    assertFalse(registry1.holdsReadyLease(DEV, token1));
    assertFalse(registry1.hasActiveLeaseToken(DEV, token1));
  }

  /** 测试意图：验证 list 查询能列出不同环境的独立路由行。 */
  @Test
  void listAndDistinctEnvironments() {
    LeaseBindResult r1 = registry1.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION);
    LeaseBindResult r2 = registry2.tryAcquire(PROD, PROD_TOKEN, LEASE_DURATION);
    assertInstanceOf(LeaseBindResult.Acquired.class, r1);
    assertInstanceOf(LeaseBindResult.Acquired.class, r2);

    List<EnvironmentConnection> list = registry1.list();
    assertEquals(2, list.size());
    assertEquals(DEV, list.get(0).environmentId());
    assertEquals(PROD, list.get(1).environmentId());
  }
}
