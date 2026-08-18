package fun.fengwk.kkstudio.core.ai.runtime.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.core.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.core.storage.S3PostgresSpringTestSupport;
import fun.fengwk.kkstudio.core.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.core.storage.StorageS3TestConfiguration;
import fun.fengwk.kkstudio.core.storage.configuration.S3StorageProperties;
import fun.fengwk.kkstudio.core.storage.service.StorageBlobIngestService;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.tool.ResourceRef;
import fun.fengwk.kkstudio.harness.tool.ResourceToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tool/Daemon Resource 引用在 durable history 插入前的全局存储外部化契约（真实 PostgreSQL + 内存 S3 + 本地 HTTP
 * server）：data/file/http/s3 四类 scheme 解析并摄入为 ACTIVE blob + session ref；s3 bucket 必须匹配配置；
 * 文本内容原样透传；内容顺序保持不变。
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
class GlobalStorageToolResultHistoryMaterializerTest extends S3PostgresSpringTestSupport {

  private static final UUID SESSION = new UUID(0L, 1L);

  @Autowired private GlobalStorageToolResultHistoryMaterializer materializer;
  @Autowired private StorageBlobIngestService ingestService;
  @Autowired private InMemoryS3StorageService s3Storage;
  @Autowired private S3StorageProperties s3Properties;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate tx;
  private HttpServer httpServer;

  @BeforeEach
  void setUp() {
    s3Storage.clear();
    tx = new TransactionTemplate(transactionManager);
    // harness_session_blob_ref.session_id 是 RESTRICT FK：先建真实 session 行。
    jdbc.update(
        "insert into harness_session (id, created_at) values (?, current_timestamp)", SESSION);
  }

  @AfterEach
  void stopHttpServer() {
    if (httpServer != null) {
      httpServer.stop(0);
      httpServer = null;
    }
  }

  @Test
  void dataUriResourceIsIngestedWithRefPair() {
    byte[] content = "data payload".getBytes(StandardCharsets.UTF_8);
    ResourceRef ref =
        new ResourceRef(
            "data:text/plain;base64," + Base64.getEncoder().encodeToString(content),
            "text/plain",
            "inline.txt",
            (long) content.length,
            sha256Hex(content));

    List<AgentMessageContent> contents =
        tx.execute(
            status ->
                materializer.materialize(
                    SESSION,
                    new ToolResult(
                        "call-1",
                        List.of(new ResourceToolContent(ref, "inline preview")),
                        false,
                        "{}",
                        false)));

    ResourceMessageContent resource =
        assertInstanceOf(ResourceMessageContent.class, contents.get(0));
    assertEquals("inline.txt", resource.name());
    assertEquals("inline preview", resource.preview());
    assertArrayEquals(
        content,
        s3Storage.objectBytes(StorageObjectKeys.blobOriginal(resource.blobId())),
        "ingested bytes must equal the resolved resource bytes");
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from harness_session_blob_ref where session_id = ? and blob_id = ?",
            Integer.class,
            SESSION,
            resource.blobId()));
  }

  @Test
  void fileUriAndHttpUriResolveToSameBlobWhenContentsMatch() throws Exception {
    byte[] fileContent = "file resource".getBytes(StandardCharsets.UTF_8);
    Path temp = Files.createTempFile("materializer", ".txt");
    Files.write(temp, fileContent);
    ResourceRef fileRef =
        new ResourceRef(
            "file://" + temp.toAbsolutePath(),
            "text/plain",
            "local.txt",
            (long) fileContent.length,
            sha256Hex(fileContent));

    byte[] httpContent = "http resource".getBytes(StandardCharsets.UTF_8);
    httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    AtomicInteger requests = new AtomicInteger();
    httpServer.createContext(
        "/res",
        exchange -> {
          requests.incrementAndGet();
          byte[] body = httpContent;
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    httpServer.start();
    String httpUri = "http://localhost:" + httpServer.getAddress().getPort() + "/res";
    ResourceRef httpRef = new ResourceRef(httpUri, "text/plain", "remote.txt", null, null);

    UUID fileBlob =
        tx
            .execute(
                status ->
                    materializer.materialize(
                        SESSION,
                        new ToolResult(
                            "call-1",
                            List.of(new ResourceToolContent(fileRef)),
                            false,
                            "{}",
                            false)))
            .stream()
            .map(ResourceMessageContent.class::cast)
            .findFirst()
            .orElseThrow()
            .blobId();
    UUID httpBlob =
        tx
            .execute(
                status ->
                    materializer.materialize(
                        SESSION,
                        new ToolResult(
                            "call-2",
                            List.of(new ResourceToolContent(httpRef)),
                            false,
                            "{}",
                            false)))
            .stream()
            .map(ResourceMessageContent.class::cast)
            .findFirst()
            .orElseThrow()
            .blobId();

    assertEquals(1, requests.get(), "http resource must be fetched exactly once");
    assertTrue(!fileBlob.equals(httpBlob), "different contents must not dedup");
    assertArrayEquals(fileContent, s3Storage.objectBytes(StorageObjectKeys.blobOriginal(fileBlob)));
    assertArrayEquals(httpContent, s3Storage.objectBytes(StorageObjectKeys.blobOriginal(httpBlob)));
  }

  @Test
  void s3UriDownloadsFromConfiguredBucketAndRejectsOthers() {
    byte[] content = "s3 resource".getBytes(StandardCharsets.UTF_8);
    s3Storage.putDirect("tools/result.txt", content, "text/plain");
    ResourceRef ref =
        new ResourceRef(
            "s3://" + s3Properties.getBucket() + "/tools/result.txt",
            "text/plain",
            "s3.txt",
            (long) content.length,
            sha256Hex(content));

    List<AgentMessageContent> contents =
        tx.execute(
            status ->
                materializer.materialize(
                    SESSION,
                    new ToolResult(
                        "call-1", List.of(new ResourceToolContent(ref)), false, "{}", false)));
    ResourceMessageContent resource =
        assertInstanceOf(ResourceMessageContent.class, contents.get(0));
    assertArrayEquals(
        content, s3Storage.objectBytes(StorageObjectKeys.blobOriginal(resource.blobId())));

    // 非配置 bucket：物化阶段确定性拒绝（构造期允许任何合法 bucket）。
    ResourceRef foreign =
        new ResourceRef(
            "s3://other-bucket/tools/result.txt",
            "text/plain",
            "foreign.txt",
            (long) content.length,
            sha256Hex(content));
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                tx.execute(
                    status ->
                        materializer.materialize(
                            SESSION,
                            new ToolResult(
                                "call-2",
                                List.of(new ResourceToolContent(foreign)),
                                false,
                                "{}",
                                false))));
    assertTrue(error.getMessage().contains("bucket"), "actual: " + error.getMessage());
  }

  @Test
  void textContentsPassThroughAndOrderIsPreserved() {
    ResourceRef ref =
        new ResourceRef(
            "data:text/plain;base64," + Base64.getEncoder().encodeToString(new byte[] {1, 2}),
            "text/plain",
            "a.bin",
            2L,
            sha256Hex(new byte[] {1, 2}));
    List<AgentMessageContent> contents =
        tx.execute(
            status ->
                materializer.materialize(
                    SESSION,
                    new ToolResult(
                        "call-1",
                        List.of(
                            new TextToolContent("first"),
                            new ResourceToolContent(ref),
                            new TextToolContent("last")),
                        false,
                        "{}",
                        false)));

    assertEquals(3, contents.size());
    assertEquals("first", assertInstanceOf(TextMessageContent.class, contents.get(0)).text());
    assertInstanceOf(ResourceMessageContent.class, contents.get(1));
    assertEquals("last", assertInstanceOf(TextMessageContent.class, contents.get(2)).text());
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from harness_session_blob_ref where session_id = ?",
            Integer.class,
            SESSION));
  }

  @Test
  void materializeOutsideTransactionIsRejected() {
    ResourceRef ref =
        new ResourceRef(
            "data:text/plain;base64," + Base64.getEncoder().encodeToString(new byte[] {1}),
            "text/plain",
            "one.bin",
            1L,
            sha256Hex(new byte[] {1}));
    assertThrows(
        IllegalTransactionStateException.class,
        () ->
            materializer.materialize(
                SESSION,
                new ToolResult(
                    "call-1", List.of(new ResourceToolContent(ref)), false, "{}", false)));
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new AssertionError(error);
    }
  }
}
