package fun.fengwk.kkstudio.platform.storage.service.impl;

import software.amazon.awssdk.http.Abortable;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用假 S3 响应流，行为刻意对齐 Apache 客户端响应流：
 *
 * <ul>
 *   <li>{@link #abort()} 让阻塞中的 read 立刻失败（等价于 socket 被关停）；
 *   <li>{@link #close()} 在未读完且未 abort 时记录排水意图（真实实现会读完剩余响应体以复用连接，可能无界阻塞）；
 *   <li>读取超过约定字节数后阻塞等待，只能被 abort（或 close）打断，并有兜底上限避免测试永久挂住。
 * </ul>
 *
 * <p>仅用于验证读取边界的截止、取消与“不排水”语义。
 */
final class StallingAbortableInputStream extends InputStream implements Abortable {

  private static final long STALL_LIMIT_MILLIS = 3_000L;

  private final Object lock = new Object();
  private final int bodyBytes;
  private final long dripIntervalMillis;
  private final AtomicInteger delivered = new AtomicInteger();
  private final AtomicBoolean aborted = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean drainAttempted = new AtomicBoolean();
  private volatile RuntimeException abortFailure;
  private volatile IOException readFailure;
  private volatile IOException closeFailure;

  private StallingAbortableInputStream(int bodyBytes, long dripIntervalMillis) {
    this.bodyBytes = bodyBytes;
    this.dripIntervalMillis = dripIntervalMillis;
  }

  /** 交付 bodyBytes 后进入 EOF。 */
  static StallingAbortableInputStream withBody(int bodyBytes) {
    return new StallingAbortableInputStream(bodyBytes, 0L);
  }

  /** 交付 prefixBytes 后阻塞，直到被 abort 或 close。 */
  static StallingAbortableInputStream stallingAfter(int prefixBytes) {
    return new StallingAbortableInputStream(prefixBytes, -1L);
  }

  /** 每 dripIntervalMillis 毫秒交付一个字节，用于模拟慢速滴流。 */
  static StallingAbortableInputStream slowDrip(int bodyBytes, long dripIntervalMillis) {
    return new StallingAbortableInputStream(bodyBytes, dripIntervalMillis);
  }

  void failAbortWith(RuntimeException error) {
    this.abortFailure = error;
  }

  void failReadWith(IOException error) {
    this.readFailure = error;
  }

  void failCloseWith(IOException error) {
    this.closeFailure = error;
  }

  int delivered() {
    return delivered.get();
  }

  boolean aborted() {
    return aborted.get();
  }

  boolean closed() {
    return closed.get();
  }

  boolean drainAttempted() {
    return drainAttempted.get();
  }

  @Override
  public int read(byte[] bytes, int offset, int length) throws IOException {
    if (length == 0) {
      return 0;
    }
    int value = read();
    if (value == -1) {
      return -1;
    }
    bytes[offset] = (byte) value;
    return 1;
  }

  @Override
  public int read() throws IOException {
    synchronized (lock) {
      for (; ; ) {
        IOException readError = readFailure;
        if (readError != null) {
          throw readError;
        }
        if (aborted.get()) {
          throw new IOException("connection shut down");
        }
        if (closed.get()) {
          throw new IOException("stream closed");
        }
        if (delivered.get() < bodyBytes) {
          delivered.incrementAndGet();
          if (dripIntervalMillis > 0L) {
            waitForLock(dripIntervalMillis);
          }
          return 'a';
        }
        if (dripIntervalMillis == 0L) {
          return -1;
        }
        // 仍有未交付的响应体：阻塞等待数据，只能由看门狗 abort 打断。
        waitForLock(STALL_LIMIT_MILLIS);
        if (!aborted.get() && !closed.get()) {
          throw new IOException("response body stalled without watchdog abort");
        }
      }
    }
  }

  private void waitForLock(long millis) throws IOException {
    try {
      lock.wait(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("blocked read interrupted", e);
    }
  }

  @Override
  public void abort() {
    RuntimeException failure = abortFailure;
    if (failure != null) {
      throw failure;
    }
    synchronized (lock) {
      aborted.set(true);
      lock.notifyAll();
    }
  }

  @Override
  public void close() throws IOException {
    synchronized (lock) {
      drainAttempted.set(!aborted.get() && delivered.get() < bodyBytes);
      closed.set(true);
      lock.notifyAll();
    }
    IOException failure = closeFailure;
    if (failure != null) {
      throw failure;
    }
  }
}
