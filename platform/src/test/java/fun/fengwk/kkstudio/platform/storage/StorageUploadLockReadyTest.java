package fun.fengwk.kkstudio.platform.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * {@code lockReady} 消费契约的 PostgreSQL 集成测试（内存 S3 假件）。
 *
 * <p>覆盖 READY 消费返回权威文件名并删除 upload 行、PENDING / 过期 / 不存在确定性拒绝，以及外层事务加入（提交可见、 回滚整体还原——事务边界内绝不半消费）。
 */
@Import({StorageS3TestConfiguration.class})
@TestPropertySource(
    properties = {
      "kk-studio.storage.s3.endpoint=http://minio.example.local:9000",
      "kk-studio.storage.s3.public-endpoint=https://cdn.example.com",
      "kk-studio.storage.s3.region=us-east-1",
      "kk-studio.storage.s3.bucket=test-bucket",
      "kk-studio.storage.s3.access-key=AKIAIOSFODNN7EXAMPLE",
      "kk-studio.storage.s3.secret-key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    })
class StorageUploadLockReadyTest extends S3PostgresSpringTestSupport {

  @Autowired private StorageUploadService storageUploadService;
  @Autowired private InMemoryS3StorageService s3Storage;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate tx;
  private StorageIntegrationSupport storage;

  @BeforeEach
  void resetS3AndBuildTransactionTemplate() {
    s3Storage.clear();
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
    // lockReady 只锁定并返回消费事实；upload 行删除由调用方（命令提交）在同一事务内执行。
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
    storageUploadService.delete(UUID.fromString(ready.getId()));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ?",
            Integer.class,
            UUID.fromString(ready.getId())),
        "caller delete consumes the upload row");
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

    // 回滚：lockReady + delete 的消费（upload 行删除）必须随外层事务一并还原。
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

    // 提交：消费生效。
    tx.execute(
        status -> {
          storageUploadService.lockReady(uploadUuid);
          storageUploadService.delete(uploadUuid);
          return null;
        });
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ?", Integer.class, uploadUuid));
  }
}
