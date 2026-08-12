package fun.fengwk.kkstudio.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.core.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.core.storage.service.StorageBlobIngestService;
import fun.fengwk.kkstudio.core.storage.service.StorageUploadService;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 服务端字节摄入（Tool/Daemon 外部化）的 PostgreSQL + 内存 S3 集成测试。
 *
 * <p>覆盖新行路径（ACTIVE + ref_count=1 + 同事务 ref 配对 + 对象存在）、同 Session 与跨 Session 去重计数、事务回滚后
 * 行/ref/候选对象一并清理，以及 MANDATORY 传播与输入校验。
 */
@Import({StorageS3TestConfiguration.class})
@TestPropertySource(
    properties = {
      "kk-studio.storage.s3.enabled=true",
      "kk-studio.storage.s3.endpoint=http://minio.example.local:9000",
      "kk-studio.storage.s3.public-endpoint=https://cdn.example.com",
      "kk-studio.storage.s3.region=us-east-1",
      "kk-studio.storage.s3.bucket=test-bucket",
      "kk-studio.storage.s3.access-key=AKIAIOSFODNN7EXAMPLE",
      "kk-studio.storage.s3.secret-key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    })
class StorageBlobIngestServiceIntegrationTest extends PostgresSpringTestSupport {

  private static final UUID SESSION_A = new UUID(0L, 1L);
  private static final UUID SESSION_B = new UUID(0L, 2L);

  @Autowired private StorageUploadService storageUploadService;
  @Autowired private StorageBlobIngestService ingestService;
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

  /** harness_session_blob_ref.session_id 是 RESTRICT FK：先建真实 session 行。 */
  private void seedSession(UUID sessionId) {
    jdbc.update(
        "insert into harness_session (id, created_at) values (?, current_timestamp)", sessionId);
  }

  @Test
  void ingestCreatesActiveBlobAndRefPairWithObject() {
    byte[] content = "tool output".getBytes(StandardCharsets.UTF_8);
    UUID blobId = tx.execute(status -> ingestService.ingest(SESSION_A, content, "text/plain"));

    assertEquals(
        1L,
        storage.blobRefCount(blobId.toString()),
        "new ingest must create the row with ref_count = 1 (the paired ref)");
    assertEquals(1, storage.blobRowCount());
    assertTrue(refManager.contains(SESSION_A, blobId));
    assertEquals(
        1, jdbc.queryForObject("select count(*) from harness_session_blob_ref", Integer.class));
    assertArrayEquals(
        content,
        s3Storage.objectBytes(StorageObjectKeys.blobOriginal(blobId)),
        "blob object must be written");

    // 同 Session 同内容：去重命中，ref 已存在 → 不重复计数。
    UUID again = tx.execute(status -> ingestService.ingest(SESSION_A, content, "text/plain"));
    assertEquals(blobId, again, "same session + same bytes must dedup to the same blob");
    assertEquals(1L, storage.blobRefCount(blobId.toString()));
    assertEquals(
        1, jdbc.queryForObject("select count(*) from harness_session_blob_ref", Integer.class));
  }

  @Test
  void ingestDedupAcrossSessionsRetainsOncePerSession() {
    byte[] content = "shared".getBytes(StandardCharsets.UTF_8);
    UUID first = tx.execute(status -> ingestService.ingest(SESSION_A, content, "text/plain"));
    UUID second = tx.execute(status -> ingestService.ingest(SESSION_B, content, "text/plain"));

    assertEquals(first, second, "cross-session dedup must resolve to the same blob");
    assertEquals(
        2L,
        storage.blobRefCount(first.toString()),
        "each session ref must be counted exactly once");
    assertEquals(
        2, jdbc.queryForObject("select count(*) from harness_session_blob_ref", Integer.class));
    assertEquals(1, storage.blobRowCount());
  }

  @Test
  void failureAfterPutStillCleansUpCandidateObject() {
    // putObject 成功之后、ref 配对之前失败（未知 session 触发 harness_session_blob_ref 的 RESTRICT FK 拒绝）：
    // 候选对象清理必须在 put 后立即注册，任何后续 DB 失败都会回收对象，绝不留孤儿。
    byte[] content = "cleanup me".getBytes(StandardCharsets.UTF_8);
    UUID unknownSession = new UUID(0L, 99L);
    assertThrows(
        DataIntegrityViolationException.class,
        () -> tx.execute(status -> ingestService.ingest(unknownSession, content, "text/plain")));
    assertEquals(0, storage.blobRowCount(), "failed ingest must leave no blob row");
    assertEquals(
        0, jdbc.queryForObject("select count(*) from harness_session_blob_ref", Integer.class));
    assertEquals(
        0,
        s3Storage.objectCount(),
        "candidate object must be cleaned when the ref insert fails after the put");
  }

  @Test
  void ingestRollbackRemovesRowRefAndCandidateObject() {
    byte[] content = "rollback me".getBytes(StandardCharsets.UTF_8);
    try {
      tx.execute(
          new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
              UUID blobId = ingestService.ingest(SESSION_A, content, "text/plain");
              assertTrue(
                  s3Storage.hasObject(StorageObjectKeys.blobOriginal(blobId)),
                  "candidate object must be written inside the transaction");
              throw new IllegalStateException("force rollback");
            }
          });
      throw new AssertionError("expected rollback");
    } catch (IllegalStateException expected) {
      // 预期回滚。
    }
    assertEquals(0, storage.blobRowCount(), "rolled-back ingest must leave no blob row");
    assertEquals(
        0, jdbc.queryForObject("select count(*) from harness_session_blob_ref", Integer.class));
    assertEquals(
        0, s3Storage.objectCount(), "rolled-back candidate object must be cleaned after rollback");
  }

  @Test
  void ingestRejectsInvalidMediaTypeAndEmptyBytes() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            tx.execute(
                status -> ingestService.ingest(SESSION_A, new byte[] {1}, "not-a-media-type")));
    assertThrows(
        IllegalArgumentException.class,
        () -> tx.execute(status -> ingestService.ingest(SESSION_A, new byte[] {}, "text/plain")));
    assertEquals(0, storage.blobRowCount());
  }

  @Test
  void ingestOutsideTransactionIsRejected() {
    assertThrows(
        IllegalTransactionStateException.class,
        () -> ingestService.ingest(SESSION_A, new byte[] {1}, "text/plain"));
  }
}
