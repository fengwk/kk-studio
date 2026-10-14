package fun.fengwk.kkstudio.platform.plugin.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.model.PluginConfigurationRowDO;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginConfigurationJdbcStore;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link PostgresqlPluginConfigurationRepository} 的真实 PostgreSQL 契约测试。
 *
 * <p>覆盖 epoch CAS、同事务权威基线防篡改（未 changed 条目不得偷退到旧 revision、既有 pluginId 不得消失）、提交后校验失败的整事务回滚、 {@code
 * plugin_catalog_changed} 仅在成功提交时投递、并发 CAS 唯一赢家、被篡改 manifest 的读取拒绝，以及 DDL 约束与 Java 门禁的同一身份/范围。
 */
class PostgresqlPluginConfigurationRepositoryIntegrationTest {

  private static final String ALPHA = "alpha.plugin";
  private static final String BETA = "beta.plugin";
  private static final String SCHEMA_ALPHA = "a".repeat(64);
  private static final String CAPABILITY_ALPHA = "b".repeat(64);
  private static final String SCHEMA_BETA = "c".repeat(64);
  private static final String CAPABILITY_BETA = "d".repeat(64);
  private static final String CATALOG_FINGERPRINT = "e".repeat(64);

  private static DataSource dataSource;
  private static JdbcClient jdbcClient;

  private TransactionTemplate transactionTemplate;
  private PostgresqlPluginConfigurationRepository repository;
  private PostgresqlPluginCatalogChangeNotifier notifier;

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
    transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    notifier = new PostgresqlPluginCatalogChangeNotifier(new JdbcTemplate(dataSource));
    repository =
        new PostgresqlPluginConfigurationRepository(
            new PluginConfigurationJdbcStore(jdbcClient), notifier);
  }

  /** Flyway V1 必须留下唯一 epoch=0 的未初始化权威单行。 */
  @Test
  void baselineRowStartsUninitialized() {
    assertEquals(1, countCatalogRows());
    var catalog = repository.readCatalog();
    assertNotNull(catalog);
    assertEquals(0, catalog.epoch());
    assertTrue(catalog.manifest().plugins().isEmpty());
    assertNull(catalog.manifest().catalogFingerprint());
  }

  @Test
  void commitAdvancesEpochAndWritesImmutableHistory() {
    assertTrue(commit(0, List.of(row(ALPHA, 1)), manifest(ALPHA, entry(ALPHA, 1))));

    assertEquals(1, repository.readCatalog().epoch());
    var stored = repository.readConfiguration(ALPHA, 1).orElseThrow();
    assertEquals(ALPHA, stored.pluginId());
    assertEquals(1, stored.revision());
    assertEquals("1.0", stored.codeVersion());
    assertEquals(SCHEMA_ALPHA, stored.schemaFingerprint());
    assertEquals(CAPABILITY_ALPHA, stored.capabilityFingerprint());
    assertEquals("{\"enabled\":true}", stored.valuesJson(), "values must round-trip exactly");
    assertEquals(1, countHistoryRows());

    // 同一 expected epoch 再次提交：CAS 未命中，不写历史、不推进 epoch。
    assertFalse(commit(0, List.of(row(ALPHA, 1)), manifest(ALPHA, entry(ALPHA, 1))));
    assertEquals(1, repository.readCatalog().epoch());
    assertEquals(1, countHistoryRows());
  }

  /** A 把 ALPHA 更新到 revision 2 后，B 基于旧 A 条目（revision 1）构造 manifest 且 expectedEpoch 是新值：必须拒绝。 */
  @Test
  void commitRejectsStaleUnchangedEntryAgainstAuthoritativeBaseline() {
    assertTrue(commit(0, List.of(row(ALPHA, 1)), manifest(ALPHA, entry(ALPHA, 1))));
    assertTrue(commit(1, List.of(row(ALPHA, 2)), manifest(ALPHA, entry(ALPHA, 2))));

    assertThrows(
        IllegalStateException.class, () -> commit(2, List.of(), manifest(ALPHA, entry(ALPHA, 1))));

    assertEquals(2, repository.readCatalog().epoch(), "rejected commit must not advance the epoch");
    assertEquals(2, countHistoryRows(), "rejected commit must not write history");
    assertEquals(2, repository.readCatalog().manifest().plugins().get(ALPHA).configRevision());
  }

  /** 既有 pluginId 一个都不能无故消失。 */
  @Test
  void commitRejectsDroppedPluginId() {
    assertTrue(commit(0, List.of(row(ALPHA, 1)), manifest(ALPHA, entry(ALPHA, 1))));

    assertThrows(
        IllegalStateException.class,
        () -> commit(1, List.of(), new PluginCatalogManifest(Map.of(), CATALOG_FINGERPRINT)));

    assertEquals(1, repository.readCatalog().epoch());
    assertNotNull(repository.readCatalog().manifest().plugins().get(ALPHA));
  }

  /** 本轮未 changed 的条目必须与基线逐字段相等。 */
  @Test
  void commitRejectsDriftedUnchangedIdentity() {
    assertTrue(commit(0, List.of(row(ALPHA, 1)), manifest(ALPHA, entry(ALPHA, 1))));

    assertThrows(
        IllegalStateException.class,
        () ->
            commit(
                1,
                List.of(),
                manifest(
                    ALPHA,
                    new PluginCatalogManifest.Entry(1, "9.9", SCHEMA_ALPHA, CAPABILITY_ALPHA))));

    assertEquals(1, repository.readCatalog().epoch());
    assertEquals("1.0", repository.readConfiguration(ALPHA, 1).orElseThrow().codeVersion());
  }

  /** 提交后校验失败（manifest 引用了不存在的历史）必须整事务回滚，epoch 不推进、不留部分写入。 */
  @Test
  void commitRollsBackWhenManifestReferenceIsMissing() throws Exception {
    assertTrue(commit(0, List.of(row(ALPHA, 1)), manifest(ALPHA, entry(ALPHA, 1))));
    deleteHistory(ALPHA, 1);

    try (Connection listener = PostgresSchemaSupport.newConnection()) {
      listener.setAutoCommit(true);
      try (Statement statement = listener.createStatement()) {
        statement.execute("listen " + PostgresqlPluginCatalogChangeNotifier.CHANNEL);
      }
      assertThrows(
          IllegalStateException.class,
          () ->
              commit(
                  1,
                  List.of(row(BETA, 2)),
                  new PluginCatalogManifest(
                      Map.of(ALPHA, entry(ALPHA, 1), BETA, entry(BETA, 2)), CATALOG_FINGERPRINT)));
      assertEquals(
          0,
          listener.unwrap(PGConnection.class).getNotifications(300).length,
          "rolled-back changes must not notify");
    }

    assertEquals(1, repository.readCatalog().epoch(), "rollback must restore the epoch");
    assertEquals(0, countHistoryRows(), "rollback must not leave a partial history row");
    assertTrue(repository.readConfiguration(BETA, 2).isEmpty());
  }

  /** 新安装条目必须由本次新增历史支撑，不能借旁路孤立记录跳过配置修订提交。 */
  @Test
  void commitRejectsNewPluginReferencingAnOrphanedOldRevision() throws SQLException {
    assertTrue(commit(0, List.of(row(ALPHA, 1)), manifest(ALPHA, entry(ALPHA, 1))));
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      insertRaw(conn, BETA, 1, "1.0", SCHEMA_BETA, CAPABILITY_BETA, "{}");
    }

    assertThrows(
        IllegalStateException.class,
        () ->
            commit(
                1,
                List.of(),
                new PluginCatalogManifest(
                    Map.of(ALPHA, entry(ALPHA, 1), BETA, entry(BETA, 1)), CATALOG_FINGERPRINT)));
    assertEquals(1, repository.readCatalog().epoch());
    assertEquals(2, countHistoryRows());
  }

  /** 通知只在成功提交时投递，payload 是写后权威 epoch 的十进制字符串。 */
  @Test
  void notificationIsDeliveredOnlyForCommittedChanges() throws Exception {
    try (Connection listener = PostgresSchemaSupport.newConnection()) {
      listener.setAutoCommit(true);
      try (Statement statement = listener.createStatement()) {
        statement.execute("listen " + PostgresqlPluginCatalogChangeNotifier.CHANNEL);
      }
      PGConnection pg = listener.unwrap(PGConnection.class);

      // epoch 不匹配：返回 false，既不插入也不通知。
      assertFalse(commit(5, List.of(row(ALPHA, 6)), manifest(ALPHA, entry(ALPHA, 6))));
      assertEquals(0, pg.getNotifications(300).length, "a non-committed attempt must not notify");

      assertTrue(commit(0, List.of(row(ALPHA, 1)), manifest(ALPHA, entry(ALPHA, 1))));
      PGNotification notification = awaitNotification(pg, "1");
      assertEquals(PostgresqlPluginCatalogChangeNotifier.CHANNEL, notification.getName());
    }
  }

  /** 无活跃事务时严禁发出失效信号。 */
  @Test
  void notificationRequiresAnActiveTransaction() {
    assertThrows(IllegalStateException.class, () -> notifier.catalogChanged(1));
  }

  /** 并发提交同一 expected epoch：SQL CAS 是最终围栏，只有一个赢家，且只有赢家的历史落库。 */
  @Test
  void concurrentCommitHasExactlyOneWinner() throws Exception {
    Map<String, PluginConfigurationRow> alphaCandidate = Map.of(ALPHA, row(ALPHA, 1));
    Map<String, PluginConfigurationRow> betaCandidate = Map.of(BETA, row(BETA, 1));
    AtomicInteger wins = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (String pluginId : List.of(ALPHA, BETA)) {
        Map<String, PluginConfigurationRow> candidate =
            ALPHA.equals(pluginId) ? alphaCandidate : betaCandidate;
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  if (commit(
                      0, List.copyOf(candidate.values()), manifest(pluginId, entry(pluginId, 1)))) {
                    wins.incrementAndGet();
                  }
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }

    assertEquals(1, wins.get(), "exactly one concurrent commit may win the CAS");
    assertEquals(1, repository.readCatalog().epoch());
    assertEquals(1, countHistoryRows(), "only the winner's history may be persisted");
  }

  /** 结构非法的 manifest 必须读取失败且只带固定消息。 */
  @Test
  void readCatalogRejectsStructurallyInvalidManifest() {
    tamperManifest("{\"plugins\":[],\"catalogFingerprint\":null}", 1);

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> repository.readCatalog());
    assertEquals("stored plugin catalog manifest is invalid", error.getMessage());
  }

  /** entry revision 领先 epoch 说明记录已损坏，绝不能静默接受。 */
  @Test
  void readCatalogRejectsEntryRevisionBeyondEpoch() {
    tamperManifest(manifestJson(ALPHA, 6), 5);

    assertThrows(IllegalArgumentException.class, () -> repository.readCatalog());
  }

  /** 权威单行缺失时读取必须返回 null，绝不合成默认记录。 */
  @Test
  void readCatalogReturnsNullWhenTheAuthoritativeRowIsMissing() {
    jdbcClient.sql("delete from plugin_catalog_state where id = 1").update();

    assertNull(repository.readCatalog());
  }

  /** CAS 在基线通过后仍返回 0 行时必须返回 false，绝不插入历史、绝不发通知。 */
  @Test
  void commitReturnsFalseWhenCasAffectsNoRow() {
    PostgresqlPluginConfigurationRepository stubRepository =
        new PostgresqlPluginConfigurationRepository(
            new PluginConfigurationJdbcStore(jdbcClient) {
              @Override
              public int compareAndSetEpoch(long expectedEpoch, String manifestJson) {
                return 0;
              }
            },
            notifier);

    assertFalse(
        commit(stubRepository, 0, List.of(row(ALPHA, 1)), manifest(ALPHA, entry(ALPHA, 1))));
    assertEquals(0, repository.readCatalog().epoch());
    assertEquals(0, countHistoryRows());
  }

  /** 历史插入未落行是内部一致性错误，必须显式失败并整事务回滚。 */
  @Test
  void commitFailsWhenHistoryInsertAffectsNoRow() {
    PostgresqlPluginConfigurationRepository stubRepository =
        new PostgresqlPluginConfigurationRepository(
            new PluginConfigurationJdbcStore(jdbcClient) {
              @Override
              public int insertRevision(PluginConfigurationRowDO row) {
                return 0;
              }
            },
            notifier);

    assertThrows(
        IllegalStateException.class,
        () -> commit(stubRepository, 0, List.of(row(ALPHA, 1)), manifest(ALPHA, entry(ALPHA, 1))));
    assertEquals(0, repository.readCatalog().epoch(), "failed insert must roll the CAS back");
    assertEquals(0, countHistoryRows());
  }

  /** Java 侧 changed↔manifest 一致性门禁：逐条非法组合都必须固定拒绝。 */
  @Test
  void commitRejectsInconsistentChangedConfigurations() {
    // changed revision 必须等于写后 epoch
    assertThrows(
        IllegalArgumentException.class,
        () -> commit(0, List.of(row(ALPHA, 5)), manifest(ALPHA, entry(ALPHA, 5))));
    // changed 不能重复 pluginId
    assertThrows(
        IllegalArgumentException.class,
        () -> commit(0, List.of(row(ALPHA, 1), row(ALPHA, 1)), manifest(ALPHA, entry(ALPHA, 1))));
    // changed 必须在 manifest 里有同 revision 的条目
    assertThrows(
        IllegalArgumentException.class,
        () -> commit(0, List.of(row(ALPHA, 1)), manifest(BETA, entry(BETA, 1))));
    // changed 身份必须与 manifest 条目逐字段一致
    assertThrows(
        IllegalArgumentException.class,
        () ->
            commit(
                0,
                List.of(row(ALPHA, 1)),
                manifest(
                    ALPHA,
                    new PluginCatalogManifest.Entry(1, "9.9", SCHEMA_ALPHA, CAPABILITY_ALPHA))));
    // manifest 不能声明没有 changed 支撑的新 revision
    assertThrows(
        IllegalArgumentException.class,
        () -> commit(0, List.of(), manifest(ALPHA, entry(ALPHA, 1))));

    assertEquals(0, repository.readCatalog().epoch());
    assertEquals(0, countHistoryRows());
  }

  /** DDL 约束必须与 Java 侧门禁同一身份/范围，不允许两边不一致。 */
  @Test
  void historyConstraintsMatchTheJavaBounds() throws SQLException {
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      PostgresSchemaSupport.assertTransactionConstraintViolation(
          conn,
          "ck_plugin_configuration_revision_plugin_id",
          () -> insertRaw(conn, "Alpha.Plugin", 1, "1.0", SCHEMA_ALPHA, CAPABILITY_ALPHA, "{}"));
      PostgresSchemaSupport.assertTransactionConstraintViolation(
          conn,
          "ck_plugin_configuration_revision_revision",
          () -> insertRaw(conn, ALPHA, 0, "1.0", SCHEMA_ALPHA, CAPABILITY_ALPHA, "{}"));
      PostgresSchemaSupport.assertTransactionConstraintViolation(
          conn,
          "ck_plugin_configuration_revision_schema_fingerprint",
          () -> insertRaw(conn, ALPHA, 1, "1.0", "XYZ", CAPABILITY_ALPHA, "{}"));
      PostgresSchemaSupport.assertTransactionConstraintViolation(
          conn,
          "ck_plugin_configuration_revision_values_object",
          () -> insertRaw(conn, ALPHA, 1, "1.0", SCHEMA_ALPHA, CAPABILITY_ALPHA, "[]"));
      PostgresSchemaSupport.assertTransactionConstraintViolation(
          conn,
          "ck_plugin_configuration_revision_values_object",
          () ->
              insertRaw(
                  conn, ALPHA, 1, "1.0", SCHEMA_ALPHA, CAPABILITY_ALPHA, "{\"a\":1,\"a\":2}"));
      PostgresSchemaSupport.assertTransactionConstraintViolation(
          conn,
          "ck_plugin_configuration_revision_values_budget",
          () ->
              insertRaw(
                  conn, ALPHA, 1, "1.0", SCHEMA_ALPHA, CAPABILITY_ALPHA, sizedValues(65_537)));
    }

    // 64 字符版本（与 PluginDescriptor.MAX_VERSION_CHARS 一致）必须被接受，不引入更窄限制。
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      insertRaw(conn, ALPHA, 1, "x".repeat(64), SCHEMA_ALPHA, CAPABILITY_ALPHA, "{}");
    }
    assertEquals(1, countHistoryRows());

    // 恰好 64 KiB UTF-8 的 values 必须被接受，与 PluginConfig.MAX_VALUES_UTF8_BYTES 同界。
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      insertRaw(conn, ALPHA, 2, "1.0", SCHEMA_ALPHA, CAPABILITY_ALPHA, sizedValues(65_536));
    }
    assertEquals(2, countHistoryRows());
  }

  /** 构造 UTF-8 恰好 {@code bytes} 字节的严格 JSON object。 */
  private static String sizedValues(int bytes) {
    return "{\"a\":\"" + "x".repeat(bytes - 8) + "\"}";
  }

  /**
   * values 文本必须原样可恢复：大指数、超长 scale、-0.0 与嵌套结构都不得被存储层规范化。
   *
   * <p>回归 jsonb 存储的真实缺陷：{@code 1e1000} 会被 jsonb 展开成 1001 位整数，读回时触发 Jackson 数字长度上限，使已成功提交的 revision
   * 无法恢复。
   */
  @Test
  void valuesJsonRoundTripsExactly() {
    String valuesJson =
        "{\"n\":1e1000,\"d\":0.1000000000000000000000000001,\"zero\":-0.0,"
            + "\"s\":\"escaped \\\"quoted\\\" text\",\"nested\":{\"a\":[1,2.50,\"x\"]}}";
    String expanded =
        jdbcClient
            .sql("select cast(:valuesJson as jsonb)::text")
            .param("valuesJson", valuesJson)
            .query(String.class)
            .single();
    assertThrows(
        IllegalArgumentException.class,
        () -> new PluginConfig(1, expanded),
        "jsonb expansion must reproduce the previously unreadable configuration");

    assertTrue(
        commit(
            0,
            List.of(
                new PluginConfigurationRow(
                    ALPHA, 1, "1.0", SCHEMA_ALPHA, CAPABILITY_ALPHA, valuesJson)),
            manifest(ALPHA, entry(ALPHA, 1))));

    assertEquals(valuesJson, repository.readConfiguration(ALPHA, 1).orElseThrow().valuesJson());
  }

  private boolean commit(
      long expectedEpoch, List<PluginConfigurationRow> changed, PluginCatalogManifest manifest) {
    return commit(repository, expectedEpoch, changed, manifest);
  }

  private boolean commit(
      PostgresqlPluginConfigurationRepository target,
      long expectedEpoch,
      List<PluginConfigurationRow> changed,
      PluginCatalogManifest manifest) {
    return Boolean.TRUE.equals(
        transactionTemplate.execute(status -> target.commit(expectedEpoch, changed, manifest)));
  }

  private static PluginCatalogManifest manifest(
      String pluginId, PluginCatalogManifest.Entry entry) {
    return new PluginCatalogManifest(Map.of(pluginId, entry), CATALOG_FINGERPRINT);
  }

  private static PluginCatalogManifest.Entry entry(String pluginId, long revision) {
    if (ALPHA.equals(pluginId)) {
      return new PluginCatalogManifest.Entry(revision, "1.0", SCHEMA_ALPHA, CAPABILITY_ALPHA);
    }
    return new PluginCatalogManifest.Entry(revision, "1.0", SCHEMA_BETA, CAPABILITY_BETA);
  }

  private static PluginConfigurationRow row(String pluginId, long revision) {
    if (ALPHA.equals(pluginId)) {
      return new PluginConfigurationRow(
          ALPHA, revision, "1.0", SCHEMA_ALPHA, CAPABILITY_ALPHA, "{\"enabled\":true}");
    }
    return new PluginConfigurationRow(
        BETA, revision, "1.0", SCHEMA_BETA, CAPABILITY_BETA, "{\"enabled\":false}");
  }

  private static String manifestJson(String pluginId, long revision) {
    return "{\"plugins\":{\""
        + pluginId
        + "\":{\"configRevision\":"
        + revision
        + ",\"pluginCodeVersion\":\"1.0\",\"schemaFingerprint\":\""
        + SCHEMA_ALPHA
        + "\",\"capabilityFingerprint\":\""
        + CAPABILITY_ALPHA
        + "\"}},\"catalogFingerprint\":\""
        + CATALOG_FINGERPRINT
        + "\"}";
  }

  private static void insertRaw(
      Connection conn,
      String pluginId,
      long revision,
      String codeVersion,
      String schemaFingerprint,
      String capabilityFingerprint,
      String valuesJson)
      throws SQLException {
    try (Statement statement = conn.createStatement()) {
      statement.execute(
          "insert into plugin_configuration_revision"
              + " (plugin_id, revision, code_version, schema_fingerprint,"
              + " capability_fingerprint, values_json) values ('"
              + pluginId
              + "', "
              + revision
              + ", '"
              + codeVersion
              + "', '"
              + schemaFingerprint
              + "', '"
              + capabilityFingerprint
              + "', '"
              + valuesJson
              + "')");
    }
  }

  private static int countCatalogRows() {
    return jdbcClient
        .sql("select count(*) from plugin_catalog_state")
        .query(Integer.class)
        .single();
  }

  private static int countHistoryRows() {
    return jdbcClient
        .sql("select count(*) from plugin_configuration_revision")
        .query(Integer.class)
        .single();
  }

  private static void deleteHistory(String pluginId, long revision) {
    jdbcClient
        .sql(
            "delete from plugin_configuration_revision"
                + " where plugin_id = :pluginId and revision = :revision")
        .param("pluginId", pluginId)
        .param("revision", revision)
        .update();
  }

  private static void tamperManifest(String manifestJson, long epoch) {
    jdbcClient
        .sql(
            "update plugin_catalog_state set manifest_json = cast(:json as jsonb), epoch = :epoch"
                + " where id = 1")
        .param("json", manifestJson)
        .param("epoch", epoch)
        .update();
  }

  private static PGNotification awaitNotification(PGConnection pg, String epoch)
      throws SQLException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      for (PGNotification notification : pg.getNotifications(500)) {
        if (epoch.equals(notification.getParameter())) {
          return notification;
        }
      }
    }
    throw new AssertionError("expected a " + epoch + " catalog change notification");
  }
}
