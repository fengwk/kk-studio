package fun.fengwk.kkstudio.platform.plugin.support;

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
 * 把调用方在 send 前冻结的 nanoTime 截止点延伸到 JDK {@code ofInputStream} 响应体。
 *
 * <p>JDK request timeout 只覆盖 headers，慢 body 会绕过预算。本类到期主动关闭底层 body 打断阻塞 read，
 * 关闭在独立虚拟线程执行，绝不让一个关闭阻塞共享调度器。每个 body 只有一个看门狗，EOF、失败及 close 都会注销；超时关闭引起的 EOF/IOException
 * 必须仍作为超时失败。close 幂等，调用方关闭后不能再读取。
 *
 * <p>与 InputStream 一致，仅支持单读取者；允许另一线程并发 close。期望长度在交给读取者前设置。
 */
public final class DeadlineResponseBody extends InputStream {

  private enum State {
    OPEN,
    EOF,
    CLOSED,
    TIMED_OUT
  }

  private static final ScheduledThreadPoolExecutor WATCHDOG = newWatchdog();
  private static final System.Logger LOGGER =
      System.getLogger(DeadlineResponseBody.class.getName());

  private final InputStream body;
  private final long deadlineNanos;
  private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
  private final AtomicBoolean bodyClosed = new AtomicBoolean();
  private volatile ScheduledFuture<?> watchdogTask;
  private long expectedLength = -1;
  private long received;

  public DeadlineResponseBody(InputStream body, long deadlineNanos) {
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

  /** 声明精确字节数；读取提前结束或越过该长度都必须失败。 */
  public void expectLength(long length) {
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
              Thread thread = new Thread(runnable, "response-body-watchdog");
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
          throw new LengthException("response body does not match Content-Length");
        }
        if (!state.compareAndSet(State.OPEN, State.EOF)) {
          ensureReadable();
        }
        cancelWatchdog();
        closeBody();
      } else {
        received += count;
        if (expectedLength >= 0 && received > expectedLength) {
          throw new LengthException("response body exceeds Content-Length");
        }
      }
      return count;
    } catch (IOException exception) {
      if (state.get() == State.OPEN && System.nanoTime() - deadlineNanos >= 0) {
        expire();
      }
      try {
        close();
      } catch (IOException ignored) {
        // closeBody 已按 class 记录次要关闭失败；主读取失败优先。
      }
      if (state.get() == State.TIMED_OUT) {
        throw timeout();
      }
      if (exception instanceof LengthException) {
        throw exception;
      }
      // 其余底层失败不证明 body 已 EOF；只给中性固定分类，不声称 ended、不透出原始 message/cause。
      throw new IOException("response body read failed");
    } catch (RuntimeException | Error failure) {
      // 未受检失败同样要取消看门狗并关闭底层；清理失败已收敛，原始失败必须重抛。
      try {
        close();
      } catch (IOException ignored) {
        // 清理失败不掩盖原始失败。
      }
      throw failure;
    }
  }

  /** 声明长度不符的受控失败，避免被截断诊断再次改写。 */
  private static final class LengthException extends IOException {

    private LengthException(String message) {
      super(message);
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
      throw new IOException("response body is closed");
    }
  }

  private static HttpTimeoutException timeout() {
    return new HttpTimeoutException("response body deadline exceeded");
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
      try {
        body.close();
      } catch (IOException error) {
        // 单一诊断点：底层关闭失败可能含连接细节，只记录 class，不传出原始 message/cause。
        LOGGER.log(
            System.Logger.Level.DEBUG,
            "response body secondary close failed: {0}",
            error.getClass().getName());
        throw new IOException("response body close failed");
      }
    }
  }

  @Override
  public void close() throws IOException {
    state.updateAndGet(current -> current == State.TIMED_OUT ? current : State.CLOSED);
    cancelWatchdog();
    closeBody();
  }
}
