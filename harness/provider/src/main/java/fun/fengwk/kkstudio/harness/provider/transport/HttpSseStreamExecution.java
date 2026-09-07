package fun.fengwk.kkstudio.harness.provider.transport;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
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
 *   <li>所有生命周期回调严格串行化，且支持从回调内部安全重入 {@link #cancel()} 而不死锁。
 *   <li>{@link #cancel()} 返回后，绝不触发任何新的回调；与终态回调竞争具有确定性胜者。
 *   <li>资源关闭与取消能够识别当前工作线程，防止终态工作器发生自中断。
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

  private final long totalTimeoutNanos;
  private final long idleTimeoutNanos;
  private final long startNano;
  private final long totalDeadlineNano;
  private volatile long lastActivityNano;

  private volatile Thread workerThread;
  private volatile Future<?> workerFuture;
  private volatile ScheduledFuture<?> watchdogFuture;
  private volatile InputStream activeInputStream;

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

    this.startNano = System.nanoTime();
    this.totalTimeoutNanos = timeoutPolicy.modelCallTimeout().toNanos();
    this.idleTimeoutNanos = timeoutPolicy.modelCallIdleTimeout().toNanos();
    this.totalDeadlineNano = startNano + totalTimeoutNanos;
    this.lastActivityNano = startNano;
  }

  void attachFutures(Future<?> workerFuture, ScheduledFuture<?> watchdogFuture) {
    this.workerFuture = workerFuture;
    this.watchdogFuture = watchdogFuture;
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
    // 尝试 CAS 进入 CANCELLED 状态
    int current = state.get();
    while (current == STATE_PENDING || current == STATE_RUNNING) {
      if (state.compareAndSet(current, STATE_CANCELLED)) {
        break;
      }
      current = state.get();
    }

    if (current == STATE_PENDING || current == STATE_RUNNING) {
      // 获得锁并释放，以确保如果当前正有回调在执行，等待其退出；随后绝不再有新回调开始
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

  int currentState() {
    return state.get();
  }

  void runWorker() {
    workerThread = Thread.currentThread();
    try {
      startGate.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
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
        dispatchFailure(
            new TransportException(TransportErrorKind.CANCELLED, "Request interrupted", e));
      }
      return;
    } catch (IOException e) {
      if (state.get() != STATE_CANCELLED) {
        long now = System.nanoTime();
        if (now >= totalDeadlineNano) {
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
              "Invalid Content-Type for SSE stream: " + contentType,
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
    IncrementalSseParser parser = new IncrementalSseParser(limits, this::dispatchEvent);
    byte[] buffer = new byte[8192];
    try {
      int read;
      while ((read = responseBodyStream.read(buffer)) != -1) {
        lastActivityNano = System.nanoTime();
        if (state.get() == STATE_CANCELLED) {
          return;
        }
        parser.feed(buffer, 0, read);
      }
      parser.flush();
      dispatchComplete();
    } catch (IOException e) {
      if (state.get() != STATE_CANCELLED) {
        long now = System.nanoTime();
        if (now >= totalDeadlineNano) {
          triggerTimeout("Total call duration exceeded " + timeoutPolicy.modelCallTimeout());
        } else if (now - lastActivityNano >= idleTimeoutNanos) {
          triggerTimeout("Stream idle timeout exceeded " + timeoutPolicy.modelCallIdleTimeout());
        } else {
          dispatchFailure(
              new TransportException(
                  TransportErrorKind.IO, "I/O failure while reading SSE stream", e));
        }
      }
    } catch (TransportException te) {
      if (state.get() != STATE_CANCELLED) {
        dispatchFailure(te);
      }
    } finally {
      closeResources();
    }
  }

  void checkWatchdog() {
    if (state.get() != STATE_RUNNING && state.get() != STATE_PENDING) {
      return;
    }

    long now = System.nanoTime();
    // 检查总调用超时
    if (now >= totalDeadlineNano) {
      triggerTimeout("Total call duration exceeded " + timeoutPolicy.modelCallTimeout());
      return;
    }
    // 检查无活动闲置超时
    if (now - lastActivityNano >= idleTimeoutNanos) {
      triggerTimeout("Stream idle timeout exceeded " + timeoutPolicy.modelCallIdleTimeout());
    }
  }

  private void triggerTimeout(String reason) {
    if (state.get() == STATE_CANCELLED
        || state.get() == STATE_COMPLETED
        || state.get() == STATE_FAILED) {
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
    if (now >= totalDeadlineNano) {
      triggerTimeout("Total call duration exceeded " + timeoutPolicy.modelCallTimeout());
      return;
    }
    if (now - lastActivityNano >= idleTimeoutNanos) {
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
    int max = limits.maxErrorBodyBytes();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[1024];
    int totalRead = 0;
    boolean truncated = false;
    try {
      int r;
      // 读取上限为 max + 1，以便精确暴露 truncated 标志
      while ((r = in.read(buf, 0, Math.min(buf.length, (max + 1) - totalRead))) != -1) {
        lastActivityNano = System.nanoTime();
        if (state.get() == STATE_CANCELLED) {
          break;
        }
        totalRead += r;
        if (totalRead > max) {
          int keep = r - (totalRead - max);
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
      if (state.get() == STATE_CANCELLED) {
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

  private void dispatchEvent(ServerSentEvent event) {
    callbackLock.lock();
    try {
      if (state.get() == STATE_CANCELLED) {
        return;
      }
      callback.onEvent(event);
    } catch (Throwable t) {
      dispatchFailureLocked(
          new TransportException(
              TransportErrorKind.CALLBACK_FAILED, "onEvent callback threw an exception", t));
    } finally {
      callbackLock.unlock();
    }
  }

  private void dispatchComplete() {
    callbackLock.lock();
    try {
      int current = state.get();
      if (current == STATE_CANCELLED || current == STATE_COMPLETED || current == STATE_FAILED) {
        return;
      }
      if (!state.compareAndSet(current, STATE_COMPLETED)) {
        return;
      }
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
    if (current == STATE_CANCELLED || current == STATE_COMPLETED || current == STATE_FAILED) {
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
    if (watchdogFuture != null) {
      watchdogFuture.cancel(false);
    }
    // 只有在当前线程不是 worker 线程自身时才中断 worker，避免 worker 自中断
    if (workerFuture != null && Thread.currentThread() != workerThread) {
      workerFuture.cancel(true);
    }
  }

  private void closeActiveStream() {
    InputStream stream = activeInputStream;
    if (stream != null) {
      activeInputStream = null;
      closeInputStream(stream);
    }
  }

  private void closeInputStream(InputStream in) {
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
}
