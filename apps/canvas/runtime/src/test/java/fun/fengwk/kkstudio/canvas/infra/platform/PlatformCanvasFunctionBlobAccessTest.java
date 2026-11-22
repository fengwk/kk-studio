package fun.fengwk.kkstudio.canvas.infra.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.Abortable;

import fun.fengwk.kkstudio.canvas.BlobFacts;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;
import fun.fengwk.kkstudio.platform.storage.ReadDeadline;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.error.StorageReadTimeoutException;
import fun.fengwk.kkstudio.platform.storage.s3.S3ObjectMetadata;
import fun.fengwk.kkstudio.platform.storage.s3.S3ObjectStream;
import fun.fengwk.kkstudio.platform.storage.s3.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Platform BlobAccess 必须冻结最小 facts，并保留 original 长度、关闭与预签名语义；强依赖 S3 与 BlobManager。
 *
 * <p>测试用的假响应流对齐 Apache 客户端行为：{@code abort()} 让阻塞中的 read 立刻失败，{@code close()} 在未 abort 时排空剩余响应体。
 */
class PlatformCanvasFunctionBlobAccessTest {

  private static final UUID BLOB = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private static final Duration BUDGET = Duration.ofMillis(200L);

  @Test
  void mapsFactsWithoutLeakingPlatformTypes() {
    // 测试意图：验证通过强依赖注入的 S3StorageService 和 StorageBlobManager 正确映射 facts。
    StorageBlobManager manager = mock(StorageBlobManager.class);
    StorageBlob blob = new StorageBlob();
    blob.setId(BLOB);
    blob.setMediaType("image/png");
    blob.setSizeBytes(3L);
    blob.setWidth(1L);
    blob.setHeight(2L);
    when(manager.getBlob(BLOB)).thenReturn(blob);
    PlatformCanvasFunctionBlobAccess access =
        new PlatformCanvasFunctionBlobAccess(mock(S3StorageService.class), manager, BUDGET);

    assertEquals(
        new BlobFacts(BLOB, "image/png", 3L, 1L, 2L, null), access.findFacts(BLOB).orElseThrow());
  }

  @Test
  void rejectsMismatchedOriginalWithoutDrainingIt() throws Exception {
    // 测试意图：S3 对象长度与冻结大小不一致时仍拒绝，且释放未消费的响应体只能 abort。
    StallingStream content = StallingStream.withBody(2);
    PlatformCanvasFunctionBlobAccess access = access(content, 2L);

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> access.openOriginal(BLOB, 3L));

    assertEquals("S3 original length does not match frozen blob size", error.getMessage());
    assertTrue(content.aborted(), "长度不一致时不能排空剩余响应体");
    assertFalse(content.drainAttempted());
  }

  @Test
  void rejectsNullDependenciesAndNonPositiveBudget() {
    // 测试意图：构造器强依赖注入非空拒绝，读取预算必须为正，否则冻结的截止永远立即到期。
    S3StorageService storage = mock(S3StorageService.class);
    StorageBlobManager manager = mock(StorageBlobManager.class);

    assertThrows(
        NullPointerException.class,
        () -> new PlatformCanvasFunctionBlobAccess(null, manager, BUDGET));
    assertThrows(
        NullPointerException.class,
        () -> new PlatformCanvasFunctionBlobAccess(storage, null, BUDGET));
    assertThrows(
        NullPointerException.class,
        () -> new PlatformCanvasFunctionBlobAccess(storage, manager, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PlatformCanvasFunctionBlobAccess(storage, manager, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PlatformCanvasFunctionBlobAccess(storage, manager, Duration.ofMillis(-1L)));
  }

  @Test
  void opensClosableOriginalWithFrozenFacts() throws Exception {
    // 测试意图：正常打开可关闭的 S3 原件流，SPI 流给出冻结长度与内容。
    StallingStream content = StallingStream.withBody(3);
    PlatformCanvasFunctionBlobAccess access = access(content, 3L);

    try (CanvasFunctionResourceStream stream = access.openOriginal(BLOB, 3L)) {
      assertEquals(3L, stream.size());
      assertEquals('a', stream.content().read());
    }
  }

  @Test
  void blockedReadFailsWithinFrozenBudgetAndAbortsConnection() throws Exception {
    // 测试意图：响应体停滞时阻塞中的 read 必须在冻结预算内以超时结束并 abort，不能永久占住 Function worker。
    StallingStream content = StallingStream.stallingAfter(1);
    PlatformCanvasFunctionBlobAccess access = access(content, 8L);
    CanvasFunctionResourceStream stream = access.openOriginal(BLOB, 8L);
    ExecutorService reader = newSingleThread("canvas-blob-blocked-reader");
    try {
      long startedAt = System.nanoTime();
      ExecutionException failure =
          assertThrows(
              ExecutionException.class,
              () ->
                  reader
                      .submit(
                          () -> {
                            assertEquals('a', stream.content().read());
                            // 第二次读取阻塞，只能由冻结截止的看门狗 abort 打断。
                            return stream.content().read();
                          })
                      .get(3, TimeUnit.SECONDS));
      long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
      assertTrue(elapsedMillis < 3_000L, "截止必须在预算附近生效，实际耗时 " + elapsedMillis + " ms");
      assertTrue(
          failure.getCause() instanceof StorageReadTimeoutException,
          "阻塞读取必须收敛为读取超时，实际为 " + failure.getCause());
      assertTrue(failure.getCause().getMessage().contains(StorageObjectKeys.blobOriginal(BLOB)));
      assertTrue(content.aborted(), "超时后必须 abort 连接");
      assertFalse(content.drainAttempted(), "超时后不能排空剩余响应体");
    } finally {
      stream.close();
      reader.shutdownNow();
    }
  }

  @Test
  void closingPartiallyReadStreamAbortsInsteadOfDraining() throws Exception {
    // 测试意图：插件读一部分就放弃时，SPI 流关闭必须 abort，不能走会排空响应体的 close。
    StallingStream content = StallingStream.withBody(100);
    PlatformCanvasFunctionBlobAccess access = access(content, 100L);

    try (CanvasFunctionResourceStream stream = access.openOriginal(BLOB, 100L)) {
      assertEquals('a', stream.content().read());
    }

    assertTrue(content.aborted(), "未读完的流关闭时必须 abort");
    assertFalse(content.drainAttempted(), "未读完的流关闭时不能排空响应体");
  }

  @Test
  void closingFullyReadStreamClosesWithoutAborting() throws Exception {
    // 测试意图：读完后关闭走正常 close，连接可复用，新的截止/abort 契约不改变成功路径。
    StallingStream content = StallingStream.withBody(2);
    PlatformCanvasFunctionBlobAccess access = access(content, 2L);
    try (CanvasFunctionResourceStream stream = access.openOriginal(BLOB, 2L)) {
      assertEquals('a', stream.content().read());
      assertEquals('a', stream.content().read());
      assertEquals(-1, stream.content().read());
    }
    assertFalse(content.aborted(), "读完的流不应 abort");
    assertTrue(content.closed());
    assertFalse(content.drainAttempted());
  }

  private static PlatformCanvasFunctionBlobAccess access(
      StallingStream content, long contentLength) {
    S3StorageService storage = mock(S3StorageService.class);
    when(storage.readObject(any(), any(ReadDeadline.class)))
        .thenReturn(
            new S3ObjectStream(content, new S3ObjectMetadata(contentLength, "image/png", null)));
    return new PlatformCanvasFunctionBlobAccess(storage, mock(StorageBlobManager.class), BUDGET);
  }

  private static ExecutorService newSingleThread(String name) {
    return Executors.newSingleThreadExecutor(
        runnable -> {
          Thread thread = new Thread(runnable, name);
          thread.setDaemon(true);
          return thread;
        });
  }

  /** 交付 prefixBytes 后按需阻塞；abort 让阻塞的 read 以 IO 失败结束，close 记录排空意图。 */
  private static final class StallingStream extends InputStream implements Abortable {

    private final Object lock = new Object();
    private final int prefixBytes;
    private final boolean stall;
    private final AtomicBoolean aborted = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean eofReached = new AtomicBoolean();
    private final AtomicBoolean drained = new AtomicBoolean();
    private final AtomicInteger delivered = new AtomicInteger();

    private StallingStream(int prefixBytes, boolean stall) {
      this.prefixBytes = prefixBytes;
      this.stall = stall;
    }

    private static StallingStream withBody(int bodyBytes) {
      return new StallingStream(bodyBytes, false);
    }

    private static StallingStream stallingAfter(int prefixBytes) {
      return new StallingStream(prefixBytes, true);
    }

    private boolean aborted() {
      return aborted.get();
    }

    private boolean closed() {
      return closed.get();
    }

    private boolean drainAttempted() {
      return drained.get();
    }

    @Override
    public int read() throws IOException {
      synchronized (lock) {
        if (aborted.get()) {
          throw new IOException("connection shut down");
        }
        if (closed.get()) {
          throw new IOException("stream closed");
        }
        if (delivered.get() < prefixBytes) {
          delivered.incrementAndGet();
          return 'a';
        }
        if (!stall) {
          eofReached.set(true);
          return -1;
        }
        // 未交付完的响应体：阻塞等待数据，只能被 abort 打断；兜底上限避免测试永久挂住。
        try {
          lock.wait(5_000L);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("blocked read interrupted", e);
        }
        if (!aborted.get()) {
          throw new IOException("response body stalled without watchdog abort");
        }
        throw new IOException("connection shut down");
      }
    }

    @Override
    public void abort() {
      synchronized (lock) {
        aborted.set(true);
        lock.notifyAll();
      }
    }

    @Override
    public void close() throws IOException {
      synchronized (lock) {
        drained.set(!aborted.get() && !eofReached.get());
        closed.set(true);
        lock.notifyAll();
      }
    }
  }
}
