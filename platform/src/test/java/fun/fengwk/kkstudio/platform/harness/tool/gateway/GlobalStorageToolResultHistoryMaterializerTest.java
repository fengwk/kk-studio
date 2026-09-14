package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextArtifactMetadata;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.platform.storage.S3PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.StorageS3TestConfiguration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tool Resource 在 durable history 插入前的全局存储物化契约：只读取注入的 Platform ResourceStore， 全量预检后摄入
 * storage_blob/session ref，并返回 Blob-backed durable 内容。
 */
@Import({
  StorageS3TestConfiguration.class,
  GlobalStorageToolResultHistoryMaterializerTest.ManagedResourceStoreConfiguration.class
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
class GlobalStorageToolResultHistoryMaterializerTest extends S3PostgresSpringTestSupport {

  private static final UUID SESSION = new UUID(0L, 1L);

  @Autowired private GlobalStorageToolResultHistoryMaterializer materializer;
  @Autowired private InMemoryS3StorageService s3Storage;
  @Autowired private ManagedResourceStore resourceStore;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate tx;

  @BeforeEach
  void setUp() {
    s3Storage.clear();
    resourceStore.clear();
    tx = new TransactionTemplate(transactionManager);
    // session_blob_ref 使用 RESTRICT FK，测试先建立真实 session owner。
    jdbc.update(
        "insert into harness_session (id, name, created_at) values (?, ?, current_timestamp)",
        SESSION,
        "test-session");
  }

  @Test
  void managedMediaIsIngestedAndContentOrderIsPreserved() {
    byte[] bytes = new byte[] {1, 2, 3};
    ResourceRef ref = resourceStore.put("image/png", "image.png", bytes);

    List<AgentMessageContent> contents =
        inTransaction(
            new ToolResult(
                "call-1",
                List.of(
                    new TextResultContent("first"),
                    new ResourceResultContent(ref, "image preview"),
                    new JsonResultContent("{\"status\":\"ok\"}")),
                false,
                "{}"));

    assertEquals(3, contents.size());
    assertEquals("first", assertInstanceOf(TextMessageContent.class, contents.get(0)).text());
    ResourceMessageContent media = assertInstanceOf(ResourceMessageContent.class, contents.get(1));
    assertFalse(media.isExternalizedText());
    assertEquals("image.png", media.name());
    assertEquals("image preview", media.preview());
    assertArrayEquals(bytes, s3Storage.objectBytes(StorageObjectKeys.blobOriginal(media.blobId())));
    assertEquals(
        "{\"status\":\"ok\"}", assertInstanceOf(JsonMessageContent.class, contents.get(2)).json());
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from session_blob_ref where session_id = ? and blob_id = ?",
            Integer.class,
            SESSION,
            media.blobId()));
  }

  @Test
  void externalizedTextIsStrictlyVerifiedAndUsesDerivedPreview() {
    String text = "hello\nworld\nline 3";
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    ResourceRef ref = resourceStore.put("text/plain", "demo_tool-result.txt", bytes);
    ResourceResultContent resource =
        new ResourceResultContent(
            ref, "untrusted preview", new TextArtifactMetadata(bytes.length, 3));

    List<AgentMessageContent> contents =
        inTransaction(new ToolResult("call-1", List.of(resource), false, "{}"));

    ResourceMessageContent externalized =
        assertInstanceOf(ResourceMessageContent.class, contents.get(0));
    assertTrue(externalized.isExternalizedText());
    assertEquals("demo_tool-result.txt", externalized.name());
    assertEquals(bytes.length, externalized.totalBytes());
    assertEquals(3, externalized.totalLines());
    assertEquals(text, externalized.preview());
    assertArrayEquals(
        bytes, s3Storage.objectBytes(StorageObjectKeys.blobOriginal(externalized.blobId())));
  }

  @Test
  void unnamedJsonTextUsesToolNameAndJsonExtension() {
    String json = "{\"data\":\"value\"}";
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    ResourceRef ref = resourceStore.put("application/json", null, bytes);

    ResourceMessageContent externalized =
        assertInstanceOf(
            ResourceMessageContent.class,
            inTransaction(
                    new ToolResult(
                        "call-1",
                        List.of(
                            new ResourceResultContent(
                                ref, null, new TextArtifactMetadata(bytes.length, 1))),
                        false,
                        "{}"))
                .get(0));

    assertTrue(externalized.isExternalizedText());
    assertEquals("demo_tool-result.json", externalized.name());
    assertEquals(json, externalized.preview());
  }

  @Test
  void validatesEveryResourceBeforeFirstGlobalIngest() {
    ResourceRef managed =
        resourceStore.put("image/png", "managed.png", "managed".getBytes(StandardCharsets.UTF_8));
    String secretUri = "https://example.com/private/presigned-secret";
    ResourceRef external =
        new ResourceRef(secretUri, "image/png", "external.png", 1L, "a".repeat(64));

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                inTransaction(
                    new ToolResult(
                        "call-1",
                        List.of(
                            new ResourceResultContent(managed),
                            new ResourceResultContent(external)),
                        false,
                        "{}")));

    assertEquals(0, s3Storage.objectCount());
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from session_blob_ref where session_id = ?", Integer.class, SESSION));
    assertNoThrowableMessageContains(failure, secretUri);
  }

  @Test
  void arbitraryUriSchemesAreRejectedWithoutDereferenceOrEcho() {
    String marker = "do-not-echo-marker";
    List<ResourceRef> externalRefs =
        List.of(
            new ResourceRef(
                "data:text/plain;base64,"
                    + Base64.getEncoder().encodeToString(marker.getBytes(StandardCharsets.UTF_8)),
                "text/plain",
                "data.txt",
                (long) marker.length(),
                sha256Hex(marker.getBytes(StandardCharsets.UTF_8))),
            new ResourceRef("file:///tmp/" + marker, "text/plain", "file.txt", 1L, "a".repeat(64)),
            new ResourceRef(
                "http://127.0.0.1/" + marker, "text/plain", "http.txt", 1L, "a".repeat(64)),
            new ResourceRef(
                "s3://foreign-bucket/" + marker, "text/plain", "s3.txt", 1L, "a".repeat(64)));

    for (ResourceRef ref : externalRefs) {
      IllegalArgumentException failure =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  inTransaction(
                      new ToolResult(
                          "call-1", List.of(new ResourceResultContent(ref)), false, "{}")));
      assertNoThrowableMessageContains(failure, marker);
    }

    assertEquals(externalRefs.size(), resourceStore.readCount());
    assertEquals(0, s3Storage.objectCount());
  }

  @Test
  void oversizedOrIncompleteRefIsRejectedBeforeResourceRead() {
    int readsBefore = resourceStore.readCount();
    ResourceRef oversized =
        new ResourceRef(
            "https://example.com/oversized",
            "image/png",
            "oversized.png",
            17L * 1024 * 1024,
            "a".repeat(64));
    ResourceRef incomplete =
        new ResourceRef(
            "https://example.com/incomplete", "image/png", "incomplete.png", null, null);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                new ToolResult(
                    "call-1", List.of(new ResourceResultContent(oversized)), false, "{}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                new ToolResult(
                    "call-2", List.of(new ResourceResultContent(incomplete)), false, "{}")));

    assertEquals(readsBefore, resourceStore.readCount());
  }

  @Test
  void sizeAndShaMismatchesAreRejectedWithoutGlobalWrites() {
    byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
    ResourceRef stored = resourceStore.put("text/plain", "hello.txt", bytes);
    ResourceRef wrongSize =
        new ResourceRef(
            stored.uri(),
            stored.mediaType(),
            stored.name(),
            (long) bytes.length + 1,
            stored.sha256());
    ResourceRef wrongSha =
        new ResourceRef(
            stored.uri(), stored.mediaType(), stored.name(), stored.size(), "a".repeat(64));

    for (ResourceRef ref : List.of(wrongSize, wrongSha)) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              inTransaction(
                  new ToolResult("call-1", List.of(new ResourceResultContent(ref)), false, "{}")));
    }
    assertEquals(0, s3Storage.objectCount());
  }

  @Test
  void invalidUtf8OrTextMetadataRollsBackWithoutBlob() {
    byte[] invalidUtf8 = new byte[] {(byte) 0xFF, (byte) 0xFF};
    ResourceRef invalidUtf8Ref = resourceStore.put("text/plain", "invalid.txt", invalidUtf8);
    byte[] text = "hello\nworld".getBytes(StandardCharsets.UTF_8);
    ResourceRef wrongLinesRef = resourceStore.put("text/plain", "lines.txt", text);

    List<ResourceResultContent> invalidResources =
        List.of(
            new ResourceResultContent(
                invalidUtf8Ref, null, new TextArtifactMetadata(invalidUtf8.length, 1)),
            new ResourceResultContent(
                wrongLinesRef, null, new TextArtifactMetadata(text.length, 1)),
            new ResourceResultContent(
                wrongLinesRef, null, new TextArtifactMetadata(text.length - 1L, 2)));

    for (ResourceResultContent resource : invalidResources) {
      assertThrows(
          IllegalArgumentException.class,
          () -> inTransaction(new ToolResult("call-1", List.of(resource), false, "{}")));
    }

    assertEquals(0, s3Storage.objectCount());
  }

  @Test
  void transientBinaryIsRejectedAtDurableBoundary() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                new ToolResult(
                    "call-1",
                    List.of(new BinaryResultContent("image/png", new byte[] {1})),
                    false,
                    "{}")));
    assertEquals(0, s3Storage.objectCount());
  }

  @Test
  void materializeRequiresTransaction() {
    ResourceRef ref = resourceStore.put("image/png", "one.png", new byte[] {1});
    assertThrows(
        IllegalTransactionStateException.class,
        () ->
            materializer.materialize(
                SESSION,
                "demo_tool",
                new ToolResult("call-1", List.of(new ResourceResultContent(ref)), false, "{}")));
  }

  private List<AgentMessageContent> inTransaction(ToolResult result) {
    return tx.execute(status -> materializer.materialize(SESSION, "demo_tool", result));
  }

  private static void assertNoThrowableMessageContains(Throwable error, String value) {
    Throwable current = error;
    while (current != null) {
      String message = current.getMessage();
      assertFalse(message != null && message.contains(value), "sensitive value leaked");
      current = current.getCause();
    }
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new AssertionError(error);
    }
  }

  @TestConfiguration
  static class ManagedResourceStoreConfiguration {

    @Bean
    @Primary
    ManagedResourceStore managedResourceStore() {
      return new ManagedResourceStore();
    }
  }

  static final class ManagedResourceStore implements ResourceStore {

    private static final String ROOT = "file:///managed-resources/";

    private final Map<String, byte[]> contents = new ConcurrentHashMap<>();
    private final AtomicInteger reads = new AtomicInteger();

    @Override
    public ResourceRef put(String mediaType, String name, byte[] content) {
      String sha256 = sha256Hex(content);
      ResourceRef ref = reference(mediaType, name, content.length, sha256);
      contents.put(ref.uri(), content.clone());
      return ref;
    }

    @Override
    public ResourceRef reference(String mediaType, String name, long size, String sha256) {
      return new ResourceRef(ROOT + sha256, mediaType, name, size, sha256);
    }

    @Override
    public byte[] read(ResourceRef resource) {
      reads.incrementAndGet();
      if (!resource.uri().startsWith(ROOT)) {
        throw new IllegalArgumentException("unsupported resource: " + resource.uri());
      }
      byte[] content = contents.get(resource.uri());
      if (content == null) {
        throw new IllegalStateException("missing managed resource: " + resource.uri());
      }
      return content.clone();
    }

    int readCount() {
      return reads.get();
    }

    void clear() {
      contents.clear();
      reads.set(0);
    }
  }
}
