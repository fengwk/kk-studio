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

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.platform.harness.model.ProviderResourceMaterializer;
import fun.fengwk.kkstudio.platform.harness.read.PlatformResourceContentReader.ResourceRead;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** 真实多模态 {@code read} 的冻结调用模型校验：授权先于存储，模态/协议/预算校验先于任何 Blob 内容读取。 */
class PlatformResourceContentReaderMediaTest {

  private static final String CALL_ID = "call-1";
  private static final String SHA = "a".repeat(64);

  private HarnessStore store;
  private HarnessStore.Transaction transaction;
  private SessionBlobRefManager refs;
  private StorageBlobManager blobManager;
  private StorageBlobContentService blobs;
  private PlatformResourceContentReader reader;

  private UUID threadId;
  private UUID sessionId;
  private UUID blobId;
  private UUID invocationId;
  private UUID modelInvocationId;

  @BeforeEach
  void setUp() {
    store = mock(HarnessStore.class);
    transaction = mock(HarnessStore.Transaction.class);
    refs = mock(SessionBlobRefManager.class);
    blobManager = mock(StorageBlobManager.class);
    blobs = mock(StorageBlobContentService.class);
    when(store.transaction(any()))
        .thenAnswer(
            invocation -> {
              Function<HarnessStore.Transaction, ?> callback = invocation.getArgument(0);
              return callback.apply(transaction);
            });
    reader = new PlatformResourceContentReader(() -> store, refs, blobManager, blobs);

    threadId = UUID.randomUUID();
    sessionId = UUID.randomUUID();
    blobId = UUID.randomUUID();
    invocationId = UUID.randomUUID();
    modelInvocationId = UUID.randomUUID();

    ThreadState thread = mock(ThreadState.class);
    when(thread.sessionId()).thenReturn(sessionId);
    when(transaction.findThread(threadId)).thenReturn(Optional.of(thread));
    when(refs.contains(sessionId, blobId)).thenReturn(true);

    ToolInvocation invocation = mock(ToolInvocation.class);
    when(invocation.call()).thenReturn(new ToolCall(CALL_ID, "read", "{}"));
    when(invocation.modelInvocationId()).thenReturn(modelInvocationId);
    when(transaction.findToolInvocation(invocationId)).thenReturn(Optional.of(invocation));
  }

  /** 支持的模型与协议下，媒体读取返回指向同一已授权 Blob 的 durable 引用，且不读取任何字节。 */
  @Test
  void mediaResourceReturnsDurableReferenceWithoutReadingBytes() {
    stubBlob("image/png", 3L);
    stubFrozenSpec(ProviderType.OPENAI, Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE));

    ResourceRead read =
        reader.readResource(
            threadId,
            invocationId,
            CALL_ID,
            blobId,
            null,
            null,
            null,
            "kkstudio:/resources/" + blobId);

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
    stubFrozenSpec(ProviderType.OPENAI, Set.of(ModelInputModality.TEXT));

    PlatformReadException error =
        assertThrows(
            PlatformReadException.class,
            () ->
                reader.readResource(
                    threadId, invocationId, CALL_ID, blobId, null, null, null, "resource"));
    assertTrue(error.getMessage().contains("does not declare IMAGE"), error.getMessage());
    verifyNoInteractions(blobs);
  }

  /** 协议两侧都不接受该模态时，在读取任何 Blob 内容之前以明确错误拒绝。 */
  @Test
  void rejectsProtocolWithoutAnyLegalPositionBeforeReading() {
    stubBlob("audio/mpeg", 3L);
    stubFrozenSpec(
        ProviderType.OPENAI_RESPONSES, Set.of(ModelInputModality.TEXT, ModelInputModality.AUDIO));

    PlatformReadException error =
        assertThrows(
            PlatformReadException.class,
            () ->
                reader.readResource(
                    threadId, invocationId, CALL_ID, blobId, null, null, null, "resource"));
    assertTrue(
        error.getMessage().contains("neither user messages nor tool results"), error.getMessage());
    verifyNoInteractions(blobs);
  }

  /** 超出冻结内联预算时在读取任何 Blob 内容之前拒绝（存储内容服务绝不被调用）。 */
  @Test
  void rejectsOverLimitBeforeReadingContent() {
    long tooLarge = ProviderResourceMaterializer.maxInlineBlobBytes() + 1L;
    stubBlob("image/png", tooLarge);
    stubFrozenSpec(ProviderType.OPENAI, Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE));

    PlatformReadException error =
        assertThrows(
            PlatformReadException.class,
            () ->
                reader.readResource(
                    threadId, invocationId, CALL_ID, blobId, null, null, null, "resource"));
    assertTrue(error.getMessage().contains("exceeds"), error.getMessage());
    verifyNoInteractions(blobs);
  }

  /** 未授权的 Blob 在读取元数据之前就被拒绝。 */
  @Test
  void rejectsUnreferencedBlobBeforeMetadata() {
    when(refs.contains(sessionId, blobId)).thenReturn(false);

    PlatformReadException error =
        assertThrows(
            PlatformReadException.class,
            () ->
                reader.readResource(
                    threadId, invocationId, CALL_ID, blobId, null, null, null, "resource"));
    assertTrue(error.getMessage().contains("not referenced"), error.getMessage());
    verifyNoInteractions(blobManager, blobs);
  }

  /** 调用绑定不一致（call id 与冻结 invocation 不符）时拒绝。 */
  @Test
  void rejectsCallBindingMismatch() {
    stubBlob("image/png", 3L);
    stubFrozenSpec(ProviderType.OPENAI, Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE));

    PlatformReadException error =
        assertThrows(
            PlatformReadException.class,
            () ->
                reader.readResource(
                    threadId, invocationId, "other-call", blobId, null, null, null, "resource"));
    assertTrue(error.getMessage().contains("does not match"), error.getMessage());
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

    ResourceRead read =
        reader.readResource(
            threadId, null, null, blobId, null, null, null, "kkstudio:/resources/" + blobId);

    ResourceRead.Text text = assertInstanceOf(ResourceRead.Text.class, read);
    assertTrue(text.text().contains("1|blob-content"), text.text());
    verify(blobs).withBlobStream(eq(blobId), any(), any());
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
            () ->
                reader.readResource(
                    threadId,
                    null,
                    null,
                    blobId,
                    null,
                    null,
                    null,
                    "kkstudio:/resources/" + blobId));

    assertEquals("failed to read resource: " + blobId, error.getMessage());
    assertFalse(error.getMessage().contains("sentinel-secret-7f3a"));
    assertSame(raw, error.getCause());
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

  private void stubFrozenSpec(ProviderType type, Set<ModelInputModality> modalities) {
    ModelDescriptor descriptor =
        new ModelDescriptor("provider", "model", "model-id", modalities, true, false);
    ModelRequestSpec spec =
        new ModelRequestSpec(
            type,
            UUID.randomUUID(),
            descriptor,
            new ModelVariant("default"),
            1024,
            "instruction",
            List.of(),
            List.of(),
            ProviderCacheControl.none());
    ModelInvocation model = mock(ModelInvocation.class);
    when(model.threadId()).thenReturn(threadId);
    when(model.requestSpec()).thenReturn(spec);
    when(transaction.findModelInvocation(modelInvocationId)).thenReturn(Optional.of(model));
  }
}
