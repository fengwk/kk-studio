package fun.fengwk.kkstudio.harness.provider.transport;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 JDK 21 {@link HttpClient} 与受管 {@link ExecutorService} 的安全有界 SSE 流传输器。
 *
 * <p>核心并发与安全契约：
 *
 * <ul>
 *   <li>{@link #stream} 方法在向受管执行器提交后立即返回实现 {@link ProviderStream} 的取消句柄，绝不阻塞当前线程进行远程 I/O。
 *   <li>受管执行器拒绝提交任务时，同步抛出包含安全错误类型的 {@link TransportException}，证明任务从未启动。
 *   <li>取消操作（{@link ProviderStream#cancel}）幂等，支持在请求发起前、响应到达前、读取中或终态后触发；取消后不再触发任何回调。
 *   <li>终态回调（{@link HttpSseCallback#onComplete} 与 {@link HttpSseCallback#onFailure}）互斥且至多触发一次。
 *   <li>在同一受管执行器上提交轻量 Watchdog，基于 {@link System#nanoTime} 单调时钟精确检测模型总调用时长与流活动闲置超时。
 *   <li>对非 2xx 响应严格有界截断读取错误正文并确保输入流关闭，绝不跟随 3xx 重定向。
 *   <li>所有异常及其原因链、{@code toString} 和 {@code getMessage} 均绝不泄露请求 URI、认证凭据、请求正文或远端错误正文。
 * </ul>
 */
@Slf4j
public class JdkHttpSseTransport {

  private final HttpClient httpClient;
  private final ExecutorService executor;

  public JdkHttpSseTransport(HttpClient httpClient, ExecutorService executor) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    this.executor = Objects.requireNonNull(executor, "executor must not be null");
  }

  /**
   * 启动 HTTP/SSE 请求并立即返回可取消流句柄。
   *
   * @param request 已构造的 JDK HTTP 请求
   * @param timeoutPolicy 模型调用总超时与闲置超时策略
   * @param limits 有界字节限制配置
   * @param callback 流事件与终态回调接收器
   * @return 幂等取消句柄
   * @throws TransportException 当受管执行器拒绝提交任务时同步抛出
   */
  public ProviderStream stream(
      HttpRequest request,
      ModelCallTimeoutPolicy timeoutPolicy,
      HttpSseLimits limits,
      HttpSseCallback callback) {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(timeoutPolicy, "timeoutPolicy must not be null");
    Objects.requireNonNull(limits, "limits must not be null");
    Objects.requireNonNull(callback, "callback must not be null");

    StreamExecution execution =
        new StreamExecution(httpClient, request, timeoutPolicy, limits, callback);
    try {
      Future<?> workerFuture = executor.submit(execution::runWorker);
      execution.setWorkerFuture(workerFuture);
      Future<?> watchdogFuture = executor.submit(execution::runWatchdog);
      execution.setWatchdogFuture(watchdogFuture);
    } catch (RejectedExecutionException error) {
      execution.markRejected();
      throw new TransportException(
          TransportErrorKind.EXECUTOR_REJECTED,
          "Stream task execution was rejected by the executor",
          error);
    }

    return execution;
  }

  private static final class StreamExecution implements ProviderStream {

    private final HttpClient httpClient;
    private final HttpRequest request;
    private final ModelCallTimeoutPolicy timeoutPolicy;
    private final HttpSseLimits limits;
    private final HttpSseCallback callback;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean terminalDispatched = new AtomicBoolean(false);
    private final AtomicLong lastActivityNanos = new AtomicLong();
    private volatile long startTimeNanos;

    private volatile InputStream activeInputStream;
    private volatile CompletableFuture<HttpResponse<InputStream>> sendFuture;
    private volatile Thread workerThread;
    private volatile Future<?> workerFuture;
    private volatile Future<?> watchdogFuture;

    StreamExecution(
        HttpClient httpClient,
        HttpRequest request,
        ModelCallTimeoutPolicy timeoutPolicy,
        HttpSseLimits limits,
        HttpSseCallback callback) {
      this.httpClient = httpClient;
      this.request = request;
      this.timeoutPolicy = timeoutPolicy;
      this.limits = limits;
      this.callback = callback;
    }

    void setWorkerFuture(Future<?> workerFuture) {
      this.workerFuture = workerFuture;
    }

    void setWatchdogFuture(Future<?> watchdogFuture) {
      this.watchdogFuture = watchdogFuture;
    }

    void markRejected() {
      cancelled.set(true);
      terminalDispatched.set(true);
    }

    @Override
    public void cancel() {
      if (cancelled.compareAndSet(false, true)) {
        closeResources();
      }
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    void runWorker() {
      workerThread = Thread.currentThread();
      if (cancelled.get()) {
        return;
      }

      startTimeNanos = System.nanoTime();
      lastActivityNanos.set(startTimeNanos);

      CompletableFuture<HttpResponse<InputStream>> future =
          httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
      this.sendFuture = future;
      if (cancelled.get()) {
        future.cancel(true);
        return;
      }

      HttpResponse<InputStream> response;
      try {
        response = future.get();
      } catch (CancellationException error) {
        if (cancelled.get()) {
          return;
        }
        dispatchFailure(
            new TransportException(TransportErrorKind.CANCELLED, "HTTP request cancelled", error));
        return;
      } catch (InterruptedException error) {
        if (cancelled.get()) {
          return;
        }
        Thread.currentThread().interrupt();
        dispatchFailure(
            new TransportException(
                TransportErrorKind.IO, "HTTP request thread was interrupted", error));
        return;
      } catch (ExecutionException error) {
        if (cancelled.get()) {
          return;
        }
        Throwable cause = error.getCause() == null ? error : error.getCause();
        if (cause instanceof HttpTimeoutException) {
          dispatchFailure(
              new TransportException(
                  TransportErrorKind.TIMEOUT, "HTTP handshake connect/send timed out", cause));
        } else if (cause instanceof IOException) {
          dispatchFailure(
              new TransportException(
                  TransportErrorKind.IO, "HTTP request I/O execution failed", cause));
        } else {
          dispatchFailure(
              new TransportException(
                  TransportErrorKind.IO, "Unexpected failure during HTTP send", cause));
        }
        return;
      } catch (Throwable error) {
        if (cancelled.get()) {
          return;
        }
        dispatchFailure(
            new TransportException(
                TransportErrorKind.IO, "Unexpected failure during HTTP send", error));
        return;
      }

      if (cancelled.get()) {
        closeInputStream(response.body());
        return;
      }
      lastActivityNanos.set(System.nanoTime());

      int statusCode = response.statusCode();
      // 3xx 重定向不跟随，作为非 2xx 处理；4xx/5xx 等同样读取有界错误正文
      if (statusCode < 200 || statusCode >= 300) {
        byte[] errorBody = readBoundedErrorBody(response.body(), limits.maxErrorBodyBytes());
        dispatchFailure(
            new TransportException(
                TransportErrorKind.HTTP_STATUS,
                "Server responded with non-2xx HTTP status " + statusCode,
                statusCode,
                errorBody,
                response.headers().map()));
        return;
      }

      String contentType = response.headers().firstValue("Content-Type").orElse("");
      if (!isValidEventStreamContentType(contentType)) {
        closeInputStream(response.body());
        dispatchFailure(
            new TransportException(
                TransportErrorKind.INVALID_RESPONSE,
                "Invalid response Content-Type, expected text/event-stream"));
        return;
      }

      dispatchOpen(new HttpOpenMetadata(statusCode, response.headers().map()));

      try (InputStream in = response.body()) {
        this.activeInputStream = in;
        if (cancelled.get()) {
          in.close();
          return;
        }
        IncrementalSseParser parser = new IncrementalSseParser(limits, this::dispatchEvent);
        byte[] buffer = new byte[4096];
        int read;
        while (!cancelled.get() && !terminalDispatched.get() && (read = in.read(buffer)) != -1) {
          lastActivityNanos.set(System.nanoTime());
          parser.feed(buffer, 0, read);
        }
        if (!cancelled.get() && !terminalDispatched.get()) {
          parser.flush();
          dispatchComplete();
        }
      } catch (HttpTimeoutException error) {
        if (!cancelled.get() && !terminalDispatched.get()) {
          dispatchFailure(
              new TransportException(
                  TransportErrorKind.TIMEOUT, "HTTP stream read timed out", error));
        }
      } catch (IOException error) {
        if (!cancelled.get() && !terminalDispatched.get()) {
          dispatchFailure(
              new TransportException(
                  TransportErrorKind.IO, "HTTP stream read encountered I/O failure", error));
        }
      } catch (TransportException error) {
        dispatchFailure(error);
      } catch (Throwable error) {
        if (!cancelled.get() && !terminalDispatched.get()) {
          dispatchFailure(
              new TransportException(
                  TransportErrorKind.IO, "Unexpected error during stream read processing", error));
        }
      }
    }

    void runWatchdog() {
      long totalTimeoutNanos = timeoutPolicy.modelCallTimeout().toNanos();
      long idleTimeoutNanos = timeoutPolicy.modelCallIdleTimeout().toNanos();

      while (!cancelled.get() && !terminalDispatched.get()) {
        long now = System.nanoTime();
        long elapsedTotal = now - startTimeNanos;
        if (startTimeNanos > 0 && elapsedTotal >= totalTimeoutNanos) {
          dispatchFailure(
              new TransportException(
                  TransportErrorKind.TIMEOUT, "Model call total timeout exceeded"));
          return;
        }

        long lastActivity = lastActivityNanos.get();
        if (lastActivity > 0) {
          long elapsedIdle = now - lastActivity;
          if (elapsedIdle >= idleTimeoutNanos) {
            dispatchFailure(
                new TransportException(
                    TransportErrorKind.TIMEOUT, "Model call idle timeout exceeded"));
            return;
          }
        }

        long remainingTotal =
            startTimeNanos > 0 ? (totalTimeoutNanos - elapsedTotal) : totalTimeoutNanos;
        long remainingIdle =
            lastActivity > 0 ? (idleTimeoutNanos - (now - lastActivity)) : idleTimeoutNanos;
        long nextCheckNanos = Math.min(remainingTotal, remainingIdle);
        long sleepMillis = Math.max(5, Math.min(100, nextCheckNanos / 1_000_000L));
        try {
          Thread.sleep(sleepMillis);
        } catch (InterruptedException error) {
          return;
        }
      }
    }

    private void dispatchOpen(HttpOpenMetadata metadata) {
      if (cancelled.get() || terminalDispatched.get()) {
        return;
      }
      try {
        callback.onOpen(metadata);
      } catch (Throwable error) {
        log.error("Unhandled exception in onOpen callback", error);
        dispatchFailure(
            new TransportException(
                TransportErrorKind.CALLBACK_FAILED,
                "Unhandled exception in onOpen callback",
                error));
      }
    }

    private void dispatchEvent(ServerSentEvent event) {
      if (cancelled.get() || terminalDispatched.get()) {
        return;
      }
      try {
        callback.onEvent(event);
      } catch (Throwable error) {
        log.error("Unhandled exception in onEvent callback", error);
        dispatchFailure(
            new TransportException(
                TransportErrorKind.CALLBACK_FAILED,
                "Unhandled exception in onEvent callback",
                error));
      }
    }

    private void dispatchComplete() {
      if (cancelled.get()) {
        return;
      }
      if (terminalDispatched.compareAndSet(false, true)) {
        closeResources();
        try {
          callback.onComplete();
        } catch (Throwable error) {
          log.error("Unhandled exception in onComplete callback", error);
        }
      }
    }

    private void dispatchFailure(TransportException error) {
      if (cancelled.get()) {
        return;
      }
      if (terminalDispatched.compareAndSet(false, true)) {
        closeResources();
        try {
          callback.onFailure(error);
        } catch (Throwable errorFromCallback) {
          log.error("Unhandled exception in onFailure callback", errorFromCallback);
        }
      }
    }

    private void closeResources() {
      CompletableFuture<?> sf = sendFuture;
      if (sf != null) {
        sf.cancel(true);
      }

      InputStream in = activeInputStream;
      if (in != null) {
        try {
          in.close();
        } catch (IOException ignored) {
        }
      }

      Future<?> wf = workerFuture;
      if (wf != null) {
        wf.cancel(true);
      }

      Future<?> df = watchdogFuture;
      if (df != null) {
        df.cancel(true);
      }

      Thread wt = workerThread;
      if (wt != null && wt != Thread.currentThread()) {
        wt.interrupt();
      }
    }

    private static byte[] readBoundedErrorBody(InputStream in, int maxBytes) {
      if (in == null) {
        return new byte[0];
      }
      try (in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int r;
        while ((r = in.read(buf)) != -1) {
          int remaining = maxBytes - out.size();
          if (remaining <= 0) {
            break;
          }
          out.write(buf, 0, Math.min(r, remaining));
          if (out.size() >= maxBytes) {
            break;
          }
        }
        return out.toByteArray();
      } catch (IOException ignored) {
        return new byte[0];
      }
    }

    private static void closeInputStream(InputStream in) {
      if (in != null) {
        try {
          in.close();
        } catch (IOException ignored) {
        }
      }
    }

    private static boolean isValidEventStreamContentType(String contentType) {
      if (contentType == null || contentType.isBlank()) {
        return false;
      }
      String[] parts = contentType.split(";");
      String mediaType = parts[0].trim().toLowerCase(Locale.ROOT);
      if (!"text/event-stream".equals(mediaType)) {
        return false;
      }
      for (int i = 1; i < parts.length; i++) {
        String param = parts[i].trim();
        if (param.isEmpty()) {
          continue;
        }
        String[] kv = param.split("=", 2);
        if (kv.length != 2) {
          return false;
        }
        String key = kv[0].trim().toLowerCase(Locale.ROOT);
        String value = kv[1].trim().replace("\"", "").toLowerCase(Locale.ROOT);
        if ("charset".equals(key) && !"utf-8".equals(value)) {
          return false;
        }
      }
      return true;
    }
  }
}
