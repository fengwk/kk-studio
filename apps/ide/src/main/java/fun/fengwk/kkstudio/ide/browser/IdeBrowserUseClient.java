package fun.fengwk.kkstudio.ide.browser;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.cluster.BoundedBodySubscriber;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.error.AiEnvironmentIdeUnavailableException;

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

  /** 精确查询该连接/document 是否仍存活并登记了相同的 environment/view/claim 意图。 */
  public boolean findExactIntent(
      ConnectionIdentity identity,
      UUID documentId,
      EnvironmentId environmentId,
      UUID viewId,
      UUID claimId) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(claimId, "claimId");
    if (isLocal(identity)) {
      return localRegistry
          .findExactIntent(identity, documentId, environmentId, viewId, claimId)
          .isPresent();
    }
    IdeBrowserUseIntentQuery query =
        IdeBrowserUseIntentQuery.of(identity, documentId, environmentId, viewId, claimId);
    String path = IdeBrowserUsePaths.intentPath();
    byte[] payload = writeJson(query);
    Response response = exchange("POST", path, identity.eventNodeId(), payload);
    if (response.status() != 200) {
      throw unavailableStatus(response.status());
    }
    return readJson(response.body(), IdeBrowserUseIntentReply.class).matched();
  }

  /** 返回该连接/document 当前可续租的已绑定持有项；没有时返回空，不允许把缺失当成传输成功。 */
  public Optional<IdeBrowserUse> activeBoundProofs(ConnectionIdentity identity, UUID documentId) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(documentId, "documentId");
    if (isLocal(identity)) {
      return localRegistry.activeBoundProofs(identity, documentId);
    }
    String path = IdeBrowserUsePaths.activeProofsPath(identity, documentId);
    Response response = exchange("GET", path, identity.eventNodeId(), null);
    if (response.status() == 404) {
      return Optional.empty();
    }
    if (response.status() != 200) {
      throw unavailableStatus(response.status());
    }
    IdeBrowserUseSnapshot snapshot = readJson(response.body(), IdeBrowserUseSnapshot.class);
    if (!snapshot.eventNodeId().equals(identity.eventNodeId().toString())
        || !snapshot.connectionId().equals(identity.connectionId().toString())
        || !snapshot.documentId().equals(documentId.toString())) {
      throw new AiEnvironmentIdeUnavailableException(
          "browser event node returned a mismatched usage snapshot");
    }
    try {
      return Optional.of(snapshot.toUsage());
    } catch (IllegalArgumentException error) {
      throw new AiEnvironmentIdeUnavailableException(
          "browser event node returned an invalid usage snapshot", error);
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
                    new AiEnvironmentIdeUnavailableException(
                        "browser event node has no live cluster lease"));
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
                new AiEnvironmentIdeUnavailableException(
                    "browser event node response exceeds the metadata limit"));
    CompletableFuture<HttpResponse<byte[]>> responseFuture =
        httpClient.sendAsync(request, responseInfo -> subscriber);
    long deadlineNanos = System.nanoTime() + requestTimeout.toNanos();
    try {
      HttpResponse<byte[]> response = await(responseFuture, deadlineNanos);
      byte[] body = await(subscriber.bodyFuture(), deadlineNanos);
      return new Response(response.statusCode(), body);
    } catch (TimeoutException error) {
      throw new AiEnvironmentIdeUnavailableException("browser event node request timed out", error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AiEnvironmentIdeUnavailableException(
          "browser event node request was interrupted", error);
    } catch (ExecutionException error) {
      Throwable cause = error.getCause();
      if (cause instanceof AiEnvironmentIdeUnavailableException unavailable) {
        throw unavailable;
      }
      throw new AiEnvironmentIdeUnavailableException("browser event node is unreachable", cause);
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
      throw new AiEnvironmentIdeUnavailableException("browser event node origin is not trusted");
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
      throw new AiEnvironmentIdeUnavailableException("browser event node request path is invalid");
    }
    return resolved;
  }

  private byte[] writeJson(Object value) {
    try {
      return objectMapper.writeValueAsBytes(value);
    } catch (JacksonException error) {
      throw new AiEnvironmentIdeUnavailableException(
          "browser event node request could not be encoded", error);
    }
  }

  private <T> T readJson(byte[] body, Class<T> type) {
    try {
      return objectMapper.readValue(new String(body, StandardCharsets.UTF_8), type);
    } catch (JacksonException | IllegalArgumentException error) {
      throw new AiEnvironmentIdeUnavailableException(
          "browser event node returned an invalid response", error);
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

  private static AiEnvironmentIdeUnavailableException unavailableStatus(int status) {
    if (status == 400) {
      return new AiEnvironmentIdeUnavailableException(
          "browser event node rejected the usage query");
    }
    return new AiEnvironmentIdeUnavailableException("browser event node returned status " + status);
  }

  private record Response(int status, byte[] body) {}
}
