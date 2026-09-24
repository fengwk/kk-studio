package fun.fengwk.kkstudio.platform.storage.service.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.platform.storage.ReadDeadline;
import fun.fengwk.kkstudio.platform.storage.S3ObjectContent;
import fun.fengwk.kkstudio.platform.storage.S3ObjectMetadata;
import fun.fengwk.kkstudio.platform.storage.S3ObjectStream;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.error.StorageReadInterruptedException;
import fun.fengwk.kkstudio.platform.storage.error.StorageReadTimeoutException;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobContent;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link StorageBlobContentServiceImpl} 单元测试。
 *
 * <p>覆盖核心边界：
 *
 * <ul>
 *   <li>成功读取：短事务 retain -> 事务外 S3 下载 -> finally 短事务 release；
 *   <li>严格保证无 DB 事务包围外部 S3 IO；
 *   <li>S3 下载发生异常时，finally 依然执行短事务 release；
 *   <li>内容大小超过上限时，抛出校验异常且 finally 依然执行短事务 release；
 *   <li>retain 阶段失败（如 404）直接抛出，不执行外部 IO 且不执行 release。
 * </ul>
 *
 * @author fengwk
 */
class StorageBlobContentServiceImplTest {

  private StorageBlobManager blobManager;
  private S3StorageService s3StorageService;
  private PlatformTransactionManager transactionManager;
  private StorageBlobContentServiceImpl contentService;

  @BeforeEach
  void setUp() {
    blobManager = mock(StorageBlobManager.class);
    s3StorageService = mock(S3StorageService.class);
    transactionManager = new TestTransactionManager();
    when(blobManager.release(any())).thenReturn(true);
    contentService =
        new StorageBlobContentServiceImpl(blobManager, s3StorageService, transactionManager);
  }

  /** 测试意图：验证成功路径下，方法严格按照 retain -> S3 download -> release 顺序执行， 且返回权威的 mediaType 与字节内容。 */
  @Test
  void shouldRetainDownloadAndReleaseOnSuccess() {
    UUID blobId = UUID.randomUUID();
    byte[] payload = new byte[] {1, 2, 3, 4};
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setSizeBytes(payload.length);
    blob.setMediaType("image/png");
    blob.setState(StorageBlobState.ACTIVE);

    when(blobManager.retain(blobId)).thenReturn(blob);
    when(s3StorageService.download(StorageObjectKeys.blobOriginal(blobId), 1024L))
        .thenReturn(new S3ObjectContent(payload, "image/png"));

    StorageBlobContent result = contentService.readBlobContent(blobId, 1024L);

    assertNotNull(result);
    assertEquals(blobId, result.getBlobId());
    assertEquals("image/png", result.getMediaType());
    assertEquals(payload.length, result.getSizeBytes());
    assertArrayEquals(payload, result.getBytes());

    InOrder inOrder = inOrder(blobManager, s3StorageService);
    inOrder.verify(blobManager).retain(blobId);
    inOrder.verify(s3StorageService).download(StorageObjectKeys.blobOriginal(blobId), 1024L);
    inOrder.verify(blobManager).release(blobId);
  }

  /** 测试意图：流的获取、消费与关闭均在事务外；关闭后才 release，不缓存整对象。 */
  @Test
  void streamsBlobOutsideTransactionAndReleasesAfterClose() throws IOException {
    UUID blobId = UUID.randomUUID();
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    when(blobManager.retain(blobId)).thenReturn(blob);
    AtomicBoolean closed = new AtomicBoolean();
    ByteArrayInputStream stream =
        new ByteArrayInputStream(new byte[] {1, 2, 3}) {
          @Override
          public void close() throws IOException {
            closed.set(true);
            super.close();
          }
        };
    when(s3StorageService.readObject(eq(StorageObjectKeys.blobOriginal(blobId)), any()))
        .thenReturn(new S3ObjectStream(stream, new S3ObjectMetadata(3, "text/plain", null)));
    when(blobManager.release(blobId))
        .thenAnswer(
            invocation -> {
              assertTrue(closed.get());
              return true;
            });
    int first =
        contentService.withBlobStream(
            blobId,
            newDeadline(),
            input -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              try {
                return input.read();
              } catch (IOException e) {
                throw new IllegalStateException(e);
              }
            });
    assertEquals(1, first);
    verify(s3StorageService).readObject(eq(StorageObjectKeys.blobOriginal(blobId)), any());
    verify(blobManager).release(blobId);
  }

  /** 测试意图：窗口解析异常不能泄漏 S3 连接或 ACTIVE blob 的引用。 */
  @Test
  void streamingFailureClosesObjectAndReleasesBlob() throws IOException {
    UUID blobId = UUID.randomUUID();
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    when(blobManager.retain(blobId)).thenReturn(blob);
    AtomicBoolean closed = new AtomicBoolean();
    ByteArrayInputStream stream =
        new ByteArrayInputStream(new byte[] {1}) {
          @Override
          public void close() throws IOException {
            closed.set(true);
            super.close();
          }
        };
    when(s3StorageService.readObject(eq(StorageObjectKeys.blobOriginal(blobId)), any()))
        .thenReturn(new S3ObjectStream(stream, new S3ObjectMetadata(1, "text/plain", null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            contentService.withBlobStream(
                blobId,
                newDeadline(),
                input -> {
                  throw new IllegalArgumentException("invalid text");
                }));
    assertTrue(closed.get());
    verify(blobManager).release(blobId);
  }

  /** 测试意图：retain 返回 null 时没有引用可释放；S3 不得被访问。 */
  @Test
  void streamRejectsNullRetain() {
    UUID blobId = UUID.randomUUID();
    assertThrows(
        IllegalStateException.class,
        () -> contentService.withBlobStream(blobId, newDeadline(), input -> "unreachable"));
    verify(s3StorageService, never()).readObject(any(), any());
    verify(blobManager, never()).release(any());
  }

  /** 测试意图：S3 读取失败和 release 失败同时发生时不能丢失原始错误。 */
  @Test
  void streamPreservesReadErrorAndSuppressedReleaseError() {
    UUID blobId = UUID.randomUUID();
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    when(blobManager.retain(blobId)).thenReturn(blob);
    when(s3StorageService.readObject(eq(StorageObjectKeys.blobOriginal(blobId)), any()))
        .thenThrow(new IllegalStateException("S3 unavailable"));
    doThrow(new IllegalStateException("release failed")).when(blobManager).release(blobId);
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> contentService.withBlobStream(blobId, newDeadline(), input -> "unreachable"));
    assertEquals("S3 unavailable", error.getMessage());
    assertEquals("release failed", error.getSuppressed()[0].getMessage());
  }

  /** 测试意图：成功读取后 release 异常仍需显式失败，防止引用泄漏被忽略。 */
  @Test
  void streamReportsReleaseErrorAfterSuccess() {
    UUID blobId = UUID.randomUUID();
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    when(blobManager.retain(blobId)).thenReturn(blob);
    when(s3StorageService.readObject(eq(StorageObjectKeys.blobOriginal(blobId)), any()))
        .thenReturn(
            new S3ObjectStream(
                new ByteArrayInputStream(new byte[] {1}),
                new S3ObjectMetadata(1, "text/plain", null)));
    when(blobManager.release(blobId)).thenReturn(false);
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> contentService.withBlobStream(blobId, newDeadline(), input -> "ok"))
            .getMessage()
            .contains("release returned false"));
  }

  /** 测试意图：响应体阻塞时读取在有界时间内超时失败，中止连接（不排水）并释放 blob 引用。 */
  @Test
  void streamFailsWithTimeoutAndAbortsConnectionWhenBodyStalls() {
    UUID blobId = UUID.randomUUID();
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    when(blobManager.retain(blobId)).thenReturn(blob);
    StallingAbortableInputStream source = StallingAbortableInputStream.stallingAfter(8);
    when(s3StorageService.readObject(eq(StorageObjectKeys.blobOriginal(blobId)), any()))
        .thenReturn(new S3ObjectStream(source, new S3ObjectMetadata(4096, "text/plain", null)));

    long startedAt = System.nanoTime();
    StorageReadTimeoutException failure =
        assertThrows(
            StorageReadTimeoutException.class,
            () ->
                contentService.withBlobStream(
                    blobId,
                    ReadDeadline.after(Duration.ofMillis(200L)),
                    input -> {
                      byte[] buffer = new byte[64];
                      try {
                        while (input.read(buffer) != -1) {
                          // 持续消费直到看门狗中止响应流。
                        }
                      } catch (IOException e) {
                        throw new IllegalStateException(e);
                      }
                      return "unreachable";
                    }));

    assertTrue(failure.getMessage().contains(blobId.toString()));
    assertTrue(source.aborted(), "超时必须中止 S3 连接");
    assertFalse(source.drainAttempted(), "超时不能排空剩余响应体");
    assertTrue(
        Duration.ofNanos(System.nanoTime() - startedAt).toMillis() < 3_000L, "阻塞读必须在截止点附近失败");
    verify(blobManager).release(blobId);
  }

  /** 测试意图：截止已过时不得发起 S3 读取，但 retain 已建立的引用必须 release。 */
  @Test
  void streamRejectsExpiredDeadlineWithoutTouchingS3AndReleasesBlob() {
    UUID blobId = UUID.randomUUID();
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    when(blobManager.retain(blobId)).thenReturn(blob);
    ReadDeadline expiredDeadline = ReadDeadline.after(Duration.ofMillis(1L));
    sleepQuietly(20L);
    assertTrue(expiredDeadline.isExpired());

    assertThrows(
        StorageReadTimeoutException.class,
        () -> contentService.withBlobStream(blobId, expiredDeadline, input -> "unreachable"));

    verify(s3StorageService, never()).readObject(any(), any());
    verify(blobManager).release(blobId);
  }

  /** 测试意图：读取被取消（中断）时以可区分的取消失败结束，不发起 S3 读取，释放引用并保留中断状态。 */
  @Test
  void streamRejectsInterruptedReadWithoutTouchingS3AndReleasesBlob() {
    UUID blobId = UUID.randomUUID();
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    when(blobManager.retain(blobId)).thenReturn(blob);

    Thread.currentThread().interrupt();
    try {
      assertThrows(
          StorageReadInterruptedException.class,
          () -> contentService.withBlobStream(blobId, newDeadline(), input -> "unreachable"));
      assertTrue(Thread.currentThread().isInterrupted(), "中断状态必须保留给调用方继续取消");
    } finally {
      Thread.interrupted();
    }

    verify(s3StorageService, never()).readObject(any(), any());
    verify(blobManager).release(blobId);
  }

  /** 测试意图：验证任何 S3 下载 IO 发生时，当前线程绝不处于 DB 事务中（保证短事务边界）。 */
  @Test
  void shouldEnsureNoActiveDbTransactionDuringS3Download() {
    UUID blobId = UUID.randomUUID();
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setSizeBytes(10L);
    blob.setMediaType("text/plain");

    when(blobManager.retain(blobId)).thenReturn(blob);

    AtomicBoolean transactionActiveDuringDownload = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              transactionActiveDuringDownload.set(
                  TransactionSynchronizationManager.isActualTransactionActive());
              return new S3ObjectContent(new byte[10], "text/plain");
            })
        .when(s3StorageService)
        .download(eq(StorageObjectKeys.blobOriginal(blobId)), anyLong());

    contentService.readBlobContent(blobId, 1024L);

    assertFalse(
        transactionActiveDuringDownload.get(),
        "S3 download IO must not occur within an active DB transaction");
    verify(blobManager).release(blobId);
  }

  /** 测试意图：当 S3 下载发生网络异常或其它错误时，finally 必须保证短事务 release 得到调用， 避免 blob 引用泄漏。 */
  @Test
  void shouldAlwaysReleaseInFinallyWhenDownloadFails() {
    UUID blobId = UUID.randomUUID();
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setSizeBytes(10L);
    blob.setMediaType("text/plain");

    when(blobManager.retain(blobId)).thenReturn(blob);
    when(s3StorageService.download(eq(StorageObjectKeys.blobOriginal(blobId)), anyLong()))
        .thenThrow(new IllegalStateException("S3 connection timeout"));

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class, () -> contentService.readBlobContent(blobId, 1024L));
    assertEquals("S3 connection timeout", exception.getMessage());

    verify(blobManager).retain(blobId);
    verify(blobManager).release(blobId);
  }

  /** 测试意图：当 blob 大小超过最大允许限制时，在事务外抛出校验异常，且 finally 必须执行 release。 */
  @Test
  void shouldRejectOversizedBlobAndStillReleaseInFinally() {
    UUID blobId = UUID.randomUUID();
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setSizeBytes(2048L);
    blob.setMediaType("image/png");

    when(blobManager.retain(blobId)).thenReturn(blob);

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class, () -> contentService.readBlobContent(blobId, 1024L));
    assertEquals("blob size 2048 bytes exceeds maximum allowed 1024 bytes", exception.getMessage());

    verify(blobManager).retain(blobId);
    verify(s3StorageService, never()).download(any(), anyLong());
    verify(blobManager).release(blobId);
  }

  /** 测试意图：当短事务 retain 失败（例如 blob 不存在或已处于 DELETING 状态）抛出异常时， 不应执行后续的 S3 download，也不应该调用 release。 */
  @Test
  void shouldNotDownloadOrReleaseWhenRetainFails() {
    UUID blobId = UUID.randomUUID();
    when(blobManager.retain(blobId))
        .thenThrow(new StorageResourceNotFoundException("blob", blobId.toString()));

    assertThrows(
        StorageResourceNotFoundException.class,
        () -> contentService.readBlobContent(blobId, 1024L));

    verify(s3StorageService, never()).download(any(), anyLong());
    verify(blobManager, never()).release(any());
  }

  /** 测试意图：当 retain 返回 null 时，必须抛出 IllegalStateException，并不应执行 S3 download。 */
  @Test
  void shouldThrowWhenRetainReturnsNull() {
    UUID blobId = UUID.randomUUID();
    when(blobManager.retain(blobId)).thenReturn(null);

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class, () -> contentService.readBlobContent(blobId, 1024L));
    assertTrue(exception.getMessage().contains("blob retain returned null"));
    verify(s3StorageService, never()).download(any(), anyLong());
  }

  /** 测试意图：当读取成功但 release 抛出异常时，必须抛出 release 异常以避免隐藏引用计数泄漏。 */
  @Test
  void shouldThrowReleaseErrorWhenReadSucceedsButReleaseThrows() {
    UUID blobId = UUID.randomUUID();
    byte[] payload = new byte[] {9, 8, 7};
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setSizeBytes(payload.length);
    blob.setMediaType("image/png");

    when(blobManager.retain(blobId)).thenReturn(blob);
    when(s3StorageService.download(StorageObjectKeys.blobOriginal(blobId), -1L))
        .thenReturn(new S3ObjectContent(payload, "image/png"));
    doThrow(new RuntimeException("release db error")).when(blobManager).release(blobId);

    RuntimeException exception =
        assertThrows(RuntimeException.class, () -> contentService.readBlobContent(blobId, -1L));
    assertEquals("release db error", exception.getMessage());
    verify(blobManager).release(blobId);
  }

  /** 测试意图：当读取和 release 同时失败时，保留原读取异常，并将 release 异常作为 suppressed 附加。 */
  @Test
  void shouldAddSuppressedWhenBothReadAndReleaseFail() {
    UUID blobId = UUID.randomUUID();
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setSizeBytes(100L);
    blob.setMediaType("image/png");

    when(blobManager.retain(blobId)).thenReturn(blob);
    when(s3StorageService.download(StorageObjectKeys.blobOriginal(blobId), -1L))
        .thenThrow(new IllegalStateException("S3 download error"));
    doThrow(new RuntimeException("release db error")).when(blobManager).release(blobId);

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class, () -> contentService.readBlobContent(blobId, -1L));
    assertEquals("S3 download error", exception.getMessage());
    assertEquals(1, exception.getSuppressed().length);
    assertEquals("release db error", exception.getSuppressed()[0].getMessage());
    verify(blobManager).release(blobId);
  }

  /** 测试意图：当 blobManager.release 返回 false 时，必须视为 invariant failure 抛出 IllegalStateException。 */
  @Test
  void shouldThrowInvariantFailureWhenReleaseReturnsFalse() {
    UUID blobId = UUID.randomUUID();
    byte[] payload = new byte[] {1, 2};
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setSizeBytes(payload.length);
    blob.setMediaType("image/png");

    when(blobManager.retain(blobId)).thenReturn(blob);
    when(s3StorageService.download(StorageObjectKeys.blobOriginal(blobId), -1L))
        .thenReturn(new S3ObjectContent(payload, "image/png"));
    when(blobManager.release(blobId)).thenReturn(false);

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class, () -> contentService.readBlobContent(blobId, -1L));
    assertTrue(exception.getMessage().contains("blob release returned false for " + blobId));
    verify(blobManager).release(blobId);
  }

  /** 缺省读取预算：本测试只关注读取边界行为，不关心具体预算长度。 */
  private static ReadDeadline newDeadline() {
    return ReadDeadline.after(Duration.ofSeconds(30));
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** 极简测试事务管理器：模拟事务开启与提交，在事务执行期间维护实际事务活跃标志。 */
  private static class TestTransactionManager implements PlatformTransactionManager {

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition)
        throws TransactionException {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }
}
