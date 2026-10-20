package fun.fengwk.kkstudio.platform.environment.cluster;

import fun.fengwk.kkstudio.harness.runtime.port.RealtimePayloadFetcher;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimePayloadLimits;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimePayloadUnavailableException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 跨节点 realtime oversize payload 的最小 JDK HTTP 客户端。
 *
 * <p>目标 origin 只从 {@code cluster_node} 当前有效租约解析，绝不使用 NOTIFY 携带的 URL 或请求 Host。请求使用现有内部 HMAC 认证，GET
 * 路径只含 canonical UUID；响应体与总读取时间都有硬上限，超时/非 200/无效 origin 一律映射为 {@link
 * RealtimePayloadUnavailableException}，绝不重试。返回的 canonical JSON 由调用方再做 Thread 关联校验。
 */
public final class ClusterRealtimePayloadClient implements RealtimePayloadFetcher {

  /** 回拉必须短于实时 LISTEN 可接受的最坏阻塞；覆盖连接、响应头与 body 读取。 */
  public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(2);

  private final HttpClient httpClient;
  private final ClusterInternalAuthentication authentication;
  private final ClusterNodeRegistry nodeRegistry;
  private final Duration requestTimeout;
  private final int maxResponseBytes;

  public ClusterRealtimePayloadClient(
      HttpClient httpClient,
      ClusterInternalAuthentication authentication,
      ClusterNodeRegistry nodeRegistry) {
    this(
        httpClient,
        authentication,
        nodeRegistry,
        DEFAULT_REQUEST_TIMEOUT,
        RealtimePayloadLimits.MAX_EVENT_BYTES);
  }

  public ClusterRealtimePayloadClient(
      HttpClient httpClient,
      ClusterInternalAuthentication authentication,
      ClusterNodeRegistry nodeRegistry,
      Duration requestTimeout,
      int maxResponseBytes) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.authentication = Objects.requireNonNull(authentication, "authentication");
    this.nodeRegistry = Objects.requireNonNull(nodeRegistry, "nodeRegistry");
    this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    if (requestTimeout.isZero() || requestTimeout.isNegative()) {
      throw new IllegalArgumentException("requestTimeout must be positive");
    }
    if (maxResponseBytes <= 0) {
      throw new IllegalArgumentException("maxResponseBytes must be positive");
    }
    this.maxResponseBytes = maxResponseBytes;
  }

  /** 回拉一条 canonical event JSON；任何失败都是明确 unavailable，不返回截断内容。 */
  @Override
  public String fetch(UUID originNodeId, UUID threadId, UUID payloadId) {
    Objects.requireNonNull(originNodeId, "originNodeId");
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(payloadId, "payloadId");
    URI origin =
        nodeRegistry
            .liveInternalOrigin(originNodeId)
            .orElseThrow(
                () ->
                    new RealtimePayloadUnavailableException(
                        "realtime payload origin has no live cluster node lease"));
    String path = ClusterInternalPaths.realtimePayloadPath(payloadId);
    URI uri = resolveStrictly(origin, path);
    Map<String, String> authHeaders = authentication.sign("GET", path, originNodeId);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("Accept", "application/json")
            .GET();
    authHeaders.forEach(builder::header);
    HttpRequest request = builder.build();

    BoundedBodySubscriber subscriber =
        new BoundedBodySubscriber(
            maxResponseBytes,
            () ->
                new RealtimePayloadUnavailableException(
                    "realtime payload response exceeds the event limit"));
    CompletableFuture<HttpResponse<byte[]>> responseFuture =
        httpClient.sendAsync(request, responseInfo -> subscriber);
    long deadlineNanos = System.nanoTime() + requestTimeout.toNanos();
    try {
      HttpResponse<byte[]> response = await(responseFuture, deadlineNanos);
      if (response.statusCode() != 200) {
        throw new RealtimePayloadUnavailableException(
            "realtime payload origin returned status " + response.statusCode());
      }
      byte[] body = await(subscriber.bodyFuture(), deadlineNanos);
      return new String(body, StandardCharsets.UTF_8);
    } catch (TimeoutException error) {
      throw new RealtimePayloadUnavailableException("realtime payload request timed out", error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new RealtimePayloadUnavailableException(
          "realtime payload request was interrupted", error);
    } catch (ExecutionException error) {
      Throwable cause = error.getCause();
      if (cause instanceof RealtimePayloadUnavailableException unavailable) {
        throw unavailable;
      }
      throw new RealtimePayloadUnavailableException(
          "realtime payload origin is unavailable", cause);
    } finally {
      // 响应头尚未到达时 subscriber 还没有 subscription；取消 exchange 本身才能立即释放在途请求。
      responseFuture.cancel(true);
      subscriber.cancel();
    }
  }

  /** origin 必须已经是裸 http(s) origin；解析后的 authority/path 也必须是固定内部路径。 */
  private static URI resolveStrictly(URI origin, String path) {
    String scheme = origin.getScheme();
    if (scheme == null
        || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
        || origin.getHost() == null
        || origin.getUserInfo() != null
        || origin.getQuery() != null
        || origin.getFragment() != null
        || (origin.getPath() != null
            && !origin.getPath().isEmpty()
            && !origin.getPath().equals("/"))) {
      throw new RealtimePayloadUnavailableException("realtime payload origin is not trusted");
    }
    URI resolved = origin.resolve(path);
    if (!scheme.equalsIgnoreCase(resolved.getScheme())
        || !origin.getAuthority().equalsIgnoreCase(resolved.getAuthority())
        || !path.equals(resolved.getRawPath())
        || resolved.getRawQuery() != null
        || resolved.getRawFragment() != null) {
      throw new RealtimePayloadUnavailableException("realtime payload request path is invalid");
    }
    return resolved;
  }

  private static <T> T await(CompletableFuture<T> future, long deadlineNanos)
      throws TimeoutException, InterruptedException, ExecutionException {
    long remaining = deadlineNanos - System.nanoTime();
    if (remaining <= 0) {
      throw new TimeoutException("realtime payload deadline exceeded");
    }
    return future.get(remaining, TimeUnit.NANOSECONDS);
  }
}
