package fun.fengwk.kkstudio.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** {@link DurableWorkQueue} 对真实 PostgreSQL 的 wake、租约围栏与到期提示契约。 */
class DurableWorkQueueIntegrationTest {

  private static final Duration LEASE = Duration.ofSeconds(30);

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("kk_studio_work_test");

  private static JdbcClient jdbc;
  private static DataSourceTransactionManager transactions;

  private DurableWorkQueue issues;
  private DurableWorkQueue canvasRuns;

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
    issues = new DurableWorkQueue(jdbc, transactions, "ISSUE");
    canvasRuns = new DurableWorkQueue(jdbc, transactions, "CANVAS_RUN");
  }

  /** 重复请求递增 wakeVersion 并保留更早的 due；不同 target type 互不可见。 */
  @Test
  void requestMergesWakesAndKeepsTheEarlierDue() {
    UUID id = UUID.randomUUID();
    DurableWork later = issues.request(id, Duration.ofMinutes(10));
    DurableWork earlier = issues.request(id, Duration.ofSeconds(2));
    DurableWork merged = issues.request(id, Duration.ofMinutes(20));

    assertEquals(1, later.wakeVersion());
    assertEquals(3, merged.wakeVersion());
    assertTrue(earlier.availableAt().isBefore(later.availableAt()));
    assertEquals(earlier.availableAt(), merged.availableAt());
    assertTrue(issues.claimNext("early", LEASE).isEmpty(), "future work is not claimable");
    assertTrue(canvasRuns.find(id).isEmpty());
    assertEquals("ISSUE", issues.targetType());
  }

  /** 已到期行只能被一个 worker 领取；租约有效期间新 wake 不影响所有权。 */
  @Test
  void claimHonoursLeasesAndOrdersByDue() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    issues.request(first, Duration.ZERO);
    issues.request(second, Duration.ZERO);
    canvasRuns.request(UUID.randomUUID(), Duration.ZERO);

    WorkClaim claim = issues.claimNext("a", LEASE).orElseThrow();
    assertEquals(first, claim.targetId());
    assertEquals(second, issues.claimNext("b", LEASE).orElseThrow().targetId());
    assertTrue(issues.claimNext("c", LEASE).isEmpty());

    issues.request(first, Duration.ZERO);
    assertTrue(issues.isOwned(claim));
    assertTrue(issues.renew(claim, Duration.ofMinutes(5)));
    assertTrue(issues.find(first).orElseThrow().leaseUntil().isAfter(claim.leaseUntil()));
    assertFalse(issues.renew(new WorkClaim(first, 1, "intruder", claim.leaseUntil()), LEASE));
  }

  /** 完成匹配当前 wake 时删除；处理期间到达的新 wake 让行释放并立即可再领取。 */
  @Test
  void completeDeletesOrReleasesForANewerWake() {
    UUID id = UUID.randomUUID();
    issues.request(id, Duration.ZERO);
    WorkClaim stale = issues.claimNext("first", LEASE).orElseThrow();
    issues.request(id, Duration.ofMinutes(5));

    assertFalse(issues.complete(stale));
    DurableWork released = issues.find(id).orElseThrow();
    assertEquals(null, released.leaseToken());

    WorkClaim current = issues.claimNext("second", LEASE).orElseThrow();
    assertEquals(2, current.wakeVersion());
    assertTrue(issues.complete(current));
    assertTrue(issues.find(id).isEmpty());
    assertFalse(issues.complete(current), "a lost claim writes nothing");
  }

  /** 重排按版本决定新 due：版本未变延后，有新 wake 时保留更早的 due。 */
  @Test
  void rescheduleDefersOrKeepsTheNewerWake() {
    UUID id = UUID.randomUUID();
    issues.request(id, Duration.ZERO);
    WorkClaim claim = issues.claimNext("lease", LEASE).orElseThrow();
    assertTrue(issues.reschedule(claim, Duration.ofMinutes(10)));
    assertTrue(issues.claimNext("next", LEASE).isEmpty());
    assertFalse(issues.isOwned(claim));
    assertFalse(issues.reschedule(claim, Duration.ZERO), "a released claim cannot reschedule");

    UUID woken = UUID.randomUUID();
    issues.request(woken, Duration.ZERO);
    WorkClaim busy = issues.claimNext("busy", LEASE).orElseThrow();
    DurableWork wake = issues.request(woken, Duration.ZERO);
    assertTrue(issues.reschedule(busy, Duration.ofMinutes(10)));
    assertEquals(wake.availableAt(), issues.find(woken).orElseThrow().availableAt());
  }

  /** 过期租约可被他人重新领取，旧 claim 随即失去全部写权限。 */
  @Test
  void expiredLeaseIsReclaimedAndFencesTheOldOwner() {
    UUID id = UUID.randomUUID();
    issues.request(id, Duration.ZERO);
    WorkClaim old = issues.claimNext("old", Duration.ofMillis(1)).orElseThrow();
    jdbc.sql("select pg_sleep(0.01)").query(String.class).list();

    WorkClaim next = issues.claimNext("new", LEASE).orElseThrow();
    assertNotEquals(old.leaseToken(), next.leaseToken());
    assertFalse(issues.isOwned(old));
    assertFalse(issues.renew(old, LEASE));
    assertFalse(issues.complete(old));
    issues.delete(id);
    assertTrue(issues.find(id).isEmpty());
  }

  /** 持锁 API 必须显式加入外层事务，且不接受错误或过期 token。 */
  @Test
  void lockOwnedRequiresTransactionAndRejectsWrongOrExpiredOwner() {
    UUID id = UUID.randomUUID();
    issues.request(id, Duration.ZERO);
    WorkClaim claim = issues.claimNext("owner", LEASE).orElseThrow();
    assertThrows(IllegalStateException.class, () -> issues.lockOwned(claim));
    TransactionTemplate tx = new TransactionTemplate(transactions);
    tx.executeWithoutResult(
        status -> {
          assertFalse(
              issues.lockOwned(
                  new WorkClaim(id, claim.wakeVersion(), "wrong", claim.leaseUntil())));
          assertTrue(issues.lockOwned(claim));
        });
    jdbc.sql(
            "update durable_work set lease_until = statement_timestamp() - interval '1 second' where target_id = :id")
        .param("id", id)
        .update();
    tx.executeWithoutResult(status -> assertFalse(issues.lockOwned(claim)));
  }

  /** 独立连接 NOWAIT 证明锁真实存在且持续至提交；竞争 claim 使用 SKIP LOCKED 返回空。 */
  @Test
  void lockOwnedHoldsRowUntilTransactionCompletes() throws SQLException {
    UUID id = UUID.randomUUID();
    issues.request(id, Duration.ZERO);
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
                assertTrue(issues.lockOwned(claim));
                SQLException blocked =
                    assertThrows(SQLException.class, () -> statement.executeQuery(lock));
                assertEquals("55P03", blocked.getSQLState());
                // 使用独立连接，不加入持锁事务。
                DurableWorkQueue competingQueue =
                    new DurableWorkQueue(
                        JdbcClient.create(
                            new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword())),
                        transactions,
                        "ISSUE");
                assertTrue(competingQueue.claimNext("competitor", LEASE).isEmpty());
              });
      try (var result = statement.executeQuery(lock)) {
        assertTrue(result.next(), "commit must release the ownership lock");
      }
    }
  }

  /** 只有让行变为「到期且无有效租约」的写入才发送提示，payload 是 target type。 */
  @Test
  void notifiesOnlyWhenWorkBecomesClaimable() throws SQLException {
    try (Connection listener = listen()) {
      UUID id = UUID.randomUUID();
      issues.request(id, Duration.ZERO);
      assertEquals(List.of("ISSUE"), notifications(listener));

      issues.request(UUID.randomUUID(), Duration.ofMinutes(5));
      WorkClaim claim = issues.claimNext("lease", LEASE).orElseThrow();
      issues.request(id, Duration.ZERO);
      assertEquals(List.of(), notifications(listener));

      assertFalse(issues.complete(claim));
      assertEquals(List.of("ISSUE"), notifications(listener));
    }
  }

  @Test
  void rejectsInvalidArguments() {
    UUID id = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class, () -> new DurableWorkQueue(jdbc, transactions, " "));
    assertThrows(
        IllegalArgumentException.class, () -> new DurableWorkQueue(jdbc, transactions, null));
    assertThrows(IllegalArgumentException.class, () -> issues.request(id, Duration.ofMillis(-1)));
    assertThrows(NullPointerException.class, () -> issues.request(id, null));
    assertThrows(IllegalArgumentException.class, () -> issues.claimNext("lease", Duration.ZERO));
    assertThrows(IllegalArgumentException.class, () -> issues.claimNext(" ", LEASE));
    assertThrows(IllegalArgumentException.class, () -> issues.claimNext(null, LEASE));
    assertThrows(IllegalArgumentException.class, () -> issues.claimNext("x".repeat(129), LEASE));
  }

  private static Connection listen() throws SQLException {
    Connection connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    try (Statement statement = connection.createStatement()) {
      statement.execute("listen " + DurableWorkQueue.CHANNEL);
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
