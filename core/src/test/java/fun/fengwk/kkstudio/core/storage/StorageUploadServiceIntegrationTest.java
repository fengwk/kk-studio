package fun.fengwk.kkstudio.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.core.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.core.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.core.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.core.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.core.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.core.storage.service.model.StorageBlobState;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadReserveRequestDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadState;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 全局 Blob 存储上传契约的 PostgreSQL 集成测试（内存 S3 假件）。
 *
 * <p>覆盖 reserve 命中/未命中、checksum/size 校验、并发去重、retain/release 原语、过期 PENDING/READY 回收、DELETE 幂等
 * 404，以及响应不泄露 bucket/key。
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
      "kk-studio.storage.s3.access-key=AKIAIOSFODNN7EXAMPLE",
      "kk-studio.storage.s3.secret-key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    })
class StorageUploadServiceIntegrationTest extends S3PostgresSpringTestSupport {

  @Autowired private StorageUploadService storageUploadService;
  @Autowired private StorageBlobManager storageBlobManager;
  @Autowired private InMemoryS3StorageService s3Storage;
  @Autowired private RecordingS3PresignService s3Presigner;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ObjectMapper objectMapper;

  private TransactionTemplate tx;

  @BeforeEach
  void resetS3AndBuildTransactionTemplate() {
    s3Storage.clear();
    s3Presigner.clear();
    tx = new TransactionTemplate(transactionManager);
  }

  // ------------------------------------------------------------------
  // reserve
  // ------------------------------------------------------------------

  @Test
  void reserveMissReturnsPendingWithChecksummedCreateOnlyPut() throws Exception {
    byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO upload = reserve("photo.png", "image/png", content.length, sha256Hex(content));

    assertEquals(StorageUploadState.PENDING, upload.getState());
    assertNull(upload.getBlobId());
    assertNotNull(upload.getPresignedPut());
    assertEquals("PUT", upload.getPresignedPut().getMethod());
    assertEquals("*", upload.getPresignedPut().getHeaders().get("if-none-match"));
    assertEquals(
        Base64.getEncoder().encodeToString(sha256(content)),
        upload.getPresignedPut().getHeaders().get("x-amz-checksum-sha256"));

    RecordingS3PresignService.PresignRecord record =
        s3Presigner.records().get(s3Presigner.records().size() - 1);
    assertEquals("checksummedCreateOnly", record.kind());
    assertEquals(StorageObjectKeys.uploadOriginal(UUID.fromString(upload.getId())), record.key());
    assertEquals("image/png", record.contentType());

    // 响应必须不暴露 bucket 与对象物理 key。
    String json = objectMapper.writeValueAsString(upload);
    assertFalse(json.contains("\"bucket\""), "response must not expose bucket: " + json);
    assertFalse(json.contains("\"key\""), "response must not expose physical key: " + json);

    assertEquals(
        0,
        jdbc.queryForObject("select count(*) from storage_blob", Integer.class),
        "reserve miss must not create a blob row");
    UUID uploadUuid = UUID.fromString(upload.getId());
    assertNull(
        jdbc.queryForObject(
            "select blob_id from storage_upload where id = ?", UUID.class, uploadUuid),
        "PENDING upload must keep blob_id null");
    assertEquals(
        (long) content.length,
        jdbc.queryForObject(
            "select declared_size from storage_upload where id = ?", Long.class, uploadUuid),
        "declared size must be persisted exactly");
  }

  @Test
  void reserveHitReturnsReadyAndRetainsExistingBlob() {
    byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
    String blobId = completeFirstUpload(content);

    StorageUploadDTO upload = reserve("copy.png", "image/png", content.length, sha256Hex(content));

    assertEquals(StorageUploadState.READY, upload.getState());
    assertEquals(blobId, upload.getBlobId());
    assertNull(upload.getPresignedPut());
    assertEquals(
        2L,
        blobRefCount(blobId),
        "READY upload must hold one reference on top of the existing one");
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_blob where state = 'ACTIVE'", Integer.class));
  }

  @Test
  void reserveValidatesDeclarationsAndNormalizesSha256Case() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> reserve("  ", "image/png", 1L, sha256Hex(new byte[] {1})));
    assertThrows(
        IllegalArgumentException.class,
        () -> reserve("a.png", "not-a-media-type", 1L, sha256Hex(new byte[] {1})));
    assertThrows(
        IllegalArgumentException.class,
        () -> reserve("a.png", "image/png", -1L, sha256Hex(new byte[] {1})));
    assertThrows(IllegalArgumentException.class, () -> reserve("a.png", "image/png", 1L, "xyz"));
    StorageUploadReserveRequestDTO missingSize = new StorageUploadReserveRequestDTO();
    missingSize.setFilename("a.png");
    missingSize.setMediaType("image/png");
    missingSize.setSizeBytes(null);
    missingSize.setSha256(sha256Hex(new byte[] {1}));
    assertThrows(IllegalArgumentException.class, () -> storageUploadService.reserve(missingSize));

    byte[] content = new byte[] {7};
    StorageUploadDTO upload =
        reserve("case.png", " image/png ", content.length, sha256Hex(content).toUpperCase());
    assertEquals(
        "image/png", s3Presigner.records().get(0).contentType(), "media type must be normalized");
    String storedSha =
        jdbc.queryForObject(
            "select declared_sha256 from storage_upload where id = ?",
            String.class,
            UUID.fromString(upload.getId()));
    assertEquals(sha256Hex(content), storedSha, "sha256 must be stored lowercase");
  }

  // ------------------------------------------------------------------
  // complete
  // ------------------------------------------------------------------

  @Test
  void completeHappyPathMaterializesBlobWithProbedFacts() {
    byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO pending =
        reserve("hello.txt", "text/plain", content.length, sha256Hex(content));
    putUploadContent(pending.getId(), content, "text/plain");

    StorageUploadDTO ready = storageUploadService.complete(UUID.fromString(pending.getId()));

    assertEquals(StorageUploadState.READY, ready.getState());
    assertNotNull(ready.getBlobId());
    assertNull(ready.getPresignedPut());

    BlobRow blob = blobRow(ready.getBlobId());
    assertEquals(sha256Hex(content), blob.sha256());
    assertEquals(content.length, blob.sizeBytes());
    assertEquals("text/plain", blob.mediaType());
    assertNull(blob.width(), "default probe records no dimensions");
    assertNull(blob.height());
    assertNull(blob.durationMs());
    assertEquals(1L, blob.refCount());
    assertEquals(StorageBlobState.ACTIVE, blob.state());

    String originalKey = StorageObjectKeys.blobOriginal(UUID.fromString(ready.getBlobId()));
    assertTrue(s3Storage.hasObject(originalKey), "complete must copy the original object");
    assertArrayEquals(content, s3Storage.objectBytes(originalKey));
    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.uploadOriginal(UUID.fromString(pending.getId()))),
        "complete must delete the temp upload object");
  }

  @Test
  void completeRejectsChecksumMismatchAndKeepsPending() {
    byte[] declared = new byte[] {1, 2, 3};
    StorageUploadDTO pending =
        reserve("bad.bin", "application/octet-stream", 3, sha256Hex(declared));
    putUploadContent(pending.getId(), new byte[] {9, 9, 9}, "application/octet-stream");

    StorageVerificationException error =
        assertThrows(
            StorageVerificationException.class,
            () -> storageUploadService.complete(UUID.fromString(pending.getId())));
    assertTrue(error.getMessage().contains("checksum"), "actual: " + error.getMessage());

    assertNull(uploadBlobId(pending.getId()), "mismatch must keep the upload PENDING");
    assertEquals(0, jdbc.queryForObject("select count(*) from storage_blob", Integer.class));
  }

  @Test
  void completeRejectsSizeMismatch() {
    byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO pending =
        reserve("size.bin", "application/octet-stream", 5, sha256Hex(content));
    putUploadContent(pending.getId(), content, "application/octet-stream");

    StorageVerificationException error =
        assertThrows(
            StorageVerificationException.class,
            () -> storageUploadService.complete(UUID.fromString(pending.getId())));
    assertTrue(error.getMessage().contains("size"), "actual: " + error.getMessage());
    assertNull(uploadBlobId(pending.getId()));
  }

  @Test
  void completeRejectsMissingObject() {
    StorageUploadDTO pending =
        reserve("void.bin", "application/octet-stream", 1, sha256Hex(new byte[] {1}));
    assertThrows(
        StorageVerificationException.class,
        () -> storageUploadService.complete(UUID.fromString(pending.getId())));
  }

  @Test
  void completeUnknownUploadThrowsNotFound() {
    assertThrows(
        StorageResourceNotFoundException.class,
        () -> storageUploadService.complete(UUID.randomUUID()));
  }

  @Test
  void completeIsIdempotentOnRetry() {
    byte[] content = "retry".getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO pending =
        reserve("retry.txt", "text/plain", content.length, sha256Hex(content));
    putUploadContent(pending.getId(), content, "text/plain");

    StorageUploadDTO first = storageUploadService.complete(UUID.fromString(pending.getId()));
    StorageUploadDTO second = storageUploadService.complete(UUID.fromString(pending.getId()));

    assertEquals(StorageUploadState.READY, first.getState());
    assertEquals(StorageUploadState.READY, second.getState());
    assertEquals(first.getBlobId(), second.getBlobId());
    assertEquals(1L, blobRefCount(first.getBlobId()), "retry must not double-count references");
    assertEquals(1, jdbc.queryForObject("select count(*) from storage_blob", Integer.class));
  }

  @Test
  void completeAfterDbFailureCrashWindowMaterializesFinalObjectBeforeBind() {
    byte[] content = "crash".getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO pending =
        reserve("crash.bin", "application/octet-stream", content.length, sha256Hex(content));
    UUID uploadUuid = UUID.fromString(pending.getId());
    putUploadContent(pending.getId(), content, "application/octet-stream");
    // 模拟 complete 在预复制最终对象后、DB 事务提交前失败：临时对象与候选对象都在，行仍 PENDING。
    UUID candidateUuid = candidateBlobIdOf(pending.getId());
    s3Storage.putDirect(
        StorageObjectKeys.blobOriginal(candidateUuid), content, "application/octet-stream");
    assertNull(uploadBlobId(pending.getId()), "crash window must leave the upload PENDING");

    StorageUploadDTO ready = storageUploadService.complete(uploadUuid);

    assertEquals(StorageUploadState.READY, ready.getState());
    assertEquals(
        candidateUuid.toString(),
        ready.getBlobId(),
        "crash-window retry must bind the pre-copied candidate");
    assertTrue(
        s3Storage.hasObject(StorageObjectKeys.blobOriginal(candidateUuid)),
        "final object must exist before the DB references it");
    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.uploadOriginal(uploadUuid)),
        "temp object must be cleaned after bind");
    assertNoActiveBlobReferencesMissingObject();
  }

  @Test
  void dedupLoserCleansUnusedCandidateObject() {
    byte[] content = "loser".getBytes(StandardCharsets.UTF_8);
    String sha = sha256Hex(content);
    StorageUploadDTO first =
        reserve("loser-a.bin", "application/octet-stream", content.length, sha);
    StorageUploadDTO second =
        reserve("loser-b.bin", "application/octet-stream", content.length, sha);
    putUploadContent(first.getId(), content, "application/octet-stream");
    putUploadContent(second.getId(), content, "application/octet-stream");
    UUID secondCandidate = candidateBlobIdOf(second.getId());
    // 模拟 second 此前 complete 预复制过候选对象（含未来媒体集成可能生成的候选预览）。
    s3Storage.putDirect(StorageObjectKeys.blobPreview(secondCandidate), content, "image/webp");

    StorageUploadDTO readyFirst = storageUploadService.complete(UUID.fromString(first.getId()));
    StorageUploadDTO readySecond = storageUploadService.complete(UUID.fromString(second.getId()));

    assertEquals(readyFirst.getBlobId(), readySecond.getBlobId(), "both must dedup to one blob");
    assertEquals(
        UUID.fromString(readyFirst.getBlobId()),
        candidateBlobIdOf(first.getId()),
        "the first completer wins with its candidate");
    assertTrue(
        s3Storage.hasObject(
            StorageObjectKeys.blobOriginal(UUID.fromString(readyFirst.getBlobId()))));
    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.blobOriginal(secondCandidate)),
        "dedup loser's unused candidate object must be cleaned");
    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.blobPreview(secondCandidate)),
        "dedup loser's unused candidate preview must be cleaned");
    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.uploadOriginal(UUID.fromString(second.getId()))),
        "dedup loser's temp object must be cleaned");
    assertNoActiveBlobReferencesMissingObject();
  }

  @Test
  void concurrentCompletesOfSamePendingUploadRetainExistingBlobExactlyOnce() throws Exception {
    byte[] content = "same-upload".getBytes(StandardCharsets.UTF_8);
    // 已有 ACTIVE blob（首传持有 1 个引用）。
    String existingBlobId = completeFirstUpload(content);
    // 同一内容的 PENDING 上传：两个线程并发 complete 它。
    StorageUploadDTO pending =
        reserve("once.bin", "application/octet-stream", content.length, sha256Hex(content));
    UUID uploadUuid = UUID.fromString(pending.getId());
    putUploadContent(pending.getId(), content, "application/octet-stream");
    // 模拟此前 complete 预复制过候选对象（含未来媒体集成可能生成的候选预览）。
    UUID candidateUuid = candidateBlobIdOf(pending.getId());
    s3Storage.putDirect(
        StorageObjectKeys.blobOriginal(candidateUuid), content, "application/octet-stream");
    s3Storage.putDirect(StorageObjectKeys.blobPreview(candidateUuid), content, "image/webp");

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<StorageUploadDTO> a =
          executor.submit(
              () -> {
                start.await(30, TimeUnit.SECONDS);
                return storageUploadService.complete(uploadUuid);
              });
      Future<StorageUploadDTO> b =
          executor.submit(
              () -> {
                start.await(30, TimeUnit.SECONDS);
                return storageUploadService.complete(uploadUuid);
              });
      start.countDown();

      StorageUploadDTO readyA = a.get(30, TimeUnit.SECONDS);
      StorageUploadDTO readyB = b.get(30, TimeUnit.SECONDS);
      assertEquals(StorageUploadState.READY, readyA.getState());
      assertEquals(StorageUploadState.READY, readyB.getState());
      assertEquals(existingBlobId, readyA.getBlobId(), "both must resolve to the existing blob");
      assertEquals(existingBlobId, readyB.getBlobId());
    } finally {
      executor.shutdownNow();
    }

    assertEquals(
        2L,
        blobRefCount(existingBlobId),
        "one bound upload must add exactly one owner reference, never two");
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_blob where state = 'ACTIVE'", Integer.class));
    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.blobOriginal(candidateBlobIdOf(pending.getId()))),
        "the never-bound candidate object must be cleaned");
    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.blobPreview(candidateBlobIdOf(pending.getId()))),
        "the never-bound candidate preview must be cleaned");
    assertNoActiveBlobReferencesMissingObject();
  }

  @Test
  void concurrentExpireSweepersDoNotDoubleProcessOrRelease() throws Exception {
    byte[] content = "sweepy".getBytes(StandardCharsets.UTF_8);
    String sha = sha256Hex(content);
    // 11 个 READY 引用（1 首传 + 10 去重命中）共享同一 blob，10 个 PENDING 各占唯一内容。
    StorageUploadDTO first =
        reserve("sweep-first.bin", "application/octet-stream", content.length, sha);
    putUploadContent(first.getId(), content, "application/octet-stream");
    storageUploadService.complete(UUID.fromString(first.getId()));
    List<String> uploadIds = new ArrayList<>();
    uploadIds.add(first.getId());
    for (int i = 0; i < 10; i++) {
      StorageUploadDTO pending =
          reserve(
              "sweep-p-" + i + ".bin",
              "application/octet-stream",
              1,
              sha256Hex(new byte[] {(byte) i}));
      putUploadContent(pending.getId(), new byte[] {(byte) i}, "application/octet-stream");
      uploadIds.add(pending.getId());
      StorageUploadDTO hit =
          reserve("sweep-r-" + i + ".bin", "application/octet-stream", content.length, sha);
      uploadIds.add(hit.getId());
    }
    // 全部建好后统一回拨过期时间，避免建行过程中的机会式过期提前回收。
    for (String id : uploadIds) {
      backdateUpload(id);
    }
    String blobId = readyBlobIdOf(first.getId());
    assertEquals(11L, blobRefCount(blobId), "11 READY uploads must hold 11 references");

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    int processedA;
    int processedB;
    try {
      Future<Integer> a =
          executor.submit(
              () -> {
                start.await(30, TimeUnit.SECONDS);
                return storageUploadService.expireOnce();
              });
      Future<Integer> b =
          executor.submit(
              () -> {
                start.await(30, TimeUnit.SECONDS);
                return storageUploadService.expireOnce();
              });
      start.countDown();
      processedA = a.get(30, TimeUnit.SECONDS);
      processedB = b.get(30, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    assertEquals(
        21,
        processedA + processedB,
        "SKIP LOCKED batch-in-one-tx must give each expired row to exactly one sweeper");
    assertEquals(0, jdbc.queryForObject("select count(*) from storage_upload", Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject("select count(*) from storage_blob", Integer.class),
        "all 11 references must be released exactly once and the blob cleaned once");
    assertFalse(s3Storage.hasObject(StorageObjectKeys.blobOriginal(UUID.fromString(blobId))));
  }

  @Test
  void concurrentCompletesDedupIntoSingleActiveBlob() throws Exception {
    byte[] content = "dedup me".getBytes(StandardCharsets.UTF_8);
    String sha = sha256Hex(content);
    StorageUploadDTO first =
        reserve("dedup-a.bin", "application/octet-stream", content.length, sha);
    StorageUploadDTO second =
        reserve("dedup-b.bin", "application/octet-stream", content.length, sha);
    putUploadContent(first.getId(), content, "application/octet-stream");
    putUploadContent(second.getId(), content, "application/octet-stream");

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<StorageUploadDTO> a =
          executor.submit(
              () -> {
                start.await(30, TimeUnit.SECONDS);
                return storageUploadService.complete(UUID.fromString(first.getId()));
              });
      Future<StorageUploadDTO> b =
          executor.submit(
              () -> {
                start.await(30, TimeUnit.SECONDS);
                return storageUploadService.complete(UUID.fromString(second.getId()));
              });
      start.countDown();

      StorageUploadDTO readyA = a.get(30, TimeUnit.SECONDS);
      StorageUploadDTO readyB = b.get(30, TimeUnit.SECONDS);
      assertEquals(StorageUploadState.READY, readyA.getState());
      assertEquals(StorageUploadState.READY, readyB.getState());
      assertEquals(readyA.getBlobId(), readyB.getBlobId(), "both uploads must resolve to one blob");
    } finally {
      executor.shutdownNow();
    }

    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_blob where state = 'ACTIVE'", Integer.class),
        "concurrent completes must dedup to a single ACTIVE blob");
    assertEquals(
        2L,
        blobRefCount(readyBlobIdOf(first.getId())),
        "both READY uploads must be counted as references");
    assertTrue(
        s3Storage.hasObject(
            StorageObjectKeys.blobOriginal(UUID.fromString(readyBlobIdOf(first.getId())))),
        "dedup winner's blob object must exist");
    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.uploadOriginal(UUID.fromString(first.getId()))));
    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.uploadOriginal(UUID.fromString(second.getId()))));
    // 去重胜方的候选对象即最终对象必须保留，落败方的候选对象必须被清理（并发下胜者不确定）。
    UUID firstCandidate = candidateBlobIdOf(first.getId());
    UUID secondCandidate = candidateBlobIdOf(second.getId());
    UUID blobUuid = UUID.fromString(readyBlobIdOf(first.getId()));
    assertTrue(
        blobUuid.equals(firstCandidate) || blobUuid.equals(secondCandidate),
        "the resolved blob must be one of the two candidates");
    UUID winnerCandidate = blobUuid.equals(firstCandidate) ? firstCandidate : secondCandidate;
    UUID loserCandidate = blobUuid.equals(firstCandidate) ? secondCandidate : firstCandidate;
    assertTrue(
        s3Storage.hasObject(StorageObjectKeys.blobOriginal(winnerCandidate)),
        "dedup winner's candidate object must be kept");
    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.blobOriginal(loserCandidate)),
        "dedup loser's unused candidate object must be cleaned");
    assertNoActiveBlobReferencesMissingObject();
  }

  // ------------------------------------------------------------------
  // retain / release primitives
  // ------------------------------------------------------------------

  @Test
  void retainAndReleaseDriveRefCountAndDeletingCleanup() {
    byte[] content = "lifecycle".getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO pending =
        reserve("lifecycle.bin", "application/octet-stream", content.length, sha256Hex(content));
    putUploadContent(pending.getId(), content, "application/octet-stream");
    StorageUploadDTO ready = storageUploadService.complete(UUID.fromString(pending.getId()));
    UUID blobUuid = UUID.fromString(ready.getBlobId());

    StorageBlob retained = tx.execute(status -> storageBlobManager.retain(blobUuid));
    assertEquals(2L, retained.getRefCount());
    assertEquals(2L, blobRefCount(ready.getBlobId()));

    boolean releasedOnce = tx.execute(status -> storageBlobManager.release(blobUuid));
    assertTrue(releasedOnce);
    assertEquals(1L, blobRefCount(ready.getBlobId()));

    // 生产顺序：owner（upload 行）先删除，再释放最后一个引用；FK RESTRICT 禁止反向顺序。
    storageUploadService.delete(UUID.fromString(ready.getId()));

    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from storage_blob where id = ?", Integer.class, blobUuid),
        "release to zero must mark DELETING and remove the row afterCommit");
    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.blobOriginal(blobUuid)),
        "original object must be deleted after commit");
    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.blobPreview(blobUuid)),
        "preview object must be deleted after commit");
  }

  @Test
  void sweepDeletingRetriesAfterOwnerUploadIsGone() {
    byte[] content = "sweep".getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO pending =
        reserve("sweep.bin", "application/octet-stream", content.length, sha256Hex(content));
    putUploadContent(pending.getId(), content, "application/octet-stream");
    StorageUploadDTO ready = storageUploadService.complete(UUID.fromString(pending.getId()));
    UUID blobUuid = UUID.fromString(ready.getBlobId());

    // 模拟中断残留：blob 已 DELETING，但 owner upload 行仍引用它。
    jdbc.update("update storage_blob set state = 'DELETING', ref_count = 0 where id = ?", blobUuid);

    assertEquals(
        0,
        storageBlobManager.sweepDeleting(),
        "FK RESTRICT must block blob deletion while an upload row references it");
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_blob where id = ? and state = 'DELETING'",
            Integer.class,
            blobUuid),
        "blocked sweep must leave the DELETING row for retry");

    // owner 删除后重试成功：对象与行一并清理。
    storageUploadService.delete(UUID.fromString(ready.getId()));
    assertEquals(
        1, storageBlobManager.sweepDeleting(), "sweep must clean up once the owner is gone");
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from storage_blob where id = ?", Integer.class, blobUuid));
    assertFalse(s3Storage.hasObject(StorageObjectKeys.blobOriginal(blobUuid)));
  }

  @Test
  void releaseOnMissingOrDeletingIsIdempotentFalse() {
    boolean unknownReleased = tx.execute(status -> storageBlobManager.release(UUID.randomUUID()));
    assertFalse(unknownReleased, "release of an unknown blob must be idempotent false");

    UUID deletingBlob = insertRawBlob(StorageBlobState.DELETING, 0L);
    boolean deletingReleased = tx.execute(status -> storageBlobManager.release(deletingBlob));
    assertFalse(deletingReleased, "release of a DELETING blob must be idempotent false");
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_blob where id = ? and state = 'DELETING'",
            Integer.class,
            deletingBlob),
        "idempotent release must not mutate the DELETING row");
  }

  @Test
  void retainOnMissingOrDeletingThrowsNotFound() {
    assertThrows(
        StorageResourceNotFoundException.class,
        () -> tx.execute(status -> storageBlobManager.retain(UUID.randomUUID())));

    UUID deletingBlob = insertRawBlob(StorageBlobState.DELETING, 0L);
    assertThrows(
        StorageResourceNotFoundException.class,
        () -> tx.execute(status -> storageBlobManager.retain(deletingBlob)));
  }

  // ------------------------------------------------------------------
  // expiry
  // ------------------------------------------------------------------

  @Test
  void expirePendingDeletesObjectThenRow() {
    StorageUploadDTO pending =
        reserve("stale.bin", "application/octet-stream", 3, sha256Hex(new byte[] {1, 2, 3}));
    putUploadContent(pending.getId(), new byte[] {1, 2, 3}, "application/octet-stream");
    // 模拟 complete 在预复制候选对象后、DB 事务提交前崩溃：候选对象残留。
    UUID candidateUuid = candidateBlobIdOf(pending.getId());
    s3Storage.putDirect(
        StorageObjectKeys.blobOriginal(candidateUuid),
        new byte[] {1, 2, 3},
        "application/octet-stream");
    s3Storage.putDirect(
        StorageObjectKeys.blobPreview(candidateUuid), new byte[] {1, 2, 3}, "image/webp");
    backdateUpload(pending.getId());

    assertEquals(1, storageUploadService.expireOnce());

    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.uploadOriginal(UUID.fromString(pending.getId()))),
        "PENDING expiry must delete the temp object first");
    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.blobOriginal(candidateUuid)),
        "PENDING expiry must also clean the unused candidate object");
    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.blobPreview(candidateUuid)),
        "PENDING expiry must also clean the unused candidate preview");
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ?",
            Integer.class,
            UUID.fromString(pending.getId())),
        "PENDING expiry must remove the row after the object");
  }

  @Test
  void expireReadyDeletesUploadAndReleasesBlob() {
    byte[] content = "expiry".getBytes(StandardCharsets.UTF_8);
    String blobId = completeFirstUpload(content);
    StorageUploadDTO hit =
        reserve("expiry-copy.bin", "application/octet-stream", content.length, sha256Hex(content));
    assertEquals(2L, blobRefCount(blobId));
    backdateUpload(hit.getId());
    // 模拟 complete 在 DB 提交后、删除临时对象前崩溃：READY 上传残留临时对象。
    s3Storage.putDirect(
        StorageObjectKeys.uploadOriginal(UUID.fromString(hit.getId())),
        content,
        "application/octet-stream");
    // 命中上传的候选对象从未被绑定为最终 blob：残留的候选原始/预览必须被清理。
    UUID hitCandidate = candidateBlobIdOf(hit.getId());
    s3Storage.putDirect(
        StorageObjectKeys.blobOriginal(hitCandidate), content, "application/octet-stream");
    s3Storage.putDirect(StorageObjectKeys.blobPreview(hitCandidate), content, "image/webp");

    assertEquals(1, storageUploadService.expireOnce());

    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from storage_upload where id = ?",
            Integer.class,
            UUID.fromString(hit.getId())));
    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.uploadOriginal(UUID.fromString(hit.getId()))),
        "READY expiry must also clean the leftover temp object");
    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.blobOriginal(hitCandidate)),
        "READY expiry must also clean the unused candidate object");
    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.blobPreview(hitCandidate)),
        "READY expiry must also clean the unused candidate preview");
    assertEquals(
        1L, blobRefCount(blobId), "READY expiry must release exactly the upload's reference");
    assertEquals(
        StorageBlobState.ACTIVE,
        blobRow(blobId).state(),
        "blob with remaining references must stay ACTIVE");
  }

  @Test
  void expireOnceBatchIsBoundedBySixteen() {
    String[] ids = new String[20];
    for (int i = 0; i < ids.length; i++) {
      StorageUploadDTO pending =
          reserve(
              "batch-" + i + ".bin",
              "application/octet-stream",
              1,
              sha256Hex(new byte[] {(byte) i}));
      ids[i] = pending.getId();
    }
    for (String id : ids) {
      backdateUpload(id);
    }
    assertEquals(16, storageUploadService.expireOnce(), "SKIP LOCKED batch must be capped at 16");
    assertEquals(4, jdbc.queryForObject("select count(*) from storage_upload", Integer.class));
  }

  // ------------------------------------------------------------------
  // delete
  // ------------------------------------------------------------------

  @Test
  void deletePendingRemovesObjectThenRow() {
    StorageUploadDTO pending =
        reserve("abort.bin", "application/octet-stream", 1, sha256Hex(new byte[] {1}));
    putUploadContent(pending.getId(), new byte[] {1}, "application/octet-stream");
    // 模拟 complete 在预复制候选对象后、DB 事务提交前崩溃：候选对象残留。
    UUID candidateUuid = candidateBlobIdOf(pending.getId());
    s3Storage.putDirect(
        StorageObjectKeys.blobOriginal(candidateUuid), new byte[] {1}, "application/octet-stream");
    s3Storage.putDirect(StorageObjectKeys.blobPreview(candidateUuid), new byte[] {1}, "image/webp");

    storageUploadService.delete(UUID.fromString(pending.getId()));

    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.uploadOriginal(UUID.fromString(pending.getId()))));
    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.blobOriginal(candidateUuid)),
        "PENDING delete must also clean the unused candidate object");
    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.blobPreview(candidateUuid)),
        "PENDING delete must also clean the unused candidate preview");
    assertEquals(0, jdbc.queryForObject("select count(*) from storage_upload", Integer.class));
  }

  @Test
  void deleteReadyRemovesUploadAndReleasesBlob() {
    byte[] content = "ready-delete".getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO pending =
        reserve("ready-delete.bin", "application/octet-stream", content.length, sha256Hex(content));
    putUploadContent(pending.getId(), content, "application/octet-stream");
    StorageUploadDTO ready = storageUploadService.complete(UUID.fromString(pending.getId()));
    // 模拟 complete 在 DB 提交后、删除临时对象前崩溃：临时对象残留。
    s3Storage.putDirect(
        StorageObjectKeys.uploadOriginal(UUID.fromString(ready.getId())), content, "text/plain");

    storageUploadService.delete(UUID.fromString(ready.getId()));

    assertEquals(
        0,
        jdbc.queryForObject("select count(*) from storage_upload", Integer.class),
        "READY delete must remove the upload row");
    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.uploadOriginal(UUID.fromString(ready.getId()))),
        "READY delete must also clean the leftover temp object");
    assertEquals(
        0,
        jdbc.queryForObject("select count(*) from storage_blob", Integer.class),
        "last reference release must delete the blob row afterCommit");
    assertFalse(
        s3Storage.hasObject(StorageObjectKeys.blobOriginal(UUID.fromString(ready.getBlobId()))));
  }

  @Test
  void deleteMissingIsIdempotentNotFound() {
    assertThrows(
        StorageResourceNotFoundException.class,
        () -> storageUploadService.delete(UUID.randomUUID()));
    StorageUploadDTO pending =
        reserve("gone.bin", "application/octet-stream", 1, sha256Hex(new byte[] {1}));
    storageUploadService.delete(UUID.fromString(pending.getId()));
    assertThrows(
        StorageResourceNotFoundException.class,
        () -> storageUploadService.delete(UUID.fromString(pending.getId())),
        "repeat delete must surface the idempotent 404");
  }

  // ------------------------------------------------------------------
  // READY consumption
  // ------------------------------------------------------------------

  @Test
  void lockReadyRequiresCallerTransactionAndReturnsAuthoritativeFacts() {
    byte[] content = "consume".getBytes(StandardCharsets.UTF_8);
    StorageUploadDTO pending =
        reserve("authoritative.txt", "text/plain", content.length, sha256Hex(content));
    putUploadContent(pending.getId(), content, "text/plain");
    StorageUploadDTO ready = storageUploadService.complete(UUID.fromString(pending.getId()));
    UUID uploadId = UUID.fromString(ready.getId());

    assertThrows(
        IllegalTransactionStateException.class,
        () -> storageUploadService.lockReady(uploadId),
        "lockReady must not release its row lock before the caller transfers ownership");

    StorageUploadService.ReadyUpload facts =
        tx.execute(status -> storageUploadService.lockReady(uploadId));
    assertEquals(UUID.fromString(ready.getBlobId()), facts.blobId());
    assertEquals("authoritative.txt", facts.filename());
  }

  // ------------------------------------------------------------------
  // blob presigned URLs
  // ------------------------------------------------------------------

  @Test
  void blobPresignEndpointsRequireActiveBlobAndHideBucketKey() throws Exception {
    byte[] content = "presign".getBytes(StandardCharsets.UTF_8);
    String blobId = completeFirstUpload(content);
    UUID blobUuid = UUID.fromString(blobId);

    StoragePresignedUrlDTO original = storageBlobManager.presignOriginalUrl(blobUuid);
    assertEquals("GET", original.getMethod());
    assertTrue(original.getUrl().contains("blobs/" + blobId + "/original"));
    assertEquals("application/octet-stream", original.getMediaType());
    assertEquals((long) content.length, original.getSizeBytes());
    StoragePresignedUrlDTO preview = storageBlobManager.presignPreviewUrl(blobUuid);
    assertTrue(preview.getUrl().contains("blobs/" + blobId + "/preview.webp"));

    for (StoragePresignedUrlDTO url : List.of(original, preview)) {
      String json = objectMapper.writeValueAsString(url);
      assertFalse(json.contains("\"bucket\""), "blob URL response must not expose bucket: " + json);
      assertFalse(
          json.contains("\"key\""), "blob URL response must not expose physical key: " + json);
    }

    assertThrows(
        StorageResourceNotFoundException.class,
        () -> storageBlobManager.presignOriginalUrl(UUID.randomUUID()));

    UUID deletingBlob = insertRawBlob(StorageBlobState.DELETING, 0L);
    assertThrows(
        StorageResourceNotFoundException.class,
        () -> storageBlobManager.presignPreviewUrl(deletingBlob),
        "DELETING blob must not issue presigned URLs");
  }

  // ------------------------------------------------------------------
  // helpers
  // ------------------------------------------------------------------

  private StorageUploadDTO reserve(String filename, String mediaType, long size, String sha256) {
    StorageUploadReserveRequestDTO request = new StorageUploadReserveRequestDTO();
    request.setFilename(filename);
    request.setMediaType(mediaType);
    request.setSizeBytes(size);
    request.setSha256(sha256);
    return storageUploadService.reserve(request);
  }

  private void putUploadContent(String uploadId, byte[] content, String contentType) {
    s3Storage.putDirect(
        StorageObjectKeys.uploadOriginal(UUID.fromString(uploadId)), content, contentType);
  }

  /** 完成一次新上传并返回 blob id（blob ref_count = 1）。 */
  private String completeFirstUpload(byte[] content) {
    StorageUploadDTO pending =
        reserve("first.bin", "application/octet-stream", content.length, sha256Hex(content));
    putUploadContent(pending.getId(), content, "application/octet-stream");
    return storageUploadService.complete(UUID.fromString(pending.getId())).getBlobId();
  }

  private void backdateUpload(String uploadId) {
    jdbc.update(
        "update storage_upload set created_at = expires_at - interval '2 hours',"
            + " expires_at = current_timestamp - interval '1 minute' where id = ?",
        UUID.fromString(uploadId));
  }

  private String readyBlobIdOf(String uploadId) {
    return jdbc.queryForObject(
            "select blob_id from storage_upload where id = ?",
            UUID.class,
            UUID.fromString(uploadId))
        .toString();
  }

  private UUID candidateBlobIdOf(String uploadId) {
    return jdbc.queryForObject(
        "select candidate_blob_id from storage_upload where id = ?",
        UUID.class,
        UUID.fromString(uploadId));
  }

  /** 不变式：任何 ACTIVE blob 行都必须存在其原始对象 —— DB 绝不引用缺失的最终对象。 */
  private void assertNoActiveBlobReferencesMissingObject() {
    List<UUID> activeBlobIds =
        jdbc.query(
            "select id from storage_blob where state = 'ACTIVE'",
            (rs, rowNum) -> rs.getObject(1, UUID.class));
    for (UUID blobId : activeBlobIds) {
      assertTrue(
          s3Storage.hasObject(StorageObjectKeys.blobOriginal(blobId)),
          "ACTIVE blob " + blobId + " references a missing original object");
    }
  }

  private UUID uploadBlobId(String uploadId) {
    return jdbc.queryForObject(
        "select blob_id from storage_upload where id = ?", UUID.class, UUID.fromString(uploadId));
  }

  private long blobRefCount(String blobId) {
    return jdbc.queryForObject(
        "select ref_count from storage_blob where id = ?", Long.class, UUID.fromString(blobId));
  }

  private BlobRow blobRow(String blobId) {
    return jdbc.query(
        "select sha256, size_bytes, media_type, width, height, duration_ms, ref_count, state"
            + " from storage_blob where id = ?",
        rs -> {
          rs.next();
          return new BlobRow(
              rs.getString("sha256"),
              rs.getLong("size_bytes"),
              rs.getString("media_type"),
              nullableLong(rs.getObject("width")),
              nullableLong(rs.getObject("height")),
              nullableLong(rs.getObject("duration_ms")),
              rs.getLong("ref_count"),
              StorageBlobState.valueOf(rs.getString("state")));
        },
        UUID.fromString(blobId));
  }

  private static Long nullableLong(Object value) {
    return value == null ? null : ((Number) value).longValue();
  }

  private UUID insertRawBlob(StorageBlobState state, long refCount) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into storage_blob (id, sha256, size_bytes, media_type, ref_count, state)"
            + " values (?, ?, 1, 'image/png', ?, ?)",
        id,
        "f".repeat(64),
        refCount,
        state.name());
    return id;
  }

  private static String sha256Hex(byte[] content) {
    return HexFormat.of().formatHex(InMemoryS3StorageService.sha256(content));
  }

  private static byte[] sha256(byte[] content) {
    return InMemoryS3StorageService.sha256(content);
  }

  private record BlobRow(
      String sha256,
      long sizeBytes,
      String mediaType,
      Long width,
      Long height,
      Long durationMs,
      long refCount,
      StorageBlobState state) {}
}
