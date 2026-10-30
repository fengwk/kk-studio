package fun.fengwk.kkstudio.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** {@link DurableWorkStore} 对真实 PostgreSQL 的跃迁、路由、事务回滚与 NOTIFY 契约。 */
class DurableWorkStoreIntegrationTest {

  private static final Duration LEASE = Duration.ofSeconds(30);

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("kk_studio_work_test");

  private static JdbcClient jdbc;
  private static DataSourceTransactionManager transactions;

  private DurableWorkStore issues;
  private DurableWorkStore tools;
  private DurableWorkStore canvasRuns;

  @BeforeAll
  static void migrate() {
    POSTGRES.start();
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = JdbcClient.create(dataSource);
    transactions = new DataSourceTransactionManager(dataSource);
  }

  @BeforeEach
  void reset() {
    jdbc.sql("delete from durable_work").update();
    jdbc.sql("delete from environment_connection").update();
    jdbc.sql("delete from environment").update();
    issues = new DurableWorkStore("ISSUE", jdbc, transactions);
    tools = new DurableWorkStore("TOOL", jdbc, transactions);
    canvasRuns = new DurableWorkStore("CANVAS_RUN", jdbc, transactions);
  }

  /** 重复请求递增 wakeVersion 并把未来 due 拉回数据库此刻；不同 target type 互不可见。 */
  @Test
  void requestMergesWakesAndPullsDueForward() {
    UUID id = UUID.randomUUID();
    DurableWork first = issues.request(id);
    jdbc.sql(
            "update durable_work set available_at = statement_timestamp() + interval '5 minutes'"
                + " where target_id = :id")
        .param("id", id)
        .update();
    DurableWork merged = issues.request(id);

    assertEquals(1, first.wakeVersion());
    assertEquals(2, merged.wakeVersion());
    assertTrue(merged.availableAt().isBefore(first.availableAt().plusSeconds(300)));
    assertEquals(2L, issues.claimNext("second", LEASE).orElseThrow().claimedWakeVersion());
    assertTrue(canvasRuns.find(id).isEmpty());
    assertEquals("ISSUE", issues.targetType());
  }

  /** 已到期行只能被一个 worker 领取；租约有效期间新 wake 不影响所有权，续租按数据库时间延长。 */
  @Test
  void claimHonoursLeasesAndOrdersByDue() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    issues.request(first);
    issues.request(second);
    canvasRuns.request(UUID.randomUUID());

    WorkClaim claim = issues.claimNext("a", LEASE).orElseThrow();
    assertEquals(first, claim.targetId());
    assertEquals(second, issues.claimNext("b", LEASE).orElseThrow().targetId());
    assertTrue(issues.claimNext("c", LEASE).isEmpty());

    issues.request(first);
    assertTrue(issues.isOwned(claim));
    assertTrue(issues.renew(claim, Duration.ofMinutes(5)));
    assertTrue(issues.find(first).orElseThrow().leaseUntil().isAfter(claim.leaseUntil()));
    assertThrows(
        IllegalArgumentException.class,
        () -> issues.renew(new WorkClaim(first, 1, "intruder", claim.leaseUntil()), LEASE));
  }

  /** 完成匹配当前 wake 时删除；处理期间到达的新 wake 让行释放租约并立即可再领取。 */
  @Test
  void completeDeletesOrReleasesForANewerWake() {
    UUID id = UUID.randomUUID();
    issues.request(id);
    WorkClaim stale = issues.claimNext("first", LEASE).orElseThrow();
    issues.request(id);

    assertTrue(issues.complete(stale).isPresent());
    DurableWork released = issues.find(id).orElseThrow();
    assertNull(released.leaseToken());

    WorkClaim current = issues.claimNext("second", LEASE).orElseThrow();
    assertEquals(2, current.claimedWakeVersion());
    assertTrue(issues.complete(current).isEmpty());
    assertTrue(issues.find(id).isEmpty());
    assertThrows(IllegalStateException.class, () -> issues.complete(current));
  }

  /** 重排按版本决定新 due：版本未变延后，有新 wake 时保留更早的 due；丢失所有权时明确拒绝。 */
  @Test
  void rescheduleDefersOrKeepsTheNewerWake() {
    UUID id = UUID.randomUUID();
    issues.request(id);
    WorkClaim claim = issues.claimNext("lease", LEASE).orElseThrow();
    issues.reschedule(claim, Duration.ofMinutes(10));
    assertTrue(issues.claimNext("next", LEASE).isEmpty());
    assertFalse(issues.isOwned(claim));
    assertThrows(IllegalArgumentException.class, () -> issues.reschedule(claim, Duration.ZERO));

    UUID woken = UUID.randomUUID();
    issues.request(woken);
    WorkClaim busy = issues.claimNext("busy", LEASE).orElseThrow();
    DurableWork wake = issues.request(woken);
    issues.reschedule(busy, Duration.ofMinutes(10));
    assertEquals(wake.availableAt(), issues.find(woken).orElseThrow().availableAt());
  }

  /** 过期租约可被他人重新领取，旧 claim 随即失去全部写权限。 */
  @Test
  void expiredLeaseIsReclaimedAndFencesTheOldOwner() {
    UUID id = UUID.randomUUID();
    issues.request(id);
    WorkClaim old = issues.claimNext("old", Duration.ofMillis(1)).orElseThrow();
    jdbc.sql("select pg_sleep(0.01)").query(String.class).list();

    WorkClaim next = issues.claimNext("new", LEASE).orElseThrow();
    assertNotEquals(old.leaseToken(), next.leaseToken());
    assertFalse(issues.isOwned(old));
    assertThrows(IllegalArgumentException.class, () -> issues.renew(old, LEASE));
    assertThrows(IllegalArgumentException.class, () -> issues.complete(old));
    issues.delete(id);
    assertTrue(issues.find(id).isEmpty());
  }

  /** 行锁 API 必须显式加入外层事务，且不接受错误或过期 token。 */
  @Test
  void lockRequiresTransactionAndRejectsWrongOrExpiredOwner() {
    UUID id = UUID.randomUUID();
    issues.request(id);
    WorkClaim claim = issues.claimNext("owner", LEASE).orElseThrow();
    assertThrows(IllegalStateException.class, () -> issues.lock(id));
    assertThrows(IllegalStateException.class, () -> issues.lockClaimed(claim));
    TransactionTemplate tx = new TransactionTemplate(transactions);
    tx.executeWithoutResult(
        status -> {
          assertFalse(
              issues
                  .lockClaimed(
                      new WorkClaim(id, claim.claimedWakeVersion(), "wrong", claim.leaseUntil()))
                  .isPresent());
          assertTrue(issues.lockClaimed(claim).isPresent());
          assertTrue(issues.lock(id).isPresent());
        });
    jdbc.sql(
            "update durable_work set lease_until = statement_timestamp() - interval '1 second' where target_id = :id")
        .param("id", id)
        .update();
    tx.executeWithoutResult(status -> assertTrue(issues.lockClaimed(claim).isEmpty()));
  }

  /** 独立连接 NOWAIT 证明锁真实存在且持续至提交；竞争 claim 使用 SKIP LOCKED 返回空。 */
  @Test
  void lockHoldsRowUntilTransactionCompletes() throws SQLException {
    UUID id = UUID.randomUUID();
    issues.request(id);
    WorkClaim claim = issues.claimNext("owner", LEASE).orElseThrow();
    try (Connection competitor =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = competitor.createStatement()) {
      String lock =
          "select target_id from durable_work where target_id = '" + id + "' for update nowait";
      new TransactionTemplate(transactions)
          .executeWithoutResult(
              status -> {
                assertTrue(issues.lockClaimed(claim).isPresent());
                SQLException blocked =
                    assertThrows(SQLException.class, () -> statement.executeQuery(lock));
                assertEquals("55P03", blocked.getSQLState());
                // 使用独立连接，不加入持锁事务。
                DurableWorkStore competingStore =
                    new DurableWorkStore(
                        "ISSUE",
                        JdbcClient.create(
                            new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword())),
                        transactions);
                assertTrue(competingStore.claimNext("competitor", LEASE).isEmpty());
              });
      try (var result = statement.executeQuery(lock)) {
        assertTrue(result.next(), "commit must release the ownership lock");
      }
    }
  }

  /** 环境路由：只有 READY、租约有效且 owner 匹配的节点才能 claim 带亲和性的行。 */
  @Test
  void claimEnforcesEnvironmentRouteFence() {
    UUID environmentId = UUID.randomUUID();
    UUID targetId = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    seedEnvironment(environmentId);
    tools.request(targetId, environmentId);

    assertTrue(tools.claimNext("no-node", LEASE).isEmpty());
    assertTrue(tools.claimNext("wrong-node", LEASE, UUID.randomUUID()).isEmpty());

    seedConnection(environmentId, owner, "CONNECTING");
    assertTrue(tools.claimNext("connecting", LEASE, owner).isEmpty());

    seedConnection(environmentId, owner, "READY");
    assertTrue(tools.claimNext("wrong-owner", LEASE, UUID.randomUUID()).isEmpty());

    jdbc.sql(
            "update environment_connection set last_seen_at = statement_timestamp() - interval '2 hours',"
                + " lease_until = statement_timestamp() - interval '1 hour'"
                + " where environment_id = :id")
        .param("id", environmentId)
        .update();
    assertTrue(tools.claimNext("expired", LEASE, owner).isEmpty());

    seedConnection(environmentId, owner, "READY");
    WorkClaim claimed = tools.claimNext("ready", LEASE, owner).orElseThrow();
    assertEquals(targetId, claimed.targetId());
    assertEquals(environmentId, claimed.requiredEnvironmentId());
  }

  /** 请求、完成与重排都在同一事务内发送 payload 为 target type 的提示；完成删除不发送。 */
  @Test
  void notifiesOnWritesThatLeaveOrCreateWork() throws SQLException {
    try (Connection listener = listen()) {
      UUID id = UUID.randomUUID();
      issues.request(id);
      assertEquals(List.of("ISSUE"), notifications(listener));

      issues.request(UUID.randomUUID());
      assertEquals(List.of("ISSUE"), notifications(listener));

      WorkClaim claim = issues.claimNext("lease", LEASE).orElseThrow();
      issues.request(id);
      assertEquals(List.of("ISSUE"), notifications(listener));

      assertTrue(issues.complete(claim).isPresent());
      assertEquals(List.of("ISSUE"), notifications(listener));

      WorkClaim current = issues.claimNext("next", LEASE).orElseThrow();
      issues.reschedule(current, Duration.ZERO);
      assertEquals(List.of("ISSUE"), notifications(listener));

      WorkClaim last = issues.claimNext("last", LEASE).orElseThrow();
      assertTrue(issues.complete(last).isEmpty());
      assertEquals(List.of(), notifications(listener));
    }
  }

  /** 无外层事务时由 DurableWorkJdbc 开启短事务；加入调用方事务后回滚必须同时撤销行与提示。 */
  @Test
  void rollbackRestoresMailboxAndSuppressesNotification() throws SQLException {
    UUID id = UUID.randomUUID();
    try (Connection listener = listen()) {
      assertThrows(
          IllegalStateException.class,
          () ->
              new TransactionTemplate(transactions)
                  .executeWithoutResult(
                      status -> {
                        issues.request(id);
                        throw new IllegalStateException("boom");
                      }));
      assertTrue(issues.find(id).isEmpty());
      assertEquals(List.of(), notifications(listener));
    }
  }

  /** 批量删除只影响当前 target type。 */
  @Test
  void deleteAllRemovesOnlyMatchingTargetTypeRows() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    issues.request(first);
    issues.request(second);
    canvasRuns.request(other);

    assertEquals(2, issues.deleteAll(List.of(first, second)));
    assertTrue(issues.find(first).isEmpty());
    assertTrue(issues.find(second).isEmpty());
    assertTrue(canvasRuns.find(other).isPresent());
    assertEquals(0, issues.deleteAll(List.of()));
  }

  @Test
  void rejectsInvalidArguments() {
    UUID id = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class, () -> new DurableWorkStore(" ", jdbc, transactions));
    assertThrows(
        IllegalArgumentException.class, () -> new DurableWorkStore(null, jdbc, transactions));
    assertThrows(NullPointerException.class, () -> issues.request(null));
    assertThrows(IllegalArgumentException.class, () -> issues.claimNext("lease", Duration.ZERO));
    assertThrows(IllegalArgumentException.class, () -> issues.claimNext(" ", LEASE));
    assertThrows(IllegalArgumentException.class, () -> issues.claimNext(null, LEASE));
    assertThrows(IllegalArgumentException.class, () -> issues.claimNext("x".repeat(129), LEASE));
    WorkClaim claim = new WorkClaim(id, 1L, "lease", Instant.now().plusSeconds(30));
    assertThrows(
        IllegalArgumentException.class, () -> issues.reschedule(claim, Duration.ofMillis(-1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> issues.reschedule(claim, Duration.ofMillis(1).plusNanos(1)));
    assertThrows(IllegalStateException.class, () -> issues.complete(claim));
    assertThrows(IllegalStateException.class, () -> issues.reschedule(claim, Duration.ZERO));
  }

  private static void seedEnvironment(UUID environmentId) {
    jdbc.sql("insert into environment (id, name, registration_token_hash) values (?, ?, ?)")
        .params(environmentId, "env-" + environmentId, "a".repeat(64))
        .update();
  }

  private static void seedConnection(UUID environmentId, UUID owner, String status) {
    jdbc.sql(
            "insert into environment_connection (environment_id, owner_node_id, lease_token,"
                + " status, runtime_info, skill_state, recent_events, last_seen_at, lease_until)"
                + " values (?, ?, ?, ?, '{}'::jsonb, '[]'::jsonb, '[]'::jsonb,"
                + " statement_timestamp(), statement_timestamp() + interval '30 minutes')"
                + " on conflict (environment_id) do update set owner_node_id = excluded.owner_node_id,"
                + " lease_token = excluded.lease_token, status = excluded.status,"
                + " runtime_info = excluded.runtime_info, last_seen_at = excluded.last_seen_at,"
                + " lease_until = excluded.lease_until")
        .params(environmentId, owner, UUID.randomUUID(), status)
        .update();
  }

  private static Connection listen() throws SQLException {
    Connection connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    try (Statement statement = connection.createStatement()) {
      statement.execute("listen " + DurableWorkStore.CHANNEL);
    }
    return connection;
  }

  private static List<String> notifications(Connection listener) throws SQLException {
    PGNotification[] received = listener.unwrap(PGConnection.class).getNotifications(200);
    List<String> payloads = new ArrayList<>();
    if (received != null) {
      for (PGNotification notification : received) {
        payloads.add(notification.getParameter());
      }
    }
    return payloads;
  }
}
