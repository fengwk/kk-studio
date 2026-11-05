package fun.fengwk.kkstudio.platform.storage;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.storage.error.SessionFileConflictException;
import fun.fengwk.kkstudio.platform.storage.model.SessionFile;
import fun.fengwk.kkstudio.platform.storage.persistence.SessionFileRepository;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.SessionFileLimits;
import fun.fengwk.kkstudio.platform.storage.service.SessionFileStore;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** SessionFileStore 的 PostgreSQL + InMemory S3 真实存储集成测试。 */
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
class SessionFileStoreIntegrationTest extends PostgresSpringTestSupport {

  private static final UUID SESSION_A = new UUID(0L, 5001L);
  private static final UUID SESSION_B = new UUID(0L, 5002L);

  @Autowired private SessionFileStore sessionFileStore;
  @Autowired private SessionFileRepository sessionFileRepository;
  @Autowired private StorageBlobRepository blobRepository;
  @Autowired private SessionBlobRefManager refManager;
  @Autowired private InMemoryS3StorageService s3Storage;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StorageUploadService uploadService;
  @Autowired private StorageBlobContentService blobContentService;
  @Autowired private Clock clock;
  @Autowired private PlatformTransactionManager transactionManager;

  @BeforeEach
  void setUp() {
    s3Storage.clear();
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

  private void seedSession(UUID sessionId) {
    jdbc.update(
        "insert into harness_session (id, name, created_at) values (?, ?, current_timestamp) "
            + "on conflict (id) do nothing",
        sessionId,
        "test-session-" + sessionId);
  }

  @Test
  void writeCreatesRevisionOne() {
    byte[] content = "hello world".getBytes(UTF_8);
    SessionFile file = sessionFileStore.write(SESSION_A, "docs/readme.txt", content, null);

    assertNotNull(file);
    assertEquals(SESSION_A, file.sessionId());
    assertEquals("docs/readme.txt", file.path());
    assertEquals(1, file.revision());
    assertNotNull(file.blobId());
    assertNotNull(file.updatedAt());

    Optional<SessionFile> queried = sessionFileStore.find(SESSION_A, "docs/readme.txt");
    assertTrue(queried.isPresent());
    assertEquals(file.blobId(), queried.get().blobId());
    assertEquals(1, queried.get().revision());

    Optional<byte[]> readBytes = sessionFileStore.read(SESSION_A, "docs/readme.txt");
    assertTrue(readBytes.isPresent());
    assertArrayEquals(content, readBytes.get());

    assertTrue(refManager.contains(SESSION_A, file.blobId()));
    StorageBlob blob = blobRepository.getById(file.blobId());
    assertNotNull(blob);
    assertEquals(StorageBlobState.ACTIVE, blob.getState());
    assertEquals(1L, blob.getRefCount());
  }

  @Test
  void overwriteWithStaleExpectedRevisionThrowsConflictAndLeavesContent() {
    byte[] contentV1 = "version 1".getBytes(UTF_8);
    SessionFile file = sessionFileStore.write(SESSION_A, "app/main.py", contentV1, null);
    assertEquals(1, file.revision());

    byte[] contentV2 = "version 2".getBytes(UTF_8);
    // Stale revision: expected 999
    assertThrows(
        SessionFileConflictException.class,
        () -> sessionFileStore.write(SESSION_A, "app/main.py", contentV2, 999));

    // Stale revision: expected null (insert when file already exists)
    assertThrows(
        SessionFileConflictException.class,
        () -> sessionFileStore.write(SESSION_A, "app/main.py", contentV2, null));

    // Content and revision remain untouched
    SessionFile current = sessionFileStore.find(SESSION_A, "app/main.py").orElseThrow();
    assertEquals(1, current.revision());
    assertEquals(file.blobId(), current.blobId());
    assertArrayEquals(contentV1, sessionFileStore.read(SESSION_A, "app/main.py").orElseThrow());
  }

  @Test
  void overwriteSucceedsWithMatchingRevisionAndIncrementsRevision() {
    byte[] contentV1 = "version 1".getBytes(UTF_8);
    SessionFile file1 = sessionFileStore.write(SESSION_A, "config.json", contentV1, null);
    assertEquals(1, file1.revision());

    byte[] contentV2 = "version 2".getBytes(UTF_8);
    SessionFile file2 = sessionFileStore.write(SESSION_A, "config.json", contentV2, 1);
    assertEquals(2, file2.revision());
    assertEquals("config.json", file2.path());
    assertFalse(file2.blobId().equals(file1.blobId()));

    assertArrayEquals(contentV2, sessionFileStore.read(SESSION_A, "config.json").orElseThrow());
  }

  @Test
  void oldBlobReleasedWhenNoOtherFileReferencesIt() {
    byte[] contentV1 = "blob-alpha".getBytes(UTF_8);
    SessionFile file1 = sessionFileStore.write(SESSION_A, "single.txt", contentV1, null);
    UUID blob1 = file1.blobId();

    assertTrue(refManager.contains(SESSION_A, blob1));
    assertEquals(1L, blobRepository.getById(blob1).getRefCount());

    byte[] contentV2 = "blob-beta".getBytes(UTF_8);
    SessionFile file2 = sessionFileStore.write(SESSION_A, "single.txt", contentV2, 1);
    UUID blob2 = file2.blobId();

    assertFalse(blob1.equals(blob2));
    assertFalse(refManager.contains(SESSION_A, blob1));
    assertEquals(StorageBlobState.DELETING, blobRepository.getById(blob1).getState());
    assertEquals(0L, blobRepository.getById(blob1).getRefCount());

    assertTrue(refManager.contains(SESSION_A, blob2));
    assertEquals(StorageBlobState.ACTIVE, blobRepository.getById(blob2).getState());
    assertEquals(1L, blobRepository.getById(blob2).getRefCount());
  }

  @Test
  void oldBlobNotReleasedWhenAnotherFileInSessionReferencesIt() {
    byte[] sharedContent = "shared content across two files".getBytes(UTF_8);
    SessionFile fileA = sessionFileStore.write(SESSION_A, "fileA.txt", sharedContent, null);
    SessionFile fileB = sessionFileStore.write(SESSION_A, "fileB.txt", sharedContent, null);

    UUID sharedBlobId = fileA.blobId();
    assertEquals(sharedBlobId, fileB.blobId());

    // Update fileA with distinct content
    byte[] newContent = "distinct content for fileA".getBytes(UTF_8);
    SessionFile fileA2 = sessionFileStore.write(SESSION_A, "fileA.txt", newContent, 1);

    assertFalse(fileA2.blobId().equals(sharedBlobId));

    // fileB still references sharedBlobId: Session A must retain sharedBlobId!
    assertTrue(refManager.contains(SESSION_A, sharedBlobId));
    StorageBlob sharedBlob = blobRepository.getById(sharedBlobId);
    assertEquals(StorageBlobState.ACTIVE, sharedBlob.getState());
    assertTrue(sharedBlob.getRefCount() >= 1L);
  }

  @Test
  void limitsEnforced() {
    // 1. Single file size limit (8 MiB)
    byte[] oversizeContent = new byte[(int) SessionFileStore.MAX_SINGLE_FILE_BYTES + 1];
    IllegalArgumentException sizeError =
        assertThrows(
            IllegalArgumentException.class,
            () -> sessionFileStore.write(SESSION_A, "huge.bin", oversizeContent, null));
    assertTrue(sizeError.getMessage().contains("session file too large"));

    // 2. Max files per session (2000 files)
    // Create a dummy blob to populate 2000 files quickly in PostgreSQL
    SessionFile seed = sessionFileStore.write(SESSION_A, "seed.txt", "seed".getBytes(UTF_8), null);
    jdbc.update(
        """
        insert into session_file (session_id, path, blob_id, revision, updated_at)
        select ?, 'file_' || s, ?, 1, current_timestamp
        from generate_series(2, 2000) s
        """,
        SESSION_A,
        seed.blobId());

    assertEquals(2000L, sessionFileRepository.countBySession(SESSION_A));

    IllegalStateException fileLimitError =
        assertThrows(
            IllegalStateException.class,
            () ->
                sessionFileStore.write(
                    SESSION_A, "overflow.txt", "overflow".getBytes(UTF_8), null));
    assertTrue(fileLimitError.getMessage().contains("session file limit reached: 2000 files"));

    // 3. Total size limit per session (64 MiB)
    // On session B, fill size close to limit
    sessionFileStore.deleteBySession(SESSION_B);
    SessionFile seedB = sessionFileStore.write(SESSION_B, "seedB.txt", "s".getBytes(UTF_8), null);
    // Artificially update storage_blob size of seedB to 64 MiB
    jdbc.update(
        "update storage_blob set size_bytes = ? where id = ?",
        SessionFileStore.MAX_TOTAL_BYTES_PER_SESSION,
        seedB.blobId());

    IllegalStateException totalLimitError =
        assertThrows(
            IllegalStateException.class,
            () -> sessionFileStore.write(SESSION_B, "exceed.txt", "more".getBytes(UTF_8), null));
    assertTrue(totalLimitError.getMessage().contains("session total size limit reached"));
  }

  /** 并发写共享文件数配额时，事务内 advisory lock 必须保证落库文件数不超过上限。 */
  @Test
  void concurrentWritersCannotExceedTheFileQuota() throws Exception {
    SessionFileStore limited = limitedStore(new SessionFileLimits(3, 1024, 64 * 1024));
    int threadCount = 8;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startGate = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger(0);
    AtomicInteger quotaFailures = new AtomicInteger(0);

    List<Future<Void>> futures = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      final int writerId = i;
      futures.add(
          executor.submit(
              () -> {
                startGate.await();
                try {
                  limited.write(SESSION_A, "quota/" + writerId + ".txt", "x".getBytes(UTF_8), null);
                  successes.incrementAndGet();
                } catch (IllegalStateException quotaExceeded) {
                  quotaFailures.incrementAndGet();
                }
                return null;
              }));
    }

    startGate.countDown();
    for (Future<Void> future : futures) {
      future.get(20, TimeUnit.SECONDS);
    }
    executor.shutdown();

    assertEquals(3, successes.get(), "only the quota allows three files");
    assertEquals(threadCount - 3, quotaFailures.get(), "every extra writer must be refused");
    assertEquals(3L, sessionFileRepository.countBySession(SESSION_A));
  }

  /** 并发写共享总大小配额时，事务内 advisory lock 必须保证总字节数不超过上限。 */
  @Test
  void concurrentWritersCannotExceedTheTotalSizeQuota() throws Exception {
    SessionFileStore limited = limitedStore(new SessionFileLimits(100, 64, 64));
    byte[] payload = new byte[16];
    int threadCount = 8;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startGate = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger(0);
    AtomicInteger quotaFailures = new AtomicInteger(0);

    List<Future<Void>> futures = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      final int writerId = i;
      futures.add(
          executor.submit(
              () -> {
                startGate.await();
                try {
                  limited.write(SESSION_A, "size/" + writerId + ".bin", payload, null);
                  successes.incrementAndGet();
                } catch (IllegalStateException quotaExceeded) {
                  quotaFailures.incrementAndGet();
                }
                return null;
              }));
    }

    startGate.countDown();
    for (Future<Void> future : futures) {
      future.get(20, TimeUnit.SECONDS);
    }
    executor.shutdown();

    assertEquals(4, successes.get(), "64 bytes of quota fits exactly four 16-byte files");
    assertEquals(threadCount - 4, quotaFailures.get(), "every extra writer must be refused");
    assertEquals(64L, limited.totalBytes(SESSION_A));
  }

  private SessionFileStore limitedStore(SessionFileLimits limits) {
    return new SessionFileStore(
        sessionFileRepository,
        blobRepository,
        uploadService,
        blobContentService,
        refManager,
        clock,
        transactionManager,
        limits);
  }

  @Test
  void deleteBySessionClearsRows() {
    sessionFileStore.write(SESSION_A, "a.txt", "a".getBytes(UTF_8), null);
    sessionFileStore.write(SESSION_A, "b.txt", "b".getBytes(UTF_8), null);
    sessionFileStore.write(SESSION_B, "other.txt", "other".getBytes(UTF_8), null);

    assertEquals(2L, sessionFileRepository.countBySession(SESSION_A));
    assertEquals(1L, sessionFileRepository.countBySession(SESSION_B));

    List<UUID> deletedBlobs = sessionFileStore.deleteBySession(SESSION_A);
    assertEquals(2, deletedBlobs.size());

    assertEquals(0L, sessionFileRepository.countBySession(SESSION_A));
    assertTrue(sessionFileStore.list(SESSION_A, "").isEmpty());

    // Session B remains untouched
    assertEquals(1L, sessionFileRepository.countBySession(SESSION_B));
  }

  @Test
  void concurrentWritersWithSameExpectedRevisionProduceExactlyOneWinner() throws Exception {
    SessionFile initial =
        sessionFileStore.write(SESSION_A, "concurrent.txt", "v1".getBytes(UTF_8), null);
    assertEquals(1, initial.revision());

    int threadCount = 8;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startGate = new CountDownLatch(1);
    AtomicInteger conflicts = new AtomicInteger(0);
    AtomicInteger successes = new AtomicInteger(0);

    List<Future<Void>> futures = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      final int writerId = i;
      futures.add(
          executor.submit(
              () -> {
                startGate.await();
                try {
                  sessionFileStore.write(
                      SESSION_A, "concurrent.txt", ("winner-" + writerId).getBytes(UTF_8), 1);
                  successes.incrementAndGet();
                } catch (SessionFileConflictException e) {
                  conflicts.incrementAndGet();
                }
                return null;
              }));
    }

    startGate.countDown();
    for (Future<Void> future : futures) {
      future.get(10, TimeUnit.SECONDS);
    }
    executor.shutdown();

    assertEquals(1, successes.get(), "Exactly one concurrent writer must succeed");
    assertEquals(
        threadCount - 1, conflicts.get(), "All other concurrent writers must get conflict");

    SessionFile current = sessionFileStore.find(SESSION_A, "concurrent.txt").orElseThrow();
    assertEquals(2, current.revision());
  }

  @Test
  void concurrentCreatorsProduceExactlyOneWinner() throws Exception {
    int threadCount = 8;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startGate = new CountDownLatch(1);
    AtomicInteger conflicts = new AtomicInteger(0);
    AtomicInteger successes = new AtomicInteger(0);

    List<Future<Void>> futures = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      final int creatorId = i;
      futures.add(
          executor.submit(
              () -> {
                startGate.await();
                try {
                  sessionFileStore.write(
                      SESSION_A,
                      "create-concurrent.txt",
                      ("creator-" + creatorId).getBytes(UTF_8),
                      null);
                  successes.incrementAndGet();
                } catch (SessionFileConflictException e) {
                  conflicts.incrementAndGet();
                }
                return null;
              }));
    }

    startGate.countDown();
    for (Future<Void> future : futures) {
      future.get(10, TimeUnit.SECONDS);
    }
    executor.shutdown();

    assertEquals(1, successes.get(), "Exactly one concurrent creator must succeed");
    assertEquals(
        threadCount - 1, conflicts.get(), "All other concurrent creators must get conflict");

    SessionFile current = sessionFileStore.find(SESSION_A, "create-concurrent.txt").orElseThrow();
    assertEquals(1, current.revision());
  }

  @Test
  void listByPrefixAndTotalBytes() {
    sessionFileStore.write(SESSION_A, "src/main/App.java", "class App {}".getBytes(UTF_8), null);
    sessionFileStore.write(SESSION_A, "src/main/Util.java", "class Util {}".getBytes(UTF_8), null);
    sessionFileStore.write(
        SESSION_A, "src/test/AppTest.java", "class AppTest {}".getBytes(UTF_8), null);
    sessionFileStore.write(SESSION_A, "pom.xml", "<project/>".getBytes(UTF_8), null);

    List<SessionFile> allFiles = sessionFileStore.list(SESSION_A, null);
    assertEquals(4, allFiles.size());

    List<SessionFile> srcMainFiles = sessionFileStore.list(SESSION_A, "src/main/");
    assertEquals(2, srcMainFiles.size());
    assertEquals("src/main/App.java", srcMainFiles.get(0).path());
    assertEquals("src/main/Util.java", srcMainFiles.get(1).path());

    List<SessionFile> pomFile = sessionFileStore.list(SESSION_A, "pom");
    assertEquals(1, pomFile.size());
    assertEquals("pom.xml", pomFile.get(0).path());

    long totalBytes = sessionFileStore.totalBytes(SESSION_A);
    assertEquals(
        "class App {}".length()
            + "class Util {}".length()
            + "class AppTest {}".length()
            + "<project/>".length(),
        totalBytes);
  }

  @Test
  void pathValidationRejectsInvalidPaths() {
    byte[] content = "valid".getBytes(UTF_8);

    assertThrows(
        IllegalArgumentException.class, () -> sessionFileStore.write(SESSION_A, "", content, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> sessionFileStore.write(SESSION_A, "  ", content, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> sessionFileStore.write(SESSION_A, "/leading/slash", content, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> sessionFileStore.write(SESSION_A, "a/../b", content, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> sessionFileStore.write(SESSION_A, "../root", content, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> sessionFileStore.write(SESSION_A, "dir/..", content, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> sessionFileStore.write(SESSION_A, "a/./b", content, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> sessionFileStore.write(SESSION_A, "./root", content, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> sessionFileStore.write(SESSION_A, "  spaces  ", content, null));
    // C0（含 TAB/LF）、DEL 与 C1 与数据库 CHECK 一致：写入前拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () -> sessionFileStore.write(SESSION_A, "tab\there.txt", content, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> sessionFileStore.write(SESSION_A, "line\nbreak.txt", content, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> sessionFileStore.write(SESSION_A, "del" + (char) 0x7F + ".txt", content, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> sessionFileStore.write(SESSION_A, "c1" + (char) 0x85 + ".txt", content, null));
  }
}
