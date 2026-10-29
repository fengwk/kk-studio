package fun.fengwk.kkstudio.harness.provider.transport;

import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * 基于 JDK {@link HttpClient} 的阻塞式安全有界 SSE 传输器。
 *
 * <p>{@link #execute} 在调用线程上完成整个请求：发送请求、校验响应、增量解析 SSE 并把每个事件同步交给调用方，返回即代表流已读完或被调用方 主动停止，失败一律以
 * {@link TransportException} 抛出。调用方通过中断线程取消调用，传输器随之关闭响应体并抛出 {@link TransportErrorKind#CANCELLED}。
 *
 * <p>安全契约：
 *
 * <ul>
 *   <li>构造时强制拒绝跟随重定向（{@code HttpClient.Redirect.NEVER}）。
 *   <li>总时长与无活动闲置时长由 watchdog 监督：响应头阶段交给 {@link HttpRequest#timeout}，响应体阶段由单个定时任务在到期时关闭响应体；
 *       响应头与响应体都以 {@link TransportErrorKind#TIMEOUT} 失败。
 *   <li>无论成功、失败、取消还是回调抛出异常，响应体都会关闭；非 2xx 响应体按 {@link HttpSseLimits#maxErrorBodyBytes()} 截断读取。
 *   <li>异常的 message 与 cause 链不含 request URI、凭据、请求体或远端错误体。
 * </ul>
 */
public class JdkHttpSseTransport {

  private final HttpClient httpClient;
  private final ScheduledExecutorService watchdogScheduler;

  public JdkHttpSseTransport(HttpClient httpClient, ScheduledExecutorService watchdogScheduler) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    this.watchdogScheduler =
        Objects.requireNonNull(watchdogScheduler, "watchdogScheduler must not be null");
    if (httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
      throw new IllegalArgumentException("HttpClient must be configured with Redirect.NEVER");
    }
  }

  /**
   * 阻塞执行一次 HTTP/SSE 请求。
   *
   * @param request 已构造的 JDK HTTP 请求
   * @param timeoutPolicy 模型调用总超时与闲置超时策略
   * @param limits 有界字节限制配置
   * @param onEvent 在调用线程上同步接收每个 SSE 事件；返回 {@code false} 表示调用方已不再需要后续事件，传输器立即关闭响应并正常返回
   * @throws TransportException 传输失败、超时、协议非法或被中断；回调自身抛出的运行时异常原样传播
   */
  public void execute(
      HttpRequest request,
      ModelCallTimeoutPolicy timeoutPolicy,
      HttpSseLimits limits,
      Predicate<ServerSentEvent> onEvent) {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(timeoutPolicy, "timeoutPolicy must not be null");
    Objects.requireNonNull(limits, "limits must not be null");
    Objects.requireNonNull(onEvent, "onEvent must not be null");

    Watchdog watchdog = new Watchdog(timeoutPolicy, watchdogScheduler);
    try {
      HttpResponse<InputStream> response = send(request, timeoutPolicy);
      InputStream body = response.body();
      try {
        watchdog.watch(body);
        consume(response, body, limits, watchdog, onEvent);
      } finally {
        closeQuietly(body);
      }
    } finally {
      watchdog.finish();
    }
  }

  private HttpResponse<InputStream> send(HttpRequest request, ModelCallTimeoutPolicy policy) {
    Duration headerTimeout =
        policy.modelCallTimeout().compareTo(policy.modelCallIdleTimeout()) <= 0
            ? policy.modelCallTimeout()
            : policy.modelCallIdleTimeout();
    HttpRequest timed =
        HttpRequest.newBuilder(request, (name, value) -> true).timeout(headerTimeout).build();
    try {
      return httpClient.send(timed, HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TransportException(TransportErrorKind.CANCELLED, "Request interrupted", e);
    } catch (HttpTimeoutException e) {
      // 请求 timeout 就是响应头期限：连接阶段到期（HttpConnectTimeoutException）同样属于超时。
      throw new TransportException(
          TransportErrorKind.TIMEOUT, "Timed out waiting for response headers", e);
    } catch (IOException e) {
      throw new TransportException(TransportErrorKind.IO, "Connection or network I/O failed", e);
    }
  }

  private static void consume(
      HttpResponse<InputStream> response,
      InputStream body,
      HttpSseLimits limits,
      Watchdog watchdog,
      Predicate<ServerSentEvent> onEvent) {
    int statusCode = response.statusCode();
    Map<String, List<String>> headers = response.headers().map();
    if (statusCode < 200 || statusCode >= 300) {
      BoundedErrorResult error = readBoundedErrorBody(body, limits.maxErrorBodyBytes(), watchdog);
      watchdog.throwIfExpired();
      throw new TransportException(
          TransportErrorKind.HTTP_STATUS,
          "HTTP " + statusCode + " response received",
          statusCode,
          error.bodyBytes(),
          error.truncated(),
          headers,
          null);
    }
    String contentType = response.headers().firstValue("Content-Type").orElse("");
    if (!isValidEventStreamContentType(contentType)) {
      readBoundedErrorBody(body, limits.maxErrorBodyBytes(), watchdog);
      throw new TransportException(
          TransportErrorKind.INVALID_RESPONSE,
          "Invalid Content-Type for SSE stream; expected text/event-stream",
          statusCode,
          null,
          headers,
          null);
    }

    IncrementalSseParser parser =
        new IncrementalSseParser(
            limits,
            event -> {
              if (!onEvent.test(event)) {
                throw new StopReading();
              }
            });
    byte[] buffer = new byte[8192];
    try {
      int read;
      while ((read = body.read(buffer)) != -1) {
        watchdog.touch();
        parser.feed(buffer, 0, read);
      }
      // 响应体被 watchdog 关闭时可能表现为 EOF，不能把被截断的流当成正常结束。
      watchdog.throwIfExpired();
      parser.flush();
    } catch (StopReading stop) {
      // 调用方已不再需要后续事件：随后在 finally 中关闭响应体。
    } catch (IOException e) {
      watchdog.throwIfExpired();
      if (Thread.currentThread().isInterrupted()) {
        throw new TransportException(TransportErrorKind.CANCELLED, "Stream read interrupted", e);
      }
      throw new TransportException(
          TransportErrorKind.IO, "I/O failure while reading SSE stream", e);
    }
  }

  record BoundedErrorResult(byte[] bodyBytes, boolean truncated) {}

  private static BoundedErrorResult readBoundedErrorBody(
      InputStream in, int max, Watchdog watchdog) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[1024];
    long totalRead = 0L;
    boolean truncated = false;
    long targetLimit = (long) max + 1L;
    try {
      int r;
      // 读取上限为 targetLimit，采用 long 运算杜绝 max == Integer.MAX_VALUE 时的整型溢出
      while ((r = in.read(buf, 0, (int) Math.min((long) buf.length, targetLimit - totalRead)))
          != -1) {
        watchdog.touch();
        totalRead += r;
        if (totalRead > (long) max) {
          int keep = (int) Math.max(0L, Math.min((long) r, (long) r - (totalRead - max)));
          out.write(buf, 0, keep);
          truncated = true;
          break;
        }
        out.write(buf, 0, r);
      }
    } catch (IOException ignored) {
      // 错误体只是诊断信息：读取失败时保留已读部分，状态码本身仍然决定失败。
    }
    return new BoundedErrorResult(out.toByteArray(), truncated);
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

  private static void closeQuietly(InputStream in) {
    try {
      in.close();
    } catch (IOException ignored) {
      // 关闭失败无可恢复动作。
    }
  }

  /** 调用方通过事件回调要求停止读取的内部信号。 */
  private static final class StopReading extends RuntimeException {
    private StopReading() {
      super(null, null, false, false);
    }
  }

  /**
   * 响应体阶段的总时长 / 闲置时长监督：一个自重排的定时任务，读取只更新 {@code lastActivityNano}；到期时关闭响应体让阻塞中的读取立即返回。
   * 定时任务只负责排期，关闭动作交给短命虚拟线程，避免在共享调度器上阻塞。
   */
  private static final class Watchdog {

    private final ScheduledExecutorService scheduler;
    private final ModelCallTimeoutPolicy policy;
    private final long totalNanos;
    private final long idleNanos;
    private final long startNano = System.nanoTime();
    private volatile long lastActivityNano = startNano;
    private volatile String expiredReason;
    private InputStream stream;
    private ScheduledFuture<?> future;
    private boolean finished;

    private Watchdog(ModelCallTimeoutPolicy policy, ScheduledExecutorService scheduler) {
      this.policy = policy;
      this.scheduler = scheduler;
      this.totalNanos = policy.modelCallTimeout().toNanos();
      this.idleNanos = policy.modelCallIdleTimeout().toNanos();
    }

    synchronized void watch(InputStream body) {
      stream = body;
      schedule();
    }

    void touch() {
      lastActivityNano = System.nanoTime();
    }

    synchronized void finish() {
      finished = true;
      if (future != null) {
        future.cancel(false);
      }
    }

    void throwIfExpired() {
      String reason = expiredReason;
      if (reason != null) {
        throw new TransportException(TransportErrorKind.TIMEOUT, reason);
      }
    }

    /** 持有 this 锁调用。 */
    private void schedule() {
      if (!finished) {
        future = scheduler.schedule(this::fire, nextDelayNanos(), TimeUnit.NANOSECONDS);
      }
    }

    // 以已流逝时间比较，nanoTime 回绕时仍不会溢出。
    private long nextDelayNanos() {
      long now = System.nanoTime();
      long totalRemaining = totalNanos - (now - startNano);
      long idleRemaining = idleNanos - (now - lastActivityNano);
      return Math.max(0L, Math.min(totalRemaining, idleRemaining));
    }

    private void fire() {
      InputStream toClose;
      synchronized (this) {
        if (finished) {
          return;
        }
        if (nextDelayNanos() > 0) {
          schedule();
          return;
        }
        boolean totalExceeded = System.nanoTime() - startNano >= totalNanos;
        expiredReason =
            totalExceeded
                ? "Total call duration exceeded " + policy.modelCallTimeout()
                : "Stream idle timeout exceeded " + policy.modelCallIdleTimeout();
        finished = true;
        toClose = stream;
      }
      Thread.ofVirtual().start(() -> closeQuietly(toClose));
    }
  }
}
