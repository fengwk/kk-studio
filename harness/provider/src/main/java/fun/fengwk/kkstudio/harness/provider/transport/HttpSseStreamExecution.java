package fun.fengwk.kkstudio.harness.provider.transport;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单次 HTTP/SSE 流请求的受控执行器。
 *
 * <p>安全与并发契约：
 *
 * <ul>
 *   <li>单一可线性化状态机仲裁：PENDING -> RUNNING -> COMPLETED / FAILED / CANCELLED。
 *   <li>启动门（start gate）防护：在工作器提交与调度器任务安排就绪前，工作器绝不碰 HttpClient。
 *   <li>检测并安全拒绝 direct / caller-runs executor 的 inline execution，防止流死锁。
 *   <li>所有生命周期回调严格串行化，且仅在权威状态 RUNNING 下派发；终态后任何非终态回调为零。
 *   <li>支持从回调内部安全重入 {@link #cancel()} 而不死锁。
 *   <li>竞态安全的 Future 挂接：任何 future 一经挂接若已处于终态必须立即 cancel。
 *   <li>非显式 cancel 导致的 {@link InterruptedException} 按基础 transport I/O 语义分类并保留中断标志。
 *   <li>单调时钟 deadline 一次性调度；timer 仅负责排期，不执行阻塞清理或回调。
 * </ul>
 */
final class HttpSseStreamExecution implements ProviderStream {

  static final int STATE_PENDING = 0;
  static final int STATE_RUNNING = 1;
  static final int STATE_CANCELLED = 2;
  static final int STATE_COMPLETED = 3;
  static final int STATE_FAILED = 4;

  private final HttpClient httpClient;
  private final HttpRequest request;
  private final ModelCallTimeoutPolicy timeoutPolicy;
  private final HttpSseLimits limits;
  private final HttpSseCallback callback;

  private final AtomicInteger state = new AtomicInteger(STATE_PENDING);
  private final ReentrantLock callbackLock = new ReentrantLock();
  private final CountDownLatch startGate = new CountDownLatch(1);

  private final Thread streamCallingThread;
  private final AtomicBoolean inlineExecutionDetected = new AtomicBoolean(false);

  private final long totalTimeoutNanos;
  private final long idleTimeoutNanos;
  private final long startNano;
  private volatile long lastActivityNano;

  private final Object futureLock = new Object();
  private volatile Thread workerThread;
  private volatile Future<?> workerFuture;
  private ScheduledFuture<?> deadlineFuture;
  private volatile InputStream activeInputStream;
  private ScheduledExecutorService scheduler;

  HttpSseStreamExecution(
      HttpClient httpClient,
      HttpRequest request,
      ModelCallTimeoutPolicy timeoutPolicy,
      HttpSseLimits limits,
      HttpSseCallback callback) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    this.request = Objects.requireNonNull(request, "request must not be null");
    this.timeoutPolicy = Objects.requireNonNull(timeoutPolicy, "timeoutPolicy must not be null");
    this.limits = Objects.requireNonNull(limits, "limits must not be null");
    this.callback = Objects.requireNonNull(callback, "callback must not be null");

    this.streamCallingThread = Thread.currentThread();
    this.startNano = System.nanoTime();
    this.totalTimeoutNanos = safeDurationToNanos(timeoutPolicy.modelCallTimeout());
    this.idleTimeoutNanos = safeDurationToNanos(timeoutPolicy.modelCallIdleTimeout());
    this.lastActivityNano = startNano;
  }

  static long safeDurationToNanos(Duration duration) {
    if (duration == null || duration.isNegative() || duration.isZero()) {
      return 0L;
    }
    long seconds = duration.getSeconds();
    int nanos = duration.getNano();
    if (seconds >= Long.MAX_VALUE / 1_000_000_000L) {
      return Long.MAX_VALUE;
    }
    long nanosFromSeconds = seconds * 1_000_000_000L;
    if (Long.MAX_VALUE - nanosFromSeconds < nanos) {
      return Long.MAX_VALUE;
    }
    return nanosFromSeconds + nanos;
  }

  // Elapsed-time comparisons avoid overflow even when nanoTime wraps around.
  long nextDeadlineDelayNanos(long now) {
    long totalRemaining =
        totalTimeoutNanos == Long.MAX_VALUE
            ? Long.MAX_VALUE
            : Math.max(0L, totalTimeoutNanos - Math.max(0L, now - startNano));
    long idleRemaining =
        idleTimeoutNanos == Long.MAX_VALUE
            ? Long.MAX_VALUE
            : Math.max(0L, idleTimeoutNanos - Math.max(0L, now - lastActivityNano));
    return Math.min(totalRemaining, idleRemaining);
  }

  void scheduleDeadline(ScheduledExecutorService scheduler) {
    synchronized (futureLock) {
      this.scheduler = scheduler;
      scheduleNextDeadline();
    }
  }

  // Called with futureLock held. A read only updates lastActivityNano; no per-byte timer churn.
  private void scheduleNextDeadline() {
    if (!isTerminal()) {
      deadlineFuture =
          scheduler.schedule(
              new DeadlineTask(this),
              nextDeadlineDelayNanos(System.nanoTime()),
              TimeUnit.NANOSECONDS);
    }
  }

  private void onDeadline() {
    synchronized (futureLock) {
      if (isTerminal()) {
        return;
      }
      if (nextDeadlineDelayNanos(System.nanoTime()) > 0) {
        try {
          scheduleNextDeadline();
        } catch (RejectedExecutionException e) {
          runDeadlineCleanup(
              () ->
                  dispatchFailure(
                      new TransportException(
                          TransportErrorKind.EXECUTOR_REJECTED,
                          "Scheduler rejected deadline rescheduling",
                          e)));
        }
        return;
      }
    }
    // Never close a stream, wait for callbackLock, or invoke user code on the shared timer.
    runDeadlineCleanup(this::checkDeadline);
  }

  private void runDeadlineCleanup(Runnable work) {
    // Only actual expiry (or a failed reschedule) starts a short-lived cleanup thread.
    // Never enqueue behind blocking I/O on the worker executor or run user callbacks on the timer.
    Thread.ofVirtual().start(work);
  }

  // Some injected schedulers retain cancelled tasks until their deadline. Do not let a long
  // cancelled timer retain the entire stream, client, callback and response body.
  private static final class DeadlineTask implements Runnable {
    private final WeakReference<HttpSseStreamExecution> execution;

    private DeadlineTask(HttpSseStreamExecution execution) {
      this.execution = new WeakReference<>(execution);
    }

    @Override
    public void run() {
      HttpSseStreamExecution current = execution.get();
      if (current != null) {
        current.onDeadline();
      }
    }
  }

  boolean isInlineExecutionDetected() {
    return inlineExecutionDetected.get();
  }

  void attachWorkerFuture(Future<?> workerFuture) {
    synchronized (futureLock) {
      this.workerFuture = workerFuture;
      if (isTerminal()) {
        cancelFutureSafe(workerFuture, true);
      }
    }
  }

  private void cancelFutureSafe(Future<?> future, boolean mayInterruptIfRunning) {
    if (future != null) {
      if (mayInterruptIfRunning && Thread.currentThread() == workerThread) {
        future.cancel(false);
      } else {
        future.cancel(mayInterruptIfRunning);
      }
    }
  }

  void openStartGate() {
    startGate.countDown();
  }

  void abortAdmission() {
    state.set(STATE_CANCELLED);
    closeResources();
  }

  @Override
  public void cancel() {
    if (state.get() == STATE_CANCELLED) {
      return;
    }
    int current = state.get();
    while (current == STATE_PENDING || current == STATE_RUNNING) {
      if (state.compareAndSet(current, STATE_CANCELLED)) {
        break;
      }
      current = state.get();
    }

    if (current == STATE_PENDING || current == STATE_RUNNING) {
      callbackLock.lock();
      try {
        closeResources();
      } finally {
        callbackLock.unlock();
      }
    }
  }

  @Override
  public boolean isCancelled() {
    return state.get() == STATE_CANCELLED;
  }

  static boolean isTerminal(int state) {
    return state == STATE_CANCELLED || state == STATE_COMPLETED || state == STATE_FAILED;
  }

  boolean isTerminal() {
    return isTerminal(state.get());
  }

  int currentState() {
    return state.get();
  }

  private boolean isTotalTimeoutExceeded(long now) {
    if (totalTimeoutNanos == Long.MAX_VALUE) {
      return false;
    }
    return (now - startNano) >= totalTimeoutNanos;
  }

  private boolean isIdleTimeoutExceeded(long now) {
    if (idleTimeoutNanos == Long.MAX_VALUE) {
      return false;
    }
    return (now - lastActivityNano) >= idleTimeoutNanos;
  }

  void runWorker() {
    // 检测 inline execution，防止 direct / caller-runs executor 导致 stream 调用死锁
    if (Thread.currentThread() == streamCallingThread) {
      inlineExecutionDetected.set(true);
      state.set(STATE_CANCELLED);
      closeResources();
      return;
    }

    workerThread = Thread.currentThread();
    try {
      startGate.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (state.get() != STATE_CANCELLED) {
        dispatchFailure(
            new TransportException(
                TransportErrorKind.IO, "Worker interrupted while awaiting start gate", e));
      }
      return;
    }

    if (!state.compareAndSet(STATE_PENDING, STATE_RUNNING)) {
      return;
    }

    HttpResponse<InputStream> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (state.get() != STATE_CANCELLED) {
        dispatchFailure(new TransportException(TransportErrorKind.IO, "Request interrupted", e));
      }
      return;
    } catch (IOException e) {
      if (state.get() == STATE_RUNNING) {
        long now = System.nanoTime();
        if (isTotalTimeoutExceeded(now)) {
          triggerTimeout("Total call duration exceeded " + timeoutPolicy.modelCallTimeout());
        } else {
          dispatchFailure(
              new TransportException(TransportErrorKind.IO, "Connection or network I/O failed", e));
        }
      }
      return;
    }

    int statusCode = response.statusCode();
    Map<String, List<String>> headers = response.headers().map();
    InputStream responseBodyStream = response.body();
    this.activeInputStream = responseBodyStream;

    // 确认权威状态仍为 RUNNING（若在 send 期间发生超时或取消，立即关闭流并退出）
    if (state.get() != STATE_RUNNING) {
      closeActiveStream();
      return;
    }

    // 状态码校验：非 2xx 响应
    if (statusCode < 200 || statusCode >= 300) {
      handleNon2xxResponse(statusCode, headers, responseBodyStream, TransportErrorKind.HTTP_STATUS);
      return;
    }

    // 2xx 响应：校验 Content-Type 是否为合法的 text/event-stream
    String contentType =
        response
            .headers()
            .firstValue("Content-Type")
            .orElse(response.headers().firstValue("content-type").orElse(""));
    if (!isValidEventStreamContentType(contentType)) {
      readBoundedErrorBody(responseBodyStream); // 丢弃非期望响应体并关闭流
      dispatchFailure(
          new TransportException(
              TransportErrorKind.INVALID_RESPONSE,
              "Invalid Content-Type for SSE stream; expected text/event-stream",
              statusCode,
              null,
              headers,
              null));
      return;
    }

    // 触发 onOpen 回调
    HttpOpenMetadata metadata = new HttpOpenMetadata(statusCode, headers);
    if (!dispatchOpen(metadata)) {
      closeResources();
      return;
    }

    // 读取 SSE 响应流并增量解析
    IncrementalSseParser parser =
        new IncrementalSseParser(
            limits,
            event -> {
              if (state.get() != STATE_RUNNING || !dispatchEvent(event)) {
                throw new StreamTerminatedException();
              }
            });
    byte[] buffer = new byte[8192];
    try {
      int read;
      while (state.get() == STATE_RUNNING && (read = responseBodyStream.read(buffer)) != -1) {
        lastActivityNano = System.nanoTime();
        parser.feed(buffer, 0, read);
        if (state.get() != STATE_RUNNING) {
          return;
        }
      }
      if (state.get() == STATE_RUNNING) {
        parser.flush();
        dispatchComplete();
      }
    } catch (StreamTerminatedException e) {
      // 终态已流转或回调失败，worker 立即停止后续读取和解析
      return;
    } catch (IOException e) {
      if (state.get() == STATE_RUNNING) {
        long now = System.nanoTime();
        if (isTotalTimeoutExceeded(now)) {
          triggerTimeout("Total call duration exceeded " + timeoutPolicy.modelCallTimeout());
        } else if (isIdleTimeoutExceeded(now)) {
          triggerTimeout("Stream idle timeout exceeded " + timeoutPolicy.modelCallIdleTimeout());
        } else {
          dispatchFailure(
              new TransportException(
                  TransportErrorKind.IO, "I/O failure while reading SSE stream", e));
        }
      }
    } catch (TransportException te) {
      if (state.get() == STATE_RUNNING) {
        dispatchFailure(te);
      }
    } finally {
      closeResources();
    }
  }

  private void checkDeadline() {
    if (state.get() != STATE_RUNNING && state.get() != STATE_PENDING) {
      return;
    }

    long now = System.nanoTime();
    // 检查总调用超时
    if (isTotalTimeoutExceeded(now)) {
      triggerTimeout("Total call duration exceeded " + timeoutPolicy.modelCallTimeout());
      return;
    }
    // 检查无活动闲置超时
    if (isIdleTimeoutExceeded(now)) {
      triggerTimeout("Stream idle timeout exceeded " + timeoutPolicy.modelCallIdleTimeout());
    } else {
      RejectedExecutionException rejected = null;
      synchronized (futureLock) {
        if (!isTerminal()) {
          try {
            scheduleNextDeadline();
          } catch (RejectedExecutionException e) {
            rejected = e;
          }
        }
      }
      if (rejected != null) {
        dispatchFailure(
            new TransportException(
                TransportErrorKind.EXECUTOR_REJECTED,
                "Scheduler rejected deadline rescheduling",
                rejected));
      }
    }
  }

  void triggerTimeout(String reason) {
    if (isTerminal()) {
      return;
    }
    dispatchFailure(new TransportException(TransportErrorKind.TIMEOUT, reason));
  }

  private void handleNon2xxResponse(
      int statusCode,
      Map<String, List<String>> headers,
      InputStream stream,
      TransportErrorKind kind) {
    BoundedErrorResult result = readBoundedErrorBody(stream);
    long now = System.nanoTime();
    if (isTotalTimeoutExceeded(now)) {
      triggerTimeout("Total call duration exceeded " + timeoutPolicy.modelCallTimeout());
      return;
    }
    if (isIdleTimeoutExceeded(now)) {
      triggerTimeout("Stream idle timeout exceeded " + timeoutPolicy.modelCallIdleTimeout());
      return;
    }
    if (state.get() != STATE_RUNNING && state.get() != STATE_PENDING) {
      return;
    }
    dispatchFailure(
        new TransportException(
            kind,
            "HTTP " + statusCode + " response received",
            statusCode,
            result.bodyBytes(),
            result.truncated(),
            headers,
            null));
  }

  record BoundedErrorResult(byte[] bodyBytes, boolean truncated) {}

  BoundedErrorResult readBoundedErrorBody(InputStream in) {
    if (in == null) {
      return new BoundedErrorResult(new byte[0], false);
    }
    if (isTerminal()) {
      closeInputStream(in);
      return new BoundedErrorResult(new byte[0], false);
    }
    int max = limits.maxErrorBodyBytes();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[1024];
    long totalRead = 0L;
    boolean truncated = false;
    long targetLimit = (long) max + 1L;
    try {
      int r;
      // 读取上限为 targetLimit，采用 long 运算杜绝 max == Integer.MAX_VALUE 时的整型溢出
      while (!isTerminal()
          && (r = in.read(buf, 0, (int) Math.min((long) buf.length, targetLimit - totalRead)))
              != -1) {
        lastActivityNano = System.nanoTime();
        if (isTerminal()) {
          break;
        }
        totalRead += r;
        if (totalRead > (long) max) {
          long keepLong = (long) r - (totalRead - (long) max);
          int keep = (int) Math.max(0L, Math.min((long) r, keepLong));
          if (keep > 0) {
            out.write(buf, 0, keep);
          }
          truncated = true;
          break;
        } else {
          out.write(buf, 0, r);
        }
      }
    } catch (IOException ignored) {
    } finally {
      closeInputStream(in);
    }
    return new BoundedErrorResult(out.toByteArray(), truncated);
  }

  private boolean dispatchOpen(HttpOpenMetadata metadata) {
    callbackLock.lock();
    try {
      if (state.get() != STATE_RUNNING) {
        return false;
      }
      callback.onOpen(metadata);
      return true;
    } catch (Throwable t) {
      // 回调抛出异常，安全转为 CALLBACK_FAILED 终态
      dispatchFailureLocked(
          new TransportException(
              TransportErrorKind.CALLBACK_FAILED, "onOpen callback threw an exception", t));
      return false;
    } finally {
      callbackLock.unlock();
    }
  }

  private boolean dispatchEvent(ServerSentEvent event) {
    callbackLock.lock();
    try {
      if (state.get() != STATE_RUNNING) {
        return false;
      }
      callback.onEvent(event);
      return true;
    } catch (Throwable t) {
      dispatchFailureLocked(
          new TransportException(
              TransportErrorKind.CALLBACK_FAILED, "onEvent callback threw an exception", t));
      return false;
    } finally {
      callbackLock.unlock();
    }
  }

  private void dispatchComplete() {
    callbackLock.lock();
    try {
      int current = state.get();
      if (isTerminal(current)) {
        return;
      }
      if (!state.compareAndSet(current, STATE_COMPLETED)) {
        return;
      }
      closeResources();
      try {
        callback.onComplete();
      } catch (Throwable ignored) {
        // 安全忽略，绝不产生第二终态
      }
    } finally {
      callbackLock.unlock();
    }
  }

  void dispatchFailure(TransportException error) {
    callbackLock.lock();
    try {
      dispatchFailureLocked(error);
    } finally {
      callbackLock.unlock();
    }
  }

  private void dispatchFailureLocked(TransportException error) {
    int current = state.get();
    if (isTerminal(current)) {
      return;
    }
    if (!state.compareAndSet(current, STATE_FAILED)) {
      return;
    }
    closeResources();
    try {
      callback.onFailure(error);
    } catch (Throwable ignored) {
      // 安全忽略，绝不产生第二终态
    }
  }

  private void closeResources() {
    closeActiveStream();
    synchronized (futureLock) {
      cancelFutureSafe(deadlineFuture, false);
      cancelFutureSafe(workerFuture, true);
    }
  }

  private void closeActiveStream() {
    InputStream stream = activeInputStream;
    if (stream != null) {
      activeInputStream = null;
      closeInputStream(stream);
    }
  }

  static void closeInputStream(InputStream in) {
    try {
      in.close();
    } catch (IOException ignored) {
    }
  }

  static boolean isValidEventStreamContentType(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return false;
    }
    String[] parts = contentType.split(";");
    String mediaType = parts[0].trim().toLowerCase(Locale.ROOT);
    if (!"text/event-stream".equals(mediaType)) {
      return false;
    }
    for (int i = 1; i < parts.length; i++) {
      String param = parts[i].trim().toLowerCase(Locale.ROOT);
      if (param.startsWith("charset=")) {
        String charset = param.substring("charset=".length()).trim();
        if (!"utf-8".equals(charset) && !"\"utf-8\"".equals(charset)) {
          return false;
        }
      }
    }
    return true;
  }

  private static final class StreamTerminatedException extends RuntimeException {}
}
