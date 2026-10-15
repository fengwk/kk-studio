package fun.fengwk.kkstudio.platform.plugin.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginAuthAttemptRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginAuthAttemptRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginAuthAttemptJdbcStore;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link PostgresqlPluginAuthAttemptRepository} 的真实 PostgreSQL 契约测试：一个 Plugin 至多一个 pending
 * attempt，消费必须单次且原子。
 *
 * <p>覆盖 upsert 替换使旧 attemptId 立即失效、摘要不命中不消费、过期不可消费、重放不可消费、epoch 前进（revoke/配置变更提交）后不可消费、 并发 complete
 * 只有唯一赢家，以及 DDL 约束与 Java 侧门禁一致。
 */
class PluginAuthAttemptPostgresIntegrationTest {

  private static final String PLUGIN_ID = "static.key.plugin";
  private static final String OTHER_PLUGIN_ID = "other.key.plugin";
  private static final UUID GENERATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID OTHER_GENERATION =
      UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String CONTEXT = "a1".repeat(32);
  private static final String OTHER_CONTEXT = "b1".repeat(32);
  private static final String NONCE_HASH = "b2".repeat(32);
  private static final String OTHER_NONCE_HASH = "c3".repeat(32);
  private static final String REGION = "CN";

  private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

  private static DataSource dataSource;
  private static JdbcClient jdbcClient;

  private PluginAuthAttemptRepository repository;

  @BeforeAll
  static void beforeAll() throws SQLException {
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      PostgresSchemaSupport.resetDatabase(conn);
      PostgresSchemaSupport.applyBaseline(conn);
    }
    dataSource =
        new SimpleDriverDataSource(
            new Driver(),
            PostgresSchemaSupport.POSTGRES.getJdbcUrl(),
            PostgresSchemaSupport.POSTGRES.getUsername(),
            PostgresSchemaSupport.POSTGRES.getPassword());
    jdbcClient = JdbcClient.create(dataSource);
  }

  @BeforeEach
  void setUp() throws SQLException {
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      PostgresSchemaSupport.resetDatabase(conn);
      PostgresSchemaSupport.applyBaseline(conn);
    }
    repository =
        new PostgresqlPluginAuthAttemptRepository(new PluginAuthAttemptJdbcStore(jdbcClient));
    seedCatalog(1L, 1L, GENERATION, CONTEXT);
  }

  /** 直接在权威表里种一份 epoch 与 manifest 历史：消费语句会与它逐字段比对。 */
  private static void seedCatalog(long epoch, long revision, UUID generation, String context) {
    // 先写该 epoch 的不可变 manifest 历史，再让 state 指针指向它（外键在语句执行时立即校验）。
    jdbcClient
        .sql(
            "insert into plugin_catalog_revision (epoch, manifest_json)"
                + " values (:epoch, cast(:manifest as jsonb))"
                + " on conflict (epoch) do update set manifest_json = excluded.manifest_json")
        .param("epoch", epoch)
        .param("manifest", manifestJson(revision, generation, context))
        .update();
    jdbcClient
        .sql("update plugin_catalog_state set epoch = :epoch where id = 1")
        .param("epoch", epoch)
        .update();
  }

  private static String manifestJson(long revision, UUID generation, String context) {
    return "{\"plugins\":{\""
        + PLUGIN_ID
        + "\":"
        + entryJson(revision, generation, context)
        + ",\""
        + OTHER_PLUGIN_ID
        + "\":"
        + entryJson(revision, generation, context)
        + "},\"catalogFingerprint\":\""
        + "e".repeat(64)
        + "\"}";
  }

  private static String entryJson(long revision, UUID generation, String context) {
    return "{\"configRevision\":"
        + revision
        + ",\"pluginCodeVersion\":\"1.0\",\"schemaFingerprint\":\""
        + "a".repeat(64)
        + "\",\"capabilityFingerprint\":\""
        + "b".repeat(64)
        + "\",\"authGeneration\":\""
        + generation
        + "\",\"contextFingerprint\":\""
        + context
        + "\"}";
  }

  /** Flyway V1 必须留下空的 attempt 表：没有待完成认证时不存在任何行。 */
  @Test
  void baselineTableStartsEmpty() {
    assertEquals(0, countAttemptRows());
    assertTrue(repository.find(PLUGIN_ID).isEmpty());
  }

  /** 新的 prepare 直接替换旧 attempt：存储里永远只有一行，且旧 nonce 绝不可能再被消费。 */
  @Test
  void replaceKeepsExactlyOnePendingAttemptAndInvalidatesTheOldNonce() {
    repository.replace(
        attempt(PLUGIN_ID, NONCE_HASH, GENERATION, CONTEXT, 1L, NOW.plusSeconds(600)));

    seedCatalog(1L, 2L, OTHER_GENERATION, CONTEXT);
    repository.replace(
        attempt(PLUGIN_ID, OTHER_NONCE_HASH, OTHER_GENERATION, CONTEXT, 2L, NOW.plusSeconds(600)));

    assertEquals(1, countAttemptRows(), "a new prepare must replace, not accumulate");
    PluginAuthAttemptRow pending = repository.find(PLUGIN_ID).orElseThrow();
    assertEquals(OTHER_NONCE_HASH, pending.nonceHash());
    assertEquals(OTHER_GENERATION, pending.authGeneration());
    assertEquals(2L, pending.configRevision());
    assertTrue(
        repository.consume(PLUGIN_ID, NONCE_HASH, NOW, 1L).isEmpty(),
        "the superseded attempt must not be consumable");
    assertEquals(1, countAttemptRows(), "a rejected consume must not delete the pending attempt");
  }

  /** attempt 按 pluginId 隔离：一个 Plugin 的 prepare 不能替换或消费另一个 Plugin 的 attempt。 */
  @Test
  void attemptsAreIsolatedPerPlugin() {
    repository.replace(
        attempt(PLUGIN_ID, NONCE_HASH, GENERATION, CONTEXT, 1L, NOW.plusSeconds(600)));
    repository.replace(
        attempt(OTHER_PLUGIN_ID, OTHER_NONCE_HASH, GENERATION, CONTEXT, 1L, NOW.plusSeconds(600)));

    assertTrue(repository.consume(OTHER_PLUGIN_ID, NONCE_HASH, NOW, 1L).isEmpty());
    assertTrue(repository.find(PLUGIN_ID).isPresent());
    assertTrue(
        repository.consume(PLUGIN_ID, NONCE_HASH, NOW, 1L).isPresent(),
        "each plugin keeps its own single attempt");
    assertTrue(repository.find(OTHER_PLUGIN_ID).isPresent());
  }

  /** 消费是单次语义：命中即删除，重放只能得到空结果。 */
  @Test
  void consumeIsSingleShot() {
    repository.replace(
        attempt(PLUGIN_ID, NONCE_HASH, GENERATION, CONTEXT, 1L, NOW.plusSeconds(600)));

    PluginAuthAttemptRow consumed =
        repository.consume(PLUGIN_ID, NONCE_HASH, NOW, 1L).orElseThrow();

    assertEquals(PLUGIN_ID, consumed.pluginId());
    assertEquals(NONCE_HASH, consumed.nonceHash());
    assertEquals(GENERATION, consumed.authGeneration());
    assertEquals(CONTEXT, consumed.contextFingerprint());
    assertEquals(1L, consumed.configRevision());
    assertEquals(NOW.plusSeconds(600), consumed.expiresAt());
    assertEquals(0, countAttemptRows(), "a consumed attempt must be deleted");
    assertTrue(repository.consume(PLUGIN_ID, NONCE_HASH, NOW, 1L).isEmpty(), "replay must fail");
    assertTrue(repository.find(PLUGIN_ID).isEmpty());
  }

  /** 摘要不命中：不消费、不删除，pending attempt 原样保留。 */
  @Test
  void consumeRejectsMismatchedNonceWithoutDeleting() {
    repository.replace(
        attempt(PLUGIN_ID, NONCE_HASH, GENERATION, CONTEXT, 1L, NOW.plusSeconds(600)));

    assertTrue(repository.consume(PLUGIN_ID, OTHER_NONCE_HASH, NOW, 1L).isEmpty());

    assertEquals(1, countAttemptRows());
    assertEquals(NONCE_HASH, repository.find(PLUGIN_ID).orElseThrow().nonceHash());
  }

  /** 过期 attempt 不可消费：边界时刻（expires_at == now）也必须拒绝。 */
  @Test
  void consumeRejectsExpiredAttemptAtTheBoundary() {
    repository.replace(attempt(PLUGIN_ID, NONCE_HASH, GENERATION, CONTEXT, 1L, NOW));

    assertTrue(repository.consume(PLUGIN_ID, NONCE_HASH, NOW, 1L).isEmpty());
    assertEquals(1, countAttemptRows(), "an expired attempt is rejected, not silently consumed");

    repository.replace(attempt(PLUGIN_ID, NONCE_HASH, GENERATION, CONTEXT, 1L, NOW.plusMillis(1)));
    assertTrue(repository.consume(PLUGIN_ID, NONCE_HASH, NOW, 1L).isPresent());
  }

  /** 并发 complete：多个线程同时消费同一 nonce，只能有唯一赢家，其余全部得到空结果。 */
  @Test
  void concurrentConsumeHasExactlyOneWinner() throws Exception {
    repository.replace(
        attempt(PLUGIN_ID, NONCE_HASH, GENERATION, CONTEXT, 1L, NOW.plusSeconds(600)));

    int contenders = 8;
    ExecutorService pool = Executors.newFixedThreadPool(contenders);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger winners = new AtomicInteger();
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int index = 0; index < contenders; index++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  if (repository.consume(PLUGIN_ID, NONCE_HASH, NOW, 1L).isPresent()) {
                    winners.incrementAndGet();
                  }
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertEquals(1, winners.get(), "exactly one concurrent complete may consume the attempt");
    assertEquals(0, countAttemptRows());
  }

  /** epoch 前进或坐标漂移（revoke / 配置变更 / 重新登录提交）之后，pending attempt 绝不可再被消费。 */
  @Test
  void pendingAttemptIsNotConsumableAfterTheCatalogMovedOn() {
    repository.replace(
        attempt(PLUGIN_ID, NONCE_HASH, GENERATION, CONTEXT, 1L, NOW.plusSeconds(600)));

    // 另一 writer 提交了 revoke：epoch 前进，该 Plugin 的代际/上下文/修订都变化。
    seedCatalog(2L, 2L, OTHER_GENERATION, OTHER_CONTEXT);

    assertTrue(
        repository.consume(PLUGIN_ID, NONCE_HASH, NOW, 1L).isEmpty(),
        "a stale epoch must not consume the attempt");
    assertTrue(
        repository.consume(PLUGIN_ID, NONCE_HASH, NOW, 2L).isEmpty(),
        "the attempt's prepared coordinates no longer match the manifest");
    assertTrue(
        repository.find(PLUGIN_ID).isPresent(),
        "rejected consumption must leave the pending attempt intact");
  }

  /** 负的期望 epoch 是被拒绝的非法入参，不会退化成「无条件消费」。 */
  @Test
  void negativeExpectedEpochIsRejected() {
    repository.replace(
        attempt(PLUGIN_ID, NONCE_HASH, GENERATION, CONTEXT, 1L, NOW.plusSeconds(600)));

    assertThrows(
        IllegalArgumentException.class, () -> repository.consume(PLUGIN_ID, NONCE_HASH, NOW, -1L));
    assertEquals(1, countAttemptRows());
  }

  /** DDL 约束必须与 Java 侧门禁同一身份/范围，不允许两边不一致。 */
  @Test
  void constraintsMatchTheJavaBounds() throws SQLException {
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      PostgresSchemaSupport.assertTransactionConstraintViolation(
          conn,
          "ck_plugin_auth_attempt_plugin_id",
          () -> insertRaw(conn, "Static.Key.Plugin", NONCE_HASH, CONTEXT, 1L, REGION, NOW));
      PostgresSchemaSupport.assertTransactionConstraintViolation(
          conn,
          "ck_plugin_auth_attempt_nonce_hash",
          () -> insertRaw(conn, PLUGIN_ID, "XYZ", CONTEXT, 1L, REGION, NOW));
      PostgresSchemaSupport.assertTransactionConstraintViolation(
          conn,
          "ck_plugin_auth_attempt_context_fingerprint",
          () -> insertRaw(conn, PLUGIN_ID, NONCE_HASH, "XYZ", 1L, REGION, NOW));
      PostgresSchemaSupport.assertTransactionConstraintViolation(
          conn,
          "ck_plugin_auth_attempt_config_revision",
          () -> insertRaw(conn, PLUGIN_ID, NONCE_HASH, CONTEXT, 0L, REGION, NOW));
      PostgresSchemaSupport.assertTransactionConstraintViolation(
          conn,
          "ck_plugin_auth_attempt_region",
          () -> insertRaw(conn, PLUGIN_ID, NONCE_HASH, CONTEXT, 1L, " CN ", NOW));
      PostgresSchemaSupport.assertTransactionConstraintViolation(
          conn,
          "ck_plugin_auth_attempt_region",
          () -> insertRaw(conn, PLUGIN_ID, NONCE_HASH, CONTEXT, 1L, "", NOW));
    }
    assertEquals(0, countAttemptRows(), "rejected inserts must not leave rows behind");
  }

  /** 主键是「每个 Plugin 至多一个 pending attempt」的数据库围栏：直接插入第二行必须被拒绝。 */
  @Test
  void primaryKeyForbidsASecondPendingAttempt() throws SQLException {
    repository.replace(
        attempt(PLUGIN_ID, NONCE_HASH, GENERATION, CONTEXT, 1L, NOW.plusSeconds(600)));

    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      PostgresSchemaSupport.assertTransactionConstraintViolation(
          conn,
          "pk_plugin_auth_attempt",
          () -> insertRaw(conn, PLUGIN_ID, OTHER_NONCE_HASH, CONTEXT, 1L, REGION, NOW));
    }

    assertEquals(1, countAttemptRows());
    assertEquals(
        NONCE_HASH,
        repository.find(PLUGIN_ID).orElseThrow().nonceHash(),
        "the rejected insert must not replace the pending attempt");
  }

  private static PluginAuthAttemptRow attempt(
      String pluginId,
      String nonceHash,
      UUID generation,
      String contextFingerprint,
      long configRevision,
      Instant expiresAt) {
    return new PluginAuthAttemptRow(
        pluginId, nonceHash, generation, contextFingerprint, configRevision, REGION, expiresAt);
  }

  private static void insertRaw(
      Connection conn,
      String pluginId,
      String nonceHash,
      String contextFingerprint,
      long configRevision,
      String region,
      Instant expiresAt)
      throws SQLException {
    try (Statement statement = conn.createStatement()) {
      statement.execute(
          "insert into plugin_auth_attempt"
              + " (plugin_id, nonce_hash, auth_generation, context_fingerprint,"
              + " config_revision, region, expires_at) values ('"
              + pluginId
              + "', '"
              + nonceHash
              + "', '"
              + GENERATION
              + "', '"
              + contextFingerprint
              + "', "
              + configRevision
              + ", '"
              + region
              + "', '"
              + expiresAt
              + "')");
    }
  }

  private static int countAttemptRows() {
    return jdbcClient.sql("select count(*) from plugin_auth_attempt").query(Integer.class).single();
  }
}
