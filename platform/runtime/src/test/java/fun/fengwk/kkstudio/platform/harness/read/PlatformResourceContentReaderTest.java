package fun.fengwk.kkstudio.platform.harness.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.tool.TurnMediaCapabilities;
import fun.fengwk.kkstudio.platform.storage.ReadDeadline;
import fun.fengwk.kkstudio.platform.storage.error.StorageReadInterruptedException;
import fun.fengwk.kkstudio.platform.storage.error.StorageReadTimeoutException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;

/** Session 所属权和 Blob 引用校验先于外部流 IO；会话身份来自执行上下文而不是存储反查。 */
class PlatformResourceContentReaderTest {

  private SessionBlobRefManager refs;
  private StorageBlobManager blobManager;
  private StorageBlobContentService blobs;
  private PlatformResourceContentReader reader;

  @BeforeEach
  void setUp() {
    refs = mock(SessionBlobRefManager.class);
    blobManager = mock(StorageBlobManager.class);
    blobs = mock(StorageBlobContentService.class);
    reader = new PlatformResourceContentReader(refs, blobManager, blobs);
    when(blobManager.getBlob(any()))
        .thenAnswer(invocation -> activeBlob(invocation.getArgument(0)));
  }

  /** 测试意图：缺少会话身份或目标 Blob 时在入口拒绝，绝不进入存储层。 */
  @Test
  void rejectsMissingSessionOrBlobBeforeOpeningBlob() {
    UUID id = UUID.randomUUID();
    assertThrows(PlatformReadException.class, () -> read(null, id));
    assertThrows(PlatformReadException.class, () -> read(id, null));
    verifyNoInteractions(blobs);
  }

  /** 测试意图：执行上下文必须携带冻结的消费者媒体能力，缺失时以明确错误拒绝而不是静默按文本处理。 */
  @Test
  void rejectsMissingFrozenMediaCapabilities() {
    UUID sessionId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    assertThrows(
        NullPointerException.class,
        () ->
            reader.readResource(
                sessionId,
                null,
                blobId,
                null,
                null,
                null,
                "resource",
                ReadDeadline.after(Duration.ofSeconds(60))));
    verifyNoInteractions(blobs);
  }

  /** 测试意图：deadline 为 null 时在入口直接拒绝。 */
  @Test
  void rejectsNullDeadline() {
    UUID sessionId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    assertThrows(
        NullPointerException.class,
        () ->
            reader.readResource(
                sessionId, TurnMediaCapabilities.NONE, blobId, null, null, null, "resource", null));
  }

  /** 测试意图：资源读取时幂等建立当前 Session 持有。 */
  @Test
  void readingResourceEstablishesSessionHold() {
    UUID sessionId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    when(blobs.withBlobStream(eq(blobId), any(), any())).thenReturn("1|blob-content");

    assertTrue(read(sessionId, blobId).contains("1|blob-content"));
    verify(refs).retainRef(sessionId, blobId);
  }

  /** 测试意图：不存在或已释放的资源不会被持有或读取。 */
  @Test
  void rejectsMissingBlob() {
    UUID sessionId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    when(blobManager.getBlob(blobId)).thenReturn(null);
    assertTrue(
        assertThrows(PlatformReadException.class, () -> read(sessionId, blobId))
            .getMessage()
            .contains("resource is not available"));
    verifyNoInteractions(blobs);
  }

  /** 测试意图：存储异常、空结果与非法编码分别输出明确错误，不能产生伪造的空文本。 */
  @Test
  void reportsUnavailableAndInvalidContent() {
    UUID sessionId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    assertTrue(
        assertThrows(PlatformReadException.class, () -> read(sessionId, blobId))
            .getMessage()
            .contains("content is unavailable"));

    when(blobs.withBlobStream(eq(blobId), any(), any()))
        .thenThrow(new IllegalStateException("storage unavailable"));
    assertTrue(
        assertThrows(PlatformReadException.class, () -> read(sessionId, blobId))
            .getMessage()
            .contains("failed to read resource"));

    when(blobs.withBlobStream(eq(blobId), any(), any()))
        .thenAnswer(
            invocation -> {
              Function<InputStream, String> callback = invocation.getArgument(2);
              return callback.apply(new ByteArrayInputStream(new byte[] {0}));
            });
    PlatformReadException binary =
        assertThrows(PlatformReadException.class, () -> read(sessionId, blobId));
    assertEquals(PlatformReadException.Kind.NOT_TEXT, binary.kind());
    assertEquals(
        "resource is a binary file (it contains NUL bytes); read supports text files, directories"
            + " and supported media.",
        binary.getMessage());
  }

  /** 测试意图：缺少 MIME 元数据时按普通文本读取，避免空类型触发媒体送达路径。 */
  @Test
  void readsBlobWithMissingMediaTypeAsText() {
    UUID sessionId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    StorageBlob blob = activeBlob(blobId);
    blob.setMediaType(null);
    when(blobManager.getBlob(blobId)).thenReturn(blob);
    when(blobs.withBlobStream(eq(blobId), any(), any())).thenReturn("1|blob-content");

    assertTrue(read(sessionId, blobId).contains("1|blob-content"));
  }

  /** 测试意图：底层 Blob 缺失转换为资源 404，其它领域错误原样传播。 */
  @Test
  void mapsMissingBlobAndPreservesOtherDomainErrors() {
    UUID sessionId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    when(blobs.withBlobStream(eq(blobId), any(), any()))
        .thenThrow(DomainException.notFound("blob"));
    assertTrue(
        assertThrows(PlatformReadException.class, () -> read(sessionId, blobId))
            .getMessage()
            .contains("resource not found"));

    when(blobs.withBlobStream(eq(blobId), any(), any()))
        .thenThrow(DomainException.notFound("other"));
    assertThrows(DomainException.class, () -> read(sessionId, blobId));
  }

  /** 测试意图：只在保留 Blob 流的生命周期内执行文本窗口投影。 */
  @Test
  void formatsAuthorizedStream() {
    UUID sessionId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    when(blobs.withBlobStream(eq(blobId), any(), any()))
        .thenAnswer(
            invocation -> {
              Function<InputStream, String> callback = invocation.getArgument(2);
              return callback.apply(
                  new ByteArrayInputStream("blob-content".getBytes(StandardCharsets.UTF_8)));
            });
    assertTrue(read(sessionId, blobId).contains("1|blob-content"));
  }

  /** 测试意图：读取预算由入口传入并在各阶段复用同一对象，资源查询耗时计入同一预算。 */
  @Test
  void passesSameDeadlineAndMeasuresAuthCost() {
    UUID sessionId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    ReadDeadline entryDeadline = ReadDeadline.after(Duration.ofSeconds(60));
    // Blob metadata lookup is part of the same read deadline.
    when(blobManager.getBlob(blobId))
        .thenAnswer(
            invocation -> {
              sleepQuietly(300L);
              return activeBlob(blobId);
            });
    when(blobs.withBlobStream(eq(blobId), eq(entryDeadline), any()))
        .thenAnswer(
            invocation -> {
              ReadDeadline deadline = invocation.getArgument(1);
              assertSame(entryDeadline, deadline, "必须原样传递同一个 deadline 对象");
              assertTrue(
                  deadline.remaining().compareTo(Duration.ofMillis(59_900L)) <= 0,
                  "截止时间必须覆盖会话鉴权耗时，实际剩余 " + deadline.remaining());
              return "1|blob-content";
            });

    assertTrue(read(sessionId, blobId, entryDeadline).contains("1|blob-content"));
  }

  /** 测试意图：超时与取消以可区分消息对外表达，不能退化成通用读取失败。 */
  @Test
  void mapsTimeoutAndInterruptFailures() {
    UUID sessionId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    when(blobs.withBlobStream(eq(blobId), any(), any()))
        .thenThrow(new StorageReadTimeoutException("blob read timed out: " + blobId));
    assertTrue(
        assertThrows(PlatformReadException.class, () -> read(sessionId, blobId))
            .getMessage()
            .contains("resource read timed out"));

    when(blobs.withBlobStream(eq(blobId), any(), any()))
        .thenThrow(new StorageReadInterruptedException("blob read interrupted: " + blobId));
    assertTrue(
        assertThrows(PlatformReadException.class, () -> read(sessionId, blobId))
            .getMessage()
            .contains("resource read interrupted"));
  }

  private static StorageBlob activeBlob(UUID blobId) {
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setMediaType("text/plain");
    blob.setSizeBytes(12L);
    blob.setSha256("a".repeat(64));
    blob.setState(StorageBlobState.ACTIVE);
    return blob;
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private String read(UUID sessionId, UUID blobId) {
    return read(sessionId, blobId, ReadDeadline.after(Duration.ofSeconds(60)));
  }

  private String read(UUID sessionId, UUID blobId, ReadDeadline deadline) {
    PlatformResourceContentReader.ResourceRead result =
        reader.readResource(
            sessionId, TurnMediaCapabilities.NONE, blobId, null, null, null, "resource", deadline);
    return switch (result) {
      case PlatformResourceContentReader.ResourceRead.Text text -> text.text();
      case PlatformResourceContentReader.ResourceRead.Media media -> throw new AssertionError(
          "expected text result but got media");
    };
  }
}
