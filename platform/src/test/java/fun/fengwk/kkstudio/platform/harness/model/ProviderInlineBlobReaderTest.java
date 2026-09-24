package fun.fengwk.kkstudio.platform.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobContent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiFunction;

/**
 * {@link ProviderInlineBlobReader} 契约：有界读取、权威事实校验、并发闸门与缓存语义。
 *
 * <p>测试意图概览：
 *
 * <ul>
 *   <li>成功路径的 data URI 必须等于实际字节的 Base64，且绝不携带任何 URL；
 *   <li>读取上限、MIME 安全性、大小合法性与权威内容一致性都在读取路径上显式失败（绝不截断内容，错误信息不回显 URI）；
 *   <li>相同 Blob 事实的重复请求命中缓存，TTL 到期或权重淘汰后重新读取，超单条目上限的大对象永不缓存；
 *   <li>并发 miss 合并为一次读取；等待下载许可被中断时保留中断标志并失败，失败路径释放许可且不写入缓存。
 * </ul>
 */
@Timeout(15)
class ProviderInlineBlobReaderTest {

  private static final UUID BLOB_ID = new UUID(0L, 1L);
  private static final byte[] BYTES = new byte[] {1, 2, 3, 4, 5};
  private static final String DATA_URI = "data:image/png;base64,AQIDBAU=";
  private static final String MEDIA_TYPE = "image/png";
  private static final long GENEROUS_CHARS = 1024L;

  private final StorageBlobContentService contentService = mock(StorageBlobContentService.class);

  @Test
  void readsContentIntoBase64DataUriEqualToActualBytes() {
    stub(BLOB_ID, BYTES);
    ProviderInlineBlobReader reader = reader(testLimits(), Ticker.systemTicker());

    String dataUri = reader.readDataUri(BLOB_ID, MEDIA_TYPE, BYTES.length, GENEROUS_CHARS);

    assertEquals(DATA_URI, dataUri);
    assertTrue(
        dataUri.endsWith(Base64.getEncoder().encodeToString(BYTES)), "data URI 必须以实际字节的 Base64 结尾");
    assertFalse(dataUri.contains("http"), "内联路径绝不产生任何 URL：" + dataUri);
    verify(contentService).readBlobContent(BLOB_ID, testLimits().maxBlobBytes());
  }

  @Test
  void secondReadOfIdenticalBlobFactsHitsCache() {
    stub(BLOB_ID, BYTES);
    ProviderInlineBlobReader reader = reader(testLimits(), Ticker.systemTicker());

    assertEquals(DATA_URI, reader.readDataUri(BLOB_ID, MEDIA_TYPE, BYTES.length, GENEROUS_CHARS));
    assertEquals(DATA_URI, reader.readDataUri(BLOB_ID, MEDIA_TYPE, BYTES.length, GENEROUS_CHARS));

    verify(contentService, times(1)).readBlobContent(eq(BLOB_ID), anyLong());
    assertEquals(1L, reader.cachedEntryCount());
  }

  @Test
  void cacheEntryExpiresAfterTtlAndIsReadAgain() {
    stub(BLOB_ID, BYTES);
    FakeTicker ticker = new FakeTicker();
    ProviderInlineBlobReader reader = reader(testLimits(), ticker);

    reader.readDataUri(BLOB_ID, MEDIA_TYPE, BYTES.length, GENEROUS_CHARS);
    ticker.advance(testLimits().cacheTtl().plusSeconds(1));
    reader.readDataUri(BLOB_ID, MEDIA_TYPE, BYTES.length, GENEROUS_CHARS);

    verify(contentService, times(2)).readBlobContent(eq(BLOB_ID), anyLong());
  }

  @Test
  void oversizedEntryIsNeverCached() {
    stub(BLOB_ID, BYTES);
    // 单条目记账上限设为 1：任何内容都超限，必须走不缓存路径。
    ProviderInlineBlobReader reader =
        reader(limits(builder().maxCacheEntryWeightBytes(1L)), Ticker.systemTicker());

    reader.readDataUri(BLOB_ID, MEDIA_TYPE, BYTES.length, GENEROUS_CHARS);
    reader.readDataUri(BLOB_ID, MEDIA_TYPE, BYTES.length, GENEROUS_CHARS);

    assertEquals(0L, reader.cachedEntryCount(), "超过单条目记账上限的对象不得进入缓存");
    verify(contentService, times(2)).readBlobContent(eq(BLOB_ID), anyLong());
  }

  @Test
  void weightBudgetBoundsRetainedEntries() {
    UUID otherBlobId = new UUID(0L, 2L);
    stub(BLOB_ID, BYTES);
    stub(otherBlobId, BYTES);
    // 总权重只够容纳一条记录；不假设 Caffeine 的准入与淘汰策略等价于 LRU。
    long singleEntryWeight = 2L * DATA_URI.length() + 256L;
    ProviderInlineBlobReader reader =
        reader(
            limits(
                builder().maxCacheEntryWeightBytes(1024L).maxCacheWeightBytes(singleEntryWeight)),
            Ticker.systemTicker());

    reader.readDataUri(BLOB_ID, MEDIA_TYPE, BYTES.length, GENEROUS_CHARS);
    reader.readDataUri(otherBlobId, MEDIA_TYPE, BYTES.length, GENEROUS_CHARS);
    reader.cleanUp();
    assertEquals(1L, reader.cachedEntryCount(), "总预算只允许保留一条记录");
    reader.readDataUri(BLOB_ID, MEDIA_TYPE, BYTES.length, GENEROUS_CHARS);
    reader.readDataUri(otherBlobId, MEDIA_TYPE, BYTES.length, GENEROUS_CHARS);

    verify(contentService, atLeast(3)).readBlobContent(any(), anyLong());
  }

  @Test
  void concurrentMissesOfSameBlobAreCoalescedIntoOneRead() throws Exception {
    CountDownLatch loaderStarted = new CountDownLatch(1);
    CountDownLatch releaseLoader = new CountDownLatch(1);
    AtomicInteger reads = new AtomicInteger();
    StorageBlobContentService coalescingService =
        service(
            (blobId, maxSizeBytes) -> {
              reads.incrementAndGet();
              loaderStarted.countDown();
              awaitQuietly(releaseLoader);
              return new StorageBlobContent(blobId, BYTES, MEDIA_TYPE, BYTES.length);
            });
    ProviderInlineBlobReader reader =
        new ProviderInlineBlobReader(coalescingService, testLimits(), Ticker.systemTicker());

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<String>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        futures.add(
            executor.submit(
                () -> reader.readDataUri(BLOB_ID, MEDIA_TYPE, BYTES.length, GENEROUS_CHARS)));
      }
      assertTrue(loaderStarted.await(5, TimeUnit.SECONDS), "首次读取未启动");
      releaseLoader.countDown();
      for (Future<String> future : futures) {
        assertEquals(DATA_URI, future.get(5, TimeUnit.SECONDS));
      }
    } finally {
      executor.shutdownNow();
    }
    assertEquals(1, reads.get(), "同一 Blob 的并发 miss 必须合并为一次读取");
  }

  @Test
  void failureIsNotCachedAndPermitIsRestored() {
    when(contentService.readBlobContent(BLOB_ID, testLimits().maxBlobBytes()))
        .thenThrow(new IllegalStateException("storage unavailable"))
        .thenReturn(new StorageBlobContent(BLOB_ID, BYTES, MEDIA_TYPE, BYTES.length));
    ProviderInlineBlobReader reader =
        reader(limits(builder().maxConcurrentDownloads(1)), Ticker.systemTicker());

    assertThrows(
        IllegalStateException.class,
        () -> reader.readDataUri(BLOB_ID, MEDIA_TYPE, BYTES.length, GENEROUS_CHARS));
    assertEquals(0L, reader.cachedEntryCount(), "失败不得写入缓存");
    // 失败路径释放许可：第二次读取可以拿到同一许可并成功。
    assertEquals(DATA_URI, reader.readDataUri(BLOB_ID, MEDIA_TYPE, BYTES.length, GENEROUS_CHARS));
    verify(contentService, times(2)).readBlobContent(eq(BLOB_ID), anyLong());
  }

  /** 意图：不缓存的大对象也共享下载闸门；所有线程达到等待点后，恰好只有两个线程能进入存储读取。 */
  @Test
  void uncachedReadsShareTheGlobalDownloadLimit() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    ProviderInlineBlobReader reader =
        new ProviderInlineBlobReader(
            service(
                (id, maximum) -> {
                  int current = active.incrementAndGet();
                  peak.accumulateAndGet(current, Math::max);
                  try {
                    awaitQuietly(release);
                    return new StorageBlobContent(id, BYTES, MEDIA_TYPE, BYTES.length);
                  } finally {
                    active.decrementAndGet();
                  }
                }),
            limits(builder().maxCacheEntryWeightBytes(1).maxConcurrentDownloads(2)),
            Ticker.systemTicker());
    List<Thread> workers = new ArrayList<>();
    List<FutureTask<String>> tasks = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      UUID id = new UUID(0, i + 10);
      FutureTask<String> task =
          new FutureTask<>(() -> reader.readDataUri(id, MEDIA_TYPE, BYTES.length, GENEROUS_CHARS));
      tasks.add(task);
      workers.add(new Thread(task, "inline-limit-" + i));
    }
    try {
      workers.forEach(Thread::start);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (!workers.stream().allMatch(ProviderInlineBlobReaderTest::isWaiting)
          && System.nanoTime() < deadline) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
      }
      assertTrue(workers.stream().allMatch(ProviderInlineBlobReaderTest::isWaiting));
      assertEquals(2, active.get(), "two readers and one semaphore waiter");
      release.countDown();
      for (FutureTask<String> task : tasks) {
        assertEquals(DATA_URI, task.get(5, TimeUnit.SECONDS));
      }
      assertEquals(2, peak.get());
      assertEquals(0, active.get());
      assertEquals(0, reader.cachedEntryCount());
    } finally {
      release.countDown();
      for (Thread worker : workers) {
        worker.interrupt();
        worker.join(5_000);
      }
    }
  }

  private static boolean isWaiting(Thread thread) {
    Thread.State state = thread.getState();
    return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
  }

  /** 意图：下载许可被占满时等待必须可中断；中断后保留中断标志、确定性失败（绝不静默降级），且在途读取结束后许可归还。 */
  @Test
  void interruptedPermitWaitKeepsInterruptFlagAndFails() throws Exception {
    CountDownLatch permitTaken = new CountDownLatch(1);
    CountDownLatch releaseHolder = new CountDownLatch(1);
    StorageBlobContentService blockingService =
        service(
            (blobId, maxSizeBytes) -> {
              permitTaken.countDown();
              awaitQuietly(releaseHolder);
              return new StorageBlobContent(blobId, BYTES, MEDIA_TYPE, BYTES.length);
            });
    ProviderInlineBlobReader reader =
        new ProviderInlineBlobReader(
            blockingService, limits(builder().maxConcurrentDownloads(1)), Ticker.systemTicker());

    ExecutorService executor = Executors.newSingleThreadExecutor();
    AtomicBoolean waiterInterrupted = new AtomicBoolean();
    Thread waiterThread;
    try {
      executor.submit(
          () -> reader.readDataUri(new UUID(0L, 11L), MEDIA_TYPE, BYTES.length, GENEROUS_CHARS));
      assertTrue(permitTaken.await(5, TimeUnit.SECONDS), "唯一许可未被占用");

      AtomicBoolean waiterFailed = new AtomicBoolean();
      waiterThread =
          new Thread(
              () -> {
                try {
                  reader.readDataUri(new UUID(0L, 99L), MEDIA_TYPE, BYTES.length, GENEROUS_CHARS);
                } catch (IllegalStateException expected) {
                  waiterFailed.set(true);
                  waiterInterrupted.set(Thread.currentThread().isInterrupted());
                }
              });
      waiterThread.start();
      // 等待被阻塞在许可获取上后再中断，确保覆盖的是等待路径而非读取路径。
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (waiterThread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
        Thread.sleep(5L);
      }
      waiterThread.interrupt();
      waiterThread.join(TimeUnit.SECONDS.toMillis(5));

      assertFalse(waiterThread.isAlive(), "中断后等待线程必须结束");
      assertTrue(waiterFailed.get(), "等待许可被中断必须确定性失败");
      assertTrue(waiterInterrupted.get(), "中断标志必须被保留");
    } finally {
      releaseHolder.countDown();
      executor.shutdownNow();
    }
    // 在途读取结束后许可归还，后续读取可以继续。
    assertEquals(
        DATA_URI, reader.readDataUri(new UUID(0L, 20L), MEDIA_TYPE, BYTES.length, GENEROUS_CHARS));
  }

  @Test
  void rejectsNonPositiveBudgetBeforeReading() {
    ProviderInlineBlobReader reader = reader(testLimits(), Ticker.systemTicker());

    assertThrows(
        IllegalArgumentException.class,
        () -> reader.readDataUri(BLOB_ID, MEDIA_TYPE, BYTES.length, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> reader.readDataUri(BLOB_ID, MEDIA_TYPE, BYTES.length, -1L));
    verify(contentService, never()).readBlobContent(any(), anyLong());
  }

  @Test
  void rejectsUnsafeMediaTypeBeforeReading() {
    ProviderInlineBlobReader reader = reader(testLimits(), Ticker.systemTicker());

    for (String mediaType : List.of("", "  ", "IMAGE/PNG", "image/png; charset=utf-8", "image")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> reader.readDataUri(BLOB_ID, mediaType, BYTES.length, GENEROUS_CHARS),
          "mediaType 必须为规范小写 type/subtype：" + mediaType);
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> reader.readDataUri(BLOB_ID, null, BYTES.length, GENEROUS_CHARS));
    verify(contentService, never()).readBlobContent(any(), anyLong());
  }

  @Test
  void rejectsInvalidOrOversizedDeclaredSizeBeforeReading() {
    ProviderInlineBlobReader reader =
        reader(limits(builder().maxBlobBytes(4L)), Ticker.systemTicker());

    assertThrows(
        IllegalArgumentException.class,
        () -> reader.readDataUri(BLOB_ID, MEDIA_TYPE, -1L, GENEROUS_CHARS));
    assertThrows(
        IllegalArgumentException.class,
        () -> reader.readDataUri(BLOB_ID, MEDIA_TYPE, 5L, GENEROUS_CHARS));
    verify(contentService, never()).readBlobContent(any(), anyLong());
  }

  @Test
  void rejectsBudgetSmallerThanRequiredDataUriBeforeReading() {
    ProviderInlineBlobReader reader = reader(testLimits(), Ticker.systemTicker());
    long required = ProviderInlineBlobReader.estimatedDataUriChars(MEDIA_TYPE, BYTES.length);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> reader.readDataUri(BLOB_ID, MEDIA_TYPE, BYTES.length, required - 1L));

    assertTrue(error.getMessage().contains("characters"), error.getMessage());
    assertFalse(error.getMessage().contains("base64"), "错误信息不得包含内容或 URI");
    verify(contentService, never()).readBlobContent(any(), anyLong());
  }

  @Test
  void failsExplicitlyWhenAuthoritativeIdentityMediaTypeOrSizeDisagrees() {
    ProviderInlineBlobReader reader = reader(testLimits(), Ticker.systemTicker());

    when(contentService.readBlobContent(BLOB_ID, testLimits().maxBlobBytes()))
        .thenReturn(new StorageBlobContent(new UUID(0L, 5L), BYTES, MEDIA_TYPE, BYTES.length));
    assertThrows(
        IllegalStateException.class,
        () -> reader.readDataUri(BLOB_ID, MEDIA_TYPE, BYTES.length, GENEROUS_CHARS));

    when(contentService.readBlobContent(BLOB_ID, testLimits().maxBlobBytes()))
        .thenReturn(new StorageBlobContent(BLOB_ID, BYTES, "image/jpeg", BYTES.length));
    assertThrows(
        IllegalStateException.class,
        () -> reader.readDataUri(BLOB_ID, MEDIA_TYPE, BYTES.length, GENEROUS_CHARS));

    when(contentService.readBlobContent(BLOB_ID, testLimits().maxBlobBytes()))
        .thenReturn(new StorageBlobContent(BLOB_ID, BYTES, MEDIA_TYPE, BYTES.length + 1L));
    assertThrows(
        IllegalStateException.class,
        () -> reader.readDataUri(BLOB_ID, MEDIA_TYPE, BYTES.length, GENEROUS_CHARS));

    // 实际读取长度与权威元数据不一致（截断或损坏）必须显式失败，绝不生成截断的 data URI。
    when(contentService.readBlobContent(BLOB_ID, testLimits().maxBlobBytes()))
        .thenReturn(new StorageBlobContent(BLOB_ID, new byte[] {1, 2}, MEDIA_TYPE, BYTES.length));
    IllegalStateException truncated =
        assertThrows(
            IllegalStateException.class,
            () -> reader.readDataUri(BLOB_ID, MEDIA_TYPE, BYTES.length, GENEROUS_CHARS));
    assertTrue(truncated.getMessage().contains("length mismatch"), truncated.getMessage());
    assertFalse(truncated.getMessage().contains("http"), truncated.getMessage());
    assertEquals(0L, reader.cachedEntryCount(), "损坏内容绝不进入缓存");
  }

  @Test
  void rejectsInvalidLimitsAndNullDependencies() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderInlineBlobReader.Limits(0L, 1L, 1L, Duration.ofMinutes(1), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderInlineBlobReader.Limits(1L, 0L, 1L, Duration.ofMinutes(1), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderInlineBlobReader.Limits(1L, 1L, 0L, Duration.ofMinutes(1), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderInlineBlobReader.Limits(1L, 1L, 1L, Duration.ZERO, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderInlineBlobReader.Limits(1L, 1L, 1L, Duration.ofMinutes(1), 0));
    assertThrows(
        NullPointerException.class,
        () -> new ProviderInlineBlobReader(null, testLimits(), Ticker.systemTicker()));
    assertThrows(
        NullPointerException.class,
        () -> new ProviderInlineBlobReader(contentService, testLimits(), null));
    assertThrows(
        NullPointerException.class,
        () -> new ProviderInlineBlobReader(contentService, null, Ticker.systemTicker()));
  }

  private ProviderInlineBlobReader reader(ProviderInlineBlobReader.Limits limits, Ticker ticker) {
    return new ProviderInlineBlobReader(contentService, limits, ticker);
  }

  /** 意图：预算公式覆盖 Base64 补位和溢出，缓存命中也不能绕过调用方更小的预算。 */
  @Test
  void exactEncodedSizeAndCachedBudgetRemainEnforced() {
    long prefix = "data:image/png;base64,".length();
    for (int size = 0; size <= 6; size++) {
      assertEquals(
          prefix + Base64.getEncoder().encodeToString(new byte[size]).length(),
          ProviderInlineBlobReader.estimatedDataUriChars(MEDIA_TYPE, size));
    }
    assertThrows(
        ArithmeticException.class,
        () -> ProviderInlineBlobReader.estimatedDataUriChars(MEDIA_TYPE, Long.MAX_VALUE));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProviderInlineBlobReader.estimatedDataUriChars(MEDIA_TYPE, -1));
    stub(BLOB_ID, BYTES);
    ProviderInlineBlobReader reader = reader(testLimits(), Ticker.systemTicker());
    assertEquals(
        DATA_URI, reader.readDataUri(BLOB_ID, MEDIA_TYPE, BYTES.length, DATA_URI.length()));
    assertThrows(
        IllegalArgumentException.class,
        () -> reader.readDataUri(BLOB_ID, MEDIA_TYPE, BYTES.length, DATA_URI.length() - 1L));
    verify(contentService).readBlobContent(eq(BLOB_ID), anyLong());
  }

  private void stub(UUID blobId, byte[] bytes) {
    when(contentService.readBlobContent(blobId, testLimits().maxBlobBytes()))
        .thenReturn(new StorageBlobContent(blobId, bytes, MEDIA_TYPE, bytes.length));
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("latch not released in time");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
  }

  private static LimitsBuilder builder() {
    return new LimitsBuilder();
  }

  private static ProviderInlineBlobReader.Limits testLimits() {
    return limits(builder());
  }

  private static ProviderInlineBlobReader.Limits limits(LimitsBuilder builder) {
    return builder.build();
  }

  private static StorageBlobContentService service(
      BiFunction<UUID, Long, StorageBlobContent> loader) {
    StorageBlobContentService result = mock(StorageBlobContentService.class);
    when(result.readBlobContent(any(), anyLong()))
        .thenAnswer(
            invocation -> loader.apply(invocation.getArgument(0), invocation.getArgument(1)));
    return result;
  }

  /** 测试用可注入上限：默认值远小于生产默认值，避免测试分配大对象。 */
  private static final class LimitsBuilder {

    private long maxBlobBytes = 64L * 1024L;
    private long maxCacheEntryWeightBytes = 1024L * 1024L;
    private long maxCacheWeightBytes = 4L * 1024L * 1024L;
    private Duration cacheTtl = Duration.ofMinutes(5);
    private int maxConcurrentDownloads = 2;

    LimitsBuilder maxBlobBytes(long value) {
      this.maxBlobBytes = value;
      return this;
    }

    LimitsBuilder maxCacheEntryWeightBytes(long value) {
      this.maxCacheEntryWeightBytes = value;
      return this;
    }

    LimitsBuilder maxCacheWeightBytes(long value) {
      this.maxCacheWeightBytes = value;
      return this;
    }

    LimitsBuilder maxConcurrentDownloads(int value) {
      this.maxConcurrentDownloads = value;
      return this;
    }

    ProviderInlineBlobReader.Limits build() {
      return new ProviderInlineBlobReader.Limits(
          maxBlobBytes,
          maxCacheEntryWeightBytes,
          maxCacheWeightBytes,
          cacheTtl,
          maxConcurrentDownloads);
    }
  }

  /** 可手动推进的 Caffeine Ticker，用于确定性验证 TTL 过期。 */
  private static final class FakeTicker implements Ticker {

    private volatile long nanos;

    @Override
    public long read() {
      return nanos;
    }

    void advance(Duration duration) {
      nanos += duration.toNanos();
    }
  }
}
