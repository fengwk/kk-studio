package fun.fengwk.kkstudio.platform.storage.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.storage.service.model.StorageUpload;

import java.time.Instant;
import java.util.UUID;

/**
 * 动态 upload TTL 纯策略单元测试：过期只由 {@code created_at + 当前 TTL} 决定，cleanup 一旦开始就不再受 TTL 影响。
 *
 * <p>覆盖「缩短 TTL 立即让存量过期」「延长 TTL 让未 claim 存量继续」「已 claim 即使 lease 过期也不复活为可存活上传」。
 */
class StorageUploadTtlPolicyTest {

  private static final Instant CREATED = Instant.parse("2026-10-09T00:00:00Z");

  /** 过期权威事实：同一 created_at，缩短 TTL 立即让存量过期，延长 TTL 立即恢复未 claim 存量。 */
  @Test
  void isExpiredFollowsTheCurrentTtlSnapshot() {
    Instant now = CREATED.plusSeconds(1_000);
    assertFalse(
        StorageUploadServiceImpl.isExpired(CREATED, now, 86_400L), "long TTL keeps it alive");
    assertTrue(StorageUploadServiceImpl.isExpired(CREATED, now, 900L), "short TTL expires it");
    // 边界：恰好到点即视为过期（created_at + TTL <= now）。
    assertTrue(StorageUploadServiceImpl.isExpired(CREATED, CREATED.plusSeconds(900L), 900L));
    assertFalse(StorageUploadServiceImpl.isExpired(CREATED, CREATED.plusSeconds(899L), 900L));
  }

  /** 未请求清理、未 claim 且未过期的行不是清理候选；过期的行（无有效 lease）才是。 */
  @Test
  void unclaimedRowIsDueOnlyWhenExpired() {
    Instant now = CREATED.plusSeconds(1_000);
    StorageUpload live = newUpload();
    assertFalse(StorageUploadServiceImpl.isCleanupDue(live, now, now.minusSeconds(86_400L)));
    assertTrue(StorageUploadServiceImpl.isCleanupDue(live, now, now.minusSeconds(900L)));
    // 有效 lease 保护下的过期行仍不可抢占。
    live.setCleanupToken("held");
    live.setCleanupUntil(now.plusSeconds(60));
    assertFalse(StorageUploadServiceImpl.isCleanupDue(live, now, now.minusSeconds(900L)));
  }

  /** 显式 cleanup request 不受 TTL 影响：即使未过期也必须被清理。 */
  @Test
  void explicitCleanupRequestIsAlwaysDue() {
    Instant now = CREATED.plusSeconds(1_000);
    StorageUpload requested = newUpload();
    requested.setCleanupRequestedAt(now.minusSeconds(5));
    assertTrue(StorageUploadServiceImpl.isCleanupDue(requested, now, now.minusSeconds(86_400L)));
  }

  /** 测试意图：cleanup 一旦 claim 就开始，延长 TTL 绝不复活；即使 claim lease 已过期，也只用于让任意节点续完清理， 而不是把它当作可存活上传。 */
  @Test
  void claimedCleanupIsNotResurrectedByExtendingTtl() {
    Instant now = CREATED.plusSeconds(1_000);
    StorageUpload claimed = newUpload();
    claimed.setCleanupToken("owner");
    // lease 仍然有效：其他节点不可抢占。
    claimed.setCleanupUntil(now.plusSeconds(60));
    assertFalse(StorageUploadServiceImpl.isCleanupDue(claimed, now, now.minusSeconds(86_400L)));
    // lease 过期后允许续完清理——即使 TTL 被大幅延长，该行仍然是清理事实，绝不回到可存活/可绑定状态。
    claimed.setCleanupUntil(now.minusSeconds(1));
    assertTrue(StorageUploadServiceImpl.isCleanupDue(claimed, now, now.minusSeconds(86_400L)));
  }

  /** null 行永远不是清理候选。 */
  @Test
  void nullRowIsNeverDue() {
    Instant now = Instant.parse("2026-10-09T00:00:00Z");
    assertFalse(StorageUploadServiceImpl.isCleanupDue(null, now, now.minusSeconds(900L)));
  }

  private static StorageUpload newUpload() {
    StorageUpload upload = new StorageUpload();
    upload.setId(UUID.randomUUID());
    upload.setCreateTime(CREATED);
    return upload;
  }
}
