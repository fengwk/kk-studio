package fun.fengwk.kkstudio.platform.harness.read;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Session 所属权和 Blob 引用校验先于外部流 IO。 */
class PlatformResourceContentReaderTest {

  private HarnessStore store;
  private HarnessStore.Transaction transaction;
  private SessionBlobRefManager refs;
  private StorageBlobContentService blobs;
  private PlatformResourceContentReader reader;

  @BeforeEach
  void setUp() {
    store = mock(HarnessStore.class);
    transaction = mock(HarnessStore.Transaction.class);
    refs = mock(SessionBlobRefManager.class);
    blobs = mock(StorageBlobContentService.class);
    when(store.transaction(any()))
        .thenAnswer(
            invocation -> {
              Function<HarnessStore.Transaction, ?> callback = invocation.getArgument(0);
              return callback.apply(transaction);
            });
    reader = new PlatformResourceContentReader(() -> store, refs, blobs);
  }

  /** 测试意图：无效身份或不存在的 Thread 不允许进入存储层。 */
  @Test
  void rejectsInvalidThreadBeforeOpeningBlob() {
    UUID id = UUID.randomUUID();
    assertThrows(PlatformReadException.class, () -> read(null, id));
    assertThrows(PlatformReadException.class, () -> read(id, null));
    when(transaction.findThread(id)).thenReturn(Optional.empty());
    assertTrue(
        assertThrows(PlatformReadException.class, () -> read(id, id))
            .getMessage()
            .contains("thread not found"));
    verifyNoInteractions(blobs);
  }

  /** 测试意图：store 尚未就绪时不能尝试授权与外部 IO。 */
  @Test
  void rejectsUnavailableStore() {
    PlatformResourceContentReader unavailable =
        new PlatformResourceContentReader(() -> null, refs, blobs);
    assertTrue(
        assertThrows(
                PlatformReadException.class,
                () ->
                    unavailable.readResourceText(
                        UUID.randomUUID(), UUID.randomUUID(), null, null, null, "resource"))
            .getMessage()
            .contains("harness store is unavailable"));
    verifyNoInteractions(blobs);
  }

  /** 测试意图：没有会话引用的 URI 不暴露 Blob 是否存在或内容。 */
  @Test
  void rejectsUnreferencedBlobBeforeOpeningStream() {
    UUID threadId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    authorize(threadId, sessionId, blobId, false);
    assertTrue(
        assertThrows(PlatformReadException.class, () -> read(threadId, blobId))
            .getMessage()
            .contains("not referenced"));
    verifyNoInteractions(blobs);
  }

  /** 测试意图：已授权但 Blob 不存在时映射为读工具错误。 */
  @Test
  void mapsMissingBlob() {
    UUID threadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    authorize(threadId, UUID.randomUUID(), blobId, true);
    when(blobs.withBlobStream(eq(blobId), any()))
        .thenThrow(new StorageResourceNotFoundException("blob", blobId.toString()));
    assertTrue(
        assertThrows(PlatformReadException.class, () -> read(threadId, blobId))
            .getMessage()
            .contains("resource not found"));
  }

  /** 测试意图：存储异常、空结果与非法编码分别输出明确错误，不能产生伪造的空文本。 */
  @Test
  void reportsUnavailableAndInvalidContent() {
    UUID threadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    authorize(threadId, UUID.randomUUID(), blobId, true);
    assertTrue(
        assertThrows(PlatformReadException.class, () -> read(threadId, blobId))
            .getMessage()
            .contains("content is unavailable"));

    when(blobs.withBlobStream(eq(blobId), any()))
        .thenThrow(new IllegalStateException("storage unavailable"));
    assertTrue(
        assertThrows(PlatformReadException.class, () -> read(threadId, blobId))
            .getMessage()
            .contains("failed to read resource"));

    when(blobs.withBlobStream(eq(blobId), any()))
        .thenAnswer(
            invocation -> {
              Function<InputStream, String> callback = invocation.getArgument(1);
              return callback.apply(new ByteArrayInputStream(new byte[] {0}));
            });
    assertTrue(
        assertThrows(PlatformReadException.class, () -> read(threadId, blobId))
            .getMessage()
            .contains("binary"));
  }

  /** 测试意图：只在保留 Blob 流的生命周期内执行文本窗口投影。 */
  @Test
  void formatsAuthorizedStream() {
    UUID threadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    authorize(threadId, UUID.randomUUID(), blobId, true);
    when(blobs.withBlobStream(eq(blobId), any()))
        .thenAnswer(
            invocation -> {
              Function<InputStream, String> callback = invocation.getArgument(1);
              return callback.apply(
                  new ByteArrayInputStream("blob-content".getBytes(StandardCharsets.UTF_8)));
            });
    assertTrue(read(threadId, blobId).contains("1|blob-content"));
  }

  private void authorize(UUID threadId, UUID sessionId, UUID blobId, boolean allowed) {
    ThreadState thread = mock(ThreadState.class);
    when(thread.sessionId()).thenReturn(sessionId);
    when(transaction.findThread(threadId)).thenReturn(Optional.of(thread));
    when(refs.contains(sessionId, blobId)).thenReturn(allowed);
  }

  private String read(UUID threadId, UUID blobId) {
    return reader.readResourceText(threadId, blobId, null, null, null, "resource");
  }
}
