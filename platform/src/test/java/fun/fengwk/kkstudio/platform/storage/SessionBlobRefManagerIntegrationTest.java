package fun.fengwk.kkstudio.platform.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * {@code session_blob_ref} 显式 manager 的 PostgreSQL 集成测试。
 *
 * <p>覆盖 retain/release 与 ref_count 账本成对维护（含重复 retain/release 幂等）、release 到零切 DELETING、 MANDATORY
 * 传播（事务外调用确定性拒绝），以及 listBlobIds 枚举。
 */
@Import({StorageS3TestConfiguration.class})
@TestPropertySource(
    properties = {
      "kk-studio.storage.s3.endpoint=http://minio.example.local:9000",
      "kk-studio.storage.s3.public-endpoint=https://cdn.example.com",
      "kk-studio.storage.s3.region=us-east-1",
      "kk-studio.storage.s3.bucket=test-bucket",
      "kk-studio.storage.s3.access-key=local-test-access-key",
      "kk-studio.storage.s3.secret-key=local-test-secret-key"
    })
class SessionBlobRefManagerIntegrationTest extends PostgresSpringTestSupport {

  private static final UUID SESSION_A = new UUID(0L, 1L);
  private static final UUID SESSION_B = new UUID(0L, 2L);

  @Autowired private StorageUploadService storageUploadService;
  @Autowired private StorageBlobManager storageBlobManager;
  @Autowired private SessionBlobRefManager refManager;
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
    seedSession(SESSION_A);
    seedSession(SESSION_B);
  }

  @AfterEach
  void everyS3CallRunsOutsideDatabaseTransactions() {
    assertTrue(
        s3Storage.networkCalls().stream()
            .noneMatch(InMemoryS3StorageService.NetworkCall::transactionActive),
        "S3 calls must run outside database transactions: " + s3Storage.networkCalls());
  }

  /** session_blob_ref.session_id 是 RESTRICT FK：先建真实 session 行。 */
  private void seedSession(UUID sessionId) {
    jdbc.update(
        "insert into harness_session (id, name, created_at) values (?, ?, current_timestamp)",
        sessionId,
        "test-session");
  }

  @Test
  void retainAndReleaseMaintainExactlyOneRefCountPerRefRow() {
    String blobId = storage.completeFirstUpload("ref-accounting".getBytes(StandardCharsets.UTF_8));
    UUID blobUuid = UUID.fromString(blobId);

    tx.execute(
        status -> {
          refManager.retainRef(SESSION_A, blobUuid);
          return null;
        });
    assertTrue(refManager.contains(SESSION_A, blobUuid));
    assertEquals(2L, storage.blobRefCount(blobId), "first ref row must retain exactly once");
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from session_blob_ref where session_id = ? and blob_id = ?",
            Integer.class,
            SESSION_A,
            blobUuid));

    // 重复 retain：ref 行已存在，必须 no-op（不重复计数）。
    tx.execute(
        status -> {
          refManager.retainRef(SESSION_A, blobUuid);
          return null;
        });
    assertEquals(
        2L, storage.blobRefCount(blobId), "repeat retain on the same ref must not double count");

    // 不同 Session：新 ref 行 + 再一次 retain。
    tx.execute(
        status -> {
          refManager.retainRef(SESSION_B, blobUuid);
          return null;
        });
    assertEquals(3L, storage.blobRefCount(blobId));
    assertEquals(2, jdbc.queryForObject("select count(*) from session_blob_ref", Integer.class));

    // release：逐行释放，删除后幂等 no-op。
    tx.execute(
        status -> {
          refManager.releaseRef(SESSION_A, blobUuid);
          return null;
        });
    assertEquals(2L, storage.blobRefCount(blobId));
    assertFalse(refManager.contains(SESSION_A, blobUuid));
    tx.execute(
        status -> {
          refManager.releaseRef(SESSION_A, blobUuid);
          return null;
        });
    assertEquals(
        2L, storage.blobRefCount(blobId), "release of a missing ref must be idempotent no-op");
    tx.execute(
        status -> {
          refManager.releaseRef(SESSION_B, blobUuid);
          return null;
        });
    assertEquals(1L, storage.blobRefCount(blobId));
  }

  @Test
  void releaseToZeroTransitionsBlobToDeletingAndCleansObjects() {
    byte[] content = "zero".getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO pending =
        storage.reserve(
            "zero.bin", "application/octet-stream", content.length, storage.sha256Hex(content));
    storage.putUploadContent(pending.getId(), content, "application/octet-stream");
    StorageUploadDTO ready = storageUploadService.complete(UUID.fromString(pending.getId()));
    UUID blobUuid = UUID.fromString(ready.getBlobId());
    tx.execute(
        status -> {
          refManager.retainRef(SESSION_A, blobUuid);
          return null;
        });
    assertEquals(2L, storage.blobRefCount(ready.getBlobId()));

    // 生产顺序：owner（upload 行）先删除，再释放最后一个引用（FK RESTRICT 禁止反向顺序）。
    storageUploadService.delete(UUID.fromString(ready.getId()));
    tx.execute(
        status -> {
          refManager.releaseRef(SESSION_A, blobUuid);
          return null;
        });
    assertEquals(1, storageUploadService.expireOnce(), "upload cleanup must precede blob sweep");
    storageBlobManager.sweepDeleting();

    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from storage_blob where id = ?", Integer.class, blobUuid),
        "release to zero must delete the DELETING row after commit");
    assertFalse(s3Storage.hasObject(StorageObjectKeys.blobOriginal(blobUuid)));
    assertEquals(0, jdbc.queryForObject("select count(*) from session_blob_ref", Integer.class));
  }

  @Test
  void retainRefRejectsUnknownBlobViaForeignKey() {
    // 未知 blob：ref 行 INSERT 先于 retain，由 RESTRICT FK 确定性拒绝并整体回滚。
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            tx.execute(
                status -> {
                  refManager.retainRef(SESSION_A, UUID.randomUUID());
                  return null;
                }));
    assertEquals(
        0,
        jdbc.queryForObject("select count(*) from session_blob_ref", Integer.class),
        "FK rejection must leave no ref row");
  }

  @Test
  void retainRefRejectsDeletingBlobWithNotFound() {
    String blobId = storage.completeFirstUpload("deleting".getBytes(StandardCharsets.UTF_8));
    UUID blobUuid = UUID.fromString(blobId);
    jdbc.update("update storage_blob set state = 'DELETING', ref_count = 0 where id = ?", blobUuid);
    assertThrows(
        StorageResourceNotFoundException.class,
        () ->
            tx.execute(
                status -> {
                  refManager.retainRef(SESSION_A, blobUuid);
                  return null;
                }));
  }

  @Test
  void mandatoryPropagationRejectsCallsOutsideTransaction() {
    assertThrows(
        IllegalTransactionStateException.class,
        () -> refManager.retainRef(SESSION_A, UUID.randomUUID()));
    assertThrows(
        IllegalTransactionStateException.class,
        () -> refManager.releaseRef(SESSION_A, UUID.randomUUID()));
    assertThrows(
        IllegalTransactionStateException.class,
        () -> refManager.insertRefIfAbsent(SESSION_A, UUID.randomUUID()));
  }

  @Test
  void insertRefIfAbsentPairsWithNewBlobRowWithoutDoubleRetain() {
    byte[] content = "paired".getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO pending =
        storage.reserve(
            "paired.bin", "application/octet-stream", content.length, storage.sha256Hex(content));
    storage.putUploadContent(pending.getId(), content, "application/octet-stream");
    StorageUploadDTO ready = storageUploadService.complete(UUID.fromString(pending.getId()));
    UUID blobUuid = UUID.fromString(ready.getBlobId());
    assertEquals(1L, storage.blobRefCount(ready.getBlobId()));

    boolean inserted = tx.execute(status -> refManager.insertRefIfAbsent(SESSION_A, blobUuid));
    assertTrue(inserted);
    assertEquals(
        1L,
        storage.blobRefCount(ready.getBlobId()),
        "insertRefIfAbsent must not touch ref_count (new blob row already carries the ref)");

    boolean second = tx.execute(status -> refManager.insertRefIfAbsent(SESSION_A, blobUuid));
    assertFalse(second, "repeat insert must be idempotent false");
    assertEquals(1L, storage.blobRefCount(ready.getBlobId()));
    assertEquals(List.of(blobUuid), refManager.listBlobIds(SESSION_A));
  }
}
