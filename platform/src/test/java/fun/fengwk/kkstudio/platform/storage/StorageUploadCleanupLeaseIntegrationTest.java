package fun.fengwk.kkstudio.platform.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageUploadRepository;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageUpload;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * {@code storage_upload} cleanup lease 的真实 PostgreSQL 集成测试。
 *
 * <p>覆盖多节点 claim 唯一性、lease 过期恢复、token fence、对象失败保留事实重试，以及 complete/consume 对 cleanup 所有权的
 * fail-closed 语义。
 */
@Import(StorageS3TestConfiguration.class)
@TestPropertySource(
    properties = {
      "kk-studio.storage.s3.endpoint=http://minio.example.local:9000",
      "kk-studio.storage.s3.public-endpoint=https://cdn.example.com",
      "kk-studio.storage.s3.region=us-east-1",
      "kk-studio.storage.s3.bucket=test-bucket",
      "kk-studio.storage.s3.access-key=local-test-access-key",
      "kk-studio.storage.s3.secret-key=local-test-secret-key",
      "kk-studio.storage.maintenance.cleanup-lease=100ms"
    })
class StorageUploadCleanupLeaseIntegrationTest extends S3PostgresSpringTestSupport {

  @Autowired private StorageUploadRepository uploadRepository;
  @Autowired private StorageUploadService uploadService;
  @Autowired private InMemoryS3StorageService s3Storage;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  // 本类直接验证 claim/lease SQL，禁用会异步 claim 同一批 upload 的后台 maintenance。
  @MockitoBean private StorageMaintenance storageMaintenance;

  private StorageIntegrationSupport storage;
  private TransactionTemplate tx;

  @BeforeEach
  void setUpStorage() {
    s3Storage.clear();
    storage = new StorageIntegrationSupport(uploadService, s3Storage, jdbc);
    tx = new TransactionTemplate(transactionManager);
  }

  @AfterEach
  void everyS3CallRunsOutsideDatabaseTransactions() {
    assertTrue(
        s3Storage.networkCalls().stream()
            .noneMatch(InMemoryS3StorageService.NetworkCall::transactionActive),
        "S3 calls must run outside database transactions: " + s3Storage.networkCalls());
  }

  @Test
  void concurrentNodesClaimEachExpiredUploadExactlyOnce() throws Exception {
    for (int i = 0; i < 21; i++) {
      StorageUploadDTO upload =
          storage.reserve(
              "claim-" + i + ".bin",
              "application/octet-stream",
              1,
              storage.sha256Hex(new byte[] {(byte) i}));
      storage.backdateUpload(upload.getId());
    }

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<List<StorageUpload>> first =
          executor.submit(
              () -> {
                start.await(30, TimeUnit.SECONDS);
                Instant now = Instant.now();
                return tx.execute(
                    status ->
                        uploadRepository.claimExpired(16, now, now.plusSeconds(30), "node-a"));
              });
      Future<List<StorageUpload>> second =
          executor.submit(
              () -> {
                start.await(30, TimeUnit.SECONDS);
                Instant now = Instant.now();
                return tx.execute(
                    status ->
                        uploadRepository.claimExpired(16, now, now.plusSeconds(30), "node-b"));
              });
      start.countDown();

      List<StorageUpload> claimedA = first.get(30, TimeUnit.SECONDS);
      List<StorageUpload> claimedB = second.get(30, TimeUnit.SECONDS);
      Set<UUID> unique = new HashSet<>();
      claimedA.forEach(upload -> assertTrue(unique.add(upload.getId())));
      claimedB.forEach(upload -> assertTrue(unique.add(upload.getId())));
      assertEquals(21, unique.size(), "SKIP LOCKED claims must be disjoint and exhaustive");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void expiredLeaseCanBeReclaimedAndOldTokenCannotFinalize() {
    StorageUploadDTO pending =
        storage.reserve(
            "fence.bin", "application/octet-stream", 1, storage.sha256Hex(new byte[] {1}));
    storage.backdateUpload(pending.getId());
    UUID uploadId = UUID.fromString(pending.getId());
    Instant firstNow = Instant.now();

    StorageUpload first =
        tx.execute(
            status ->
                uploadRepository
                    .claimExpired(1, firstNow, firstNow.plusMillis(100), "first-token")
                    .getFirst());
    assertEquals("first-token", first.getCleanupToken());
    assertTrue(
        tx.execute(
                status ->
                    uploadRepository.claimExpired(
                        1, firstNow.plusMillis(50), firstNow.plusSeconds(1), "too-early"))
            .isEmpty());

    StorageUpload reclaimed =
        tx.execute(
            status ->
                uploadRepository
                    .claimExpired(
                        1, firstNow.plusMillis(101), firstNow.plusSeconds(1), "second-token")
                    .getFirst());
    assertEquals(uploadId, reclaimed.getId());
    assertFalse(
        Boolean.TRUE.equals(
            tx.execute(status -> uploadRepository.finalizePending(uploadId, "first-token"))),
        "stale owner must be fenced");
    assertTrue(
        Boolean.TRUE.equals(
            tx.execute(status -> uploadRepository.finalizePending(uploadId, "second-token"))));
  }

  @Test
  void cleanupClaimReleaseIsTokenFenced() {
    StorageUploadDTO pending =
        storage.reserve(
            "release.bin", "application/octet-stream", 1, storage.sha256Hex(new byte[] {2}));
    UUID uploadId = UUID.fromString(pending.getId());
    Instant now = Instant.now();
    StorageUpload claimed =
        tx.execute(
            status ->
                uploadRepository.claimById(uploadId, now, now.plusSeconds(30), "release-owner"));
    assertNotNull(claimed);

    assertFalse(
        Boolean.TRUE.equals(
            tx.execute(status -> uploadRepository.releaseCleanupClaim(uploadId, "wrong-owner"))));
    assertTrue(
        Boolean.TRUE.equals(
            tx.execute(status -> uploadRepository.releaseCleanupClaim(uploadId, "release-owner"))));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ? and cleanup_token is not null",
            Integer.class,
            uploadId));
  }

  @Test
  void objectFailureKeepsLeaseAndRetriesAfterExpiry() {
    byte[] content = new byte[] {7};
    StorageUploadDTO pending =
        storage.reserve(
            "retry.bin", "application/octet-stream", content.length, storage.sha256Hex(content));
    storage.putUploadContent(pending.getId(), content, "application/octet-stream");
    storage.backdateUpload(pending.getId());
    UUID uploadId = UUID.fromString(pending.getId());
    String tempKey = StorageObjectKeys.uploadOriginal(uploadId);
    s3Storage.failNextDelete(tempKey, new IllegalStateException("temporary S3 failure"));

    assertEquals(0, uploadService.expireOnce());
    assertNotNull(
        jdbc.queryForObject(
            "select cleanup_token from storage_upload where id = ?", String.class, uploadId),
        "failure must retain the lease instead of erasing cleanup ownership");
    assertTrue(s3Storage.hasObject(tempKey));

    jdbc.update(
        "update storage_upload set cleanup_until = current_timestamp - interval '1 second'"
            + " where id = ?",
        uploadId);
    assertEquals(1, uploadService.expireOnce());
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ?", Integer.class, uploadId));
    assertFalse(s3Storage.hasObject(tempKey));
  }

  @Test
  void completeAndConsumeFailClosedOnceCleanupIsClaimed() {
    byte[] content = new byte[] {3};
    StorageUploadDTO pending =
        storage.reserve(
            "pending.bin", "application/octet-stream", content.length, storage.sha256Hex(content));
    storage.putUploadContent(pending.getId(), content, "application/octet-stream");
    UUID pendingId = UUID.fromString(pending.getId());
    Instant now = Instant.now();
    tx.execute(
        status ->
            uploadRepository.claimById(pendingId, now, now.plusSeconds(30), "pending-cleanup"));
    assertThrows(StorageVerificationException.class, () -> uploadService.complete(pendingId));

    StorageUploadDTO readyPending =
        storage.reserve(
            "ready.bin", "application/octet-stream", content.length, storage.sha256Hex(content));
    storage.putUploadContent(readyPending.getId(), content, "application/octet-stream");
    StorageUploadDTO ready = uploadService.complete(UUID.fromString(readyPending.getId()));
    UUID readyId = UUID.fromString(ready.getId());
    tx.execute(
        status -> uploadRepository.claimById(readyId, now, now.plusSeconds(30), "ready-cleanup"));

    assertThrows(
        StorageVerificationException.class,
        () -> tx.execute(status -> uploadService.lockReady(readyId)));
  }

  @Test
  void explicitCleanupRequestIsClaimedBeforeExpiry() {
    StorageUploadDTO pending =
        storage.reserve(
            "requested.bin", "application/octet-stream", 1, storage.sha256Hex(new byte[] {4}));
    UUID uploadId = UUID.fromString(pending.getId());

    s3Storage.clearNetworkCalls();
    uploadService.delete(uploadId);
    assertNotNull(
        jdbc.queryForObject(
            "select cleanup_requested_at from storage_upload where id = ?",
            Timestamp.class,
            uploadId));
    assertTrue(
        s3Storage.networkCalls().isEmpty(),
        "explicit delete must only persist a durable request before maintenance");

    StorageUpload claimed =
        tx.execute(
            status ->
                uploadRepository
                    .claimExpired(1, Instant.now(), Instant.now().plusSeconds(30), "requested-node")
                    .getFirst());
    assertEquals(uploadId, claimed.getId());
    assertNotNull(claimed.getCleanupRequestedAt());
    assertTrue(
        Boolean.TRUE.equals(
            tx.execute(status -> uploadRepository.finalizePending(uploadId, "requested-node"))));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ?", Integer.class, uploadId));
  }

  @Test
  void explicitCleanupRequestFailsClosedForCompleteAndLockReady() {
    byte[] content = new byte[] {5};
    StorageUploadDTO pending =
        storage.reserve(
            "requested-pending.bin",
            "application/octet-stream",
            content.length,
            storage.sha256Hex(content));
    UUID pendingId = UUID.fromString(pending.getId());
    uploadService.delete(pendingId);
    s3Storage.clearNetworkCalls();
    assertThrows(StorageVerificationException.class, () -> uploadService.complete(pendingId));
    assertTrue(s3Storage.networkCalls().isEmpty(), "complete must fail before any S3 call");

    StorageUploadDTO readyPending =
        storage.reserve(
            "requested-ready.bin",
            "application/octet-stream",
            content.length,
            storage.sha256Hex(content));
    storage.putUploadContent(readyPending.getId(), content, "application/octet-stream");
    StorageUploadDTO ready = uploadService.complete(UUID.fromString(readyPending.getId()));
    UUID readyId = UUID.fromString(ready.getId());
    uploadService.delete(readyId);

    assertThrows(
        StorageVerificationException.class,
        () -> tx.execute(status -> uploadService.lockReady(readyId)));
  }
}
