package fun.fengwk.kkstudio.platform.plugin.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.postgresql.Driver;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginCredentialJdbcStore;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class PluginCredentialJdbcStoreIntegrationTest {

  /** 本测试使用的固定认证代际：仓库/CAS 断言只要求同代际内一致。 */
  private static final UUID AUTH_GENERATION =
      UUID.fromString("00000000-0000-0000-0000-0000000000b1");

  /** 本测试使用的固定 64 位十六进制认证上下文指纹。 */
  private static final String CONTEXT = "a1".repeat(32);

  /** 用于断言上下文指纹不匹配场景的备用指纹。 */
  private static final String OTHER_CONTEXT = "b2".repeat(32);

  private static DataSource dataSource;
  private static JdbcClient jdbcClient;

  private TransactionTemplate transactionTemplate;
  private PostgresqlPluginCredentialRepository repository;

  @BeforeAll
  static void beforeAll() throws SQLException {
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      PostgresSchemaSupport.resetDatabase(conn);
      PostgresSchemaSupport.applyBaseline(conn);
    }
    // 直接使用生产 JdbcClient 语句：行映射由 store 的显式 RowMapper 声明，测试不做任何补注册。
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
    repository = createRepository(jdbcClient);
  }

  private PostgresqlPluginCredentialRepository createRepository(JdbcClient jdbcClient) {
    return new PostgresqlPluginCredentialRepository(new PluginCredentialJdbcStore(jdbcClient));
  }

  private PluginCredentialRow createSampleRow(
      String pluginId, PluginCredentialStatus status, Instant nextRefreshAt, Instant now) {
    return new PluginCredentialRow(
        pluginId,
        AUTH_GENERATION,
        CONTEXT,
        new byte[] {0x01, 0x02, 0x03},
        "us-east-1",
        now.plusSeconds(3600),
        nextRefreshAt,
        status,
        null,
        null,
        null,
        null,
        0L,
        now,
        now);
  }

  private void assertCheckConstraintViolation(String expectedConstraint, Executable executable) {
    Throwable thrown = assertThrows(Throwable.class, executable);
    PSQLException pgException = findPsqLException(thrown);
    assertNotNull(pgException, "Expected PSQLException in cause chain, but got: " + thrown);
    assertEquals("23514", pgException.getSQLState(), "SQLState must be 23514 for CHECK constraint");
    String actualConstraint =
        pgException.getServerErrorMessage() != null
            ? pgException.getServerErrorMessage().getConstraint()
            : null;
    assertEquals(expectedConstraint, actualConstraint);
  }

  private PSQLException findPsqLException(Throwable t) {
    Throwable current = t;
    while (current != null) {
      if (current instanceof PSQLException psqle) {
        return psqle;
      }
      current = current.getCause();
    }
    return null;
  }

  /**
   * 测试意图 1：验证通过 PostgresqlPluginCredentialRepository 执行 insert 后再 find，所有字段（包括 bytea 二进制载荷与
   * timestamptz(3) 毫秒精度时间）均被精确双向映射，无精度或内容损失。
   */
  @Test
  void insertAndFind_ShouldMapAllFieldsAccurately_IncludingByteaAndTimestamptz() {
    Instant createTime = Instant.parse("2026-09-21T10:15:30.123Z");
    Instant updateTime = Instant.parse("2026-09-21T10:15:30.123Z");
    Instant expiresAt = Instant.parse("2026-09-21T12:00:00.456Z");
    Instant nextRefreshAt = Instant.parse("2026-09-21T11:00:00.789Z");
    byte[] payload = new byte[] {0x00, 0x01, 0x02, (byte) 0xfe, (byte) 0xff, 0x7f, (byte) 0x80};

    PluginCredentialRow row =
        new PluginCredentialRow(
            "test-plugin-mapping",
            AUTH_GENERATION,
            CONTEXT,
            payload,
            "us-east-1",
            expiresAt,
            nextRefreshAt,
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            null,
            null,
            0L,
            createTime,
            updateTime);

    boolean inserted = repository.insert(row);
    assertTrue(inserted);
    assertFalse(repository.insert(row));

    Optional<PluginCredentialRow> foundOpt = repository.find("test-plugin-mapping");
    assertTrue(foundOpt.isPresent());
    PluginCredentialRow found = foundOpt.get();

    assertEquals(row.pluginId(), found.pluginId());
    assertEquals(row.authGeneration(), found.authGeneration());
    assertEquals(row.contextFingerprint(), found.contextFingerprint());
    assertArrayEquals(row.encryptedPayload(), found.encryptedPayload());
    assertEquals(row.region(), found.region());
    assertEquals(row.expiresAt(), found.expiresAt());
    assertEquals(row.nextRefreshAt(), found.nextRefreshAt());
    assertEquals(row.status(), found.status());
    assertNull(found.lastRefreshedAt());
    assertNull(found.lastRefreshError());
    assertNull(found.refreshLeaseToken());
    assertNull(found.refreshLeaseUntil());
    assertEquals(0L, found.version());
    assertEquals(row.createTime(), found.createTime());
    assertEquals(row.updateTime(), found.updateTime());
    assertEquals(row, found);

    // 进一步验证当存在 lease 与 error 时，行映射依然精确
    Instant leaseUntil = nextRefreshAt.plusSeconds(300);
    List<PluginCredentialRow> claimed =
        repository.claimDue(
            List.of("test-plugin-mapping"), nextRefreshAt, leaseUntil, "mapping-lease-token", 1);
    assertEquals(1, claimed.size());
    // 不手工 clearCache：claimDue 是写语句，必须自己 flush 一级缓存，否则同一 session 会读到领取前的 lease。
    PluginCredentialRow leasedRow = repository.find("test-plugin-mapping").orElseThrow();
    assertEquals("mapping-lease-token", leasedRow.refreshLeaseToken());
    assertEquals(leaseUntil, leasedRow.refreshLeaseUntil());
    assertEquals(1L, leasedRow.version());
    assertEquals(nextRefreshAt, leasedRow.updateTime());

    // 验证 finalizeSuccess 产生 lastRefreshedAt 时的行映射
    Instant refreshedAt = nextRefreshAt.plusSeconds(10);
    Instant newExpiresAt = expiresAt.plusSeconds(3600);
    Instant newNextRefreshAt = nextRefreshAt.plusSeconds(1800);
    byte[] newPayload = new byte[] {0x10, 0x20};
    assertTrue(
        repository.finalizeSuccess(
            "test-plugin-mapping",
            AUTH_GENERATION,
            CONTEXT,
            "mapping-lease-token",
            1L,
            newPayload,
            "us-west-2",
            newExpiresAt,
            newNextRefreshAt,
            refreshedAt,
            refreshedAt));
    PluginCredentialRow finalizedRow = repository.find("test-plugin-mapping").orElseThrow();
    assertArrayEquals(newPayload, finalizedRow.encryptedPayload());
    assertEquals("us-west-2", finalizedRow.region());
    assertEquals(newExpiresAt, finalizedRow.expiresAt());
    assertEquals(newNextRefreshAt, finalizedRow.nextRefreshAt());
    assertEquals(PluginCredentialStatus.CONNECTED, finalizedRow.status());
    assertEquals(refreshedAt, finalizedRow.lastRefreshedAt());
    assertNull(finalizedRow.lastRefreshError());
    assertNull(finalizedRow.refreshLeaseToken());
    assertNull(finalizedRow.refreshLeaseUntil());
    assertEquals(2L, finalizedRow.version());
  }

  /**
   * 测试意图 2：验证条件写契约：首次写入通过 insert(row) 落库，version=0、lease 与 error 为 null；同一 pluginId 二次写入 必须通过匹配
   * (expectedAuthGeneration, expectedContextFingerprint, expectedVersion) 的 replace 完成整行替换， 递增
   * version 并清除原有的 refresh lease 与 error；不匹配的三元组返回 false 且保持原行不变。
   */
  @Test
  void insertAndReplace_FirstInsertAndSubsequentUpdate_ShouldResetLeaseAndErrorAndAdvanceVersion() {
    Instant now = Instant.parse("2026-09-21T10:00:00.000Z");
    PluginCredentialRow row1 =
        new PluginCredentialRow(
            "test-plugin-upsert",
            AUTH_GENERATION,
            CONTEXT,
            new byte[] {0x11, 0x22},
            "us-east-1",
            now.plusSeconds(3600),
            now.minusSeconds(10),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            null,
            null,
            0L,
            now,
            now);
    assertTrue(repository.insert(row1));
    assertFalse(repository.insert(row1));

    PluginCredentialRow firstRow = repository.find("test-plugin-upsert").orElseThrow();
    assertEquals(0L, firstRow.version());
    assertNull(firstRow.refreshLeaseToken());
    assertNull(firstRow.refreshLeaseUntil());
    assertNull(firstRow.lastRefreshError());
    assertNull(firstRow.lastRefreshedAt());

    // 制造 lease：claimDue 成功领取，version 变为 1
    List<PluginCredentialRow> claimed =
        repository.claimDue(
            List.of("test-plugin-upsert"), now, now.plusSeconds(60), "lease-token-1", 10);
    assertEquals(1, claimed.size());
    assertEquals(1L, claimed.get(0).version());

    // 通过 finalizeFailure 制造 error，version 变为 2
    assertTrue(
        repository.finalizeFailure(
            "test-plugin-upsert",
            AUTH_GENERATION,
            CONTEXT,
            "lease-token-1",
            1L,
            PluginCredentialStatus.REFRESH_FAILED,
            now.minusSeconds(5),
            "initial refresh failed",
            now));

    // 再次 claim 制造带有 lease 的状态，version 变为 3
    List<PluginCredentialRow> claimed2 =
        repository.claimDue(
            List.of("test-plugin-upsert"), now, now.plusSeconds(60), "lease-token-2", 10);
    assertEquals(1, claimed2.size());
    assertEquals(3L, claimed2.get(0).version());

    PluginCredentialRow dirtyRow = repository.find("test-plugin-upsert").orElseThrow();
    assertEquals(3L, dirtyRow.version());
    assertEquals("lease-token-2", dirtyRow.refreshLeaseToken());
    assertNotNull(dirtyRow.refreshLeaseUntil());
    assertEquals("initial refresh failed", dirtyRow.lastRefreshError());

    // 模拟用户重新登录，二次写入通过 replace 完成，且验证 CAS 围栏
    Instant updateNow = now.plusSeconds(100);
    byte[] newPayload = new byte[] {0x33, 0x44, 0x55};
    PluginCredentialRow row2 =
        new PluginCredentialRow(
            "test-plugin-upsert",
            AUTH_GENERATION,
            CONTEXT,
            newPayload,
            "eu-west-1",
            updateNow.plusSeconds(7200),
            updateNow.plusSeconds(3600),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            null,
            null,
            0L,
            now,
            updateNow);

    // 任意不匹配的条件均返回 false 且行内容不变
    assertFalse(
        repository.replace(
            row2, UUID.randomUUID(), dirtyRow.contextFingerprint(), dirtyRow.version()));
    assertFalse(
        repository.replace(row2, dirtyRow.authGeneration(), OTHER_CONTEXT, dirtyRow.version()));
    assertFalse(
        repository.replace(
            row2,
            dirtyRow.authGeneration(),
            dirtyRow.contextFingerprint(),
            dirtyRow.version() - 1));
    assertEquals(dirtyRow, repository.find("test-plugin-upsert").orElseThrow());

    assertTrue(
        repository.replace(
            row2, dirtyRow.authGeneration(), dirtyRow.contextFingerprint(), dirtyRow.version()));

    PluginCredentialRow reloaded = repository.find("test-plugin-upsert").orElseThrow();
    assertArrayEquals(newPayload, reloaded.encryptedPayload());
    assertEquals("eu-west-1", reloaded.region());
    assertEquals(updateNow.plusSeconds(7200), reloaded.expiresAt());
    assertEquals(updateNow.plusSeconds(3600), reloaded.nextRefreshAt());
    assertEquals(PluginCredentialStatus.CONNECTED, reloaded.status());
    assertEquals(4L, reloaded.version());
    assertNull(reloaded.refreshLeaseToken());
    assertNull(reloaded.refreshLeaseUntil());
    assertNull(reloaded.lastRefreshError());
    assertNull(reloaded.lastRefreshedAt());
    assertEquals(updateNow, reloaded.updateTime());
  }

  /**
   * 测试意图 3：验证 find 在行不存在时返回 empty；delete 在代际、上下文指纹或 version 不匹配时返回 false 且不删行；全匹配时成功删除返回 true； 重复
   * delete 返回 false。
   */
  @Test
  void findAndEmpty_DeleteAndRepeatedDelete() {
    Optional<PluginCredentialRow> notFound = repository.find("non-existent-plugin");
    assertTrue(notFound.isEmpty());

    Instant now = Instant.parse("2026-09-21T10:00:00.000Z");
    PluginCredentialRow row =
        new PluginCredentialRow(
            "test-plugin-delete",
            AUTH_GENERATION,
            CONTEXT,
            new byte[] {0x01},
            "us-east-1",
            now.plusSeconds(3600),
            now.plusSeconds(1800),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            null,
            null,
            0L,
            now,
            now);
    assertTrue(repository.insert(row));
    assertFalse(repository.insert(row));
    assertTrue(repository.find("test-plugin-delete").isPresent());

    // 任何不匹配的删除条件均返回 false 且行保留
    assertFalse(repository.delete("test-plugin-delete", UUID.randomUUID(), CONTEXT, 0L));
    assertFalse(repository.delete("test-plugin-delete", AUTH_GENERATION, OTHER_CONTEXT, 0L));
    assertFalse(repository.delete("test-plugin-delete", AUTH_GENERATION, CONTEXT, 1L));
    assertTrue(repository.find("test-plugin-delete").isPresent());

    boolean deleted = repository.delete("test-plugin-delete", AUTH_GENERATION, CONTEXT, 0L);
    assertTrue(deleted);

    assertTrue(repository.find("test-plugin-delete").isEmpty());

    boolean repeatedDelete = repository.delete("test-plugin-delete", AUTH_GENERATION, CONTEXT, 0L);
    assertFalse(repeatedDelete);
  }

  /**
   * 测试意图 4：验证 claimDue 的严格过滤语义（只领取 CONNECTED/REFRESH_FAILED、next_refresh_at 到期、lease 为空或已过期、且在
   * pluginIds 列表内的行）以及按 (next_refresh_at, plugin_id) 升序排序与 limit 截断。
   */
  @Test
  void claimDue_FilteringAndOrdering() {
    Instant now = Instant.parse("2026-09-21T10:00:00.000Z");

    // 1. plugin-ok-connected: CONNECTED, next_refresh_at = now - 10s, 无 lease -> 应该领取
    assertTrue(
        repository.insert(
            createSampleRow(
                "plugin-ok-connected",
                PluginCredentialStatus.CONNECTED,
                now.minusSeconds(10),
                now)));

    // 2. plugin-ok-failed: REFRESH_FAILED, next_refresh_at = now - 5s, 无 lease -> 应该领取
    assertTrue(
        repository.insert(
            createSampleRow(
                "plugin-ok-failed", PluginCredentialStatus.CONNECTED, now.minusSeconds(20), now)));
    List<PluginCredentialRow> initClaim2 =
        repository.claimDue(
            List.of("plugin-ok-failed"), now, now.plusSeconds(60), "token-init-2", 1);
    assertEquals(1, initClaim2.size());
    assertTrue(
        repository.finalizeFailure(
            "plugin-ok-failed",
            AUTH_GENERATION,
            CONTEXT,
            "token-init-2",
            initClaim2.get(0).version(),
            PluginCredentialStatus.REFRESH_FAILED,
            now.minusSeconds(5),
            "failed",
            now));

    // 3. plugin-ok-expired: CONNECTED, next_refresh_at = now - 20s, lease 已过期 (now - 1s) -> 应该领取
    assertTrue(
        repository.insert(
            createSampleRow(
                "plugin-ok-expired", PluginCredentialStatus.CONNECTED, now.minusSeconds(20), now)));
    List<PluginCredentialRow> initClaim3 =
        repository.claimDue(
            List.of("plugin-ok-expired"), now, now.minusSeconds(1), "token-init-3", 1);
    assertEquals(1, initClaim3.size());

    // 4. plugin-filter-future: next_refresh_at 未到 (now + 10s) -> 不应领取
    assertTrue(
        repository.insert(
            createSampleRow(
                "plugin-filter-future",
                PluginCredentialStatus.CONNECTED,
                now.plusSeconds(10),
                now)));

    // 5. plugin-filter-reauth: REAUTH_REQUIRED -> 不应领取
    assertTrue(
        repository.insert(
            createSampleRow(
                "plugin-filter-reauth",
                PluginCredentialStatus.CONNECTED,
                now.minusSeconds(10),
                now)));
    List<PluginCredentialRow> initClaim5 =
        repository.claimDue(
            List.of("plugin-filter-reauth"), now, now.plusSeconds(60), "token-init-5", 1);
    assertEquals(1, initClaim5.size());
    assertTrue(
        repository.finalizeFailure(
            "plugin-filter-reauth",
            AUTH_GENERATION,
            CONTEXT,
            "token-init-5",
            initClaim5.get(0).version(),
            PluginCredentialStatus.REAUTH_REQUIRED,
            now.minusSeconds(10),
            "reauth needed",
            now));

    // 6. plugin-filter-uncertain: REFRESH_UNCERTAIN -> 不应领取
    assertTrue(
        repository.insert(
            createSampleRow(
                "plugin-filter-uncertain",
                PluginCredentialStatus.CONNECTED,
                now.minusSeconds(10),
                now)));
    List<PluginCredentialRow> initClaim6 =
        repository.claimDue(
            List.of("plugin-filter-uncertain"), now, now.minusSeconds(1), "token-init-6", 1);
    assertEquals(1, initClaim6.size());
    assertEquals(
        1,
        repository.markExpiredLeasesUncertain(List.of("plugin-filter-uncertain"), now, "expired"));

    // 7. plugin-filter-active-lease: lease 仍有效 (now + 60s) -> 不应领取
    assertTrue(
        repository.insert(
            createSampleRow(
                "plugin-filter-active-lease",
                PluginCredentialStatus.CONNECTED,
                now.minusSeconds(10),
                now)));
    List<PluginCredentialRow> initClaim7 =
        repository.claimDue(
            List.of("plugin-filter-active-lease"), now, now.plusSeconds(60), "token-init-7", 1);
    assertEquals(1, initClaim7.size());

    // 8. plugin-filter-uninstalled: 条件满足但不在传入的 pluginIds 列表中 -> 不应领取
    assertTrue(
        repository.insert(
            createSampleRow(
                "plugin-filter-uninstalled",
                PluginCredentialStatus.CONNECTED,
                now.minusSeconds(10),
                now)));

    List<String> installedPluginIds =
        List.of(
            "plugin-ok-connected",
            "plugin-ok-failed",
            "plugin-ok-expired",
            "plugin-filter-future",
            "plugin-filter-reauth",
            "plugin-filter-uncertain",
            "plugin-filter-active-lease");

    Instant leaseUntil = now.plusSeconds(300);
    String leaseToken = "claim-test-token";
    List<PluginCredentialRow> claimed =
        repository.claimDue(installedPluginIds, now, leaseUntil, leaseToken, 10);

    assertEquals(3, claimed.size());
    Set<String> claimedIds =
        new HashSet<>(claimed.stream().map(PluginCredentialRow::pluginId).toList());
    assertEquals(
        Set.of("plugin-ok-connected", "plugin-ok-failed", "plugin-ok-expired"), claimedIds);

    for (PluginCredentialRow row : claimed) {
      assertEquals(CONTEXT, row.contextFingerprint());
      assertEquals(leaseToken, row.refreshLeaseToken());
      assertEquals(leaseUntil, row.refreshLeaseUntil());
      assertEquals(now, row.updateTime());
    }

    // 验证 limit 与 (next_refresh_at, plugin_id) 升序排序
    // 插入新记录用于纯粹排序验证
    Instant sortNow = Instant.parse("2026-09-21T12:00:00.000Z");
    assertTrue(
        repository.insert(
            createSampleRow(
                "sort-plugin-b",
                PluginCredentialStatus.CONNECTED,
                sortNow.minusSeconds(10),
                sortNow)));
    assertTrue(
        repository.insert(
            createSampleRow(
                "sort-plugin-a",
                PluginCredentialStatus.CONNECTED,
                sortNow.minusSeconds(10),
                sortNow)));
    assertTrue(
        repository.insert(
            createSampleRow(
                "sort-plugin-c",
                PluginCredentialStatus.CONNECTED,
                sortNow.minusSeconds(20),
                sortNow)));
    assertTrue(
        repository.insert(
            createSampleRow(
                "sort-plugin-d",
                PluginCredentialStatus.CONNECTED,
                sortNow.minusSeconds(5),
                sortNow)));

    List<String> sortCandidates =
        List.of("sort-plugin-b", "sort-plugin-a", "sort-plugin-c", "sort-plugin-d");

    // limit = 2，预期选出前两个：sort-plugin-c (-20s) 与 sort-plugin-a (-10s)
    List<PluginCredentialRow> sortedClaimed =
        repository.claimDue(sortCandidates, sortNow, sortNow.plusSeconds(60), "sort-token-1", 2);

    assertEquals(2, sortedClaimed.size());
    assertEquals("sort-plugin-c", sortedClaimed.get(0).pluginId());
    assertEquals("sort-plugin-a", sortedClaimed.get(1).pluginId());

    // 释放上面领取的 2 个 lease，重新测试 limit = 4 时的全部 4 项严格升序排列
    assertTrue(
        repository.releaseLease(
            "sort-plugin-c",
            AUTH_GENERATION,
            CONTEXT,
            "sort-token-1",
            sortedClaimed.get(0).version(),
            sortNow));
    assertTrue(
        repository.releaseLease(
            "sort-plugin-a",
            AUTH_GENERATION,
            CONTEXT,
            "sort-token-1",
            sortedClaimed.get(1).version(),
            sortNow));

    List<PluginCredentialRow> allSorted =
        repository.claimDue(sortCandidates, sortNow, sortNow.plusSeconds(60), "sort-token-2", 4);
    assertEquals(4, allSorted.size());
    assertEquals("sort-plugin-c", allSorted.get(0).pluginId());
    assertEquals("sort-plugin-a", allSorted.get(1).pluginId());
    assertEquals("sort-plugin-b", allSorted.get(2).pluginId());
    assertEquals("sort-plugin-d", allSorted.get(3).pluginId());
  }

  /**
   * 测试意图 5：验证 SKIP LOCKED 的多连接真实并发互斥：8 个并发线程同时对 8 行到期行执行 claimDue，每个线程使用独立事务与 lease token，断言所有被认领的
   * pluginId 互不重复（无二重 claim）、总认领数不超过到期行数，且数据库中每行的 leaseToken 均属于唯一对应的 claimer。
   */
  @Test
  void claimDue_SkipLocked_ConcurrentExecution_ShouldHaveNoDuplicateClaims() throws Exception {
    int rowCount = 8;
    int workerCount = 8;
    Instant now = Instant.parse("2026-09-21T10:00:00.000Z");
    Instant leaseUntil = now.plusSeconds(300);

    List<String> allPluginIds = new ArrayList<>();
    for (int i = 0; i < rowCount; i++) {
      String pluginId = "concurrent-plugin-" + i;
      allPluginIds.add(pluginId);
      assertTrue(
          repository.insert(
              createSampleRow(
                  pluginId, PluginCredentialStatus.CONNECTED, now.minusSeconds(10 + i), now)));
    }

    CountDownLatch readyLatch = new CountDownLatch(workerCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    List<List<PluginCredentialRow>> allResults = Collections.synchronizedList(new ArrayList<>());
    AtomicReference<Throwable> workerError = new AtomicReference<>();

    ExecutorService executor = Executors.newFixedThreadPool(workerCount);
    List<Future<?>> futures = new ArrayList<>();

    try {
      for (int i = 0; i < workerCount; i++) {
        final int workerIndex = i;
        futures.add(
            executor.submit(
                () -> {
                  try {
                    readyLatch.countDown();
                    if (!startLatch.await(5, TimeUnit.SECONDS)) {
                      throw new IllegalStateException("Timed out waiting for start latch");
                    }
                    String token = "worker-token-" + workerIndex;
                    List<PluginCredentialRow> claimed =
                        transactionTemplate.execute(
                            status ->
                                createRepository(jdbcClient)
                                    .claimDue(allPluginIds, now, leaseUntil, token, rowCount));
                    allResults.add(claimed);
                  } catch (Throwable t) {
                    workerError.compareAndSet(null, t);
                  }
                }));
      }

      assertTrue(readyLatch.await(5, TimeUnit.SECONDS), "All workers must be ready");
      startLatch.countDown();

      for (Future<?> future : futures) {
        future.get(15, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }

    assertNull(workerError.get(), "No worker should fail with exception");

    List<String> flatClaimedIds =
        allResults.stream().flatMap(List::stream).map(PluginCredentialRow::pluginId).toList();

    Set<String> uniqueClaimedIds = new HashSet<>(flatClaimedIds);
    assertEquals(
        flatClaimedIds.size(),
        uniqueClaimedIds.size(),
        "Each pluginId must be claimed at most once across all concurrent workers");
    assertEquals(rowCount, uniqueClaimedIds.size(), "All eligible rows must be claimed by workers");

    // 验证数据库中持久化的 lease_token 与领取的 worker token 完全一致
    for (List<PluginCredentialRow> workerClaimed : allResults) {
      for (PluginCredentialRow row : workerClaimed) {
        PluginCredentialRow inDb = repository.find(row.pluginId()).orElseThrow();
        assertEquals(row.refreshLeaseToken(), inDb.refreshLeaseToken());
        assertEquals(CONTEXT, inDb.contextFingerprint());
        assertEquals(1L, inDb.version());
        assertEquals(leaseUntil, inDb.refreshLeaseUntil());
      }
    }
  }

  /**
   * 测试意图 6：验证 finalizeSuccess、finalizeFailure 与 releaseLease 的 token+version+context 多维围栏机制：当 token
   * 错、version 错、contextFingerprint 错或组合错时，更新返回 false 且数据库行内容逐字段保持不变；当全部正确时更新返回 true 且按契约推进行状态与
   * version。
   */
  @Test
  void tokenAndVersion_DualFencing_OnFinalizeAndRelease() {
    Instant now = Instant.parse("2026-09-21T10:00:00.000Z");
    String pluginId = "plugin-dual-fence";
    assertTrue(
        repository.insert(
            createSampleRow(
                pluginId, PluginCredentialStatus.CONNECTED, now.minusSeconds(10), now)));

    Instant leaseUntil = now.plusSeconds(300);
    String validToken = "valid-lease-token";
    List<PluginCredentialRow> claimed =
        repository.claimDue(List.of(pluginId), now, leaseUntil, validToken, 1);
    assertEquals(1, claimed.size());
    PluginCredentialRow baselineRow = repository.find(pluginId).orElseThrow();
    long validVersion = baselineRow.version();
    assertEquals(1L, validVersion);

    String wrongToken = "wrong-lease-token";
    long wrongVersion = validVersion + 99L;

    byte[] newPayload = new byte[] {0x09, 0x08};
    Instant newExpiresAt = now.plusSeconds(7200);
    Instant newNextRefreshAt = now.plusSeconds(3600);
    Instant refreshedAt = now;

    // 1. finalizeSuccess: 错 token + 对 version
    assertFalse(
        repository.finalizeSuccess(
            pluginId,
            AUTH_GENERATION,
            CONTEXT,
            wrongToken,
            validVersion,
            newPayload,
            "us-west-2",
            newExpiresAt,
            newNextRefreshAt,
            refreshedAt,
            now));
    assertEquals(baselineRow, repository.find(pluginId).orElseThrow());

    // 2. finalizeSuccess: 对 token + 错 version
    assertFalse(
        repository.finalizeSuccess(
            pluginId,
            AUTH_GENERATION,
            CONTEXT,
            validToken,
            wrongVersion,
            newPayload,
            "us-west-2",
            newExpiresAt,
            newNextRefreshAt,
            refreshedAt,
            now));
    assertEquals(baselineRow, repository.find(pluginId).orElseThrow());

    // 3. finalizeSuccess: 错 token + 错 version
    assertFalse(
        repository.finalizeSuccess(
            pluginId,
            AUTH_GENERATION,
            CONTEXT,
            wrongToken,
            wrongVersion,
            newPayload,
            "us-west-2",
            newExpiresAt,
            newNextRefreshAt,
            refreshedAt,
            now));
    assertEquals(baselineRow, repository.find(pluginId).orElseThrow());

    // 3b. finalizeSuccess: 错 contextFingerprint + 对 token + 对 version
    assertFalse(
        repository.finalizeSuccess(
            pluginId,
            AUTH_GENERATION,
            OTHER_CONTEXT,
            validToken,
            validVersion,
            newPayload,
            "us-west-2",
            newExpiresAt,
            newNextRefreshAt,
            refreshedAt,
            now));
    assertEquals(baselineRow, repository.find(pluginId).orElseThrow());

    // 4. finalizeFailure: 错 token / 错 version / 错 context 组合
    assertFalse(
        repository.finalizeFailure(
            pluginId,
            AUTH_GENERATION,
            CONTEXT,
            wrongToken,
            validVersion,
            PluginCredentialStatus.REFRESH_FAILED,
            newNextRefreshAt,
            "err",
            now));
    assertEquals(baselineRow, repository.find(pluginId).orElseThrow());
    assertFalse(
        repository.finalizeFailure(
            pluginId,
            AUTH_GENERATION,
            CONTEXT,
            validToken,
            wrongVersion,
            PluginCredentialStatus.REFRESH_FAILED,
            newNextRefreshAt,
            "err",
            now));
    assertEquals(baselineRow, repository.find(pluginId).orElseThrow());
    assertFalse(
        repository.finalizeFailure(
            pluginId,
            AUTH_GENERATION,
            CONTEXT,
            wrongToken,
            wrongVersion,
            PluginCredentialStatus.REFRESH_FAILED,
            newNextRefreshAt,
            "err",
            now));
    assertEquals(baselineRow, repository.find(pluginId).orElseThrow());
    assertFalse(
        repository.finalizeFailure(
            pluginId,
            AUTH_GENERATION,
            OTHER_CONTEXT,
            validToken,
            validVersion,
            PluginCredentialStatus.REFRESH_FAILED,
            newNextRefreshAt,
            "err",
            now));
    assertEquals(baselineRow, repository.find(pluginId).orElseThrow());

    // 5. releaseLease: 错 token / 错 version / 错 context 组合
    assertFalse(
        repository.releaseLease(pluginId, AUTH_GENERATION, CONTEXT, wrongToken, validVersion, now));
    assertEquals(baselineRow, repository.find(pluginId).orElseThrow());
    assertFalse(
        repository.releaseLease(pluginId, AUTH_GENERATION, CONTEXT, validToken, wrongVersion, now));
    assertEquals(baselineRow, repository.find(pluginId).orElseThrow());
    assertFalse(
        repository.releaseLease(pluginId, AUTH_GENERATION, CONTEXT, wrongToken, wrongVersion, now));
    assertEquals(baselineRow, repository.find(pluginId).orElseThrow());
    assertFalse(
        repository.releaseLease(
            pluginId, AUTH_GENERATION, OTHER_CONTEXT, validToken, validVersion, now));
    assertEquals(baselineRow, repository.find(pluginId).orElseThrow());

    // 6. releaseLease 成功路径
    assertTrue(
        repository.releaseLease(pluginId, AUTH_GENERATION, CONTEXT, validToken, validVersion, now));
    PluginCredentialRow afterRelease = repository.find(pluginId).orElseThrow();
    assertEquals(validVersion + 1, afterRelease.version());
    assertNull(afterRelease.refreshLeaseToken());
    assertNull(afterRelease.refreshLeaseUntil());
    assertEquals(PluginCredentialStatus.CONNECTED, afterRelease.status());

    // 7. 再次 claim 后测试 finalizeFailure 成功路径
    List<PluginCredentialRow> claimed2 =
        repository.claimDue(List.of(pluginId), now, leaseUntil, "token-2", 1);
    assertEquals(1, claimed2.size());
    long version2 = claimed2.get(0).version();
    assertTrue(
        repository.finalizeFailure(
            pluginId,
            AUTH_GENERATION,
            CONTEXT,
            "token-2",
            version2,
            PluginCredentialStatus.REFRESH_FAILED,
            newNextRefreshAt,
            "failed temporarily",
            now));
    PluginCredentialRow afterFailure = repository.find(pluginId).orElseThrow();
    assertEquals(version2 + 1, afterFailure.version());
    assertNull(afterFailure.refreshLeaseToken());
    assertNull(afterFailure.refreshLeaseUntil());
    assertEquals(PluginCredentialStatus.REFRESH_FAILED, afterFailure.status());
    assertEquals("failed temporarily", afterFailure.lastRefreshError());
    assertEquals(newNextRefreshAt, afterFailure.nextRefreshAt());

    // 8. 再次 claim 后测试 finalizeSuccess 成功路径
    List<PluginCredentialRow> claimed3 =
        repository.claimDue(
            List.of(pluginId), newNextRefreshAt, newNextRefreshAt.plusSeconds(300), "token-3", 1);
    assertEquals(1, claimed3.size());
    long version3 = claimed3.get(0).version();
    assertTrue(
        repository.finalizeSuccess(
            pluginId,
            AUTH_GENERATION,
            CONTEXT,
            "token-3",
            version3,
            newPayload,
            "us-west-2",
            newExpiresAt,
            newNextRefreshAt.plusSeconds(3600),
            refreshedAt,
            newNextRefreshAt));
    PluginCredentialRow afterSuccess = repository.find(pluginId).orElseThrow();
    assertEquals(version3 + 1, afterSuccess.version());
    assertNull(afterSuccess.refreshLeaseToken());
    assertNull(afterSuccess.refreshLeaseUntil());
    assertEquals(PluginCredentialStatus.CONNECTED, afterSuccess.status());
    assertNull(afterSuccess.lastRefreshError());
    assertEquals(refreshedAt, afterSuccess.lastRefreshedAt());
    assertArrayEquals(newPayload, afterSuccess.encryptedPayload());
    assertEquals("us-west-2", afterSuccess.region());
    assertEquals(newExpiresAt, afterSuccess.expiresAt());
    assertEquals(newNextRefreshAt.plusSeconds(3600), afterSuccess.nextRefreshAt());
  }

  /**
   * 测试意图 7：验证晚到的 finalize 操作与用户重新登录 replace 的竞争保护：claim 后用户 replace 推进 version 并清空 lease，随后晚到的
   * finalizeSuccess 与 finalizeFailure 均返回 false，绝不覆盖用户的新凭据。
   */
  @Test
  void lateFinalize_RaceConditionWithUpsert_ShouldFailAndNotOverwrite() {
    Instant now = Instant.parse("2026-09-21T10:00:00.000Z");
    String pluginId = "plugin-late-finalize";

    byte[] userPayloadA = new byte[] {0x11, 0x12};
    PluginCredentialRow rowA =
        new PluginCredentialRow(
            pluginId,
            AUTH_GENERATION,
            CONTEXT,
            userPayloadA,
            "us-east-1",
            now.plusSeconds(3600),
            now.minusSeconds(10),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            null,
            null,
            0L,
            now,
            now);
    assertTrue(repository.insert(rowA));

    // 后台 claim 成功
    String staleToken = "stale-lease-token";
    List<PluginCredentialRow> claimed =
        repository.claimDue(List.of(pluginId), now, now.plusSeconds(300), staleToken, 1);
    assertEquals(1, claimed.size());
    long staleVersion = claimed.get(0).version();
    assertEquals(1L, staleVersion);

    // 用户重新登录：replace 新凭据 B
    Instant loginTime = now.plusSeconds(10);
    byte[] userPayloadB = new byte[] {0x21, 0x22, 0x23};
    PluginCredentialRow rowB =
        new PluginCredentialRow(
            pluginId,
            AUTH_GENERATION,
            CONTEXT,
            userPayloadB,
            "ap-northeast-1",
            loginTime.plusSeconds(7200),
            loginTime.plusSeconds(3600),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            null,
            null,
            0L,
            now,
            loginTime);

    PluginCredentialRow beforeRelogin = repository.find(pluginId).orElseThrow();
    assertFalse(
        repository.replace(
            rowB, UUID.randomUUID(), beforeRelogin.contextFingerprint(), beforeRelogin.version()));
    assertFalse(
        repository.replace(
            rowB, beforeRelogin.authGeneration(), OTHER_CONTEXT, beforeRelogin.version()));
    assertFalse(
        repository.replace(
            rowB,
            beforeRelogin.authGeneration(),
            beforeRelogin.contextFingerprint(),
            beforeRelogin.version() - 1));
    assertEquals(beforeRelogin, repository.find(pluginId).orElseThrow());

    assertTrue(
        repository.replace(
            rowB,
            beforeRelogin.authGeneration(),
            beforeRelogin.contextFingerprint(),
            beforeRelogin.version()));

    PluginCredentialRow current = repository.find(pluginId).orElseThrow();
    assertEquals(2L, current.version());
    assertNull(current.refreshLeaseToken());
    assertArrayEquals(userPayloadB, current.encryptedPayload());

    // 晚到的后台刷新尝试使用旧 token 和旧 version 调用 finalizeSuccess
    byte[] stalePayload = new byte[] {(byte) 0x99, (byte) 0x99};
    boolean successResult =
        repository.finalizeSuccess(
            pluginId,
            AUTH_GENERATION,
            CONTEXT,
            staleToken,
            staleVersion,
            stalePayload,
            "us-west-2",
            now.plusSeconds(10000),
            now.plusSeconds(5000),
            now,
            now);
    assertFalse(successResult, "Late finalizeSuccess must return false");

    // 晚到的后台刷新尝试使用旧 token 和旧 version 调用 finalizeFailure
    boolean failureResult =
        repository.finalizeFailure(
            pluginId,
            AUTH_GENERATION,
            CONTEXT,
            staleToken,
            staleVersion,
            PluginCredentialStatus.REFRESH_FAILED,
            now.plusSeconds(5000),
            "stale network error",
            now);
    assertFalse(failureResult, "Late finalizeFailure must return false");

    // 断言数据库内的新凭据完全未受污染
    PluginCredentialRow intact = repository.find(pluginId).orElseThrow();
    assertEquals(2L, intact.version());
    assertArrayEquals(userPayloadB, intact.encryptedPayload());
    assertEquals("ap-northeast-1", intact.region());
    assertEquals(PluginCredentialStatus.CONNECTED, intact.status());
    assertNull(intact.lastRefreshError());
    assertNull(intact.refreshLeaseToken());
  }

  /**
   * 测试意图 8：验证过期 lease 收敛语义：markExpiredLeasesUncertain 只将 lease_until <= now 的行原子收敛为
   * REFRESH_UNCERTAIN 并清空 lease、递增 version，不影响有效 lease 或无 lease 的行；且收敛后的行在此后任何时间点永远不会再被 claimDue
   * 领取。
   */
  @Test
  void markExpiredLeasesUncertain_ShouldConvergeOnlyExpiredLeases_AndNeverBeClaimedAgain() {
    Instant now = Instant.parse("2026-09-21T10:00:00.000Z");

    // 行 A: 过期 lease（设置 lease_until = now - 10s）
    String pluginExpired = "plugin-expired-lease";
    assertTrue(
        repository.insert(
            createSampleRow(
                pluginExpired, PluginCredentialStatus.CONNECTED, now.minusSeconds(60), now)));
    List<PluginCredentialRow> claimA =
        repository.claimDue(List.of(pluginExpired), now, now.minusSeconds(10), "token-a", 1);
    assertEquals(1, claimA.size());
    long versionBeforeA = claimA.get(0).version();

    // 行 B: 有效 lease（设置 lease_until = now + 300s）
    String pluginActive = "plugin-active-lease";
    assertTrue(
        repository.insert(
            createSampleRow(
                pluginActive, PluginCredentialStatus.CONNECTED, now.minusSeconds(60), now)));
    List<PluginCredentialRow> claimB =
        repository.claimDue(List.of(pluginActive), now, now.plusSeconds(300), "token-b", 1);
    assertEquals(1, claimB.size());
    long versionBeforeB = claimB.get(0).version();

    // 行 C: 无 lease 正常行
    String pluginNoLease = "plugin-no-lease";
    assertTrue(
        repository.insert(
            createSampleRow(
                pluginNoLease, PluginCredentialStatus.CONNECTED, now.minusSeconds(60), now)));
    long versionBeforeC = repository.find(pluginNoLease).orElseThrow().version();

    // 收敛过期 lease
    String errorMsg = "lease expired in-flight";
    int convergedCount =
        repository.markExpiredLeasesUncertain(
            List.of(pluginExpired, pluginActive, pluginNoLease), now, errorMsg);
    assertEquals(1, convergedCount, "Only expired lease row should be converged");

    // 行 A 断言已收敛
    PluginCredentialRow rowA = repository.find(pluginExpired).orElseThrow();
    assertEquals(PluginCredentialStatus.REFRESH_UNCERTAIN, rowA.status());
    assertNull(rowA.refreshLeaseToken());
    assertNull(rowA.refreshLeaseUntil());
    assertEquals(errorMsg, rowA.lastRefreshError());
    assertEquals(versionBeforeA + 1, rowA.version());

    // 行 B 断言不受影响
    PluginCredentialRow rowB = repository.find(pluginActive).orElseThrow();
    assertEquals(PluginCredentialStatus.CONNECTED, rowB.status());
    assertEquals("token-b", rowB.refreshLeaseToken());
    assertNotNull(rowB.refreshLeaseUntil());
    assertEquals(versionBeforeB, rowB.version());

    // 行 C 断言不受影响
    PluginCredentialRow rowC = repository.find(pluginNoLease).orElseThrow();
    assertEquals(PluginCredentialStatus.CONNECTED, rowC.status());
    assertNull(rowC.refreshLeaseToken());
    assertEquals(versionBeforeC, rowC.version());

    // 推进时间验证行 A 永远不可被重 claim
    Instant future1 = now.plusSeconds(3600);
    List<PluginCredentialRow> claimAttempt1 =
        repository.claimDue(
            List.of(pluginExpired), future1, future1.plusSeconds(60), "token-future", 10);
    assertTrue(claimAttempt1.isEmpty());

    Instant future2 = now.plusSeconds(86400);
    List<PluginCredentialRow> claimAttempt2 =
        repository.claimDue(
            List.of(pluginExpired), future2, future2.plusSeconds(60), "token-future", 10);
    assertTrue(claimAttempt2.isEmpty());

    assertEquals(
        PluginCredentialStatus.REFRESH_UNCERTAIN,
        repository.find(pluginExpired).orElseThrow().status());
  }

  /** 测试意图 9：验证 markExpiredLeasesUncertain 在传入空列表时短路返回 0，不执行 SQL 且不抛出异常。 */
  @Test
  void markExpiredLeasesUncertain_WithEmptyList_ShouldReturnZeroImmediately() {
    int result = repository.markExpiredLeasesUncertain(List.of(), Instant.now(), "error");
    assertEquals(0, result);
  }

  /**
   * 测试意图 10：验证数据库 ck_plugin_credential_last_refresh_error 约束：当 last_refresh_error 超过 4096
   * 字节时，PostgreSQL 拒绝该写入并报错对应的 check 约束；而在 4096 字节有界长度内正常写入。
   */
  @Test
  void ckPluginCredentialLastRefreshError_ShouldRejectErrorsExceeding4096Bytes() {
    Instant now = Instant.parse("2026-09-21T10:00:00.000Z");
    String pluginId = "plugin-constraint-check";
    assertTrue(
        repository.insert(
            createSampleRow(
                pluginId, PluginCredentialStatus.CONNECTED, now.minusSeconds(10), now)));

    List<PluginCredentialRow> claimed =
        repository.claimDue(List.of(pluginId), now, now.plusSeconds(300), "lease-token", 1);
    assertEquals(1, claimed.size());
    long version = claimed.get(0).version();

    String oversizedError = "a".repeat(4097);
    assertCheckConstraintViolation(
        "ck_plugin_credential_last_refresh_error",
        () ->
            repository.finalizeFailure(
                pluginId,
                AUTH_GENERATION,
                CONTEXT,
                "lease-token",
                version,
                PluginCredentialStatus.REFRESH_FAILED,
                now,
                oversizedError,
                now));

    // 4096 字节应当合法允许写入
    String validMaxError = "a".repeat(4096);
    boolean success =
        repository.finalizeFailure(
            pluginId,
            AUTH_GENERATION,
            CONTEXT,
            "lease-token",
            version,
            PluginCredentialStatus.REFRESH_FAILED,
            now,
            validMaxError,
            now);
    assertTrue(success);

    PluginCredentialRow updated = repository.find(pluginId).orElseThrow();
    assertEquals(validMaxError, updated.lastRefreshError());
    assertEquals(PluginCredentialStatus.REFRESH_FAILED, updated.status());
  }

  /** 测试意图 11：发请求前的租约核验必须同时要求代际、上下文指纹、token、version 与未过期截止时刻；任一不符都必须返回 false，调用方据此取消外呼。 */
  @Test
  void ownsUnexpiredLease_ShouldRequireTokenVersionAndUnexpiredDeadline() {
    Instant now = Instant.parse("2026-09-21T10:00:00.000Z");
    String pluginId = "plugin-lease-ownership";
    assertTrue(
        repository.insert(
            createSampleRow(pluginId, PluginCredentialStatus.CONNECTED, now.minusSeconds(1), now)));

    List<PluginCredentialRow> claimed =
        repository.claimDue(List.of(pluginId), now, now.plusSeconds(60), "lease-token", 1);
    assertEquals(1, claimed.size());
    long version = claimed.get(0).version();

    assertTrue(
        repository.ownsUnexpiredLease(
            pluginId, AUTH_GENERATION, CONTEXT, "lease-token", version, now));
    assertFalse(
        repository.ownsUnexpiredLease(
            pluginId, AUTH_GENERATION, CONTEXT, "another-token", version, now),
        "a foreign lease token must never look owned");
    assertFalse(
        repository.ownsUnexpiredLease(
            pluginId, AUTH_GENERATION, CONTEXT, "lease-token", version + 1, now),
        "a stale version must never look owned");
    assertFalse(
        repository.ownsUnexpiredLease(
            pluginId, AUTH_GENERATION, OTHER_CONTEXT, "lease-token", version, now),
        "a foreign context fingerprint must never look owned");
    assertFalse(
        repository.ownsUnexpiredLease(
            pluginId, UUID.randomUUID(), CONTEXT, "lease-token", version, now),
        "a foreign auth generation must never look owned");
    assertFalse(
        repository.ownsUnexpiredLease(
            pluginId, AUTH_GENERATION, CONTEXT, "lease-token", version, now.plusSeconds(60)),
        "an expired deadline must never look owned");
    assertFalse(
        repository.ownsUnexpiredLease(
            "plugin-absent", AUTH_GENERATION, CONTEXT, "lease-token", version, now));
  }

  /** 测试意图 12：下一次扫描时刻必须来自未被在途租约占用的可调度行，且只考虑 CONNECTED / REFRESH_FAILED。 */
  @Test
  void earliestRefreshAt_ShouldIgnoreLeasedAndNonSchedulableRows() {
    Instant now = Instant.parse("2026-09-21T10:00:00.000Z");
    String dueSoon = "plugin-earliest-a";
    String leased = "plugin-earliest-b";
    String needsLogin = "plugin-earliest-c";
    assertTrue(
        repository.insert(
            createSampleRow(dueSoon, PluginCredentialStatus.CONNECTED, now.plusSeconds(60), now)));
    assertTrue(
        repository.insert(
            createSampleRow(leased, PluginCredentialStatus.CONNECTED, now.minusSeconds(5), now)));
    assertTrue(
        repository.insert(
            createSampleRow(
                needsLogin, PluginCredentialStatus.REAUTH_REQUIRED, now.plusSeconds(1), now)));

    // 已经到期并被领取，因此处于「在途未过期租约」状态。
    assertEquals(
        1,
        repository.claimDue(List.of(leased), now, now.plusSeconds(300), "lease-token", 1).size());

    assertEquals(
        Optional.of(now.plusSeconds(60)),
        repository.earliestRefreshAt(List.of(dueSoon, leased, needsLogin), now),
        "leased and non-schedulable rows must not decide the next scan time");
    assertEquals(Optional.empty(), repository.earliestRefreshAt(List.of(leased), now));
    assertEquals(Optional.empty(), repository.earliestRefreshAt(List.of(), now));

    // 租约过期后该行重新可调度，且它是唯一一行，因此重新成为最早到期时刻。
    Instant afterLease = now.plusSeconds(301);
    assertEquals(
        Optional.of(now.minusSeconds(5)),
        repository.earliestRefreshAt(List.of(leased), afterLease));
  }

  /** 测试意图 13：调用期认证拒绝在代际与上下文一致的前提下按 version 或原始密文 CAS 作废本行；并发重新登录写入的新代际、新 version 与新密文都必须逃过这次作废。 */
  @Test
  void rejectUsedCredential_ShouldMatchVersionOrCiphertextAndSpareConcurrentRelogin() {
    Instant now = Instant.parse("2026-09-21T10:00:00.000Z");
    String byVersion = "plugin-reject-version";
    String byCiphertext = "plugin-reject-ciphertext";
    String relogged = "plugin-reject-relogged";
    byte[] used = {0x11, 0x22, 0x33};
    byte[] fresh = {0x44, 0x55, 0x66};
    byte[] unrelated = {0x2a, 0x2b};

    // 1. version 匹配：在途 claim 已把 version 推进到 1，密文虽然换了参数也必须作废。
    assertTrue(repository.insert(rowWithPayload(byVersion, used, now)));
    List<PluginCredentialRow> claimed =
        repository.claimDue(List.of(byVersion), now, now.plusSeconds(60), "lease-token", 1);
    assertEquals(1, claimed.size());
    assertEquals(1L, claimed.get(0).version());
    assertTrue(
        repository.rejectUsedCredential(
            byVersion,
            AUTH_GENERATION,
            CONTEXT,
            1L,
            unrelated,
            "provider rejected the access token",
            now),
        "an advanced version must be invalidated even when the ciphertext argument differs");
    PluginCredentialRow rejected = repository.find(byVersion).orElseThrow();
    assertEquals(PluginCredentialStatus.REAUTH_REQUIRED, rejected.status());
    assertNull(rejected.refreshLeaseToken());
    assertNull(rejected.refreshLeaseUntil());
    assertEquals(2L, rejected.version(), "rejection advances the version once");

    // 2. 同代际 claim 推进了 version，但本次调用用的密文没变，仍必须作废。
    assertTrue(repository.insert(rowWithPayload(byCiphertext, used, now)));
    assertEquals(
        1,
        repository
            .claimDue(List.of(byCiphertext), now, now.plusSeconds(60), "cipher-lease", 1)
            .size());
    assertEquals(1L, repository.find(byCiphertext).orElseThrow().version());
    assertTrue(
        repository.rejectUsedCredential(
            byCiphertext,
            AUTH_GENERATION,
            CONTEXT,
            0L,
            used,
            "provider rejected the access token",
            now),
        "an unchanged ciphertext must be invalidated even when the version argument is stale");
    assertEquals(
        PluginCredentialStatus.REAUTH_REQUIRED,
        repository.find(byCiphertext).orElseThrow().status());

    // 3. 并发重新登录同时换了 version 与密文：迟到的作废必须是 0 行，且新凭据原样保留。
    assertTrue(repository.insert(rowWithPayload(relogged, used, now)));
    UUID newGeneration = UUID.randomUUID();
    PluginCredentialRow beforeRelogin = repository.find(relogged).orElseThrow();
    PluginCredentialRow newRow = rowWithGeneration(relogged, newGeneration, fresh, now);
    assertFalse(
        repository.replace(
            newRow,
            UUID.randomUUID(),
            beforeRelogin.contextFingerprint(),
            beforeRelogin.version()));
    assertTrue(
        repository.replace(
            newRow,
            beforeRelogin.authGeneration(),
            beforeRelogin.contextFingerprint(),
            beforeRelogin.version()));

    assertFalse(
        repository.rejectUsedCredential(
            relogged,
            AUTH_GENERATION,
            CONTEXT,
            0L,
            used,
            "provider rejected the access token",
            now),
        "a concurrent re-login must not be invalidated by a stale rejection");
    PluginCredentialRow kept = repository.find(relogged).orElseThrow();
    assertEquals(PluginCredentialStatus.CONNECTED, kept.status());
    assertArrayEquals(fresh, kept.encryptedPayload());
    assertEquals(1L, kept.version());
    assertEquals(newGeneration, kept.authGeneration());
    assertFalse(
        repository.rejectUsedCredential(
            relogged, AUTH_GENERATION, CONTEXT, 9L, new byte[] {0x77}, "stale", now),
        "a rejection matching neither version nor ciphertext must be a no-op");
    assertFalse(
        repository.rejectUsedCredential(
            relogged, newGeneration, OTHER_CONTEXT, 1L, fresh, "wrong context", now),
        "a rejection with a mismatching context fingerprint must be a no-op");
    assertFalse(
        repository.rejectUsedCredential(
            "plugin-absent", AUTH_GENERATION, CONTEXT, 0L, used, "stale", now),
        "an absent row must not report a rejection");
  }

  /**
   * 测试意图 14：真实 PG 的断连重建 ABA。旧代际 A 被 claim 后 delete，再以新代际 B 重建并把 B 领取到与 A 完全相同的 version 与
   * leaseToken；A 的所有 CAS 写入都必须 0 行，B 的正常操作通过。不能只靠不同随机 lease 遮蔽代际条件。
   */
  @Test
  void authGenerationFencesRecreatedRowsWithTheSameVersionAndLeaseToken() {
    Instant now = Instant.parse("2026-09-21T10:00:00.000Z");
    String pluginId = "plugin-aba-generation";
    UUID generationA = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    UUID generationB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    byte[] payloadA = {0x0a, 0x0a};
    byte[] payloadB = {0x0b, 0x0b};

    assertTrue(repository.insert(rowWithGeneration(pluginId, generationA, payloadA, now)));
    List<PluginCredentialRow> claimedA =
        repository.claimDue(List.of(pluginId), now, now.plusSeconds(300), "aba-lease", 1);
    assertEquals(1, claimedA.size());
    long sharedVersion = claimedA.get(0).version();
    assertEquals(generationA, claimedA.get(0).authGeneration());

    // 断连删除后以新代际 B 重建，并领取到与 A 相同的 version 与 leaseToken。
    assertFalse(repository.delete(pluginId, generationB, CONTEXT, sharedVersion));
    assertFalse(repository.delete(pluginId, generationA, OTHER_CONTEXT, sharedVersion));
    assertFalse(repository.delete(pluginId, generationA, CONTEXT, sharedVersion - 1));
    assertTrue(repository.delete(pluginId, generationA, CONTEXT, sharedVersion));
    assertTrue(repository.insert(rowWithGeneration(pluginId, generationB, payloadB, now)));
    List<PluginCredentialRow> claimedB =
        repository.claimDue(List.of(pluginId), now, now.plusSeconds(300), "aba-lease", 1);
    assertEquals(1, claimedB.size());
    assertEquals(sharedVersion, claimedB.get(0).version());
    assertEquals("aba-lease", claimedB.get(0).refreshLeaseToken());
    assertEquals(generationB, claimedB.get(0).authGeneration());

    // A 的所有 CAS 写入都被代际条件拒绝。
    assertFalse(
        repository.finalizeSuccess(
            pluginId,
            generationA,
            CONTEXT,
            "aba-lease",
            sharedVersion,
            new byte[] {0x7f},
            "us-east-1",
            now.plusSeconds(3600),
            now.plusSeconds(1800),
            now,
            now),
        "a stale generation must never finalize success");
    assertFalse(
        repository.finalizeFailure(
            pluginId,
            generationA,
            CONTEXT,
            "aba-lease",
            sharedVersion,
            PluginCredentialStatus.REFRESH_FAILED,
            now,
            "stale",
            now),
        "a stale generation must never finalize failure");
    assertFalse(
        repository.releaseLease(pluginId, generationA, CONTEXT, "aba-lease", sharedVersion, now),
        "a stale generation must never release the lease");
    assertFalse(
        repository.ownsUnexpiredLease(
            pluginId, generationA, CONTEXT, "aba-lease", sharedVersion, now),
        "a stale generation must never own the lease");
    assertFalse(
        repository.rejectUsedCredential(
            pluginId, generationA, CONTEXT, sharedVersion, payloadB, "stale", now),
        "a stale generation must never invalidate the recreated row");

    // 同样，带错误 contextFingerprint 的写入也必须被拒绝
    assertFalse(
        repository.finalizeSuccess(
            pluginId,
            generationB,
            OTHER_CONTEXT,
            "aba-lease",
            sharedVersion,
            new byte[] {0x7f},
            "us-east-1",
            now.plusSeconds(3600),
            now.plusSeconds(1800),
            now,
            now),
        "a mismatching context fingerprint must never finalize success");

    // B 仍然完整，且可以正常操作。
    PluginCredentialRow stillB = repository.find(pluginId).orElseThrow();
    assertEquals(generationB, stillB.authGeneration());
    assertEquals(CONTEXT, stillB.contextFingerprint());
    assertEquals(PluginCredentialStatus.CONNECTED, stillB.status());
    assertEquals(sharedVersion, stillB.version());
    assertArrayEquals(payloadB, stillB.encryptedPayload());
    assertTrue(
        repository.ownsUnexpiredLease(
            pluginId, generationB, CONTEXT, "aba-lease", sharedVersion, now),
        "the recreated generation must still own its lease");
    assertTrue(
        repository.rejectUsedCredential(
            pluginId, generationB, CONTEXT, sharedVersion, payloadA, "genuine rejection", now),
        "the current generation must still be invalidated by its own rejection");
    assertEquals(
        PluginCredentialStatus.REAUTH_REQUIRED, repository.find(pluginId).orElseThrow().status());
  }

  /**
   * 测试意图 15：验证上下文指纹的完整隔离与围栏：在 pluginId、authGeneration、version 与 leaseToken 均相同的情况下， 不同的
   * contextFingerprint 依然无法命中任何 CAS
   * 操作（finalizeSuccess、finalizeFailure、releaseLease、ownsUnexpiredLease、rejectUsedCredential、replace、delete）。
   */
  @Test
  void contextFingerprintFencesOperationsWithTheSameVersionAndLeaseToken() {
    Instant now = Instant.parse("2026-09-21T10:00:00.000Z");
    String pluginId = "plugin-context-fence";
    byte[] payload = {0x01, 0x02};

    PluginCredentialRow row =
        new PluginCredentialRow(
            pluginId,
            AUTH_GENERATION,
            CONTEXT,
            payload,
            "us-east-1",
            now.plusSeconds(3600),
            now.minusSeconds(1),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            null,
            null,
            0L,
            now,
            now);
    assertTrue(repository.insert(row));

    List<PluginCredentialRow> claimed =
        repository.claimDue(List.of(pluginId), now, now.plusSeconds(300), "context-lease", 1);
    assertEquals(1, claimed.size());
    long version = claimed.get(0).version();

    // 所有带 OTHER_CONTEXT 的 CAS 操作都必须返回 false
    assertFalse(
        repository.finalizeSuccess(
            pluginId,
            AUTH_GENERATION,
            OTHER_CONTEXT,
            "context-lease",
            version,
            payload,
            "us-east-1",
            now.plusSeconds(3600),
            now.plusSeconds(1800),
            now,
            now));
    assertFalse(
        repository.finalizeFailure(
            pluginId,
            AUTH_GENERATION,
            OTHER_CONTEXT,
            "context-lease",
            version,
            PluginCredentialStatus.REFRESH_FAILED,
            now,
            "stale context",
            now));
    assertFalse(
        repository.releaseLease(
            pluginId, AUTH_GENERATION, OTHER_CONTEXT, "context-lease", version, now));
    assertFalse(
        repository.ownsUnexpiredLease(
            pluginId, AUTH_GENERATION, OTHER_CONTEXT, "context-lease", version, now));
    assertFalse(
        repository.rejectUsedCredential(
            pluginId, AUTH_GENERATION, OTHER_CONTEXT, version, payload, "error", now));
    assertFalse(repository.delete(pluginId, AUTH_GENERATION, OTHER_CONTEXT, version));
    assertFalse(repository.replace(row, AUTH_GENERATION, OTHER_CONTEXT, version));

    // 正确的 CONTEXT 操作能够正常通过
    assertTrue(
        repository.ownsUnexpiredLease(
            pluginId, AUTH_GENERATION, CONTEXT, "context-lease", version, now));
    assertTrue(
        repository.releaseLease(pluginId, AUTH_GENERATION, CONTEXT, "context-lease", version, now));
  }

  private PluginCredentialRow rowWithGeneration(
      String pluginId, UUID authGeneration, byte[] encryptedPayload, Instant now) {
    return new PluginCredentialRow(
        pluginId,
        authGeneration,
        CONTEXT,
        encryptedPayload,
        "us-east-1",
        now.plusSeconds(3600),
        now.minusSeconds(1),
        PluginCredentialStatus.CONNECTED,
        null,
        null,
        null,
        null,
        0L,
        now,
        now);
  }

  private PluginCredentialRow rowWithPayload(
      String pluginId, byte[] encryptedPayload, Instant now) {
    return new PluginCredentialRow(
        pluginId,
        AUTH_GENERATION,
        CONTEXT,
        encryptedPayload,
        "us-east-1",
        now.plusSeconds(3600),
        now.minusSeconds(1),
        PluginCredentialStatus.CONNECTED,
        null,
        null,
        null,
        null,
        0L,
        now,
        now);
  }
}
