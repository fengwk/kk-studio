package fun.fengwk.kkstudio.harness.common.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 共享响应体 helper 的状态边界；真实网络阻塞与 JDK close 行为由调用方集成测试验证。 */
@Timeout(10)
class DeadlineResponseBodyTest {

  @Test
  void eofLengthChecksAndRepeatedCloseReleaseWatchdog() throws Exception {
    // 正常 EOF 关闭一次；过短/过长的实际 body 都必须明确失败，重复 close 不重试底层关闭。
    for (long expected : new long[] {1, 2, 3}) {
      CountingBody input = new CountingBody(new byte[] {(byte) 255, 2});
      try (var body = new DeadlineResponseBody(input, deadline())) {
        body.expectLength(expected);
        assertEquals(0, body.read(new byte[0]));
        if (expected == 2) {
          assertEquals(255, body.read());
          assertEquals(2, body.read());
          assertEquals(-1, body.read());
          assertEquals(-1, body.read());
        } else {
          assertTrue(
              assertThrows(IOException.class, body::readAllBytes)
                  .getMessage()
                  .contains("Content-Length"));
        }
        body.close();
        body.close();
        assertEquals(1, input.closes.get());
        assertThrows(IOException.class, body::read);
      }
    }
    assertEquals(0, DeadlineResponseBody.pendingWatchdogTasks());
  }

  @Test
  void readFailureIsSanitizedAndReleasesWatchdog() throws Exception {
    // 底层读取失败的原始 message/cause 绝不外泄；失败后看门狗必须注销。
    InputStream input =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("read failure /secret/path");
          }
        };
    var body = new DeadlineResponseBody(input, deadline());
    var error = assertThrows(IOException.class, body::read);
    assertEquals("response body read failed", error.getMessage());
    assertNull(error.getCause());
    assertEquals(0, error.getSuppressed().length);
    body.close();
    assertEquals(0, DeadlineResponseBody.pendingWatchdogTasks());
  }

  @Test
  void closeFailureIsSanitizedAndDeregistersWatchdogOnce() throws Exception {
    // 底层关闭失败的原始 message 不外泄；二次 close 幂等且不重试底层关闭。
    AtomicInteger closes = new AtomicInteger();
    InputStream input =
        new InputStream() {
          @Override
          public int read() {
            return -1;
          }

          @Override
          public void close() throws IOException {
            closes.incrementAndGet();
            throw new IOException("close failure /secret/url");
          }
        };
    var body = new DeadlineResponseBody(input, deadline());
    var error = assertThrows(IOException.class, body::close);
    assertEquals("response body close failed", error.getMessage());
    assertNull(error.getCause());
    body.close();
    assertEquals(1, closes.get());
    assertEquals(0, DeadlineResponseBody.pendingWatchdogTasks());
  }

  @Test
  void underlyingErrorFromCloseIsNotSwallowedAndWatchdogIsDeregistered() throws Exception {
    // Error 不属于 IO 失败，不得被吞；看门狗仍必须在 close 前注销。
    InputStream input =
        new InputStream() {
          @Override
          public int read() {
            return -1;
          }

          @Override
          public void close() {
            throw new AssertionError("fatal close error");
          }
        };
    var body = new DeadlineResponseBody(input, deadline());
    assertThrows(AssertionError.class, body::close);
    assertEquals(0, DeadlineResponseBody.pendingWatchdogTasks());
  }

  @Test
  void truncatedStreamFailureReportsLengthMismatchWithoutRawCause() throws Exception {
    // 底层在不足声明长度时抛 IO（连接被截断）：转成明确的长度事实，不外抛模糊的底层 cause。
    InputStream truncating =
        new InputStream() {
          private int reads;

          @Override
          public int read() throws IOException {
            if (reads++ < 2) {
              return 7;
            }
            throw new IOException("connection reset");
          }
        };
    var body = new DeadlineResponseBody(truncating, deadline());
    body.expectLength(5);
    var error = assertThrows(IOException.class, body::readAllBytes);
    assertEquals("response body ended after 2 of 5 bytes", error.getMessage());
    assertEquals(0, error.getSuppressed().length);
    assertEquals(0, DeadlineResponseBody.pendingWatchdogTasks());
  }

  @Test
  void timeoutProducedEofIsNotSuccessAndSlowCloseCannotBlockOtherWatchdogs() throws Exception {
    // 人工控制 close 阻塞，仅用于证明共享调度器隔离；同时覆盖超时 close 产生 EOF 的状态竞态。
    CountDownLatch closing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    InputStream slow =
        new InputStream() {
          @Override
          public int read() throws IOException {
            try {
              release.await();
              return -1;
            } catch (InterruptedException exception) {
              throw new IOException(exception);
            }
          }

          @Override
          public void close() throws IOException {
            closing.countDown();
            try {
              release.await();
              throw new IOException("secondary close failure");
            } catch (InterruptedException exception) {
              throw new IOException(exception);
            }
          }
        };
    try (var workers = Executors.newVirtualThreadPerTaskExecutor();
        var body =
            new DeadlineResponseBody(slow, System.nanoTime() + Duration.ofMillis(100).toNanos())) {
      try {
        var reading = workers.submit(() -> assertThrows(HttpTimeoutException.class, body::read));
        assertTrue(closing.await(1, TimeUnit.SECONDS));
        CountingBody other = new CountingBody(new byte[] {1});
        try (var second = new DeadlineResponseBody(other, System.nanoTime() - 1)) {
          assertThrows(HttpTimeoutException.class, second::read);
        }
        release.countDown();
        reading.get(1, TimeUnit.SECONDS);
        assertTrue(other.closed.await(1, TimeUnit.SECONDS));
        assertEquals(1, other.closes.get());
      } finally {
        release.countDown();
      }
    } finally {
      release.countDown();
    }
    assertEquals(0, DeadlineResponseBody.pendingWatchdogTasks());
  }

  @Test
  void deadlineAndCloseRaceClosesExactlyOnce() throws Exception {
    // 同一截止点释放显式 close 与看门狗，不要求哪方获胜，但必须恰好关闭一次且禁止后续读取。
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int index = 0; index < 100; index++) {
        CountingBody input = new CountingBody(new byte[] {1});
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2);
        var body = new DeadlineResponseBody(input, deadline);
        var closing =
            workers.submit(
                () -> {
                  while (System.nanoTime() - deadline < 0) {
                    Thread.onSpinWait();
                  }
                  body.close();
                  body.close();
                  return null;
                });
        closing.get(1, TimeUnit.SECONDS);
        assertThrows(IOException.class, body::read);
        assertTrue(input.closed.await(1, TimeUnit.SECONDS));
        assertEquals(1, input.closes.get());
      }
    }
    assertEquals(0, DeadlineResponseBody.pendingWatchdogTasks());
  }

  private static long deadline() {
    return System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
  }

  private static final class CountingBody extends ByteArrayInputStream {
    private final AtomicInteger closes = new AtomicInteger();
    private final CountDownLatch closed = new CountDownLatch(1);

    CountingBody(byte[] bytes) {
      super(bytes);
    }

    @Override
    public void close() {
      closes.incrementAndGet();
      closed.countDown();
    }
  }
}
