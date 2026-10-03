package fun.fengwk.kkstudio.platform.canvas.function.opencli;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hub 专用响应体：沿用 send 前冻结的 nanoTime 截止点，不按 read 重置预算。
 *
 * <p>JDK ofInputStream 的 request timeout 只覆盖 headers。到期主动关闭底层 body 打断网络 read； close
 * 在独立虚拟线程执行，绝不让一个关闭阻塞共享调度器。每个 body 只有一个看门狗，EOF、失败及 close 都会注销；超时关闭引起的 EOF/IOException 必须仍作为超时失败。
 *
 * <p>与 InputStream 一致，仅支持单读取者；允许另一线程并发 close。资源长度在交给读取者前设置。
 */
final class DeadlineResponseBody extends InputStream {

  private enum State {
    OPEN,
    EOF,
    CLOSED,
    TIMED_OUT
  }

  private static final ScheduledThreadPoolExecutor WATCHDOG = newWatchdog();

  private final InputStream body;
  private final long deadlineNanos;
  private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
  private final AtomicBoolean bodyClosed = new AtomicBoolean();
  private volatile ScheduledFuture<?> watchdogTask;
  private long expectedLength = -1;
  private long received;

  DeadlineResponseBody(InputStream body, long deadlineNanos) {
    this.body = Objects.requireNonNull(body, "body");
    this.deadlineNanos = deadlineNanos;
    watchdogTask =
        WATCHDOG.schedule(
            this::expire, Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
    // 极短预算可能在 schedule 返回前到期。
    if (state.get() != State.OPEN) {
      cancelWatchdog();
    }
  }

  void expectLength(long length) {
    expectedLength = length;
  }

  static int pendingWatchdogTasks() {
    return WATCHDOG.getQueue().size();
  }

  private static ScheduledThreadPoolExecutor newWatchdog() {
    var executor =
        new ScheduledThreadPoolExecutor(
            1,
            runnable -> {
              Thread thread = new Thread(runnable, "opencli-response-watchdog");
              thread.setDaemon(true);
              return thread;
            });
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }

  @Override
  public int read() throws IOException {
    byte[] single = new byte[1];
    return read(single, 0, 1) == -1 ? -1 : single[0] & 0xff;
  }

  @Override
  public int read(byte[] bytes, int offset, int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, bytes.length);
    ensureReadable();
    if (length == 0) {
      return 0;
    }
    if (state.get() == State.EOF) {
      return -1;
    }
    try {
      int count = body.read(bytes, offset, length);
      ensureReadable();
      if (count == -1) {
        if (expectedLength >= 0 && received != expectedLength) {
          throw new IOException("OpenCLI Hub response body does not match Content-Length");
        }
        if (!state.compareAndSet(State.OPEN, State.EOF)) {
          ensureReadable();
        }
        cancelWatchdog();
        closeBody();
      } else {
        received += count;
        if (expectedLength >= 0 && received > expectedLength) {
          throw new IOException("OpenCLI Hub response body exceeds Content-Length");
        }
      }
      return count;
    } catch (IOException exception) {
      if (state.get() == State.OPEN && System.nanoTime() - deadlineNanos >= 0) {
        expire();
      }
      try {
        close();
      } catch (IOException closeError) {
        exception.addSuppressed(closeError);
      }
      if (state.get() == State.TIMED_OUT) {
        throw timeout();
      }
      throw exception;
    }
  }

  private void ensureReadable() throws IOException {
    if (state.get() == State.OPEN && System.nanoTime() - deadlineNanos >= 0) {
      expire();
    }
    State current = state.get();
    if (current == State.TIMED_OUT) {
      throw timeout();
    }
    if (current == State.CLOSED) {
      throw new IOException("OpenCLI Hub response body is closed");
    }
  }

  private static HttpTimeoutException timeout() {
    return new HttpTimeoutException("OpenCLI Hub response body deadline exceeded");
  }

  private void expire() {
    if (state.compareAndSet(State.OPEN, State.TIMED_OUT)) {
      cancelWatchdog();
      Thread.startVirtualThread(
          () -> {
            try {
              closeBody();
            } catch (IOException ignored) {
              // 超时状态已冻结；不能用次要关闭错误替代超时。
            }
          });
    }
  }

  private void cancelWatchdog() {
    ScheduledFuture<?> task = watchdogTask;
    if (task != null) {
      task.cancel(false);
    }
  }

  private void closeBody() throws IOException {
    if (bodyClosed.compareAndSet(false, true)) {
      body.close();
    }
  }

  @Override
  public void close() throws IOException {
    state.updateAndGet(current -> current == State.TIMED_OUT ? current : State.CLOSED);
    cancelWatchdog();
    closeBody();
  }
}
