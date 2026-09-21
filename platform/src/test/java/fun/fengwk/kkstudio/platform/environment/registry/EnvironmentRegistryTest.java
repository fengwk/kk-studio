package fun.fengwk.kkstudio.platform.environment.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.time.Clock;
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
  private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
  private static final String DEV_TOKEN = "token-1";
  private static final String PROD_TOKEN = "token-2";
  private static final DaemonCapabilities CAPABILITIES =
      new DaemonCapabilities(
          DaemonCapabilities.VERSION,
          new DaemonEnvironmentInfo(
              DaemonOperatingSystem.LINUX, "UTC", "dev", "/home/dev", "Linux environment."));

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
    this.registry1 = new EnvironmentRegistry(jdbcTemplate, node1, Clock.systemUTC());
    this.registry2 = new EnvironmentRegistry(jdbcTemplate, node2, Clock.systemUTC());

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

  /**
   * 测试意图：验证 markReady 在与 READY 推进的同一条语句内把最近一次被接受的宿主 metadata 写入 {@code
   * environment_connection.runtime_info}：
   *
   * <ol>
   *   <li>围栏失效（错误 leaseToken）时既不推进状态也不写入保留 metadata；
   *   <li>围栏成立时 metadata 与 READY 原子同生；
   *   <li>同环境再次 READY 时同一行被整体覆盖，绝不累积第二行。
   * </ol>
   */
  @Test
  void markReadyAtomicallyRecordsHostMetadataUnderLeaseFence() {
    LeaseBindResult r1 = registry1.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION);
    UUID token1 = ((LeaseBindResult.Acquired) r1).leaseToken();

    // 围栏失效：既不能推进 READY，也不能写入任何保留事实。
    assertFalse(registry1.markReady(DEV, UUID.randomUUID(), CAPABILITIES, LEASE_DURATION));
    assertNull(registry1.find(DEV).orElseThrow().daemonCapabilities());
    assertEquals(LiveEnvironmentStatus.CONNECTING, registry1.find(DEV).orElseThrow().status());

    // 围栏成立：保留 metadata 与 READY 原子同生。
    assertTrue(registry1.markReady(DEV, token1, CAPABILITIES, LEASE_DURATION));
    EnvironmentConnection ready = registry1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.READY, ready.status());
    assertEquals(CAPABILITIES, ready.daemonCapabilities());
    Integer rows =
        jdbcTemplate.queryForObject("select count(1) from environment_connection", Integer.class);
    assertEquals(1, rows);
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

  /** 测试意图：验证 disconnect 保留重连宽限期，将状态回退为 CONNECTING，同时保留最近一次被接受的宿主 metadata（断线不清空）。 */
  @Test
  void disconnectFencedUpdatePreservesGraceLease() {
    LeaseBindResult r1 = registry1.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION);
    UUID token1 = ((LeaseBindResult.Acquired) r1).leaseToken();
    assertTrue(registry1.markReady(DEV, token1, CAPABILITIES, LEASE_DURATION));

    // 错误 token 的断开不会影响现有行
    assertFalse(registry1.disconnect(DEV, UUID.randomUUID(), LEASE_DURATION));
    assertEquals(LiveEnvironmentStatus.READY, registry1.find(DEV).orElseThrow().status());

    // 正确 token 断开 -> 状态回退为 CONNECTING，保留的宿主 metadata 与租约行都仍在
    assertTrue(registry1.disconnect(DEV, token1, LEASE_DURATION));
    EnvironmentConnection env = registry1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.CONNECTING, env.status());
    assertEquals(CAPABILITIES, env.daemonCapabilities());
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

  /**
   * 测试意图：验证 CONNECTING/READY/DISCONNECTED 三个生命周期事件由推进状态的同一条围栏语句原子追加，且断线后事件与 Skill 投影都作为保留事实继续存在。
   */
  @Test
  void lifecycleEventsAreAppendedAtomicallyAlongStatusTransitions() {
    LeaseBindResult r1 = registry1.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION);
    UUID token = assertInstanceOf(LeaseBindResult.Acquired.class, r1).leaseToken();

    EnvironmentConnection connecting = registry1.find(DEV).orElseThrow();
    assertEquals(List.of(EnvironmentEvent.TYPE_CONNECTING), eventTypes(connecting.recentEvents()));
    assertEquals(EnvironmentEvent.LEVEL_INFO, connecting.recentEvents().get(0).level());

    assertTrue(registry1.markReady(DEV, token, CAPABILITIES, LEASE_DURATION));
    assertTrue(
        registry1.replaceSkillState(
            DEV,
            token,
            List.of(EnvironmentSkillState.installed("dev", COMMIT, "/home/dev/skills/dev")),
            new EnvironmentEvent(
                Instant.now(),
                EnvironmentEvent.LEVEL_INFO,
                EnvironmentEvent.TYPE_SKILL_SYNC_SUCCEEDED,
                "skill package sync succeeded: dev")));

    assertTrue(registry1.disconnect(DEV, token, LEASE_DURATION));
    EnvironmentConnection offline = registry1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.CONNECTING, offline.status());
    assertEquals(
        List.of(
            EnvironmentEvent.TYPE_CONNECTING,
            EnvironmentEvent.TYPE_READY,
            EnvironmentEvent.TYPE_SKILL_SYNC_SUCCEEDED,
            EnvironmentEvent.TYPE_DISCONNECTED),
        eventTypes(offline.recentEvents()));
    assertEquals(EnvironmentEvent.LEVEL_WARN, offline.recentEvents().get(3).level());
    // 保留事实：断线不清空宿主 metadata、Skill 投影与事件窗口。
    assertNotNull(offline.daemonCapabilities());
    assertEquals(1, offline.skillState().size());
    assertEquals("/home/dev/skills/dev", offline.installedSkillRoot("dev", COMMIT).orElseThrow());
    assertTrue(offline.lastAlert().isPresent());
  }

  /**
   * 测试意图：验证 READY 在同一条围栏语句里清空前一次连接的 Skill 同步投影，且围栏失效时绝不清空。
   *
   * <p>清空是「每次 READY 都由编排器全量重建」的前提：残留投影不得跨连接生命周期存活。
   */
  @Test
  void readyClearsSkillStateUnderLeaseFence() {
    UUID token1 = acquireReady(registry1, DEV, DEV_TOKEN);
    assertTrue(
        registry1.replaceSkillState(
            DEV,
            token1,
            List.of(EnvironmentSkillState.installed("dev", COMMIT, "/home/dev/skills/dev")),
            new EnvironmentEvent(
                Instant.now(),
                EnvironmentEvent.LEVEL_INFO,
                EnvironmentEvent.TYPE_SKILL_SYNC_SUCCEEDED,
                "skill package sync succeeded: dev")));

    // 围栏失效（错误 leaseToken）时既不推进状态也不清空投影。
    assertFalse(registry1.markReady(DEV, UUID.randomUUID(), CAPABILITIES, LEASE_DURATION));
    assertEquals(1, registry1.find(DEV).orElseThrow().skillState().size());

    assertTrue(registry1.disconnect(DEV, token1, LEASE_DURATION));
    UUID token2 = acquireReady(registry1, DEV, DEV_TOKEN);
    assertNotEquals(token1, token2);
    EnvironmentConnection ready = registry1.find(DEV).orElseThrow();
    assertEquals(LiveEnvironmentStatus.READY, ready.status());
    assertEquals(List.of(), ready.skillState());
    assertTrue(ready.installedSkillRoot("dev", COMMIT).isEmpty());
  }

  /** 测试意图：验证 Skill 投影与事件的写回受 owner + leaseToken + 未过期 + READY 四重围栏保护，任何一环失效都 fail-closed。 */
  @Test
  void skillStateWritesAreFencedByOwnerLeaseExpiryAndReadyStatus() {
    UUID token1 = acquireReady(registry1, DEV, DEV_TOKEN);
    List<EnvironmentSkillState> state =
        List.of(EnvironmentSkillState.installed("dev", COMMIT, "/home/dev/skills/dev"));
    EnvironmentEvent event =
        new EnvironmentEvent(
            Instant.now(),
            EnvironmentEvent.LEVEL_INFO,
            EnvironmentEvent.TYPE_SKILL_SYNC_SUCCEEDED,
            "skill package sync succeeded: dev");

    // 错误 leaseToken 与其它节点都不得写入。
    assertFalse(registry1.replaceSkillState(DEV, UUID.randomUUID(), state, event));
    assertFalse(registry2.replaceSkillState(DEV, token1, state, event));
    assertFalse(
        registry2.recordSkillEvent(
            DEV,
            token1,
            new EnvironmentEvent(
                Instant.now(),
                EnvironmentEvent.LEVEL_WARN,
                EnvironmentEvent.TYPE_DISCONNECTED,
                "daemon connection lost")));
    assertTrue(registry1.find(DEV).orElseThrow().skillState().isEmpty());

    // 租约过期后原持有者不得写入。
    jdbcTemplate.update(
        "update environment_connection set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_id = ?",
        DEV.value());
    assertFalse(registry1.replaceSkillState(DEV, token1, state, event));
    assertTrue(registry1.find(DEV).orElseThrow().skillState().isEmpty());

    // 接管后的新持有者写入成功。
    UUID token2 = acquireReady(registry1, DEV, DEV_TOKEN);
    assertTrue(registry1.replaceSkillState(DEV, token2, state, event));
    EnvironmentConnection connection = registry1.find(DEV).orElseThrow();
    assertEquals(state, connection.skillState());
    assertEquals(
        EnvironmentEvent.TYPE_SKILL_SYNC_SUCCEEDED,
        connection.recentEvents().get(connection.recentEvents().size() - 1).type());

    // 重新 CONNECTING（断线）后同一 leaseToken 也不得再写入。
    assertTrue(registry1.disconnect(DEV, token2, LEASE_DURATION));
    assertFalse(registry1.replaceSkillState(DEV, token2, state, event));
    assertFalse(registry1.recordSkillEvent(DEV, token2, event));
    assertEquals(state, registry1.find(DEV).orElseThrow().skillState());
  }

  /** 测试意图：验证 recent_events 始终是 200 条以内的按时间正序窗口，最旧的先被淘汰。 */
  @Test
  void recentEventsKeepNewestTwoHundred() {
    UUID token = acquireReady(registry1, DEV, DEV_TOKEN);
    for (int index = 0; index < 250; index++) {
      assertTrue(
          registry1.recordSkillEvent(
              DEV,
              token,
              new EnvironmentEvent(
                  Instant.now(),
                  EnvironmentEvent.LEVEL_INFO,
                  EnvironmentEvent.TYPE_SKILL_SYNC_STARTED,
                  "event " + index)));
    }

    List<EnvironmentEvent> events = registry1.find(DEV).orElseThrow().recentEvents();
    assertEquals(EnvironmentConnection.MAX_RECENT_EVENTS, events.size());
    assertEquals("event 50", events.get(0).message());
    assertEquals("event 249", events.get(events.size() - 1).message());
    for (int index = 1; index < events.size(); index++) {
      assertFalse(events.get(index).time().isBefore(events.get(index - 1).time()));
    }
  }

  /** 测试意图：验证增量 Skill 同步的目标集合严格等于「本节点持有未过期 READY 租约」的 Environment 行。 */
  @Test
  void listReadyOwnedByNodeReturnsOnlyLocallyOwnedReadyRows() {
    UUID token1 = acquireReady(registry1, DEV, DEV_TOKEN);
    registry2.tryAcquire(PROD, PROD_TOKEN, LEASE_DURATION);

    assertEquals(List.of(DEV), environmentIds(registry1.listReadyOwnedByNode()));
    assertEquals(List.of(), registry2.listReadyOwnedByNode());

    UUID token2 = acquireReady(registry2, PROD, PROD_TOKEN);
    assertEquals(List.of(PROD), environmentIds(registry2.listReadyOwnedByNode()));

    // 租约过期即从目标集合中消失，无需任何进程内缓存失效。
    jdbcTemplate.update(
        "update environment_connection set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_id = ?",
        DEV.value());
    assertEquals(List.of(), registry1.listReadyOwnedByNode());
    assertFalse(
        registry1.replaceSkillState(
            DEV,
            token1,
            List.of(),
            new EnvironmentEvent(
                Instant.now(),
                EnvironmentEvent.LEVEL_INFO,
                EnvironmentEvent.TYPE_SKILL_SYNC_SUCCEEDED,
                "skill package sync succeeded: dev")));
    assertNotNull(token2);
  }

  private UUID acquireReady(
      EnvironmentRegistry registry, EnvironmentId environmentId, String token) {
    LeaseBindResult bound = registry.tryAcquire(environmentId, token, LEASE_DURATION);
    UUID leaseToken = assertInstanceOf(LeaseBindResult.Acquired.class, bound).leaseToken();
    assertTrue(registry.markReady(environmentId, leaseToken, CAPABILITIES, LEASE_DURATION));
    return leaseToken;
  }

  private static List<String> eventTypes(List<EnvironmentEvent> events) {
    return events.stream().map(EnvironmentEvent::type).toList();
  }

  private static List<EnvironmentId> environmentIds(List<EnvironmentConnection> connections) {
    return connections.stream().map(EnvironmentConnection::environmentId).toList();
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
