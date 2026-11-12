package fun.fengwk.kkstudio.platform.harness.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.tool.TurnMediaCapabilities;
import fun.fengwk.kkstudio.platform.harness.model.ProviderResourceMaterializer;
import fun.fengwk.kkstudio.platform.harness.read.PlatformResourceContentReader.ResourceRead;
import fun.fengwk.kkstudio.platform.storage.ReadDeadline;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** 真实多模态 {@code read} 的冻结上下文校验：授权先于存储，模态/协议/预算校验先于任何 Blob 内容读取。 */
class PlatformResourceContentReaderMediaTest {

  private static final String SHA = "a".repeat(64);

  private SessionBlobRefManager refs;
  private StorageBlobManager blobManager;
  private StorageBlobContentService blobs;
  private PlatformResourceContentReader reader;

  private UUID sessionId;
  private UUID blobId;

  @BeforeEach
  void setUp() {
    refs = mock(SessionBlobRefManager.class);
    blobManager = mock(StorageBlobManager.class);
    blobs = mock(StorageBlobContentService.class);
    reader = new PlatformResourceContentReader(refs, blobManager, blobs);

    sessionId = UUID.randomUUID();
    blobId = UUID.randomUUID();
    when(blobManager.getBlob(blobId)).thenAnswer(invocation -> activeBlob());
  }

  /** 支持的模型与协议下，媒体读取返回指向同一已授权 Blob 的 durable 引用，且不读取任何字节。 */
  @Test
  void mediaResourceReturnsDurableReferenceWithoutReadingBytes() {
    stubBlob("image/png", 3L);
    TurnMediaCapabilities capabilities =
        capabilities(
            ProviderType.OPENAI, Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE));

    ResourceRead read = read(capabilities, "resource://files/" + blobId, deadline());

    ResourceRead.Media media = assertInstanceOf(ResourceRead.Media.class, read);
    assertEquals(blobId, media.resource().sessionBlobId());
    assertEquals("image/png", media.resource().mediaType());
    assertEquals(3L, media.resource().size());
    verifyNoInteractions(blobs);
  }

  /** 模型未声明该模态时，在读取任何 Blob 内容之前以明确错误拒绝。 */
  @Test
  void rejectsModelWithoutDeclaredModalityBeforeReading() {
    stubBlob("image/png", 3L);
    TurnMediaCapabilities capabilities =
        capabilities(ProviderType.OPENAI, Set.of(ModelInputModality.TEXT));

    PlatformReadException error =
        assertThrows(PlatformReadException.class, () -> read(capabilities, "resource", deadline()));
    assertTrue(error.getMessage().contains("does not declare IMAGE"), error.getMessage());
    verifyNoInteractions(blobs);
  }

  /** 协议两侧都不接受该模态时，在读取任何 Blob 内容之前以明确错误拒绝。 */
  @Test
  void rejectsProtocolWithoutAnyLegalPositionBeforeReading() {
    stubBlob("audio/mpeg", 3L);
    TurnMediaCapabilities capabilities =
        capabilities(
            ProviderType.OPENAI_RESPONSES,
            Set.of(ModelInputModality.TEXT, ModelInputModality.AUDIO));

    PlatformReadException error =
        assertThrows(PlatformReadException.class, () -> read(capabilities, "resource", deadline()));
    assertTrue(
        error.getMessage().contains("neither user messages nor tool results"), error.getMessage());
    verifyNoInteractions(blobs);
  }

  /** 超出冻结内联预算时在读取任何 Blob 内容之前拒绝（存储内容服务绝不被调用）。 */
  @Test
  void rejectsOverLimitBeforeReadingContent() {
    long tooLarge = ProviderResourceMaterializer.maxInlineBlobBytes() + 1L;
    stubBlob("image/png", tooLarge);
    TurnMediaCapabilities capabilities =
        capabilities(
            ProviderType.OPENAI, Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE));

    PlatformReadException error =
        assertThrows(PlatformReadException.class, () -> read(capabilities, "resource", deadline()));
    assertTrue(error.getMessage().contains("exceeds"), error.getMessage());
    verifyNoInteractions(blobs);
  }

  /** 首次读取即为当前 Session 建立持有，避免有效资源在 Session 删除前被回收。 */
  @Test
  void readingResourceRetainsItForTheSession() {
    stubBlob("image/png", 3L);
    TurnMediaCapabilities capabilities =
        capabilities(
            ProviderType.OPENAI, Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE));

    read(capabilities, "resource://files/" + blobId, deadline());

    verify(refs).retainRef(sessionId, blobId);
  }

  /** 冻结能力快照不含该模态时即拒绝，即使同一 Blob 的 MIME 在别处被声明为媒体：绝不反查实时模型。 */
  @Test
  void usesProvidedFrozenCapabilitiesOnly() {
    stubBlob("image/png", 3L);

    PlatformReadException error =
        assertThrows(
            PlatformReadException.class,
            () -> read(TurnMediaCapabilities.NONE, "resource", deadline()));
    assertTrue(error.getMessage().contains("does not declare IMAGE"), error.getMessage());
    verifyNoInteractions(blobs);
  }

  /** 非媒体 Blob 保持既有文本窗口读取，形态不变。 */
  @Test
  void textBlobUsesBoundedTextWindow() {
    stubBlob("text/plain", 12L);
    when(blobs.withBlobStream(eq(blobId), any(), any()))
        .thenAnswer(
            invocation -> {
              Function<InputStream, String> callback = invocation.getArgument(2);
              return callback.apply(
                  new ByteArrayInputStream("blob-content".getBytes(StandardCharsets.UTF_8)));
            });

    ReadDeadline deadline = deadline();
    ResourceRead read = read(TurnMediaCapabilities.NONE, "resource://files/" + blobId, deadline);

    ResourceRead.Text text = assertInstanceOf(ResourceRead.Text.class, read);
    assertTrue(text.text().contains("1|blob-content"), text.text());
    verify(blobs).withBlobStream(eq(blobId), eq(deadline), any());
  }

  /** 文本读取时任何底层 S3/OS 原始错误都必须收敛为固定安全英文消息，原始 cause 只留在异常链，绝不进入模型可见文本。 */
  @Test
  void textReadFailureHidesRawCauseFromModelFacingMessage() {
    stubBlob("text/plain", 12L);
    RuntimeException raw = new RuntimeException("sentinel-secret-7f3a");
    when(blobs.withBlobStream(eq(blobId), any(), any())).thenThrow(raw);

    PlatformReadException error =
        assertThrows(
            PlatformReadException.class,
            () -> read(TurnMediaCapabilities.NONE, "resource://files/" + blobId, deadline()));

    assertEquals("failed to read resource: " + blobId, error.getMessage());
    assertFalse(error.getMessage().contains("sentinel-secret-7f3a"));
    assertSame(raw, error.getCause());
  }

  /** 测试意图：媒体路径即使无需下载内容，在返回 ref 前也必须遵守 deadline；已超时必须拒绝，不得返回成功 ref。 */
  @Test
  void mediaPathRejectsExpiredDeadlineWithoutDownload() throws InterruptedException {
    stubBlob("image/png", 1024L);
    TurnMediaCapabilities capabilities =
        capabilities(
            ProviderType.OPENAI, Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE));
    ReadDeadline expired = ReadDeadline.after(Duration.ofMillis(10));
    Thread.sleep(15L);

    PlatformReadException error =
        assertThrows(
            PlatformReadException.class,
            () -> read(capabilities, "resource://files/" + blobId, expired));

    assertTrue(error.getMessage().contains("interrupted or timed out"), error.getMessage());
    verifyNoInteractions(blobs);
  }

  /** 测试意图：媒体路径即使无需下载内容，在返回 ref 前也必须遵守线程中断；中断必须拒绝，不得返回成功 ref。 */
  @Test
  void mediaPathRejectsInterruptedThreadWithoutDownload() {
    stubBlob("image/png", 1024L);
    TurnMediaCapabilities capabilities =
        capabilities(
            ProviderType.OPENAI, Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE));
    Thread.currentThread().interrupt();
    try {
      PlatformReadException error =
          assertThrows(
              PlatformReadException.class,
              () -> read(capabilities, "resource://files/" + blobId, deadline()));

      assertTrue(error.getMessage().contains("interrupted or timed out"), error.getMessage());
      verifyNoInteractions(blobs);
    } finally {
      Thread.interrupted();
    }
  }

  private ResourceRead read(
      TurnMediaCapabilities capabilities, String displayPath, ReadDeadline deadline) {
    return reader.readResource(
        sessionId, capabilities, blobId, null, null, null, displayPath, deadline);
  }

  private static ReadDeadline deadline() {
    return ReadDeadline.after(Duration.ofSeconds(60));
  }

  private StorageBlob activeBlob() {
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setMediaType("image/png");
    blob.setSizeBytes(3L);
    blob.setSha256(SHA);
    blob.setState(StorageBlobState.ACTIVE);
    return blob;
  }

  private void stubBlob(String mediaType, long sizeBytes) {
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setMediaType(mediaType);
    blob.setSizeBytes(sizeBytes);
    blob.setSha256(SHA);
    blob.setState(StorageBlobState.ACTIVE);
    when(blobManager.getBlob(blobId)).thenReturn(blob);
  }

  /** 与 Builtin 解析时同一来源的冻结能力快照：消费者声明的输入模态 + 协议合法的内容位置。 */
  private static TurnMediaCapabilities capabilities(
      ProviderType type, Set<ModelInputModality> modalities) {
    return new TurnMediaCapabilities(
        modalities,
        type.mediaCapabilities().userModalities(),
        type.mediaCapabilities().toolResultModalities());
  }
}
