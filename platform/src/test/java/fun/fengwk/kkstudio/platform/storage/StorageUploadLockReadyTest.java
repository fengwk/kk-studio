package fun.fengwk.kkstudio.platform.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * {@code lockReady} 消费契约的 PostgreSQL 集成测试（内存 S3 假件）。
 *
 * <p>覆盖 READY 消费返回权威文件名并持久化 cleanup request、PENDING / 过期 / 不存在确定性拒绝，以及外层事务加入
 * （提交可见、回滚整体还原——事务边界内绝不半消费）。
 */
@Import({
  StorageS3TestConfiguration.class,
  StorageUploadLockReadyTest.StorageUploadWakeupTestConfiguration.class
})
@TestPropertySource(
    properties = {
      "kk-studio.storage.s3.endpoint=http://minio.example.local:9000",
      "kk-studio.storage.s3.public-endpoint=https://cdn.example.com",
      "kk-studio.storage.s3.region=us-east-1",
      "kk-studio.storage.s3.bucket=test-bucket",
      "kk-studio.storage.s3.access-key=local-test-access-key",
      "kk-studio.storage.s3.secret-key=local-test-secret-key"
    })
class StorageUploadLockReadyTest extends PostgresSpringTestSupport {

  @Autowired private StorageUploadService storageUploadService;
  @Autowired private InMemoryS3StorageService s3Storage;
  @Autowired private StorageMaintenanceWakeup maintenanceWakeup;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate tx;
  private StorageIntegrationSupport storage;

  @BeforeEach
  void resetS3AndBuildTransactionTemplate() {
    s3Storage.clear();
    clearInvocations(maintenanceWakeup);
    storage = new StorageIntegrationSupport(storageUploadService, s3Storage, jdbc);
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
  void lockReadyConsumesReadyUploadAndReturnsAuthoritativeFilename() {
    byte[] content = "attachment".getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO pending =
        storage.reserve("report.txt", "text/plain", content.length, storage.sha256Hex(content));
    storage.putUploadContent(pending.getId(), content, "text/plain");
    StorageUploadDTO ready = storageUploadService.complete(UUID.fromString(pending.getId()));

    StorageUploadService.ReadyUpload consumed =
        tx.execute(status -> storageUploadService.lockReady(UUID.fromString(ready.getId())));

    assertEquals(UUID.fromString(ready.getBlobId()), consumed.blobId());
    assertEquals(
        "report.txt", consumed.filename(), "authoritative filename must come from the upload row");
    // lockReady 只锁定并返回消费事实；调用方 delete 在同一事务内持久化 cleanup request。
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ?",
            Integer.class,
            UUID.fromString(ready.getId())),
        "lockReady alone must keep the row until the caller deletes it");
    assertEquals(
        1L,
        storage.blobRefCount(ready.getBlobId()),
        "consuming the upload must not release the blob; the owner releases separately");
    assertTrue(
        s3Storage.hasObject(StorageObjectKeys.blobOriginal(UUID.fromString(ready.getBlobId()))));
    s3Storage.clearNetworkCalls();
    storageUploadService.delete(UUID.fromString(ready.getId()));
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ? and cleanup_requested_at is not null",
            Integer.class,
            UUID.fromString(ready.getId())));
    assertTrue(
        s3Storage.networkCalls().isEmpty(),
        "consuming delete must not perform S3 I/O before maintenance");
    verify(maintenanceWakeup, atLeastOnce()).wake();
    assertEquals(1, storageUploadService.expireOnce());
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ?",
            Integer.class,
            UUID.fromString(ready.getId())),
        "maintenance consumes the requested upload row");
  }

  @Test
  void lockReadyRejectsPendingUploadAndKeepsRow() {
    StorageUploadDTO pending =
        storage.reserve(
            "pending.bin", "application/octet-stream", 1, storage.sha256Hex(new byte[] {1}));
    StorageVerificationException error =
        assertThrows(
            StorageVerificationException.class,
            () ->
                tx.execute(
                    status -> storageUploadService.lockReady(UUID.fromString(pending.getId()))));
    assertTrue(error.getMessage().contains("PENDING"), "actual: " + error.getMessage());
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ?",
            Integer.class,
            UUID.fromString(pending.getId())),
        "rejected PENDING upload must remain consumable later");
  }

  @Test
  void lockReadyRejectsExpiredUpload() {
    byte[] content = "stale".getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO pending =
        storage.reserve(
            "stale.bin", "application/octet-stream", content.length, storage.sha256Hex(content));
    storage.putUploadContent(pending.getId(), content, "application/octet-stream");
    StorageUploadDTO ready = storageUploadService.complete(UUID.fromString(pending.getId()));
    storage.backdateUpload(ready.getId());

    StorageVerificationException error =
        assertThrows(
            StorageVerificationException.class,
            () ->
                tx.execute(
                    status -> storageUploadService.lockReady(UUID.fromString(ready.getId()))));
    assertTrue(error.getMessage().contains("expired"), "actual: " + error.getMessage());
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ?",
            Integer.class,
            UUID.fromString(ready.getId())));
  }

  @Test
  void lockReadyUnknownUploadThrowsNotFound() {
    assertThrows(
        StorageResourceNotFoundException.class,
        () -> tx.execute(status -> storageUploadService.lockReady(UUID.randomUUID())));
  }

  @Test
  void lockReadyJoinsOuterTransactionAndRollsBackAtomically() {
    byte[] content = "atomic".getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO pending =
        storage.reserve(
            "atomic.bin", "application/octet-stream", content.length, storage.sha256Hex(content));
    storage.putUploadContent(pending.getId(), content, "application/octet-stream");
    StorageUploadDTO ready = storageUploadService.complete(UUID.fromString(pending.getId()));
    UUID uploadUuid = UUID.fromString(ready.getId());

    // 回滚：lockReady + delete 的消费标记必须随外层事务一并还原。
    try {
      tx.execute(
          new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
              storageUploadService.lockReady(uploadUuid);
              storageUploadService.delete(uploadUuid);
              throw new IllegalStateException("force rollback");
            }
          });
    } catch (IllegalStateException expected) {
      // 预期回滚。
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ?", Integer.class, uploadUuid),
        "rollback must restore the consumed upload row");
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ? and cleanup_requested_at is not null",
            Integer.class,
            uploadUuid),
        "rollback must not persist the cleanup request");
    assertEquals(
        1L,
        jdbc.queryForObject(
            "select ref_count from storage_blob where id = ?",
            Long.class,
            UUID.fromString(ready.getBlobId())),
        "rollback must not release the upload reference");
    verify(maintenanceWakeup, never()).wake();

    // 提交：消费生效。
    tx.execute(
        status -> {
          storageUploadService.lockReady(uploadUuid);
          storageUploadService.delete(uploadUuid);
          return null;
        });
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ? and cleanup_requested_at is not null",
            Integer.class,
            uploadUuid));
    verify(maintenanceWakeup, atLeastOnce()).wake();
    assertEquals(1, storageUploadService.expireOnce());
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ?", Integer.class, uploadUuid));
  }

  /**
   * 测试意图：{@code peekReady} 是只读事实读取——返回权威 blobId 与权威文件名，但不加行锁、不标记 cleanup、不换 blob、不做任何 S3 I/O，因此
   * upload 之后仍可被 {@code lockReady} 正式消费。只读预览正依赖这一点。
   */
  @Test
  void peekReadyReturnsAuthoritativeFactsWithoutConsumingTheUpload() {
    byte[] content = "preview".getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO pending =
        storage.reserve("preview.txt", "text/plain", content.length, storage.sha256Hex(content));
    storage.putUploadContent(pending.getId(), content, "text/plain");
    StorageUploadDTO ready = storageUploadService.complete(UUID.fromString(pending.getId()));
    s3Storage.clearNetworkCalls();

    // peek 不需要活动事务：只读预览路径就是这样直接调用它的。
    StorageUploadService.ReadyUpload peeked =
        storageUploadService.peekReady(UUID.fromString(ready.getId()));

    assertEquals(UUID.fromString(ready.getBlobId()), peeked.blobId());
    assertEquals("preview.txt", peeked.filename(), "权威文件名必须来自 upload 行");
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ?"
                + " and cleanup_requested_at is null and cleanup_token is null",
            Integer.class,
            UUID.fromString(ready.getId())),
        "peek 不得推进任何生命周期标记");
    assertEquals(1L, storage.blobRefCount(ready.getBlobId()), "peek 不得 retain 或 release blob ref");
    assertTrue(s3Storage.networkCalls().isEmpty(), "peek 不得产生任何 S3 I/O");

    // peek 不是消费授权：之后仍必须能被正式消费。
    tx.execute(
        status -> {
          storageUploadService.lockReady(UUID.fromString(ready.getId()));
          storageUploadService.delete(UUID.fromString(ready.getId()));
          return null;
        });
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ? and cleanup_requested_at is not null",
            Integer.class,
            UUID.fromString(ready.getId())));
  }

  /**
   * 测试意图：{@code peekReady} 与 {@code lockReady} 共用同一份 READY 判定——PENDING、已过期、已被正式消费（cleanup 已请求）与不存在的
   * upload 一律确定性拒绝，且被拒绝的行保持原样。
   */
  @Test
  void peekReadyRejectsUnreadyUploadsWithTheSameJudgmentAsLockReady() {
    StorageUploadDTO pending =
        storage.reserve(
            "pending-peek.bin", "application/octet-stream", 1, storage.sha256Hex(new byte[] {1}));
    StorageVerificationException pendingError =
        assertThrows(
            StorageVerificationException.class,
            () -> storageUploadService.peekReady(UUID.fromString(pending.getId())));
    assertTrue(
        pendingError.getMessage().contains("PENDING"), "actual: " + pendingError.getMessage());

    byte[] expiredContent = "expired-peek".getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO expiring =
        storage.reserve(
            "expired-peek.bin",
            "application/octet-stream",
            expiredContent.length,
            storage.sha256Hex(expiredContent));
    storage.putUploadContent(expiring.getId(), expiredContent, "application/octet-stream");
    StorageUploadDTO expired = storageUploadService.complete(UUID.fromString(expiring.getId()));
    storage.backdateUpload(expired.getId());
    StorageVerificationException expiredError =
        assertThrows(
            StorageVerificationException.class,
            () -> storageUploadService.peekReady(UUID.fromString(expired.getId())));
    assertTrue(
        expiredError.getMessage().contains("expired"), "actual: " + expiredError.getMessage());

    byte[] consumedContent = "consumed-peek".getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO consumable =
        storage.reserve(
            "consumed-peek.bin",
            "application/octet-stream",
            consumedContent.length,
            storage.sha256Hex(consumedContent));
    storage.putUploadContent(consumable.getId(), consumedContent, "application/octet-stream");
    StorageUploadDTO consumed = storageUploadService.complete(UUID.fromString(consumable.getId()));
    tx.execute(
        status -> {
          storageUploadService.lockReady(UUID.fromString(consumed.getId()));
          storageUploadService.delete(UUID.fromString(consumed.getId()));
          return null;
        });
    StorageVerificationException consumedError =
        assertThrows(
            StorageVerificationException.class,
            () -> storageUploadService.peekReady(UUID.fromString(consumed.getId())));
    assertTrue(
        consumedError.getMessage().contains("cleanup was requested"),
        "actual: " + consumedError.getMessage());

    assertThrows(
        StorageResourceNotFoundException.class,
        () -> storageUploadService.peekReady(UUID.randomUUID()));

    // 被拒绝的 PENDING 行必须保持可完成状态。
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ?"
                + " and blob_id is null and cleanup_requested_at is null",
            Integer.class,
            UUID.fromString(pending.getId())));
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class StorageUploadWakeupTestConfiguration {

    @Bean
    @Primary
    StorageMaintenanceWakeup storageUploadWakeup() {
      return mock(StorageMaintenanceWakeup.class);
    }
  }
}
