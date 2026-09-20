package fun.fengwk.kkstudio.platform.harness.read;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobContent;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** {@link PlatformResourceContentReader} 的单元测试。 */
class PlatformResourceContentReaderTest {

  private HarnessStore harnessStore;
  private HarnessStore.Transaction transaction;
  private SessionBlobRefManager sessionBlobRefManager;
  private StorageBlobContentService storageBlobContentService;
  private PlatformResourceContentReader reader;

  @BeforeEach
  void setUp() {
    harnessStore = mock(HarnessStore.class);
    transaction = mock(HarnessStore.Transaction.class);
    sessionBlobRefManager = mock(SessionBlobRefManager.class);
    storageBlobContentService = mock(StorageBlobContentService.class);

    when(harnessStore.transaction(any()))
        .thenAnswer(
            invocation -> {
              Function<HarnessStore.Transaction, ?> callback = invocation.getArgument(0);
              return callback.apply(transaction);
            });

    reader =
        new PlatformResourceContentReader(
            () -> harnessStore, sessionBlobRefManager, storageBlobContentService);
  }

  /** 传入空 threadId 或 blobId 时抛出异常 */
  @Test
  void nullArgumentsThrowException() {
    UUID id = UUID.randomUUID();
    assertThrows(PlatformReadException.class, () -> reader.readResource(null, id));
    assertThrows(PlatformReadException.class, () -> reader.readResource(id, null));
  }

  /** HarnessStore 缺失时抛出确定性异常 */
  @Test
  void harnessStoreUnavailableThrowsException() {
    PlatformResourceContentReader readerWithNoStore =
        new PlatformResourceContentReader(
            () -> null, sessionBlobRefManager, storageBlobContentService);

    PlatformReadException error =
        assertThrows(
            PlatformReadException.class,
            () -> readerWithNoStore.readResource(UUID.randomUUID(), UUID.randomUUID()));
    assertTrue(error.getMessage().contains("harness store is unavailable"));
  }

  /** 找不到 ThreadState 时抛出 thread not found 异常 */
  @Test
  void threadNotFoundThrowsException() {
    UUID threadId = UUID.randomUUID();
    when(transaction.findThread(threadId)).thenReturn(Optional.empty());

    PlatformReadException error =
        assertThrows(
            PlatformReadException.class, () -> reader.readResource(threadId, UUID.randomUUID()));
    assertTrue(error.getMessage().contains("thread not found: " + threadId));
  }

  /** Session 未引用目标 Blob 时拒绝并抛出异常 */
  @Test
  void sessionNotReferencingBlobThrowsException() {
    UUID threadId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();

    ThreadState threadState = mock(ThreadState.class);
    when(threadState.sessionId()).thenReturn(sessionId);
    when(transaction.findThread(threadId)).thenReturn(Optional.of(threadState));
    when(sessionBlobRefManager.contains(sessionId, blobId)).thenReturn(false);

    PlatformReadException error =
        assertThrows(PlatformReadException.class, () -> reader.readResource(threadId, blobId));
    assertTrue(error.getMessage().contains("resource is not referenced by this session"));
  }

  /** StorageBlobContentService 找不到 Blob 时映射为异常 */
  @Test
  void blobNotFoundThrowsException() {
    UUID threadId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();

    ThreadState threadState = mock(ThreadState.class);
    when(threadState.sessionId()).thenReturn(sessionId);
    when(transaction.findThread(threadId)).thenReturn(Optional.of(threadState));
    when(sessionBlobRefManager.contains(sessionId, blobId)).thenReturn(true);
    when(storageBlobContentService.readBlobContent(eq(blobId), anyLong()))
        .thenThrow(new StorageResourceNotFoundException("blob", blobId.toString()));

    PlatformReadException error =
        assertThrows(PlatformReadException.class, () -> reader.readResource(threadId, blobId));
    assertTrue(error.getMessage().contains("resource not found: " + blobId));
  }

  /** Blob 大小超过限制时映射为异常 */
  @Test
  void blobSizeExceedsLimitThrowsException() {
    UUID threadId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();

    ThreadState threadState = mock(ThreadState.class);
    when(threadState.sessionId()).thenReturn(sessionId);
    when(transaction.findThread(threadId)).thenReturn(Optional.of(threadState));
    when(sessionBlobRefManager.contains(sessionId, blobId)).thenReturn(true);
    when(storageBlobContentService.readBlobContent(eq(blobId), anyLong()))
        .thenThrow(new IllegalArgumentException("blob size too large"));

    PlatformReadException error =
        assertThrows(PlatformReadException.class, () -> reader.readResource(threadId, blobId));
    assertTrue(error.getMessage().contains("resource size exceeds limit: " + blobId));
  }

  /** 成功校验 Session 授权并读取 Blob 字节内容 */
  @Test
  void successfulReadReturnsBytes() {
    UUID threadId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();

    ThreadState threadState = mock(ThreadState.class);
    when(threadState.sessionId()).thenReturn(sessionId);
    when(transaction.findThread(threadId)).thenReturn(Optional.of(threadState));
    when(sessionBlobRefManager.contains(sessionId, blobId)).thenReturn(true);

    byte[] expectedBytes = "blob-content".getBytes(StandardCharsets.UTF_8);
    StorageBlobContent content =
        new StorageBlobContent(blobId, expectedBytes, "text/plain", expectedBytes.length);
    when(storageBlobContentService.readBlobContent(eq(blobId), anyLong())).thenReturn(content);

    byte[] actualBytes = reader.readResource(threadId, blobId);

    assertArrayEquals(expectedBytes, actualBytes);
  }
}
