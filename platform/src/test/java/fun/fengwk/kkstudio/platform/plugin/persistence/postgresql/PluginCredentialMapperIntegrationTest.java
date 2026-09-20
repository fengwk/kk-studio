package fun.fengwk.kkstudio.platform.plugin.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.postgresql.Driver;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.mapper.PluginCredentialMapper;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class PluginCredentialMapperIntegrationTest {

  private static SqlSessionFactory sqlSessionFactory;

  private SqlSession session;
  private PostgresqlPluginCredentialRepository repository;

  @BeforeAll
  static void beforeAll() throws SQLException {
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      PostgresSchemaSupport.resetDatabase(conn);
      PostgresSchemaSupport.applyBaseline(conn);
    }
    SimpleDriverDataSource dataSource =
        new SimpleDriverDataSource(
            new Driver(),
            PostgresSchemaSupport.POSTGRES.getJdbcUrl(),
            PostgresSchemaSupport.POSTGRES.getUsername(),
            PostgresSchemaSupport.POSTGRES.getPassword());
    Configuration configuration = new Configuration();
    configuration.setEnvironment(
        new Environment("pg-test", new JdbcTransactionFactory(), dataSource));
    // 直接注册 mapper：result map 必须由生产注解自行声明，测试不做任何补注册，否则会掩盖注解错误。
    configuration.addMapper(PluginCredentialMapper.class);
    sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
  }

  @BeforeEach
  void setUp() throws SQLException {
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      PostgresSchemaSupport.resetDatabase(conn);
      PostgresSchemaSupport.applyBaseline(conn);
    }
    session = sqlSessionFactory.openSession(true);
    repository = createRepository(session);
  }

  @AfterEach
  void tearDown() {
    if (session != null) {
      session.close();
    }
  }

  private PostgresqlPluginCredentialRepository createRepository(SqlSession sqlSession) {
    return new PostgresqlPluginCredentialRepository(
        sqlSession.getMapper(PluginCredentialMapper.class));
  }

  private PluginCredentialRow createSampleRow(
      String pluginId, PluginCredentialStatus status, Instant nextRefreshAt, Instant now) {
    return new PluginCredentialRow(
        pluginId,
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
   * 测试意图 1：验证通过 PostgresqlPluginCredentialRepository 执行 upsert 后再 find，所有字段（包括 bytea 二进制载荷与
   * timestamptz(3) 毫秒精度时间）均被精确双向映射，无精度或内容损失。
   */
  @Test
  void upsertAndFind_ShouldMapAllFieldsAccurately_IncludingByteaAndTimestamptz() {
    Instant createTime = Instant.parse("2026-09-21T10:15:30.123Z");
    Instant updateTime = Instant.parse("2026-09-21T10:15:30.123Z");
    Instant expiresAt = Instant.parse("2026-09-21T12:00:00.456Z");
    Instant nextRefreshAt = Instant.parse("2026-09-21T11:00:00.789Z");
    byte[] payload = new byte[] {0x00, 0x01, 0x02, (byte) 0xfe, (byte) 0xff, 0x7f, (byte) 0x80};

    PluginCredentialRow row =
        new PluginCredentialRow(
            "test-plugin-mapping",
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

    boolean upserted = repository.upsert(row);
    assertTrue(upserted);

    Optional<PluginCredentialRow> foundOpt = repository.find("test-plugin-mapping");
    assertTrue(foundOpt.isPresent());
    PluginCredentialRow found = foundOpt.get();

    assertEquals(row.pluginId(), found.pluginId());
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
   * 测试意图 2：验证 upsert 的原子语义：首次插入时 version=0、lease 与 error 为 null；同一 pluginId 二次 upsert
   * 时整行替换载荷与元数据、递增 version，并清除原有的 refresh lease、error 与 lastRefreshedAt。
   */
  @Test
  void upsert_FirstInsertAndSubsequentUpdate_ShouldResetLeaseAndErrorAndAdvanceVersion() {
    Instant now = Instant.parse("2026-09-21T10:00:00.000Z");
    PluginCredentialRow row1 =
        new PluginCredentialRow(
            "test-plugin-upsert",
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
    assertTrue(repository.upsert(row1));

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

    // 模拟用户重新登录，二次 upsert
    Instant updateNow = now.plusSeconds(100);
    byte[] newPayload = new byte[] {0x33, 0x44, 0x55};
    PluginCredentialRow row2 =
        new PluginCredentialRow(
            "test-plugin-upsert",
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
    assertTrue(repository.upsert(row2));

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

  /** 测试意图 3：验证 find 在行不存在时返回 empty；delete 成功删除行返回 true 且行被移除；重复 delete 返回 false。 */
  @Test
  void findAndEmpty_DeleteAndRepeatedDelete() {
    Optional<PluginCredentialRow> notFound = repository.find("non-existent-plugin");
    assertTrue(notFound.isEmpty());

    Instant now = Instant.parse("2026-09-21T10:00:00.000Z");
    PluginCredentialRow row =
        new PluginCredentialRow(
            "test-plugin-delete",
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
    assertTrue(repository.upsert(row));
    assertTrue(repository.find("test-plugin-delete").isPresent());

    boolean deleted = repository.delete("test-plugin-delete");
    assertTrue(deleted);

    assertTrue(repository.find("test-plugin-delete").isEmpty());

    boolean repeatedDelete = repository.delete("test-plugin-delete");
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
        repository.upsert(
            createSampleRow(
                "plugin-ok-connected",
                PluginCredentialStatus.CONNECTED,
                now.minusSeconds(10),
                now)));

    // 2. plugin-ok-failed: REFRESH_FAILED, next_refresh_at = now - 5s, 无 lease -> 应该领取
    assertTrue(
        repository.upsert(
            createSampleRow(
                "plugin-ok-failed", PluginCredentialStatus.CONNECTED, now.minusSeconds(20), now)));
    List<PluginCredentialRow> initClaim2 =
        repository.claimDue(
            List.of("plugin-ok-failed"), now, now.plusSeconds(60), "token-init-2", 1);
    assertEquals(1, initClaim2.size());
    assertTrue(
        repository.finalizeFailure(
            "plugin-ok-failed",
            "token-init-2",
            initClaim2.get(0).version(),
            PluginCredentialStatus.REFRESH_FAILED,
            now.minusSeconds(5),
            "failed",
            now));

    // 3. plugin-ok-expired: CONNECTED, next_refresh_at = now - 20s, lease 已过期 (now - 1s) -> 应该领取
    assertTrue(
        repository.upsert(
            createSampleRow(
                "plugin-ok-expired", PluginCredentialStatus.CONNECTED, now.minusSeconds(20), now)));
    List<PluginCredentialRow> initClaim3 =
        repository.claimDue(
            List.of("plugin-ok-expired"), now, now.minusSeconds(1), "token-init-3", 1);
    assertEquals(1, initClaim3.size());

    // 4. plugin-filter-future: next_refresh_at 未到 (now + 10s) -> 不应领取
    assertTrue(
        repository.upsert(
            createSampleRow(
                "plugin-filter-future",
                PluginCredentialStatus.CONNECTED,
                now.plusSeconds(10),
                now)));

    // 5. plugin-filter-reauth: REAUTH_REQUIRED -> 不应领取
    assertTrue(
        repository.upsert(
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
            "token-init-5",
            initClaim5.get(0).version(),
            PluginCredentialStatus.REAUTH_REQUIRED,
            now.minusSeconds(10),
            "reauth needed",
            now));

    // 6. plugin-filter-uncertain: REFRESH_UNCERTAIN -> 不应领取
    assertTrue(
        repository.upsert(
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
        repository.upsert(
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
        repository.upsert(
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
      assertEquals(leaseToken, row.refreshLeaseToken());
      assertEquals(leaseUntil, row.refreshLeaseUntil());
      assertEquals(now, row.updateTime());
    }

    // 验证 limit 与 (next_refresh_at, plugin_id) 升序排序
    // 插入新记录用于纯粹排序验证
    Instant sortNow = Instant.parse("2026-09-21T12:00:00.000Z");
    assertTrue(
        repository.upsert(
            createSampleRow(
                "sort-plugin-b",
                PluginCredentialStatus.CONNECTED,
                sortNow.minusSeconds(10),
                sortNow)));
    assertTrue(
        repository.upsert(
            createSampleRow(
                "sort-plugin-a",
                PluginCredentialStatus.CONNECTED,
                sortNow.minusSeconds(10),
                sortNow)));
    assertTrue(
        repository.upsert(
            createSampleRow(
                "sort-plugin-c",
                PluginCredentialStatus.CONNECTED,
                sortNow.minusSeconds(20),
                sortNow)));
    assertTrue(
        repository.upsert(
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
            "sort-plugin-c", "sort-token-1", sortedClaimed.get(0).version(), sortNow));
    assertTrue(
        repository.releaseLease(
            "sort-plugin-a", "sort-token-1", sortedClaimed.get(1).version(), sortNow));

    List<PluginCredentialRow> allSorted =
        repository.claimDue(sortCandidates, sortNow, sortNow.plusSeconds(60), "sort-token-2", 4);
    assertEquals(4, allSorted.size());
    assertEquals("sort-plugin-c", allSorted.get(0).pluginId());
    assertEquals("sort-plugin-a", allSorted.get(1).pluginId());
    assertEquals("sort-plugin-b", allSorted.get(2).pluginId());
    assertEquals("sort-plugin-d", allSorted.get(3).pluginId());
  }

  /**
   * 测试意图 5：验证 SKIP LOCKED 的多连接真实并发互斥：8 个并发线程同时对 8 行到期行执行 claimDue，每个线程使用独立的 SqlSession 与 lease
   * token，断言所有被认领的 pluginId 互不重复（无二重 claim）、总认领数不超过到期行数，且数据库中每行的 leaseToken 均属于唯一对应的 claimer。
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
          repository.upsert(
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
                  try (SqlSession workerSession = sqlSessionFactory.openSession(false)) {
                    PostgresqlPluginCredentialRepository workerRepo =
                        createRepository(workerSession);
                    readyLatch.countDown();
                    if (!startLatch.await(5, TimeUnit.SECONDS)) {
                      throw new IllegalStateException("Timed out waiting for start latch");
                    }
                    String token = "worker-token-" + workerIndex;
                    List<PluginCredentialRow> claimed =
                        workerRepo.claimDue(allPluginIds, now, leaseUntil, token, rowCount);
                    workerSession.commit();
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
        assertEquals(1L, inDb.version());
        assertEquals(leaseUntil, inDb.refreshLeaseUntil());
      }
    }
  }

  /**
   * 测试意图 6：验证 finalizeSuccess、finalizeFailure 与 releaseLease 的 token+version 双围栏机制：当 token
   * 错、version 错或两者均错时，更新返回 false 且数据库行内容逐字段保持不变；当两者均正确时更新返回 true 且按契约推进行状态与 version。
   */
  @Test
  void tokenAndVersion_DualFencing_OnFinalizeAndRelease() {
    Instant now = Instant.parse("2026-09-21T10:00:00.000Z");
    String pluginId = "plugin-dual-fence";
    assertTrue(
        repository.upsert(
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
            wrongToken,
            wrongVersion,
            newPayload,
            "us-west-2",
            newExpiresAt,
            newNextRefreshAt,
            refreshedAt,
            now));
    assertEquals(baselineRow, repository.find(pluginId).orElseThrow());

    // 4. finalizeFailure: 错 token / 错 version 组合
    assertFalse(
        repository.finalizeFailure(
            pluginId,
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
            wrongToken,
            wrongVersion,
            PluginCredentialStatus.REFRESH_FAILED,
            newNextRefreshAt,
            "err",
            now));
    assertEquals(baselineRow, repository.find(pluginId).orElseThrow());

    // 5. releaseLease: 错 token / 错 version 组合
    assertFalse(repository.releaseLease(pluginId, wrongToken, validVersion, now));
    assertEquals(baselineRow, repository.find(pluginId).orElseThrow());
    assertFalse(repository.releaseLease(pluginId, validToken, wrongVersion, now));
    assertEquals(baselineRow, repository.find(pluginId).orElseThrow());
    assertFalse(repository.releaseLease(pluginId, wrongToken, wrongVersion, now));
    assertEquals(baselineRow, repository.find(pluginId).orElseThrow());

    // 6. releaseLease 成功路径
    assertTrue(repository.releaseLease(pluginId, validToken, validVersion, now));
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
   * 测试意图 7：验证晚到的 finalize 操作与用户重新登录 upsert 的竞争保护：claim 后用户 upsert 推进 version 并清空 lease，随后晚到的
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
    assertTrue(repository.upsert(rowA));

    // 后台 claim 成功
    String staleToken = "stale-lease-token";
    List<PluginCredentialRow> claimed =
        repository.claimDue(List.of(pluginId), now, now.plusSeconds(300), staleToken, 1);
    assertEquals(1, claimed.size());
    long staleVersion = claimed.get(0).version();
    assertEquals(1L, staleVersion);

    // 用户重新登录：upsert 新凭据 B
    Instant loginTime = now.plusSeconds(10);
    byte[] userPayloadB = new byte[] {0x21, 0x22, 0x23};
    PluginCredentialRow rowB =
        new PluginCredentialRow(
            pluginId,
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
    assertTrue(repository.upsert(rowB));

    PluginCredentialRow current = repository.find(pluginId).orElseThrow();
    assertEquals(2L, current.version());
    assertNull(current.refreshLeaseToken());
    assertArrayEquals(userPayloadB, current.encryptedPayload());

    // 晚到的后台刷新尝试使用旧 token 和旧 version 调用 finalizeSuccess
    byte[] stalePayload = new byte[] {(byte) 0x99, (byte) 0x99};
    boolean successResult =
        repository.finalizeSuccess(
            pluginId,
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
        repository.upsert(
            createSampleRow(
                pluginExpired, PluginCredentialStatus.CONNECTED, now.minusSeconds(60), now)));
    List<PluginCredentialRow> claimA =
        repository.claimDue(List.of(pluginExpired), now, now.minusSeconds(10), "token-a", 1);
    assertEquals(1, claimA.size());
    long versionBeforeA = claimA.get(0).version();

    // 行 B: 有效 lease（设置 lease_until = now + 300s）
    String pluginActive = "plugin-active-lease";
    assertTrue(
        repository.upsert(
            createSampleRow(
                pluginActive, PluginCredentialStatus.CONNECTED, now.minusSeconds(60), now)));
    List<PluginCredentialRow> claimB =
        repository.claimDue(List.of(pluginActive), now, now.plusSeconds(300), "token-b", 1);
    assertEquals(1, claimB.size());
    long versionBeforeB = claimB.get(0).version();

    // 行 C: 无 lease 正常行
    String pluginNoLease = "plugin-no-lease";
    assertTrue(
        repository.upsert(
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
        repository.upsert(
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
}
