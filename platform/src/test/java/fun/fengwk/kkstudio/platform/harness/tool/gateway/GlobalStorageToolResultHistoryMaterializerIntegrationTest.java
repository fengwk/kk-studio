package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextArtifactMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.platform.storage.StorageS3TestConfiguration;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** READY upload 到 durable Tool history 的真实 PostgreSQL 原子转移契约。 */
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
class GlobalStorageToolResultHistoryMaterializerIntegrationTest extends PostgresSpringTestSupport {

  private static final UUID SESSION_ID = new UUID(0L, 7001L);

  @Autowired private GlobalStorageToolResultHistoryMaterializer materializer;
  @Autowired private StorageUploadService uploadService;
  @Autowired private InMemoryS3StorageService s3Storage;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate transaction;

  @BeforeEach
  void setUp() {
    s3Storage.clear();
    transaction = new TransactionTemplate(transactionManager);
    jdbc.update(
        "insert into harness_session (id, name, created_at) values (?, ?, current_timestamp)",
        SESSION_ID,
        "tool-history-test");
  }

  @Test
  void consumesReadyUploadWithoutObjectIo() {
    // Gateway 已完成对象准备；持有 Harness 事务的物化阶段只转移数据库引用。
    byte[] bytes = "hello\nworld".getBytes(StandardCharsets.UTF_8);
    StorageUploadService.StagedUpload staged = stage("result.txt", "text/plain", bytes);
    s3Storage.clearNetworkCalls();

    List<AgentMessageContent> contents =
        inTransaction(
            new ToolResult(
                "call-1",
                List.of(
                    new ResourceResultContent(
                        ref(staged), "hello\nworld", new TextArtifactMetadata(bytes.length, 2))),
                false,
                "{}"));

    ResourceMessageContent content =
        assertInstanceOf(ResourceMessageContent.class, contents.getFirst());
    assertTrue(content.isExternalizedText());
    assertEquals(staged.blobId(), content.blobId());
    assertEquals("result.txt", content.name());
    assertEquals(0, s3Storage.networkCalls().size());
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from session_blob_ref where session_id = ? and blob_id = ?",
            Integer.class,
            SESSION_ID,
            staged.blobId()));
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from storage_upload"
                + " where id = ? and cleanup_requested_at is not null",
            Integer.class,
            staged.uploadId()));
  }

  @Test
  void laterInvalidResourceRollsBackEarlierOwnershipTransfer() {
    // 同一 ToolResult 的后置引用校验失败时，前置 retain/delete 必须与 Entry 一起整体回滚。
    StorageUploadService.StagedUpload first =
        stage("first.bin", "application/octet-stream", new byte[] {1});
    StorageUploadService.StagedUpload second =
        stage("second.bin", "application/octet-stream", new byte[] {2});
    ResourceRef invalidSecond =
        new ResourceRef(
            ResourceRef.blobUploadUri(second.uploadId()),
            second.mediaType(),
            second.filename(),
            second.sizeBytes(),
            "a".repeat(64));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                new ToolResult(
                    "call-1",
                    List.of(
                        new ResourceResultContent(ref(first)),
                        new ResourceResultContent(invalidSecond)),
                    false,
                    "{}")));

    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from session_blob_ref where session_id = ?",
            Integer.class,
            SESSION_ID));
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from storage_upload"
                + " where id in (?, ?) and cleanup_requested_at is not null",
            Integer.class,
            first.uploadId(),
            second.uploadId()));
  }

  @Test
  void requiresCallerTransaction() {
    StorageUploadService.StagedUpload staged =
        stage("result.bin", "application/octet-stream", new byte[] {1});

    assertThrows(
        IllegalTransactionStateException.class,
        () ->
            materializer.materialize(
                SESSION_ID,
                "tool",
                new ToolResult(
                    "call-1", List.of(new ResourceResultContent(ref(staged))), false, "{}")));
  }

  private StorageUploadService.StagedUpload stage(
      String filename, String mediaType, byte[] content) {
    return uploadService.stage(
        filename, mediaType, new ByteArrayInputStream(content), Math.max(1, content.length));
  }

  private static ResourceRef ref(StorageUploadService.StagedUpload staged) {
    return new ResourceRef(
        ResourceRef.blobUploadUri(staged.uploadId()),
        staged.mediaType(),
        staged.filename(),
        staged.sizeBytes(),
        staged.sha256());
  }

  private List<AgentMessageContent> inTransaction(ToolResult result) {
    return transaction.execute(status -> materializer.materialize(SESSION_ID, "tool", result));
  }
}
