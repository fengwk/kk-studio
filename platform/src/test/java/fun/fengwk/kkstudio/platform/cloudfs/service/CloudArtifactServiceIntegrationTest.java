package fun.fengwk.kkstudio.platform.cloudfs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNode;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNodeKind;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.ToolArtifactPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudArtifactConflictException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathForbiddenException;
import fun.fengwk.kkstudio.platform.storage.S3PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.storage.StorageS3TestConfiguration;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** {@link CloudArtifactService} PostgreSQL 集成测试。 */
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
class CloudArtifactServiceIntegrationTest extends S3PostgresSpringTestSupport {

  @Autowired private CloudArtifactService artifactService;
  @Autowired private CloudFileSystemService fileSystemService;
  @Autowired private StorageBlobManager storageBlobManager;
  @Autowired private JdbcTemplate jdbc;

  private UUID seedActiveBlob(String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    String sha256 = sha256Hex(bytes);
    UUID blobId = UUID.randomUUID();
    jdbc.update(
        "insert into storage_blob (id, sha256, size_bytes, media_type, ref_count, state, created_at, updated_at) "
            + "values (?, ?, ?, 'text/plain', 1, 'ACTIVE', current_timestamp, current_timestamp)",
        blobId,
        sha256,
        (long) bytes.length);
    return blobId;
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(bytes);
      StringBuilder sb = new StringBuilder(64);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void testCreateToolArtifactSuccessAndIdempotentReplay() {
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    UUID blobId = seedActiveBlob("Artifact tool output payload");

    CloudNode node = artifactService.createToolArtifact(threadId, invocationId, "txt", blobId);
    assertNotNull(node);
    assertEquals(CloudNodeKind.BLOB, node.getKind());
    assertEquals(blobId, node.getBlobId());

    CloudPath expectedPath = ToolArtifactPath.format(threadId, invocationId, "txt");
    Optional<CloudNode> found = fileSystemService.findNode(expectedPath);
    assertTrue(found.isPresent());
    assertEquals(node.getId(), found.get().getId());

    // Storage blob retain was called once (ref_count 1 -> 2)
    StorageBlob blobAfterCreate = storageBlobManager.getBlob(blobId);
    assertEquals(2L, blobAfterCreate.getRefCount());

    // Idempotent replay: calling with same coordinates and blob
    CloudNode replayed = artifactService.createToolArtifact(threadId, invocationId, "txt", blobId);
    assertEquals(node.getId(), replayed.getId());

    // Ref count must NOT be incremented again
    StorageBlob blobAfterReplay = storageBlobManager.getBlob(blobId);
    assertEquals(2L, blobAfterReplay.getRefCount());
  }

  @Test
  void testCreateToolArtifactConflictWithMismatchedContent() {
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    UUID blob1 = seedActiveBlob("Payload 1");
    UUID blob2 = seedActiveBlob("Payload 2 different");

    artifactService.createToolArtifact(threadId, invocationId, "json", blob1);

    // Conflict: same artifact path, different content
    assertThrows(
        CloudArtifactConflictException.class,
        () -> artifactService.createToolArtifact(threadId, invocationId, "json", blob2));

    // Blob 2 ref_count was not modified
    StorageBlob blob2State = storageBlobManager.getBlob(blob2);
    assertEquals(1L, blob2State.getRefCount());
  }

  @Test
  void testCreateToolArtifactWithInactiveOrMissingBlob() {
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    UUID missingBlobId = UUID.randomUUID();

    assertThrows(
        StorageResourceNotFoundException.class,
        () -> artifactService.createToolArtifact(threadId, invocationId, "txt", missingBlobId));
  }

  @Test
  void testReservedPathAndFailClosedPublicMutations() {
    CloudPath artifactPath = ToolArtifactPath.format(UUID.randomUUID(), UUID.randomUUID(), "txt");

    assertTrue(artifactService.isReservedPath(artifactPath));
    assertTrue(artifactService.isReservedPath(CloudPath.of("/.artifacts")));
    assertTrue(artifactService.isReservedPath(CloudPath.of("/.artifacts/tool-results")));
    assertFalse(artifactService.isReservedPath(CloudPath.of("/workspace/doc.txt")));
    assertFalse(artifactService.isReservedPath(CloudPath.of("/")));
    assertFalse(artifactService.isReservedPath(null));

    // Public CFS service rejects mutations on /.artifacts
    assertThrows(
        CloudPathForbiddenException.class,
        () -> fileSystemService.writeText(artifactPath, "fake content", 0L));
    assertThrows(
        CloudPathForbiddenException.class,
        () -> fileSystemService.mkdir(artifactPath.parent(), true));
    assertThrows(
        CloudPathForbiddenException.class, () -> fileSystemService.deleteNode(artifactPath, 0L));
  }

  @Test
  void testConcurrentCreateToolArtifactSamePayload() throws Exception {
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    UUID blobId = seedActiveBlob("Concurrent payload");

    int threadCount = 2;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CyclicBarrier barrier = new CyclicBarrier(threadCount);

    List<CloudNode> results = Collections.synchronizedList(new ArrayList<>());
    List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await();
                  CloudNode node =
                      artifactService.createToolArtifact(threadId, invocationId, "txt", blobId);
                  results.add(node);
                } catch (Throwable t) {
                  errors.add(t);
                }
              }));
    }

    for (Future<?> f : futures) {
      f.get(10, TimeUnit.SECONDS);
    }
    executor.shutdown();

    assertTrue(errors.isEmpty(), () -> "Unexpected errors: " + errors);
    assertEquals(2, results.size());
    assertEquals(results.get(0).getId(), results.get(1).getId());

    // Storage blob retain must only be called once (ref_count was 1 initially, now 2)
    StorageBlob blob = storageBlobManager.getBlob(blobId);
    assertEquals(2L, blob.getRefCount());
  }

  @Test
  void testConcurrentCreateToolArtifactDifferingPayload() throws Exception {
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    UUID blob1 = seedActiveBlob("Payload A");
    UUID blob2 = seedActiveBlob("Payload B");

    int threadCount = 2;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CyclicBarrier barrier = new CyclicBarrier(threadCount);

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger conflictCount = new AtomicInteger(0);
    List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

    List<Future<?>> futures = new ArrayList<>();
    futures.add(
        executor.submit(
            () -> {
              try {
                barrier.await();
                artifactService.createToolArtifact(threadId, invocationId, "txt", blob1);
                successCount.incrementAndGet();
              } catch (CloudArtifactConflictException e) {
                conflictCount.incrementAndGet();
              } catch (Throwable t) {
                errors.add(t);
              }
            }));
    futures.add(
        executor.submit(
            () -> {
              try {
                barrier.await();
                artifactService.createToolArtifact(threadId, invocationId, "txt", blob2);
                successCount.incrementAndGet();
              } catch (CloudArtifactConflictException e) {
                conflictCount.incrementAndGet();
              } catch (Throwable t) {
                errors.add(t);
              }
            }));

    for (Future<?> f : futures) {
      f.get(10, TimeUnit.SECONDS);
    }
    executor.shutdown();

    assertTrue(errors.isEmpty(), () -> "Unexpected errors: " + errors);
    assertEquals(1, successCount.get(), "Exactly one payload should succeed");
    assertEquals(
        1, conflictCount.get(), "Conflicting payload should throw CloudArtifactConflictException");
  }
}
