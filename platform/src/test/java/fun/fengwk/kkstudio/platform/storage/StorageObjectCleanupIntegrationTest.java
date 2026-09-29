package fun.fengwk.kkstudio.platform.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.storage.configuration.StorageMaintenanceProperties;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageObjectCleanupRepository;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageUploadRepository;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobPreviewService;
import fun.fengwk.kkstudio.platform.storage.service.StorageObjectCleanupService;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageUpload;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadState;

import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对象清理记录（tombstone）在真实 PostgreSQL + 内存 S3 上的收敛回归。
 *
 * <p>核心不变式：物理删除对象之前必须先持久登记不可再绑定的对象 key，记录永不按时间删除，后台清扫用数据库时间认领到期批次并在事务外幂等删除； 因此任何迟到的
 * PUT/COPY/预览写入最终都会收敛，而 ACTIVE 去重对象绝不登记更不误删。
 *
 * @author fengwk
 */
@Import(StorageS3TestConfiguration.class)
@TestPropertySource(
    properties = {
      "kk-studio.storage.s3.endpoint=http://minio.example.local:9000",
      "kk-studio.storage.s3.public-endpoint=https://cdn.example.com",
      "kk-studio.storage.s3.region=us-east-1",
      "kk-studio.storage.s3.bucket=test-bucket",
      "kk-studio.storage.s3.access-key=local-test-access-key",
      "kk-studio.storage.s3.secret-key=local-test-secret-key"
    })
class StorageObjectCleanupIntegrationTest extends PostgresSpringTestSupport {

  @Autowired private StorageUploadService uploadService;
  @Autowired private StorageBlobManager blobManager;
  @Autowired private StorageObjectCleanupService objectCleanupService;
  @Autowired private StorageObjectCleanupRepository cleanupRepository;
  @Autowired private InMemoryS3StorageService s3Storage;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private StorageMaintenanceProperties maintenanceProperties;

  // 本类显式驱动 expireOnce/sweepOnce，禁用会异步处理同一批事实的后台 maintenance 与真实预览生成。
  @MockitoBean private StorageMaintenance storageMaintenance;
  @MockitoBean private StorageBlobPreviewService blobPreviewService;

  /** 用于把 stage 精确停在「行已提交、写窗口已释放」的窗口，模拟写会话与后台回收的竞争。 */
  @MockitoSpyBean private StorageUploadRepository uploadRepository;

  private TransactionTemplate tx;

  @BeforeEach
  void resetS3() {
    s3Storage.clear();
    tx = new TransactionTemplate(transactionManager);
  }

  @AfterEach
  void everyS3CallRunsOutsideDatabaseTransactions() {
    assertTrue(
        s3Storage.networkCalls().stream()
            .noneMatch(InMemoryS3StorageService.NetworkCall::transactionActive),
        "S3 calls must run outside database transactions: " + s3Storage.networkCalls());
  }

  /**
   * F01 回归：写会话在服务端 COPY 飞行中死亡（advisory lock 随会话释放），后台回收删掉上传事实并登记清理记录； 直到 COPY 落盘之后，清扫才需要真正删除该对象。
   * 多次提前清扫、再迟到写入、再清扫依然最终收敛，且记录永不消失。
   */
  @Test
  void sessionDeathMidCopyConvergesThroughPermanentCleanupRecords() throws Exception {
    byte[] content = StorageIntegrationSupport.utf8("session-death");
    StorageUploadDTO pending = reserve("session-death.bin", "application/octet-stream", content);
    UUID uploadId = UUID.fromString(pending.getId());
    UUID candidateBlobId = candidateBlobIdOf(uploadId);
    s3Storage.putDirect(
        StorageObjectKeys.uploadOriginal(uploadId), content, "application/octet-stream");

    CountDownLatch copyInFlight = new CountDownLatch(1);
    CountDownLatch copyResume = new CountDownLatch(1);
    s3Storage.setCopyInFlightHook(
        () -> {
          copyInFlight.countDown();
          awaitQuietly(copyResume);
        });

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<StorageUploadDTO> completing = executor.submit(() -> uploadService.complete(uploadId));
      assertTrue(
          copyInFlight.await(30, TimeUnit.SECONDS),
          "complete must reach the copy while holding the upload lock");

      // 只终止本测试自己那条 storage advisory lock 会话：等价于「写会话被意外终止」。
      List<Long> lockBackends =
          jdbc.query(
              "select pid from pg_locks where locktype = 'advisory' and granted"
                  + " and pid <> pg_backend_pid()",
              (rs, row) -> rs.getLong(1));
      assertEquals(1, lockBackends.size(), "exactly the writer session holds the upload lock");
      jdbc.queryForList(
          "select pg_terminate_backend(pid) from pg_locks where locktype = 'advisory'"
              + " and granted and pid <> pg_backend_pid()",
          Boolean.class);
      assertEquals(
          0, advisoryLockCount(), "session termination releases the advisory lock immediately");

      backdateUpload(uploadId);
      assertEquals(1, uploadService.expireOnce(), "the dead anchor row must be reclaimed");
      assertEquals(0, uploadRowCount());
      assertTrue(hasCleanupRecord(StorageObjectKeys.uploadOriginal(uploadId)));
      assertTrue(hasCleanupRecord(StorageObjectKeys.blobOriginal(candidateBlobId)));
      assertTrue(hasCleanupRecord(StorageObjectKeys.blobPreview(candidateBlobId)));

      // 迟到写入之前连续多轮清扫：只推进下次尝试时间，不删除任何东西，也不会无限忙循环。
      makeCleanupsDue();
      assertTrue(objectCleanupService.sweepOnce() > 0);
      assertEquals(0, objectCleanupService.sweepOnce(), "a claimed record must not be re-claimed");
      assertEquals(0, objectCleanupService.sweepOnce());
      assertFalse(s3Storage.hasObject(StorageObjectKeys.blobOriginal(candidateBlobId)));

      copyResume.countDown();
      assertThrows(
          ExecutionException.class,
          () -> completing.get(30, TimeUnit.SECONDS),
          "the old writer must fail once its anchor row is gone");
      assertTrue(
          s3Storage.hasObject(StorageObjectKeys.blobOriginal(candidateBlobId)),
          "the late server-side COPY still lands: exactly the orphan F01 is about");

      // 记录被认领时已经推进过下次尝试时间：立即清扫不会命中，必须等长间隔到期。
      assertEquals(0, objectCleanupService.sweepOnce());
      assertTrue(s3Storage.hasObject(StorageObjectKeys.blobOriginal(candidateBlobId)));

      makeCleanupsDue();
      assertTrue(objectCleanupService.sweepOnce() > 0);
      assertFalse(
          s3Storage.hasObject(StorageObjectKeys.blobOriginal(candidateBlobId)),
          "the late copy must converge away on the next sweep");
      assertEquals(0, s3Storage.objectCount());
      assertTrue(
          cleanupRowCount() > 0,
          "cleanup records are permanent evidence and are never removed after a successful delete");
    } finally {
      copyResume.countDown();
      executor.shutdownNow();
    }
  }

  /** 迟到浏览器直传：PENDING 上传回收后重建临时对象，清扫必须把它删掉而记录仍在。 */
  @Test
  void lateTempPutAfterPendingExpiryIsSwept() {
    byte[] content = StorageIntegrationSupport.utf8("late-put");
    StorageUploadDTO pending = reserve("late.bin", "application/octet-stream", content);
    UUID uploadId = UUID.fromString(pending.getId());
    String tempKey = StorageObjectKeys.uploadOriginal(uploadId);

    backdateUpload(uploadId);
    assertEquals(1, uploadService.expireOnce());
    assertTrue(hasCleanupRecord(tempKey));

    // 迟到的浏览器 PUT 在事实消失之后才落盘。
    s3Storage.putDirect(tempKey, content, "application/octet-stream");
    makeCleanupsDue();
    assertTrue(objectCleanupService.sweepOnce() > 0);

    assertFalse(s3Storage.hasObject(tempKey));
    assertTrue(hasCleanupRecord(tempKey));
  }

  /** 迟到预览写入：blob 回收后重建预览对象，清扫必须把它删掉而记录仍在。 */
  @Test
  void latePreviewWriteAfterBlobReleaseIsSwept() {
    byte[] content = StorageIntegrationSupport.utf8("preview");
    StorageUploadDTO pending = reserve("preview.png", "image/png", content);
    UUID uploadId = UUID.fromString(pending.getId());
    UUID blobId = UUID.fromString(complete(pending, content, "image/png"));
    String previewKey = StorageObjectKeys.blobPreview(blobId);

    uploadService.delete(uploadId);
    assertEquals(1, uploadService.expireOnce());
    assertEquals(1, blobManager.sweepDeleting(), "the released blob must be swept");
    assertFalse(s3Storage.hasObject(StorageObjectKeys.blobOriginal(blobId)));
    assertEquals(0, blobRowCount());
    assertTrue(hasCleanupRecord(previewKey));

    // 迟到的预览写入在 blob 行消失之后才落盘。
    s3Storage.putDirect(previewKey, content, "image/webp");
    makeCleanupsDue();
    assertTrue(objectCleanupService.sweepOnce() > 0);

    assertFalse(s3Storage.hasObject(previewKey));
    assertTrue(hasCleanupRecord(previewKey));
  }

  /** 去重命中的 ACTIVE blob：任一 owner 回收都不得登记或删除它的对象，直到最后一个引用释放。 */
  @Test
  void deduplicatedActiveBlobIsNeverTombstonedByUploadCleanup() {
    byte[] content = StorageIntegrationSupport.utf8("shared");
    StorageUploadDTO first = reserve("first.bin", "application/octet-stream", content);
    UUID firstId = UUID.fromString(first.getId());
    UUID blobId = UUID.fromString(complete(first, content, "application/octet-stream"));
    String originalKey = StorageObjectKeys.blobOriginal(blobId);

    // 第二个上传命中同一 ACTIVE 内容，直接 READY 并 retain。
    StorageUploadDTO second = reserve("second.bin", "application/octet-stream", content);
    assertEquals(StorageUploadState.READY, second.getState());
    assertEquals(blobId.toString(), second.getBlobId());

    uploadService.delete(firstId);
    assertEquals(1, uploadService.expireOnce());

    StorageBlob active = blobManager.getBlob(blobId);
    assertEquals(StorageBlobState.ACTIVE, active.getState());
    assertEquals(1L, active.getRefCount());
    assertTrue(s3Storage.hasObject(originalKey), "a still-referenced blob object must survive");
    assertFalse(
        hasCleanupRecord(originalKey),
        "an ACTIVE deduplicated blob must never be registered for cleanup");
    assertFalse(hasCleanupRecord(StorageObjectKeys.blobPreview(blobId)));
    makeCleanupsDue();
    objectCleanupService.sweepOnce();
    assertTrue(
        s3Storage.hasObject(originalKey),
        "sweeping unrelated records must never touch a live deduplicated blob");

    // 最后一个持有者释放后，blob 转 DELETING，清扫才登记并删除对象。
    uploadService.delete(UUID.fromString(second.getId()));
    assertEquals(1, uploadService.expireOnce());
    assertEquals(1, blobManager.sweepDeleting());
    assertFalse(s3Storage.hasObject(originalKey));
    assertTrue(hasCleanupRecord(originalKey));
  }

  /** 回滚的事务不得留下任何清理记录：登记与事实变更必须同生共死。 */
  @Test
  void rolledBackEnqueueLeavesNoRecord() {
    String key = StorageObjectKeys.uploadOriginal(UUID.randomUUID());
    tx.executeWithoutResult(
        status -> {
          objectCleanupService.enqueue(key);
          status.setRollbackOnly();
        });
    assertFalse(hasCleanupRecord(key));
    assertEquals(0, cleanupRowCount());
  }

  /** 对象删除失败：记录保留并改到短重试截止点，重试后成功删除且记录仍在。 */
  @Test
  void failedObjectDeleteKeepsRecordAndRetries() {
    byte[] content = StorageIntegrationSupport.utf8("retry");
    StorageUploadDTO pending = reserve("retry.bin", "application/octet-stream", content);
    UUID uploadId = UUID.fromString(pending.getId());
    String tempKey = StorageObjectKeys.uploadOriginal(uploadId);

    backdateUpload(uploadId);
    assertEquals(1, uploadService.expireOnce());
    s3Storage.putDirect(tempKey, content, "application/octet-stream");
    s3Storage.failNextDelete(tempKey, new IllegalStateException("temporary S3 failure"));

    makeCleanupsDue();
    assertEquals(3, objectCleanupService.sweepOnce());
    assertTrue(s3Storage.hasObject(tempKey), "a failed delete must keep the object");
    assertTrue(hasCleanupRecord(tempKey));
    assertTrue(
        nextAttemptInFuture(tempKey),
        "a failed key must be pushed to a short retry deadline instead of the long interval");

    makeCleanupsDue();
    assertTrue(objectCleanupService.sweepOnce() > 0);
    assertFalse(s3Storage.hasObject(tempKey));
    assertTrue(hasCleanupRecord(tempKey));
  }

  /** 多节点并发清扫：一条记录只被一个节点认领，另一个节点拿到空批次而不是重复删除。 */
  @Test
  void concurrentSweepsClaimEachRecordOnce() throws Exception {
    byte[] content = StorageIntegrationSupport.utf8("concurrent");
    StorageUploadDTO pending = reserve("concurrent.bin", "application/octet-stream", content);
    UUID uploadId = UUID.fromString(pending.getId());
    backdateUpload(uploadId);
    assertEquals(1, uploadService.expireOnce());
    String tempKey = StorageObjectKeys.uploadOriginal(uploadId);
    s3Storage.putDirect(tempKey, content, "application/octet-stream");

    CountDownLatch deleteBlocked = new CountDownLatch(1);
    CountDownLatch deleteResume = new CountDownLatch(1);
    s3Storage.setNetworkCallObserver(
        call -> {
          if ("deleteObject".equals(call.operation())) {
            deleteBlocked.countDown();
            awaitQuietly(deleteResume);
          }
        });

    makeCleanupsDue();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<Integer> nodeA = executor.submit(objectCleanupService::sweepOnce);
      assertTrue(deleteBlocked.await(30, TimeUnit.SECONDS));
      // 节点 B 在 A 认领（并把 next_attempt_at 推后）之后才扫描：必须拿到空批次。
      assertEquals(0, objectCleanupService.sweepOnce());
      deleteResume.countDown();
      assertEquals(3, nodeA.get(30, TimeUnit.SECONDS));
    } finally {
      deleteResume.countDown();
      executor.shutdownNow();
    }
    assertFalse(s3Storage.hasObject(tempKey));
  }

  /** 有界批次 + 失败退避：任意一轮最多处理 MAX_CLEANUP_BATCH，反复调用可以排空全部到期记录。 */
  @Test
  void boundedBatchDrainsEveryDueRecord() {
    int total = StorageObjectCleanupService.MAX_CLEANUP_BATCH * 2 + 1;
    for (int index = 0; index < total; index++) {
      String key = "blobs/" + UUID.randomUUID() + "/original";
      tx.executeWithoutResult(status -> objectCleanupService.enqueue(key));
      s3Storage.putDirect(key, new byte[] {(byte) index}, "application/octet-stream");
    }
    makeCleanupsDue();

    int swept = 0;
    int rounds = 0;
    int batch;
    do {
      batch = objectCleanupService.sweepOnce();
      assertTrue(batch <= StorageObjectCleanupService.MAX_CLEANUP_BATCH);
      swept += batch;
      rounds++;
    } while (batch > 0 && rounds < 10);

    assertEquals(total, swept);
    assertEquals(total, cleanupRowCount());
    assertEquals(0, s3Storage.objectCount());
  }

  /**
   * 认领后的下次尝试时间由数据库时钟推进，而不是 JVM 时钟。
   *
   * <p>测试意图：认领把 {@code next_attempt_at} 设为「数据库当前时间 + 配置间隔」；慢节点或客户端时钟偏移（±天）都不可能把它留在过去，
   * 因此紧接着反复立即清扫必须全部拿到空批次 —— 否则后台维护「批满则继续」的循环会退化成永久忙循环并反复删除同一批对象。
   */
  @Test
  void claimedNextAttemptIsAdvancedByTheDatabaseClockAndNeverReclaimedImmediately() {
    byte[] content = StorageIntegrationSupport.utf8("db-clock");
    StorageUploadDTO pending = reserve("db-clock.bin", "application/octet-stream", content);
    UUID uploadId = UUID.fromString(pending.getId());
    String tempKey = StorageObjectKeys.uploadOriginal(uploadId);

    backdateUpload(uploadId);
    assertEquals(1, uploadService.expireOnce());
    s3Storage.putDirect(tempKey, content, "application/octet-stream");

    makeCleanupsDue();
    assertTrue(objectCleanupService.sweepOnce() > 0);
    assertFalse(s3Storage.hasObject(tempKey));

    assertNextAttemptIsDatabaseClockAdvance(tempKey);
    for (int round = 0; round < 5; round++) {
      assertEquals(
          0,
          objectCleanupService.sweepOnce(),
          "a claimed record must never be re-claimed immediately, whatever the JVM clock says");
    }
    assertTrue(hasCleanupRecord(tempKey), "cleanup records outlive the object they removed");
  }

  /** 清扫必须拒绝活动数据库事务：对象删除是 S3 I/O，绝不能在事务或行锁内执行。 */
  @Test
  void sweepRefusesToRunInsideADatabaseTransaction() {
    tx.executeWithoutResult(
        status -> assertThrows(IllegalStateException.class, objectCleanupService::sweepOnce));
  }

  /** 没有活动事务时 enqueue 必须拒绝，否则会产生「事实已回滚、记录已提交」的伪清理证据。 */
  @Test
  void enqueueOutsideTransactionIsRejectedInsteadOfCreatingPhantomEvidence() {
    String key = StorageObjectKeys.uploadOriginal(UUID.randomUUID());
    assertThrows(IllegalStateException.class, () -> objectCleanupService.enqueue(key));
    assertFalse(hasCleanupRecord(key));
  }

  /** 登记幂等：同一 key 重复登记不覆盖已存在的下次尝试时间（已推进的退避不会被后来者提前）。 */
  @Test
  void reEnqueueKeepsTheExistingDeadline() {
    String key = StorageObjectKeys.uploadOriginal(UUID.randomUUID());
    tx.executeWithoutResult(status -> objectCleanupService.enqueue(key));
    jdbc.update(
        "update storage_object_cleanup set next_attempt_at = current_timestamp"
            + " + interval '5 minutes' where key = ?",
        key);
    Timestamp advanced = nextAttempt(key);

    tx.executeWithoutResult(status -> objectCleanupService.enqueue(key));

    assertEquals(advanced, nextAttempt(key), "re-enqueue must not rewrite an existing deadline");
    assertEquals(1, cleanupRowCount());
  }

  /** 重试只作用于已存在的记录：未知 key 的 reschedule 不凭空造出清理证据。 */
  @Test
  void rescheduleOfAnUnknownKeyDoesNotCreateRecords() {
    assertFalse(cleanupRepository.reschedule("blobs/" + UUID.randomUUID() + "/original", 1_000L));
    assertEquals(0, cleanupRowCount());
  }

  /**
   * stage 失败兜底绝不盲删对象。
   *
   * <p>测试意图：用 spied 仓储让绑定阶段发现锚点行已被后台回收（真实竞争结果），此时失败路径只能留下耐久清理请求， 不得「尽力而为」地删除对象 —— 尤其不能删掉迟到 COPY
   * 刚落下的 candidate 对象（它可能已被绑定为 ACTIVE）。 对象最终仍由同一套 durable 记录收敛。
   */
  @Test
  void stageFailureAfterTheFactIsGoneNeverBlindDeletesObjects() {
    byte[] content = StorageIntegrationSupport.utf8("stage-failure");
    AtomicReference<StorageUpload> staged = new AtomicReference<>();
    doAnswer(
            invocation -> {
              staged.set(invocation.getArgument(0));
              return invocation.callRealMethod();
            })
        .when(uploadRepository)
        .insert(any());
    doReturn(null).when(uploadRepository).getByIdForUpdate(any());

    assertThrows(
        RuntimeException.class,
        () ->
            uploadService.stage(
                "stage-failure.bin",
                "application/octet-stream",
                new ByteArrayInputStream(content),
                content.length));

    UUID uploadId = staged.get().getId();
    String tempKey = StorageObjectKeys.uploadOriginal(uploadId);
    String candidateKey = StorageObjectKeys.blobOriginal(staged.get().getCandidateBlobId());
    assertTrue(
        s3Storage.hasObject(candidateKey),
        "a candidate object that a copy already landed must not be blind-deleted");
    assertTrue(s3Storage.hasObject(tempKey));
    assertTrue(
        s3Storage.networkCalls().stream()
            .noneMatch(call -> "deleteObject".equals(call.operation())),
        "a failed stage must not delete objects outside the durable cleanup path");
    assertEquals(1, uploadRowCount(), "the durable cleanup request rolls back with the fact");

    // 收敛仍走同一条 durable 路径：事实过期后由后台回收登记，清扫才删除对象。
    backdateUpload(uploadId);
    assertEquals(1, uploadService.expireOnce());
    assertTrue(hasCleanupRecord(tempKey));
    assertTrue(hasCleanupRecord(candidateKey));
    makeCleanupsDue();
    assertTrue(objectCleanupService.sweepOnce() > 0);
    assertEquals(0, s3Storage.objectCount());
    assertTrue(hasCleanupRecord(candidateKey));
  }

  // ------------------------------------------------------------------
  // helpers
  // ------------------------------------------------------------------

  private StorageUploadDTO reserve(String filename, String mediaType, byte[] content) {
    StorageIntegrationSupport support =
        new StorageIntegrationSupport(uploadService, s3Storage, jdbc);
    return support.reserve(filename, mediaType, content.length, support.sha256Hex(content));
  }

  private String complete(StorageUploadDTO pending, byte[] content, String contentType) {
    UUID uploadId = UUID.fromString(pending.getId());
    s3Storage.putDirect(StorageObjectKeys.uploadOriginal(uploadId), content, contentType);
    return uploadService.complete(uploadId).getBlobId();
  }

  private UUID candidateBlobIdOf(UUID uploadId) {
    return jdbc.queryForObject(
        "select candidate_blob_id from storage_upload where id = ?", UUID.class, uploadId);
  }

  private void backdateUpload(UUID uploadId) {
    jdbc.update(
        "update storage_upload set created_at = expires_at - interval '2 hours',"
            + " expires_at = current_timestamp - interval '1 minute' where id = ?",
        uploadId);
  }

  private void makeCleanupsDue() {
    jdbc.update(
        "update storage_object_cleanup set next_attempt_at = current_timestamp"
            + " - interval '1 second'");
  }

  private boolean hasCleanupRecord(String key) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select count(*) > 0 from storage_object_cleanup where key = ?", Boolean.class, key));
  }

  private boolean nextAttemptInFuture(String key) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select next_attempt_at > current_timestamp from storage_object_cleanup where key = ?",
            Boolean.class,
            key));
  }

  private Timestamp nextAttempt(String key) {
    return jdbc.queryForObject(
        "select next_attempt_at from storage_object_cleanup where key = ?", Timestamp.class, key);
  }

  /** 认领把 {@code next_attempt_at} 设为「数据库当前时间 + 配置间隔」：断言它落在该窗口内即可证明推进基于数据库时钟（服务不持有 JVM 时钟）。 */
  private void assertNextAttemptIsDatabaseClockAdvance(String key) {
    long seconds = maintenanceProperties.getObjectCleanupInterval().toSeconds();
    Boolean within =
        jdbc.queryForObject(
            "select next_attempt_at > current_timestamp"
                + " and next_attempt_at > current_timestamp"
                + " + (cast(? as double precision) * interval '1 second') - interval '60 seconds'"
                + " and next_attempt_at < current_timestamp"
                + " + (cast(? as double precision) * interval '1 second') + interval '60 seconds'"
                + " from storage_object_cleanup where key = ?",
            Boolean.class,
            seconds,
            seconds,
            key);
    assertTrue(
        Boolean.TRUE.equals(within),
        "next_attempt_at must be database now + the configured interval for " + key);
  }

  private int cleanupRowCount() {
    return jdbc.queryForObject("select count(*) from storage_object_cleanup", Integer.class);
  }

  private int uploadRowCount() {
    return jdbc.queryForObject("select count(*) from storage_upload", Integer.class);
  }

  private int blobRowCount() {
    return jdbc.queryForObject("select count(*) from storage_blob", Integer.class);
  }

  private int advisoryLockCount() {
    return jdbc.queryForObject(
        "select count(*) from pg_locks where locktype = 'advisory' and granted", Integer.class);
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting for the S3 interleaving latch");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
  }
}
