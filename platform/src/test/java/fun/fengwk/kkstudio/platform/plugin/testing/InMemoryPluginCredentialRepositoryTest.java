package fun.fengwk.kkstudio.platform.plugin.testing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 内存凭据仓库自身模拟 SQL 语义的单元测试。
 *
 * <p>逐条验证 upsert、find、delete、markExpiredLeasesUncertain、claimDue、finalizeSuccess、
 * finalizeFailure、releaseLease 以及版本偷取等行为与 PostgreSQL 实现的一致性。
 */
class InMemoryPluginCredentialRepositoryTest {

  private InMemoryPluginCredentialRepository repository;
  private final Instant now = Instant.parse("2026-09-21T18:00:00Z");

  @BeforeEach
  void setUp() {
    repository = new InMemoryPluginCredentialRepository();
  }

  private PluginCredentialRow createRow(
      String pluginId, PluginCredentialStatus status, Instant nextRefreshAt) {
    return new PluginCredentialRow(
        pluginId,
        new byte[] {1, 2, 3},
        "CN",
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

  /** upsert 模拟：首次插入 version=0；已存在时整体替换并推进 version+1，并清空在途 lease。 */
  @Test
  void upsertInsertsAtVersionZeroAndUpdatesWithIncrementedVersionAndClearsLease() {
    PluginCredentialRow row =
        createRow("p1", PluginCredentialStatus.CONNECTED, now.plusSeconds(100));
    assertTrue(repository.upsert(row));

    PluginCredentialRow inserted = repository.getDirect("p1");
    assertNotNull(inserted);
    assertEquals(0L, inserted.version());
    assertNull(inserted.refreshLeaseToken());

    // 人工加上 lease
    repository.setDirect(
        new PluginCredentialRow(
            inserted.pluginId(),
            inserted.encryptedPayload(),
            inserted.region(),
            inserted.expiresAt(),
            inserted.nextRefreshAt(),
            inserted.status(),
            inserted.lastRefreshedAt(),
            inserted.lastRefreshError(),
            "in-flight-lease",
            now.plusSeconds(60),
            inserted.version(),
            inserted.createTime(),
            inserted.updateTime()));

    // 再次 upsert：覆盖字段并清空 lease，version 推进
    PluginCredentialRow replacement =
        new PluginCredentialRow(
            "p1",
            new byte[] {9, 9, 9},
            "US",
            now.plusSeconds(7200),
            now.plusSeconds(3600),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            null,
            null,
            999L, // 传入的 version 被忽略，按原有 version + 1
            now,
            now);
    assertTrue(repository.upsert(replacement));

    PluginCredentialRow updated = repository.getDirect("p1");
    assertEquals(1L, updated.version());
    assertEquals("US", updated.region());
    assertArrayEquals(new byte[] {9, 9, 9}, updated.encryptedPayload());
    assertNull(updated.refreshLeaseToken());
    assertNull(updated.refreshLeaseUntil());
  }

  /** find 与快照隔离：返回 Optional 行快照，修改返回的快照不影响仓库内部状态。 */
  @Test
  void findReturnsIsolatedSnapshot() {
    PluginCredentialRow row =
        createRow("p1", PluginCredentialStatus.CONNECTED, now.plusSeconds(100));
    repository.upsert(row);

    Optional<PluginCredentialRow> foundOpt = repository.find("p1");
    assertTrue(foundOpt.isPresent());
    PluginCredentialRow found = foundOpt.get();

    byte[] payload = found.encryptedPayload();
    payload[0] = 99; // 尝试污染返回数组

    PluginCredentialRow reRead = repository.getDirect("p1");
    assertEquals(
        1, reRead.encryptedPayload()[0], "repository internal array must not be corrupted");
  }

  /** delete 模拟：删除存在行返回 true，删除不存在行返回 false。 */
  @Test
  void deleteReturnsTrueOnlyWhenRowExisted() {
    assertFalse(repository.delete("not-exist"));

    repository.upsert(createRow("p1", PluginCredentialStatus.CONNECTED, now));
    assertTrue(repository.delete("p1"));
    assertFalse(repository.delete("p1"));
    assertTrue(repository.find("p1").isEmpty());
  }

  /** markExpiredLeasesUncertain 模拟：仅对 token != null 且 until <= now 且在列表内的行生效。 */
  @Test
  void markExpiredLeasesUncertainOnlyAffectsExpiredInFlightRowsInIdList() {
    // 1. 已过期 lease（until <= now）
    PluginCredentialRow expired =
        new PluginCredentialRow(
            "expired-p",
            new byte[] {1},
            "CN",
            now.plusSeconds(1000),
            now.minusSeconds(10),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            "token-exp",
            now.minusSeconds(1),
            2L,
            now,
            now);
    repository.setDirect(expired);

    // 2. 未过期 lease（until > now）
    PluginCredentialRow active =
        new PluginCredentialRow(
            "active-p",
            new byte[] {1},
            "CN",
            now.plusSeconds(1000),
            now.minusSeconds(10),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            "token-act",
            now.plusSeconds(60),
            2L,
            now,
            now);
    repository.setDirect(active);

    // 3. 无 lease（token == null）
    PluginCredentialRow noLease =
        new PluginCredentialRow(
            "no-lease-p",
            new byte[] {1},
            "CN",
            now.plusSeconds(1000),
            now.minusSeconds(10),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            null,
            null,
            2L,
            now,
            now);
    repository.setDirect(noLease);

    // 4. 已过期但不在 pluginIds 列表内
    PluginCredentialRow outsideList =
        new PluginCredentialRow(
            "outside-p",
            new byte[] {1},
            "CN",
            now.plusSeconds(1000),
            now.minusSeconds(10),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            "token-out",
            now.minusSeconds(10),
            2L,
            now,
            now);
    repository.setDirect(outsideList);

    int affected =
        repository.markExpiredLeasesUncertain(
            List.of("expired-p", "active-p", "no-lease-p"), now, "lease expired error");

    assertEquals(1, affected);

    // 验证 expired-p
    PluginCredentialRow afterExpired = repository.getDirect("expired-p");
    assertEquals(PluginCredentialStatus.REFRESH_UNCERTAIN, afterExpired.status());
    assertEquals("lease expired error", afterExpired.lastRefreshError());
    assertNull(afterExpired.refreshLeaseToken());
    assertEquals(3L, afterExpired.version());

    // 验证 active-p 不受影响
    PluginCredentialRow afterActive = repository.getDirect("active-p");
    assertEquals(PluginCredentialStatus.CONNECTED, afterActive.status());
    assertEquals("token-act", afterActive.refreshLeaseToken());

    // 验证 no-lease-p 不受影响
    assertEquals(PluginCredentialStatus.CONNECTED, repository.getDirect("no-lease-p").status());

    // 验证 outside-p 不受影响
    assertEquals("token-out", repository.getDirect("outside-p").refreshLeaseToken());
  }

  /** claimDue 模拟：状态过滤、到期时间过滤、lease 过滤、排序与 limit 上限。 */
  @Test
  void claimDueFiltersAndSortsAndLimitsClaimableRows() {
    // 候选 1: CONNECTED, 到期 now-10s
    repository.setDirect(createRow("p1", PluginCredentialStatus.CONNECTED, now.minusSeconds(10)));
    // 候选 2: REFRESH_FAILED, 到期 now-20s (应当排在最前)
    repository.setDirect(
        createRow("p2", PluginCredentialStatus.REFRESH_FAILED, now.minusSeconds(20)));
    // 候选 3: REAUTH_REQUIRED, 到期 now-30s (不可 claim)
    repository.setDirect(
        createRow("p3", PluginCredentialStatus.REAUTH_REQUIRED, now.minusSeconds(30)));
    // 候选 4: CONNECTED, 未到期 now+10s (不可 claim)
    repository.setDirect(createRow("p4", PluginCredentialStatus.CONNECTED, now.plusSeconds(10)));
    // 候选 5: CONNECTED, 到期 now-5s, 但已被占用有效 lease (until > now)
    repository.setDirect(
        new PluginCredentialRow(
            "p5",
            new byte[] {1},
            "CN",
            now.plusSeconds(1000),
            now.minusSeconds(5),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            "busy-token",
            now.plusSeconds(60),
            0L,
            now,
            now));

    List<String> queryIds = List.of("p1", "p2", "p3", "p4", "p5");
    Instant leaseUntil = now.plusSeconds(120);

    List<PluginCredentialRow> claimed =
        repository.claimDue(queryIds, now, leaseUntil, "new-token", 10);
    assertEquals(2, claimed.size());
    // 排序校验：p2 (now-20) 优先于 p1 (now-10)
    assertEquals("p2", claimed.get(0).pluginId());
    assertEquals("p1", claimed.get(1).pluginId());

    assertEquals("new-token", claimed.get(0).refreshLeaseToken());
    assertEquals(1L, claimed.get(0).version());

    // 验证 limit 语义：单次只取 1
    repository.setDirect(createRow("q1", PluginCredentialStatus.CONNECTED, now.minusSeconds(10)));
    repository.setDirect(createRow("q2", PluginCredentialStatus.CONNECTED, now.minusSeconds(20)));
    List<PluginCredentialRow> limited =
        repository.claimDue(List.of("q1", "q2"), now, leaseUntil, "tok", 1);
    assertEquals(1, limited.size());
    assertEquals("q2", limited.get(0).pluginId());
  }

  /** 条件终结与释放 lease：只有 token 和 version 同时匹配才生效，否则不改动任何字段。 */
  @Test
  void conditionalFinalizeAndReleaseRequireMatchingTokenAndVersion() {
    PluginCredentialRow row =
        new PluginCredentialRow(
            "p1",
            new byte[] {1},
            "CN",
            now.plusSeconds(1000),
            now.minusSeconds(10),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            "tok-1",
            now.plusSeconds(60),
            5L,
            now,
            now);
    repository.setDirect(row);

    // 1. finalizeSuccess: token 错
    assertFalse(
        repository.finalizeSuccess(
            "p1",
            "wrong-tok",
            5L,
            new byte[] {2},
            "CN",
            now.plusSeconds(2000),
            now.plusSeconds(1000),
            now,
            now));
    assertEquals(5L, repository.getDirect("p1").version());
    assertEquals("tok-1", repository.getDirect("p1").refreshLeaseToken());

    // 2. finalizeSuccess: version 错
    assertFalse(
        repository.finalizeSuccess(
            "p1",
            "tok-1",
            4L,
            new byte[] {2},
            "CN",
            now.plusSeconds(2000),
            now.plusSeconds(1000),
            now,
            now));
    assertEquals(5L, repository.getDirect("p1").version());

    // 3. finalizeSuccess: 匹配成功
    assertTrue(
        repository.finalizeSuccess(
            "p1",
            "tok-1",
            5L,
            new byte[] {2},
            "CN",
            now.plusSeconds(2000),
            now.plusSeconds(1000),
            now,
            now));
    PluginCredentialRow afterSuccess = repository.getDirect("p1");
    assertEquals(6L, afterSuccess.version());
    assertNull(afterSuccess.refreshLeaseToken());
    assertArrayEquals(new byte[] {2}, afterSuccess.encryptedPayload());

    // 重新植入 lease 进行 finalizeFailure 验证
    repository.setDirect(
        new PluginCredentialRow(
            "p1",
            new byte[] {2},
            "CN",
            now.plusSeconds(2000),
            now.plusSeconds(1000),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            "tok-2",
            now.plusSeconds(60),
            6L,
            now,
            now));

    // 4. finalizeFailure 成功
    assertTrue(
        repository.finalizeFailure(
            "p1",
            "tok-2",
            6L,
            PluginCredentialStatus.REFRESH_FAILED,
            now.plusSeconds(500),
            "err",
            now));
    PluginCredentialRow afterFailure = repository.getDirect("p1");
    assertEquals(7L, afterFailure.version());
    assertEquals(PluginCredentialStatus.REFRESH_FAILED, afterFailure.status());
    assertEquals("err", afterFailure.lastRefreshError());
    assertNull(afterFailure.refreshLeaseToken());

    // 重新植入 lease 进行 releaseLease 验证
    repository.setDirect(
        new PluginCredentialRow(
            "p1",
            new byte[] {2},
            "CN",
            now.plusSeconds(2000),
            now.plusSeconds(1000),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            "tok-3",
            now.plusSeconds(60),
            7L,
            now,
            now));

    // 5. releaseLease 成功：lease 清空且推进 version+1，状态保持 CONNECTED
    assertTrue(repository.releaseLease("p1", "tok-3", 7L, now));
    PluginCredentialRow afterRelease = repository.getDirect("p1");
    assertEquals(8L, afterRelease.version());
    assertEquals(PluginCredentialStatus.CONNECTED, afterRelease.status());
    assertNull(afterRelease.refreshLeaseToken());
  }

  /** stealVersion 与 changeLeaseToken 辅助方法契约断言。 */
  @Test
  void helperMethodsMutateStateCorrectlyForConcurrencySimulation() {
    PluginCredentialRow row =
        new PluginCredentialRow(
            "p1",
            new byte[] {1},
            "CN",
            now.plusSeconds(1000),
            now,
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            "tok-1",
            now.plusSeconds(60),
            10L,
            now,
            now);
    repository.setDirect(row);

    repository.stealVersion("p1");
    assertEquals(11L, repository.getDirect("p1").version());

    repository.changeLeaseToken("p1", "stolen-tok");
    assertEquals("stolen-tok", repository.getDirect("p1").refreshLeaseToken());
  }
}
