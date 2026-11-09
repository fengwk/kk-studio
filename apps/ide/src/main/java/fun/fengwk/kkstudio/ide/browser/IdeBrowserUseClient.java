package fun.fengwk.kkstudio.ide.browser;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.cluster.BoundedBodySubscriber;
import fun.fengwk.kkstudio.platform.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.cluster.ClusterInternalErrors;
import fun.fengwk.kkstudio.platform.cluster.ClusterInternalErrors.Problem;
import fun.fengwk.kkstudio.platform.cluster.ClusterNodeRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * IDE claim/lease 服务查询浏览器使用登记的窄客户端。
 *
 * <p>eventNodeId 等于本节点时直接读本地 {@link IdeBrowserUseRegistry}；否则只用 {@link
 * ClusterNodeRegistry#liveInternalOrigin} 解析目标事件节点，绝不接受调用方提供的任意
 * origin。每次查询至多一次认证请求，失败不重试；响应体与总读取时间都有硬上限。
 */
public final class IdeBrowserUseClient {

  public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final ClusterInternalAuthentication authentication;
  private final ClusterNodeRegistry nodeRegistry;
  private final IdeBrowserUseRegistry localRegistry;
  private final UUID localNodeId;
  private final Duration requestTimeout;
  private final int maxResponseBytes;

  public IdeBrowserUseClient(
      HttpClient httpClient,
      ObjectMapper objectMapper,
      ClusterInternalAuthentication authentication,
      ClusterNodeRegistry nodeRegistry,
      IdeBrowserUseRegistry localRegistry,
      UUID localNodeId) {
    this(
        httpClient,
        objectMapper,
        authentication,
        nodeRegistry,
        localRegistry,
        localNodeId,
        DEFAULT_REQUEST_TIMEOUT,
        IdeBrowserUsePaths.MAX_BODY_BYTES);
  }

  public IdeBrowserUseClient(
      HttpClient httpClient,
      ObjectMapper objectMapper,
      ClusterInternalAuthentication authentication,
      ClusterNodeRegistry nodeRegistry,
      IdeBrowserUseRegistry localRegistry,
      UUID localNodeId,
      Duration requestTimeout,
      int maxResponseBytes) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.authentication = Objects.requireNonNull(authentication, "authentication");
    this.nodeRegistry = Objects.requireNonNull(nodeRegistry, "nodeRegistry");
    this.localRegistry = Objects.requireNonNull(localRegistry, "localRegistry");
    this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
    this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    if (requestTimeout.isZero() || requestTimeout.isNegative()) {
      throw new IllegalArgumentException("requestTimeout must be positive");
    }
    if (maxResponseBytes <= 0) {
      throw new IllegalArgumentException("maxResponseBytes must be positive");
    }
    this.maxResponseBytes = maxResponseBytes;
  }

  /** 精确查询该连接/browser session 是否仍存活并登记了相同的 environment/view/claim 意图。 */
  public boolean findExactIntent(
      ConnectionIdentity identity,
      UUID browserSessionId,
      EnvironmentId environmentId,
      UUID viewId,
      UUID claimId) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(browserSessionId, "browserSessionId");
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(claimId, "claimId");
    if (isLocal(identity)) {
      return localRegistry
          .findExactIntent(identity, browserSessionId, environmentId, viewId, claimId)
          .isPresent();
    }
    IdeBrowserUseIntentQuery query =
        IdeBrowserUseIntentQuery.of(identity, browserSessionId, environmentId, viewId, claimId);
    String path = IdeBrowserUsePaths.intentPath();
    byte[] payload = writeJson(query);
    Response response = exchange("POST", path, identity.eventNodeId(), payload);
    if (response.status() != 200) {
      throw unavailableStatus(response);
    }
    return readJson(response.body(), IdeBrowserUseIntentReply.class).matched();
  }

  /** 返回该连接/browser session 当前可续租的已绑定持有项；没有时返回空，不允许把缺失当成传输成功。 */
  public Optional<IdeBrowserUse> activeBoundProofs(
      ConnectionIdentity identity, UUID browserSessionId) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(browserSessionId, "browserSessionId");
    if (isLocal(identity)) {
      return localRegistry.activeBoundProofs(identity, browserSessionId);
    }
    String path = IdeBrowserUsePaths.activeProofsPath(identity, browserSessionId);
    Response response = exchange("GET", path, identity.eventNodeId(), null);
    Problem problem = ClusterInternalErrors.parse(objectMapper, response.body());
    if (response.status() == 404 && "not_found".equals(problem.code())) {
      return Optional.empty();
    }
    if (response.status() != 200) {
      throw unavailableStatus(response);
    }
    IdeBrowserUseSnapshot snapshot = readJson(response.body(), IdeBrowserUseSnapshot.class);
    if (!snapshot.eventNodeId().equals(identity.eventNodeId().toString())
        || !snapshot.connectionId().equals(identity.connectionId().toString())
        || !snapshot.browserSessionId().equals(browserSessionId.toString())) {
      throw DomainException.unavailable(
          "IDE_UNAVAILABLE", "browser event node returned a mismatched usage snapshot");
    }
    try {
      return Optional.of(snapshot.toUsage());
    } catch (IllegalArgumentException error) {
      throw DomainException.unavailable(
          "IDE_UNAVAILABLE", "browser event node returned an invalid usage snapshot", error);
    }
  }

  private boolean isLocal(ConnectionIdentity identity) {
    return identity.eventNodeId().equals(localNodeId);
  }

  private Response exchange(String method, String path, UUID eventNodeId, byte[] payload) {
    URI origin =
        nodeRegistry
            .liveInternalOrigin(eventNodeId)
            .orElseThrow(
                () ->
                    DomainException.unavailable(
                        "IDE_UNAVAILABLE", "browser event node has no live cluster lease"));
    URI uri = resolveStrictly(origin, path);
    Map<String, String> authHeaders = authentication.sign(method, path, eventNodeId);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri).timeout(requestTimeout).header("Accept", "application/json");
    if (payload == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder.header("Content-Type", "application/json");
      builder.method(method, HttpRequest.BodyPublishers.ofByteArray(payload));
    }
    authHeaders.forEach(builder::header);
    HttpRequest request = builder.build();

    BoundedBodySubscriber subscriber =
        new BoundedBodySubscriber(
            maxResponseBytes,
            () ->
                DomainException.unavailable(
                    "IDE_UNAVAILABLE", "browser event node response exceeds the metadata limit"));
    CompletableFuture<HttpResponse<byte[]>> responseFuture =
        httpClient.sendAsync(request, responseInfo -> subscriber);
    long deadlineNanos = System.nanoTime() + requestTimeout.toNanos();
    try {
      HttpResponse<byte[]> response = await(responseFuture, deadlineNanos);
      byte[] body = await(subscriber.bodyFuture(), deadlineNanos);
      return new Response(response.statusCode(), body);
    } catch (TimeoutException error) {
      throw DomainException.unavailable(
          "IDE_UNAVAILABLE", "browser event node request timed out", error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw DomainException.unavailable(
          "IDE_UNAVAILABLE", "browser event node request was interrupted", error);
    } catch (ExecutionException error) {
      Throwable cause = error.getCause();
      if (cause instanceof DomainException unavailable
          && unavailable.code() == DomainErrorCode.UNAVAILABLE
          && "IDE_UNAVAILABLE".equals(unavailable.reason())) {
        throw unavailable;
      }
      throw DomainException.unavailable(
          "IDE_UNAVAILABLE", "browser event node is unreachable", cause);
    } finally {
      responseFuture.cancel(true);
      subscriber.cancel();
    }
  }

  /** origin 必须是裸 http(s) origin；解析结果只能增加固定的内部 path/query。 */
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
      throw DomainException.unavailable(
          "IDE_UNAVAILABLE", "browser event node origin is not trusted");
    }
    URI resolved = origin.resolve(path);
    int queryAt = path.indexOf('?');
    String rawPath = queryAt < 0 ? path : path.substring(0, queryAt);
    String rawQuery = queryAt < 0 ? null : path.substring(queryAt + 1);
    if (!scheme.equalsIgnoreCase(resolved.getScheme())
        || !origin.getAuthority().equalsIgnoreCase(resolved.getAuthority())
        || !rawPath.equals(resolved.getRawPath())
        || !Objects.equals(rawQuery, resolved.getRawQuery())
        || resolved.getRawFragment() != null) {
      throw DomainException.unavailable(
          "IDE_UNAVAILABLE", "browser event node request path is invalid");
    }
    return resolved;
  }

  private byte[] writeJson(Object value) {
    try {
      return objectMapper.writeValueAsBytes(value);
    } catch (JacksonException error) {
      throw DomainException.unavailable(
          "IDE_UNAVAILABLE", "browser event node request could not be encoded", error);
    }
  }

  private <T> T readJson(byte[] body, Class<T> type) {
    try {
      return objectMapper.readValue(new String(body, StandardCharsets.UTF_8), type);
    } catch (JacksonException | IllegalArgumentException error) {
      throw DomainException.unavailable(
          "IDE_UNAVAILABLE", "browser event node returned an invalid response", error);
    }
  }

  private static <T> T await(CompletableFuture<T> future, long deadlineNanos)
      throws TimeoutException, InterruptedException, ExecutionException {
    long remaining = deadlineNanos - System.nanoTime();
    if (remaining <= 0) {
      throw new TimeoutException("browser event node deadline exceeded");
    }
    return future.get(remaining, TimeUnit.NANOSECONDS);
  }

  private DomainException unavailableStatus(Response response) {
    Problem problem = ClusterInternalErrors.parse(objectMapper, response.body());
    if (response.status() == 400 && "validation".equals(problem.code())) {
      return DomainException.unavailable(
          "IDE_UNAVAILABLE",
          problem.detail() == null
              ? "browser event node rejected the usage query"
              : problem.detail());
    }
    return DomainException.unavailable(
        "IDE_UNAVAILABLE", "browser event node returned status " + response.status());
  }

  private record Response(int status, byte[] body) {}
}
