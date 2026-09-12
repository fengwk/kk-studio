package fun.fengwk.kkstudio.platform.cloudfs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudNodeKindConflictException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudNodeNotFoundException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathForbiddenException;
import fun.fengwk.kkstudio.platform.cloudfs.repository.CloudNodeRepository;
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

  private static final UUID PRESEEDED_ARTIFACTS_DIR_ID =
      UUID.fromString("c0000000-0000-0000-0000-000000000003");
  private static final UUID PRESEEDED_TOOL_RESULTS_DIR_ID =
      UUID.fromString("c0000000-0000-0000-0000-000000000004");

  @Autowired private CloudArtifactService artifactService;
  @Autowired private CloudFileSystemService fileSystemService;
  @Autowired private CloudNodeRepository nodeRepository;
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

  /** 验证创建工具产物并进行相同内容的幂等重放，断言 storage_blob 的 retain 引用计数仅递增一次。 */
  @Test
  void testCreateToolArtifactSuccessAndIdempotentReplay() {
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    UUID blobId = seedActiveBlob("Tool execution output payload");

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

  /** 验证同一产物路径在遇到内容不一致（哈希或大小不同）的重新提交时，稳定抛出业务冲突异常。 */
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

  /** 验证引用不存在或非 ACTIVE 状态的 storage_blob 时快速失败，抛出资源未找到异常。 */
  @Test
  void testCreateToolArtifactWithInactiveOrMissingBlob() {
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    UUID missingBlobId = UUID.randomUUID();

    assertThrows(
        StorageResourceNotFoundException.class,
        () -> artifactService.createToolArtifact(threadId, invocationId, "txt", missingBlobId));
  }

  /** 验证必填入参非空校验。 */
  @Test
  void testCreateToolArtifactRejectsNullParameters() {
    UUID dummy = UUID.randomUUID();
    assertThrows(
        NullPointerException.class,
        () -> artifactService.createToolArtifact(null, dummy, "txt", dummy));
    assertThrows(
        NullPointerException.class,
        () -> artifactService.createToolArtifact(dummy, null, "txt", dummy));
    assertThrows(
        NullPointerException.class,
        () -> artifactService.createToolArtifact(dummy, dummy, "txt", null));
  }

  /** 验证公开 CloudFileSystemService 对 /.artifacts 路径树的写操作一律 Fail-Closed 拦截。 */
  @Test
  void testFailClosedPublicMutationsOnArtifactPath() {
    CloudPath artifactPath = ToolArtifactPath.format(UUID.randomUUID(), UUID.randomUUID(), "txt");
    assertTrue(artifactPath.isArtifactPath());

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

  /** 验证当 tool-results base 节点缺失时快速抛出 CloudNodeNotFoundException。 */
  @Test
  void testCreateToolArtifactRejectsMissingToolResultsBase() {
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    UUID blobId = seedActiveBlob("payload");

    // Temporarily delete tool-results preseeded directory
    jdbc.update("delete from cloud_node where id = ?", PRESEEDED_TOOL_RESULTS_DIR_ID);

    assertThrows(
        CloudNodeNotFoundException.class,
        () -> artifactService.createToolArtifact(threadId, invocationId, "txt", blobId));
  }

  /** 验证当 tool-results base 节点的属性被篡改（名称、父节点或类型非 DIRECTORY）时抛出 CloudNodeKindConflictException。 */
  @Test
  void testCreateToolArtifactRejectsCorruptedToolResultsBase() {
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    UUID blobId = seedActiveBlob("payload");

    // Corrupt tool-results base name
    jdbc.update(
        "update cloud_node set name = 'corrupted-results' where id = ?",
        PRESEEDED_TOOL_RESULTS_DIR_ID);
    assertThrows(
        CloudNodeKindConflictException.class,
        () -> artifactService.createToolArtifact(threadId, invocationId, "txt", blobId));

    // Restore name, corrupt parent_id
    jdbc.update(
        "update cloud_node set name = 'tool-results', parent_id = null where id = ?",
        PRESEEDED_TOOL_RESULTS_DIR_ID);
    assertThrows(
        CloudNodeKindConflictException.class,
        () -> artifactService.createToolArtifact(threadId, invocationId, "txt", blobId));

    // Restore parent_id, corrupt kind to BLOB
    UUID dummyBlob = seedActiveBlob("dummy for dir");
    jdbc.update(
        "update cloud_node set parent_id = ?, kind = 'BLOB', blob_id = ? where id = ?",
        PRESEEDED_ARTIFACTS_DIR_ID,
        dummyBlob,
        PRESEEDED_TOOL_RESULTS_DIR_ID);
    assertThrows(
        CloudNodeKindConflictException.class,
        () -> artifactService.createToolArtifact(threadId, invocationId, "txt", blobId));
  }

  /** 验证当会话线程节点被篡改为非 DIRECTORY（如 BLOB）时，产物创建快速失败并抛出类型冲突异常。 */
  @Test
  void testCreateToolArtifactRejectsCorruptedThreadKind() {
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    UUID dummyBlobId = seedActiveBlob("corrupted blob as parent");
    UUID targetBlobId = seedActiveBlob("actual artifact content");

    CloudNode toolResultsDir = nodeRepository.findById(PRESEEDED_TOOL_RESULTS_DIR_ID).orElseThrow();

    // Corrupt thread node by creating it as a BLOB instead of DIRECTORY under tool-results
    CloudNode corruptedThreadNode =
        CloudNode.builder()
            .id(UUID.randomUUID())
            .parentId(toolResultsDir.getId())
            .name(threadId.toString())
            .kind(CloudNodeKind.BLOB)
            .blobId(dummyBlobId)
            .version(0L)
            .build();
    nodeRepository.insert(corruptedThreadNode);

    assertThrows(
        CloudNodeKindConflictException.class,
        () -> artifactService.createToolArtifact(threadId, invocationId, "txt", targetBlobId));
  }

  /** 验证多线程并发创建相同内容的工具产物时正常收敛，仅创建单个节点且底层 blob 仅递增一次引用。 */
  @Test
  void testConcurrentCreateToolArtifactSamePayload() throws Exception {
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    UUID blobId = seedActiveBlob("Concurrent payload");

    int threadCount = 2;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    try {
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

      assertTrue(errors.isEmpty(), () -> "Concurrent artifact creation failed: " + errors);
      assertEquals(threadCount, results.size());
      assertEquals(results.get(0).getId(), results.get(1).getId());

      // Storage blob retain was called exactly once for the node
      StorageBlob blob = storageBlobManager.getBlob(blobId);
      assertEquals(2L, blob.getRefCount());
    } finally {
      executor.shutdownNow();
    }
  }

  /** 验证两个线程并发向相同产物路径提交不同内容时，仅一人成功，另一人稳定收到领域冲突异常。 */
  @Test
  void testConcurrentCreateToolArtifactDifferentPayload() throws Exception {
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    UUID blobId1 = seedActiveBlob("Payload one");
    UUID blobId2 = seedActiveBlob("Payload two different");

    int threadCount = 2;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    try {
      CyclicBarrier barrier = new CyclicBarrier(threadCount);

      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger conflictCount = new AtomicInteger(0);
      List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

      List<UUID> blobs = List.of(blobId1, blobId2);
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < threadCount; i++) {
        final int idx = i;
        futures.add(
            executor.submit(
                () -> {
                  try {
                    barrier.await();
                    artifactService.createToolArtifact(
                        threadId, invocationId, "txt", blobs.get(idx));
                    successCount.incrementAndGet();
                  } catch (CloudArtifactConflictException e) {
                    conflictCount.incrementAndGet();
                  } catch (Throwable t) {
                    unexpectedErrors.add(t);
                  }
                }));
      }

      for (Future<?> f : futures) {
        f.get(10, TimeUnit.SECONDS);
      }

      assertTrue(unexpectedErrors.isEmpty(), () -> "Unexpected errors: " + unexpectedErrors);
      assertEquals(1, successCount.get(), "Exactly one artifact creation should succeed");
      assertEquals(
          1, conflictCount.get(), "Losing thread must receive CloudArtifactConflictException");
    } finally {
      executor.shutdownNow();
    }
  }
}
