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
import java.util.UUID;

/**
 * 内存凭据仓库自身模拟 SQL 语义的单元测试。
 *
 * <p>逐条验证 insert、replace、find、delete、markExpiredLeasesUncertain、claimDue、finalizeSuccess、
 * finalizeFailure、releaseLease、ownsUnexpiredLease、rejectUsedCredential 以及版本偷取等行为与 PostgreSQL
 * 实现的一致性。
 */
class InMemoryPluginCredentialRepositoryTest {

  /** 本测试使用的固定认证代际：仓库/CAS 断言只要求同代际内一致。 */
  private static final UUID AUTH_GENERATION =
      UUID.fromString("00000000-0000-0000-0000-0000000000b1");

  /** 本测试使用的固定 64 位十六进制认证上下文指纹。 */
  private static final String CONTEXT = "a1".repeat(32);

  /** 用于断言上下文指纹不匹配场景的备用指纹。 */
  private static final String OTHER_CONTEXT = "b2".repeat(32);

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
        AUTH_GENERATION,
        CONTEXT,
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

  /** 构造指定认证代际的行，用于断言 replace 采用传入代际。 */
  private PluginCredentialRow rowWithGeneration(
      String pluginId, UUID authGeneration, Instant nextRefreshAt) {
    return new PluginCredentialRow(
        pluginId,
        authGeneration,
        CONTEXT,
        new byte[] {1, 2, 3},
        "CN",
        now.plusSeconds(3600),
        nextRefreshAt,
        PluginCredentialStatus.CONNECTED,
        null,
        null,
        null,
        null,
        0L,
        now,
        now);
  }

  /** replace 采用传入的新代际（模拟 SQL 的 auth_generation = excluded）：人工完整替换必然换代际而不是保留旧值。 */
  @Test
  void replaceAdoptsTheIncomingAuthGenerationOnEveryReplace() {
    UUID firstGeneration = UUID.fromString("11111111-1111-1111-1111-111111111111");
    UUID secondGeneration = UUID.fromString("22222222-2222-2222-2222-222222222222");

    PluginCredentialRow firstRow = rowWithGeneration("p1", firstGeneration, now.plusSeconds(100));
    assertTrue(repository.insert(firstRow));
    assertFalse(repository.insert(firstRow));
    assertEquals(firstGeneration, repository.getDirect("p1").authGeneration());
    assertEquals(0L, repository.getDirect("p1").version());

    PluginCredentialRow existing = repository.getDirect("p1");
    PluginCredentialRow secondRow = rowWithGeneration("p1", secondGeneration, now.plusSeconds(200));

    // CAS 围栏校验：代际、上下文指纹或 version 不匹配时 replace 返回 false 且不改变原行
    assertFalse(
        repository.replace(
            secondRow, UUID.randomUUID(), existing.contextFingerprint(), existing.version()));
    assertFalse(
        repository.replace(
            secondRow, existing.authGeneration(), OTHER_CONTEXT, existing.version()));
    assertFalse(
        repository.replace(
            secondRow,
            existing.authGeneration(),
            existing.contextFingerprint(),
            existing.version() + 1L));
    assertEquals(existing, repository.getDirect("p1"));

    assertTrue(
        repository.replace(
            secondRow,
            existing.authGeneration(),
            existing.contextFingerprint(),
            existing.version()));
    PluginCredentialRow replaced = repository.getDirect("p1");
    assertEquals(secondGeneration, replaced.authGeneration());
    assertEquals(1L, replaced.version());
  }

  /** insert 与 replace 模拟：首次插入 version=0；已存在时二次插入返回 false；replace 整体替换并推进 version+1，并清空在途 lease。 */
  @Test
  void insertAndReplaceInsertsAtVersionZeroAndUpdatesWithIncrementedVersionAndClearsLease() {
    PluginCredentialRow row =
        createRow("p1", PluginCredentialStatus.CONNECTED, now.plusSeconds(100));
    assertTrue(repository.insert(row));
    // 二次 insert 返回 false 且不改变已有行
    assertFalse(repository.insert(row));

    PluginCredentialRow inserted = repository.getDirect("p1");
    assertNotNull(inserted);
    assertEquals(0L, inserted.version());
    assertNull(inserted.refreshLeaseToken());

    // 人工加上 lease
    repository.setDirect(
        new PluginCredentialRow(
            inserted.pluginId(),
            inserted.authGeneration(),
            inserted.contextFingerprint(),
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

    PluginCredentialRow current = repository.getDirect("p1");

    // 再次 replace：覆盖字段并清空 lease，version 推进
    PluginCredentialRow replacement =
        new PluginCredentialRow(
            "p1",
            AUTH_GENERATION,
            CONTEXT,
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

    // 围栏校验：代际、上下文指纹或 version 不匹配时 replace 返回 false 且原行不受影响
    assertFalse(
        repository.replace(
            replacement, UUID.randomUUID(), current.contextFingerprint(), current.version()));
    assertFalse(
        repository.replace(
            replacement, current.authGeneration(), OTHER_CONTEXT, current.version()));
    assertFalse(
        repository.replace(
            replacement,
            current.authGeneration(),
            current.contextFingerprint(),
            current.version() + 1L));
    assertEquals(current, repository.getDirect("p1"));

    assertTrue(
        repository.replace(
            replacement,
            current.authGeneration(),
            current.contextFingerprint(),
            current.version()));

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
    assertTrue(repository.find("non-existent").isEmpty());

    PluginCredentialRow row =
        createRow("p1", PluginCredentialStatus.CONNECTED, now.plusSeconds(100));
    assertTrue(repository.insert(row));

    Optional<PluginCredentialRow> foundOpt = repository.find("p1");
    assertTrue(foundOpt.isPresent());
    PluginCredentialRow found = foundOpt.get();

    byte[] payload = found.encryptedPayload();
    payload[0] = 99; // 尝试污染返回数组

    PluginCredentialRow reRead = repository.getDirect("p1");
    assertEquals(
        1, reRead.encryptedPayload()[0], "repository internal array must not be corrupted");
  }

  /** delete 模拟：只有代际、上下文指纹与 version 全部匹配时才删除；不匹配时返回 false 且不删行；删除存在行返回 true，重复删除返回 false。 */
  @Test
  void deleteReturnsTrueOnlyWhenRowExistedAndFenced() {
    assertFalse(repository.delete("not-exist", AUTH_GENERATION, CONTEXT, 0L));

    PluginCredentialRow row = createRow("p1", PluginCredentialStatus.CONNECTED, now);
    assertTrue(repository.insert(row));
    PluginCredentialRow current = repository.getDirect("p1");

    // 不匹配条件：代际、上下文指纹或 version 不匹配均返回 false 且行保留
    assertFalse(repository.delete("p1", UUID.randomUUID(), CONTEXT, current.version()));
    assertFalse(repository.delete("p1", AUTH_GENERATION, OTHER_CONTEXT, current.version()));
    assertFalse(repository.delete("p1", AUTH_GENERATION, CONTEXT, current.version() + 1L));
    assertTrue(repository.find("p1").isPresent());

    // 匹配删除成功
    assertTrue(repository.delete("p1", AUTH_GENERATION, CONTEXT, current.version()));
    assertTrue(repository.find("p1").isEmpty());

    // 重复删除返回 false
    assertFalse(repository.delete("p1", AUTH_GENERATION, CONTEXT, current.version()));
  }

  /** markExpiredLeasesUncertain 模拟：仅对 token != null 且 until <= now 且在列表内的行生效。 */
  @Test
  void markExpiredLeasesUncertainOnlyAffectsExpiredInFlightRowsInIdList() {
    assertEquals(0, repository.markExpiredLeasesUncertain(null, now, "error"));
    assertEquals(0, repository.markExpiredLeasesUncertain(List.of(), now, "error"));

    // 1. 已过期 lease（until <= now）
    PluginCredentialRow expired =
        new PluginCredentialRow(
            "expired-p",
            AUTH_GENERATION,
            CONTEXT,
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
            AUTH_GENERATION,
            CONTEXT,
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
            AUTH_GENERATION,
            CONTEXT,
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
            AUTH_GENERATION,
            CONTEXT,
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
    assertEquals(CONTEXT, afterExpired.contextFingerprint());

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
    Instant leaseUntil = now.plusSeconds(120);
    assertTrue(repository.claimDue(null, now, leaseUntil, "tok", 10).isEmpty());
    assertTrue(repository.claimDue(List.of(), now, leaseUntil, "tok", 10).isEmpty());
    assertTrue(repository.claimDue(List.of("p1"), now, leaseUntil, "tok", 0).isEmpty());

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
            AUTH_GENERATION,
            CONTEXT,
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

    List<PluginCredentialRow> claimed =
        repository.claimDue(queryIds, now, leaseUntil, "new-token", 10);
    assertEquals(2, claimed.size());
    // 排序校验：p2 (now-20) 优先于 p1 (now-10)
    assertEquals("p2", claimed.get(0).pluginId());
    assertEquals("p1", claimed.get(1).pluginId());

    assertEquals("new-token", claimed.get(0).refreshLeaseToken());
    assertEquals(1L, claimed.get(0).version());
    assertEquals(CONTEXT, claimed.get(0).contextFingerprint());

    // 验证 limit 语义：单次只取 1
    repository.setDirect(createRow("q1", PluginCredentialStatus.CONNECTED, now.minusSeconds(10)));
    repository.setDirect(createRow("q2", PluginCredentialStatus.CONNECTED, now.minusSeconds(20)));
    List<PluginCredentialRow> limited =
        repository.claimDue(List.of("q1", "q2"), now, leaseUntil, "tok", 1);
    assertEquals(1, limited.size());
    assertEquals("q2", limited.get(0).pluginId());
  }

  /** 条件终结与释放 lease：只有代际、上下文指纹、token 和 version 同时匹配才生效，否则不改动任何字段。 */
  @Test
  void conditionalFinalizeAndReleaseRequireMatchingGenerationContextTokenAndVersion() {
    PluginCredentialRow row =
        new PluginCredentialRow(
            "p1",
            AUTH_GENERATION,
            CONTEXT,
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
            AUTH_GENERATION,
            CONTEXT,
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
            AUTH_GENERATION,
            CONTEXT,
            "tok-1",
            4L,
            new byte[] {2},
            "CN",
            now.plusSeconds(2000),
            now.plusSeconds(1000),
            now,
            now));
    assertEquals(5L, repository.getDirect("p1").version());

    // 3. finalizeSuccess: authGeneration 错
    assertFalse(
        repository.finalizeSuccess(
            "p1",
            UUID.randomUUID(),
            CONTEXT,
            "tok-1",
            5L,
            new byte[] {2},
            "CN",
            now.plusSeconds(2000),
            now.plusSeconds(1000),
            now,
            now));
    assertEquals(5L, repository.getDirect("p1").version());

    // 4. finalizeSuccess: contextFingerprint 错
    assertFalse(
        repository.finalizeSuccess(
            "p1",
            AUTH_GENERATION,
            OTHER_CONTEXT,
            "tok-1",
            5L,
            new byte[] {2},
            "CN",
            now.plusSeconds(2000),
            now.plusSeconds(1000),
            now,
            now));
    assertEquals(5L, repository.getDirect("p1").version());

    // 5. finalizeSuccess: 匹配成功
    assertTrue(
        repository.finalizeSuccess(
            "p1",
            AUTH_GENERATION,
            CONTEXT,
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
    assertEquals(PluginCredentialStatus.CONNECTED, afterSuccess.status());

    // 重新植入 lease 进行 finalizeFailure 验证
    repository.setDirect(
        new PluginCredentialRow(
            "p1",
            AUTH_GENERATION,
            CONTEXT,
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

    // finalizeFailure 围栏：代际/上下文/token/version 不匹配均失败且不改动原行
    assertFalse(
        repository.finalizeFailure(
            "p1",
            AUTH_GENERATION,
            CONTEXT,
            "wrong-tok",
            6L,
            PluginCredentialStatus.REFRESH_FAILED,
            now.plusSeconds(500),
            "err",
            now));
    assertFalse(
        repository.finalizeFailure(
            "p1",
            AUTH_GENERATION,
            CONTEXT,
            "tok-2",
            5L,
            PluginCredentialStatus.REFRESH_FAILED,
            now.plusSeconds(500),
            "err",
            now));
    assertFalse(
        repository.finalizeFailure(
            "p1",
            UUID.randomUUID(),
            CONTEXT,
            "tok-2",
            6L,
            PluginCredentialStatus.REFRESH_FAILED,
            now.plusSeconds(500),
            "err",
            now));
    assertFalse(
        repository.finalizeFailure(
            "p1",
            AUTH_GENERATION,
            OTHER_CONTEXT,
            "tok-2",
            6L,
            PluginCredentialStatus.REFRESH_FAILED,
            now.plusSeconds(500),
            "err",
            now));
    assertEquals(6L, repository.getDirect("p1").version());
    assertEquals("tok-2", repository.getDirect("p1").refreshLeaseToken());

    // 6. finalizeFailure 成功
    assertTrue(
        repository.finalizeFailure(
            "p1",
            AUTH_GENERATION,
            CONTEXT,
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
            AUTH_GENERATION,
            CONTEXT,
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

    // releaseLease 围栏：代际/上下文/token/version 不匹配均失败且不改动原行
    assertFalse(repository.releaseLease("p1", AUTH_GENERATION, CONTEXT, "wrong-tok", 7L, now));
    assertFalse(repository.releaseLease("p1", AUTH_GENERATION, CONTEXT, "tok-3", 6L, now));
    assertFalse(repository.releaseLease("p1", UUID.randomUUID(), CONTEXT, "tok-3", 7L, now));
    assertFalse(repository.releaseLease("p1", AUTH_GENERATION, OTHER_CONTEXT, "tok-3", 7L, now));
    assertEquals(7L, repository.getDirect("p1").version());
    assertEquals("tok-3", repository.getDirect("p1").refreshLeaseToken());

    // 7. releaseLease 成功：lease 清空且推进 version+1，状态保持 CONNECTED
    assertTrue(repository.releaseLease("p1", AUTH_GENERATION, CONTEXT, "tok-3", 7L, now));
    PluginCredentialRow afterRelease = repository.getDirect("p1");
    assertEquals(8L, afterRelease.version());
    assertEquals(PluginCredentialStatus.CONNECTED, afterRelease.status());
    assertNull(afterRelease.refreshLeaseToken());
  }

  /**
   * 验证上下文指纹的完整隔离与围栏：在 pluginId、authGeneration、version 与 leaseToken 均相同的情况下，不同的 contextFingerprint
   * 依然无法命中任何 CAS 操作。
   */
  @Test
  void contextFingerprintFencesAllMutationsAndQueries() {
    String pluginId = "fence-p";
    byte[] payload = new byte[] {1, 2};
    PluginCredentialRow row =
        new PluginCredentialRow(
            pluginId,
            AUTH_GENERATION,
            CONTEXT,
            payload,
            "CN",
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
    PluginCredentialRow current = repository.getDirect(pluginId);
    long version = current.version();

    // 针对当前已持有 lease 的行，验证以下 7 个操作使用 OTHER_CONTEXT 时均返回 false 且行内容完全不变：
    // 1. replace
    PluginCredentialRow replacement =
        new PluginCredentialRow(
            pluginId,
            AUTH_GENERATION,
            OTHER_CONTEXT,
            new byte[] {9},
            "US",
            now.plusSeconds(7200),
            now.plusSeconds(3600),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            null,
            null,
            0L,
            now,
            now);
    assertFalse(repository.replace(replacement, AUTH_GENERATION, OTHER_CONTEXT, version));
    assertEquals(current, repository.getDirect(pluginId));

    // 2. delete
    assertFalse(repository.delete(pluginId, AUTH_GENERATION, OTHER_CONTEXT, version));
    assertEquals(current, repository.getDirect(pluginId));

    // 3. finalizeSuccess
    assertFalse(
        repository.finalizeSuccess(
            pluginId,
            AUTH_GENERATION,
            OTHER_CONTEXT,
            "context-lease",
            version,
            payload,
            "CN",
            now.plusSeconds(3600),
            now.plusSeconds(1800),
            now,
            now));
    assertEquals(current, repository.getDirect(pluginId));

    // 4. finalizeFailure
    assertFalse(
        repository.finalizeFailure(
            pluginId,
            AUTH_GENERATION,
            OTHER_CONTEXT,
            "context-lease",
            version,
            PluginCredentialStatus.REFRESH_FAILED,
            now.plusSeconds(60),
            "fence error",
            now));
    assertEquals(current, repository.getDirect(pluginId));

    // 5. releaseLease
    assertFalse(
        repository.releaseLease(
            pluginId, AUTH_GENERATION, OTHER_CONTEXT, "context-lease", version, now));
    assertEquals(current, repository.getDirect(pluginId));

    // 6. ownsUnexpiredLease
    assertFalse(
        repository.ownsUnexpiredLease(
            pluginId, AUTH_GENERATION, OTHER_CONTEXT, "context-lease", version, now));
    assertEquals(current, repository.getDirect(pluginId));

    // 7. rejectUsedCredential
    assertFalse(
        repository.rejectUsedCredential(
            pluginId, AUTH_GENERATION, OTHER_CONTEXT, version, payload, "reject error", now));
    assertEquals(current, repository.getDirect(pluginId));

    // 验证使用正确的 CONTEXT 时操作能正常通过
    assertTrue(
        repository.ownsUnexpiredLease(
            pluginId, AUTH_GENERATION, CONTEXT, "context-lease", version, now));
    assertTrue(
        repository.releaseLease(pluginId, AUTH_GENERATION, CONTEXT, "context-lease", version, now));
    PluginCredentialRow released = repository.getDirect(pluginId);
    assertNull(released.refreshLeaseToken());
    assertEquals(version + 1L, released.version());
  }

  /** ownsUnexpiredLease 要求代际、上下文指纹、token、version 与未过期截止时刻全部匹配。 */
  @Test
  void
      ownsUnexpiredLeaseRequiresMatchingAuthGenerationContextFingerprintTokenVersionAndUnexpiredDeadline() {
    String pluginId = "lease-own-p";
    PluginCredentialRow row =
        createRow(pluginId, PluginCredentialStatus.CONNECTED, now.minusSeconds(1));
    assertTrue(repository.insert(row));

    List<PluginCredentialRow> claimed =
        repository.claimDue(List.of(pluginId), now, now.plusSeconds(60), "lease-tok", 1);
    assertEquals(1, claimed.size());
    long version = claimed.get(0).version();

    assertTrue(
        repository.ownsUnexpiredLease(
            pluginId, AUTH_GENERATION, CONTEXT, "lease-tok", version, now));
    assertFalse(
        repository.ownsUnexpiredLease(
            pluginId, AUTH_GENERATION, CONTEXT, "other-tok", version, now));
    assertFalse(
        repository.ownsUnexpiredLease(
            pluginId, AUTH_GENERATION, CONTEXT, "lease-tok", version + 1L, now));
    assertFalse(
        repository.ownsUnexpiredLease(
            pluginId, AUTH_GENERATION, OTHER_CONTEXT, "lease-tok", version, now));
    assertFalse(
        repository.ownsUnexpiredLease(
            pluginId, UUID.randomUUID(), CONTEXT, "lease-tok", version, now));
    assertFalse(
        repository.ownsUnexpiredLease(
            pluginId, AUTH_GENERATION, CONTEXT, "lease-tok", version, now.plusSeconds(60)));
    assertFalse(
        repository.ownsUnexpiredLease(
            "not-exist", AUTH_GENERATION, CONTEXT, "lease-tok", version, now));
  }

  /** rejectUsedCredential 在代际与上下文一致的前提下按 version 或原始密文 CAS 作废本行；并发重新登录的新代际与新密文不受影响。 */
  @Test
  void
      rejectUsedCredentialMatchesVersionOrCiphertextWhenAuthGenerationAndContextFingerprintMatch() {
    String pluginId = "reject-p";
    byte[] used = new byte[] {1, 2, 3};
    byte[] otherPayload = new byte[] {4, 5, 6};

    // 1. version 匹配：密文不匹配但 version 匹配时成功作废
    PluginCredentialRow row1 =
        new PluginCredentialRow(
            pluginId,
            AUTH_GENERATION,
            CONTEXT,
            used,
            "CN",
            now.plusSeconds(3600),
            now.minusSeconds(1),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            "tok",
            now.plusSeconds(60),
            1L,
            now,
            now);
    repository.setDirect(row1);

    assertTrue(
        repository.rejectUsedCredential(
            pluginId, AUTH_GENERATION, CONTEXT, 1L, otherPayload, "auth rejected", now));
    PluginCredentialRow rejected1 = repository.getDirect(pluginId);
    assertEquals(PluginCredentialStatus.REAUTH_REQUIRED, rejected1.status());
    assertEquals("auth rejected", rejected1.lastRefreshError());
    assertNull(rejected1.refreshLeaseToken());
    assertEquals(2L, rejected1.version());

    // 2. 密文匹配：version 不匹配但密文匹配时成功作废
    PluginCredentialRow row2 =
        new PluginCredentialRow(
            pluginId,
            AUTH_GENERATION,
            CONTEXT,
            used,
            "CN",
            now.plusSeconds(3600),
            now.minusSeconds(1),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            null,
            null,
            5L,
            now,
            now);
    repository.setDirect(row2);

    assertTrue(
        repository.rejectUsedCredential(
            pluginId, AUTH_GENERATION, CONTEXT, 0L, used, "auth rejected by ciphertext", now));
    PluginCredentialRow rejected2 = repository.getDirect(pluginId);
    assertEquals(PluginCredentialStatus.REAUTH_REQUIRED, rejected2.status());
    assertEquals("auth rejected by ciphertext", rejected2.lastRefreshError());
    assertEquals(6L, rejected2.version());

    // 3. 代际不同或上下文不同或（version 与密文均不匹配）时不作废
    PluginCredentialRow current = repository.getDirect(pluginId);
    assertFalse(
        repository.rejectUsedCredential(
            pluginId,
            UUID.randomUUID(),
            CONTEXT,
            current.version(),
            current.encryptedPayload(),
            "err",
            now));
    assertFalse(
        repository.rejectUsedCredential(
            pluginId,
            AUTH_GENERATION,
            OTHER_CONTEXT,
            current.version(),
            current.encryptedPayload(),
            "err",
            now));
    assertFalse(
        repository.rejectUsedCredential(
            pluginId, AUTH_GENERATION, CONTEXT, 999L, otherPayload, "err", now));
    assertFalse(
        repository.rejectUsedCredential(
            "not-exist", AUTH_GENERATION, CONTEXT, 0L, used, "err", now));
    assertEquals(current, repository.getDirect(pluginId));
  }

  /** earliestRefreshAt 返回可调度的最早刷新时刻，忽略已持有未过期 lease 的行与非调度状态行。 */
  @Test
  void earliestRefreshAtReturnsEarliestSchedulableUnleasedRefreshTime() {
    assertTrue(repository.earliestRefreshAt(null, now).isEmpty());
    assertTrue(repository.earliestRefreshAt(List.of(), now).isEmpty());

    String dueSoon = "p-soon";
    String leased = "p-leased";
    String inactive = "p-inactive";

    repository.insert(createRow(dueSoon, PluginCredentialStatus.CONNECTED, now.plusSeconds(60)));
    repository.insert(createRow(leased, PluginCredentialStatus.CONNECTED, now.minusSeconds(5)));
    repository.insert(
        createRow(inactive, PluginCredentialStatus.REAUTH_REQUIRED, now.plusSeconds(1)));

    // 占用 leased 行的租约
    assertEquals(
        1, repository.claimDue(List.of(leased), now, now.plusSeconds(300), "lease-tok", 1).size());

    Optional<Instant> earliest =
        repository.earliestRefreshAt(List.of(dueSoon, leased, inactive), now);
    assertTrue(earliest.isPresent());
    assertEquals(now.plusSeconds(60), earliest.get());
  }

  /** stealVersion 与 changeLeaseToken 辅助方法契约断言。 */
  @Test
  void helperMethodsMutateStateCorrectlyForConcurrencySimulation() {
    PluginCredentialRow row =
        new PluginCredentialRow(
            "p1",
            AUTH_GENERATION,
            CONTEXT,
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

    assertEquals(1, repository.size());
    repository.stealVersion("p1");
    assertEquals(11L, repository.getDirect("p1").version());

    repository.changeLeaseToken("p1", "stolen-tok");
    assertEquals("stolen-tok", repository.getDirect("p1").refreshLeaseToken());

    repository.clear();
    assertEquals(0, repository.size());
    assertNull(repository.getDirect("p1"));
  }
}
