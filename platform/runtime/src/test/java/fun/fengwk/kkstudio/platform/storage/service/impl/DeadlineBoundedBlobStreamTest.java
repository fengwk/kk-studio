package fun.fengwk.kkstudio.platform.storage.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.AbortedException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;

import fun.fengwk.kkstudio.platform.storage.ReadDeadline;
import fun.fengwk.kkstudio.platform.storage.S3ObjectMetadata;
import fun.fengwk.kkstudio.platform.storage.S3ObjectStream;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.error.StorageReadInterruptedException;
import fun.fengwk.kkstudio.platform.storage.error.StorageReadTimeoutException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;

/**
 * {@link DeadlineBoundedBlobStream} 单元测试。
 *
 * <p>测试意图：验证同步 S3 响应流的绝对截止语义 —— 阻塞中的 read 能被看门狗打断，提前退出只 abort 不排空，正常读到 EOF 才
 * close，超时与取消是可区分的失败，且看门狗任务在读取结束后注销。
 *
 * <p>测试用的假响应流刻意模拟 Apache 客户端行为：{@code abort()} 让阻塞中的 read 立刻失败（socket 被关停），而 {@code close()} 在未读完且未
 * abort 时会尝试排空剩余响应体。
 */
class DeadlineBoundedBlobStreamTest {

  private static final String KEY = "blobs/00000000-0000-0000-0000-000000000000/original";

  private S3StorageService s3StorageService;

  @BeforeEach
  void setUp() {
    s3StorageService = mock(S3StorageService.class);
  }

  /** 测试意图：截止已过的读取必须在发起 S3 请求前失败，不能先建连接再超时。 */
  @Test
  void rejectsExpiredDeadlineWithoutCallingS3() {
    assertThrows(
        StorageReadTimeoutException.class,
        () -> DeadlineBoundedBlobStream.open(s3StorageService, KEY, expiredDeadline()));
    verify(s3StorageService, never()).readObject(any(), any());
  }

  /** 测试意图：已取消（中断）的读取不发起 S3 请求，并保留线程中断状态供调用方继续取消。 */
  @Test
  void rejectsInterruptedThreadWithoutCallingS3() {
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          StorageReadInterruptedException.class,
          () -> DeadlineBoundedBlobStream.open(s3StorageService, KEY, freshDeadline(5_000L)));
      assertTrue(Thread.currentThread().isInterrupted());
      verify(s3StorageService, never()).readObject(any(), any());
    } finally {
      Thread.interrupted();
    }
  }

  /** 测试意图：消费方提前停止读取时只能 abort（不排空剩余响应体），否则 close 会阻塞在排水上。 */
  @Test
  void abortsWithoutDrainingWhenConsumerStopsBeforeEof() throws IOException {
    StallingAbortableInputStream source = StallingAbortableInputStream.withBody(100);
    stubObject(source, 100);
    int first = 0;
    try (DeadlineBoundedBlobStream stream =
        DeadlineBoundedBlobStream.open(s3StorageService, KEY, freshDeadline(5_000L))) {
      first = stream.read();
    }
    assertEquals('a', first);
    assertTrue(source.aborted(), "提前退出必须 abort 连接");
    assertFalse(source.drainAttempted(), "提前退出不能排空未读完的响应体");
  }

  /** 测试意图：正常读到 EOF 后走 close（不 abort），连接可留在池中复用。 */
  @Test
  void closesWithoutAbortingAfterFullEof() throws IOException {
    StallingAbortableInputStream source = StallingAbortableInputStream.withBody(3);
    stubObject(source, 3);
    int total = 0;
    try (DeadlineBoundedBlobStream stream =
        DeadlineBoundedBlobStream.open(s3StorageService, KEY, freshDeadline(5_000L))) {
      int read;
      while ((read = stream.read()) != -1) {
        total++;
      }
    }
    assertEquals(3, total);
    assertFalse(source.aborted(), "读到 EOF 后不应 abort");
    assertTrue(source.closed(), "读到 EOF 后必须关闭流");
    assertFalse(source.drainAttempted(), "已读完时 close 不会排水");
  }

  /** 测试意图：响应体阻塞时看门狗在绝对截止点打断 read，并报告可区分的超时失败而不是伪装成 IO/二进制错误。 */
  @Test
  void failsWithTimeoutWhenBodyReadStallsBeyondDeadline() throws IOException {
    StallingAbortableInputStream source = StallingAbortableInputStream.stallingAfter(16);
    stubObject(source, 4096);
    long startedAt = System.nanoTime();
    StorageReadTimeoutException failure =
        assertThrows(
            StorageReadTimeoutException.class,
            () ->
                consumeAll(
                    DeadlineBoundedBlobStream.open(s3StorageService, KEY, freshDeadline(300L))));
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    assertTrue(failure.getMessage().contains(KEY));
    assertTrue(source.aborted(), "超时后必须 abort 连接");
    assertFalse(source.drainAttempted(), "超时后不能排空剩余响应体");
    assertTrue(elapsedMillis < 3_000L, "看门狗必须在截止点附近失败，实际耗时 " + elapsedMillis + " ms");
  }

  /** 测试意图：慢速滴流的响应体在截止点后不再被继续消费，读取以超时结束而不是无限等待。 */
  @Test
  void failsWithTimeoutWhenDripFeedOutlivesDeadline() throws IOException {
    StallingAbortableInputStream source = StallingAbortableInputStream.slowDrip(1_000, 20L);
    stubObject(source, 1_000);
    long startedAt = System.nanoTime();
    assertThrows(
        StorageReadTimeoutException.class,
        () ->
            consumeAll(DeadlineBoundedBlobStream.open(s3StorageService, KEY, freshDeadline(150L))));
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    assertTrue(source.delivered() < 1_000, "截止后不能继续消费滴流响应体");
    assertTrue(source.aborted());
    assertTrue(elapsedMillis < 3_000L, "滴流必须在截止点附近失败，实际耗时 " + elapsedMillis + " ms");
  }

  /** 测试意图：读取中途被中断按取消处理（不是超时），并保留中断状态。 */
  @Test
  void failsWithInterruptFailureWhenThreadInterruptedBetweenReads() throws IOException {
    StallingAbortableInputStream source = StallingAbortableInputStream.withBody(100);
    stubObject(source, 100);
    try (DeadlineBoundedBlobStream stream =
        DeadlineBoundedBlobStream.open(s3StorageService, KEY, freshDeadline(5_000L))) {
      assertEquals('a', stream.read());
      Thread.currentThread().interrupt();
      assertThrows(StorageReadInterruptedException.class, stream::read);
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(source.aborted(), "取消后必须 abort 连接");
    } finally {
      Thread.interrupted();
    }
  }

  /** 测试意图：到点或取消时不进入 read，直接以看门狗状态判定失败，避免再次阻塞。 */
  @Test
  void failsWithoutReadingWhenDeadlineExpiresBetweenReads() throws IOException {
    StallingAbortableInputStream source = StallingAbortableInputStream.withBody(100);
    stubObject(source, 100);
    try (DeadlineBoundedBlobStream stream =
        DeadlineBoundedBlobStream.open(s3StorageService, KEY, freshDeadline(50L))) {
      sleepQuietly(80L);
      assertThrows(StorageReadTimeoutException.class, stream::read);
      assertEquals(0, source.delivered(), "已到点后不应再读取响应体");
      assertTrue(source.aborted());
    }
  }

  /** 测试意图：abort 自身的失败不能静默，必须以 suppressed 附在超时结论上。 */
  @Test
  void reportsAbortFailureAsSuppressedOnTimeout() throws IOException {
    StallingAbortableInputStream source = StallingAbortableInputStream.stallingAfter(0);
    source.failAbortWith(new IllegalStateException("abort failed"));
    stubObject(source, 4096);
    StorageReadTimeoutException failure =
        assertThrows(
            StorageReadTimeoutException.class,
            () ->
                consumeAll(
                    DeadlineBoundedBlobStream.open(s3StorageService, KEY, freshDeadline(200L))));
    assertEquals(1, failure.getSuppressed().length);
    assertEquals("abort failed", failure.getSuppressed()[0].getMessage());
  }

  /** 测试意图：读取结束（含正常读完）后看门狗任务必须注销，不能把调度任务留在共享调度器上。 */
  @Test
  void unregistersWatchdogTaskAfterCompletion() throws IOException {
    StallingAbortableInputStream source = StallingAbortableInputStream.withBody(2);
    stubObject(source, 2);
    try (DeadlineBoundedBlobStream stream =
        DeadlineBoundedBlobStream.open(s3StorageService, KEY, freshDeadline(100L))) {
      consumeAll(stream);
    }
    assertEquals(0, DeadlineBoundedBlobStream.pendingWatchdogTasks());
    sleepQuietly(150L);
    assertFalse(source.aborted(), "已完成的读取不能被迟到的看门狗 abort");
  }

  /** 测试意图：S3 握手阶段的 API 调用超时按读取超时对外表达，而不是裸 SDK 异常。 */
  @Test
  void mapsApiCallTimeoutDuringHandshakeToReadTimeout() {
    when(s3StorageService.readObject(eq(KEY), any()))
        .thenThrow(ApiCallTimeoutException.create(10L));
    assertThrows(
        StorageReadTimeoutException.class,
        () -> DeadlineBoundedBlobStream.open(s3StorageService, KEY, freshDeadline(5_000L)));
  }

  /** 测试意图：阻塞的响应体读取被中断时按取消结束（不是超时、也不是普通 IO 失败），并保留中断状态。 */
  @Test
  void failsWithInterruptFailureWhenBlockedReadIsInterrupted() throws IOException {
    StallingAbortableInputStream source = StallingAbortableInputStream.slowDrip(1_000, 20L);
    stubObject(source, 1_000);

    Thread consumer = Thread.currentThread();
    Thread interrupter =
        new Thread(
            () -> {
              sleepQuietly(100L);
              consumer.interrupt();
            },
            "blocked-read-interrupter");
    interrupter.start();
    try {
      assertThrows(
          StorageReadInterruptedException.class,
          () ->
              consumeAll(
                  DeadlineBoundedBlobStream.open(s3StorageService, KEY, freshDeadline(10_000L))));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
      joinQuietly(interrupter);
    }
    assertTrue(source.aborted(), "取消后必须 abort 连接");
    assertTrue(source.delivered() < 1_000, "取消后不能继续消费响应体");
  }

  /** 测试意图：握手期间被取消（中断）时以取消失败而不是裸 SDK/IO 失败结束。 */
  @Test
  void mapsInterruptedHandshakeToInterruptFailure() {
    when(s3StorageService.readObject(eq(KEY), any()))
        .thenAnswer(
            invocation -> {
              Thread.currentThread().interrupt();
              throw AbortedException.create("Thread was interrupted", new InterruptedException());
            });
    try {
      assertThrows(
          StorageReadInterruptedException.class,
          () -> DeadlineBoundedBlobStream.open(s3StorageService, KEY, freshDeadline(5_000L)));
    } finally {
      Thread.interrupted();
    }
  }

  /** 测试意图：与截止/取消无关的 IO 失败原样上报，不能被误判为超时或取消。 */
  @Test
  void propagatesUnrelatedIoFailureWithoutMislabeling() throws IOException {
    StallingAbortableInputStream source = StallingAbortableInputStream.withBody(64);
    source.failReadWith(new IOException("s3 stream broken"));
    stubObject(source, 64);

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                consumeAll(
                    DeadlineBoundedBlobStream.open(s3StorageService, KEY, freshDeadline(5_000L))));
    assertEquals("s3 stream broken", failure.getMessage());
  }

  /** 测试意图：读到 EOF 后的关闭失败必须显式上报，不能静默丢弃连接清理错误。 */
  @Test
  void reportsCloseFailureAfterEof() throws IOException {
    StallingAbortableInputStream source = StallingAbortableInputStream.withBody(1);
    source.failCloseWith(new IOException("close failed"));
    stubObject(source, 1);

    DeadlineBoundedBlobStream stream =
        DeadlineBoundedBlobStream.open(s3StorageService, KEY, freshDeadline(5_000L));
    assertEquals('a', stream.read());
    assertEquals(-1, stream.read());
    UncheckedIOException failure = assertThrows(UncheckedIOException.class, stream::close);
    assertEquals("close failed", failure.getCause().getMessage());
  }

  /** 测试意图：提前退出时关闭阶段失败不能覆盖真正的读取失败（超时仍然上报且无 suppressed）。 */
  @Test
  void keepsTimeoutFailureWhenAbortCloseFails() throws IOException {
    StallingAbortableInputStream source = StallingAbortableInputStream.stallingAfter(0);
    source.failCloseWith(new IOException("close failed"));
    stubObject(source, 4096);

    StorageReadTimeoutException failure =
        assertThrows(
            StorageReadTimeoutException.class,
            () ->
                consumeAll(
                    DeadlineBoundedBlobStream.open(s3StorageService, KEY, freshDeadline(200L))));
    assertEquals(0, failure.getSuppressed().length);
    assertTrue(source.aborted());
  }

  private static void consumeAll(DeadlineBoundedBlobStream stream) throws IOException {
    try (stream) {
      byte[] buffer = new byte[64];
      while (stream.read(buffer, 0, buffer.length) != -1) {
        // 持续消费直到看门狗中断读取。
      }
    }
  }

  private static void joinQuietly(Thread thread) {
    try {
      thread.join(1_000L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void stubObject(StallingAbortableInputStream source, long contentLength) {
    when(s3StorageService.readObject(eq(KEY), any()))
        .thenReturn(
            new S3ObjectStream(source, new S3ObjectMetadata(contentLength, "text/plain", null)));
  }

  private static ReadDeadline freshDeadline(long millis) {
    return ReadDeadline.after(Duration.ofMillis(millis));
  }

  private static ReadDeadline expiredDeadline() {
    ReadDeadline deadline = ReadDeadline.after(Duration.ofMillis(1L));
    sleepQuietly(20L);
    assertTrue(deadline.isExpired());
    return deadline;
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
