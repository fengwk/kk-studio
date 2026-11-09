package fun.fengwk.kkstudio.ide.cluster;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.uri.UrlEncoding;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeCloseViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeDirectoryListingDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeReadViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewBindingDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewSnapshotDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewsDTO;
import fun.fengwk.kkstudio.platform.cluster.BoundedBodySubscriber;
import fun.fengwk.kkstudio.platform.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.cluster.ClusterInternalErrors;
import fun.fengwk.kkstudio.platform.cluster.ClusterInternalErrors.Problem;
import fun.fengwk.kkstudio.platform.cluster.ClusterOwnerRoute;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 跨节点 IDE 元数据命令的简洁 HTTP 客户端：只承载小 metadata JSON（view 命令与 ViewDTO 回执），绝不传输环境字节。
 *
 * <p>每次调用最多一次认证 HTTP 请求，失败即上抛，绝不自动重放。响应体交给私有的有界 {@link HttpResponse.BodySubscriber}
 * 读取：按到达字节数真正计数，一旦超过上限就 {@code subscription.cancel()} 并让 completion 异常完成， 除等待用户确认的普通关闭外，以 {@code
 * requestTimeout} 作为覆盖响应体的硬截止，取消订阅并关闭连接，绝不整包无界读取。大字节数据面（HTTP/WS 逐 exchange）由 gateway 宿主复用 {@link
 * #signHeaders} 与既有流式客户端实现，不经过本类。
 */
public class IdeClusterClient {

  public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private static final int DEFAULT_MAX_RESPONSE_BYTES = 64 * 1024;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final ClusterInternalAuthentication authentication;
  private final Duration requestTimeout;
  private final int maxResponseBytes;

  public IdeClusterClient(
      HttpClient httpClient,
      ObjectMapper objectMapper,
      ClusterInternalAuthentication authentication) {
    this(
        httpClient,
        objectMapper,
        authentication,
        DEFAULT_REQUEST_TIMEOUT,
        DEFAULT_MAX_RESPONSE_BYTES);
  }

  public IdeClusterClient(
      HttpClient httpClient,
      ObjectMapper objectMapper,
      ClusterInternalAuthentication authentication,
      Duration requestTimeout,
      int maxResponseBytes) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.authentication = Objects.requireNonNull(authentication, "authentication");
    this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    if (maxResponseBytes <= 0) {
      throw new IllegalArgumentException("maxResponseBytes must be positive");
    }
    this.maxResponseBytes = maxResponseBytes;
  }

  /**
   * 暴露发往目标 owner 的内部请求认证头，供 gateway 宿主直接用既有流式客户端发送反向数据面请求。
   *
   * @param method HTTP 方法
   * @param requestPath 已编码的 canonical path（含 query），如 {@code
   *     /internal/ide/environments/{env}/data-http?path=...}
   * @param owner 目标 owner 路由
   * @return 认证头
   */
  public Map<String, String> signHeaders(
      String method, String requestPath, ClusterOwnerRoute owner) {
    return authentication.sign(method, requestPath, owner.nodeInstanceId());
  }

  public EnvironmentIdeViewDTO openView(
      EnvironmentId environmentId, ClusterOwnerRoute owner, EnvironmentIdeOpenViewDTO request) {
    return exchange(
        "POST",
        IdeInternalPaths.viewsPath(environmentId),
        owner,
        request,
        EnvironmentIdeViewDTO.class);
  }

  public EnvironmentIdeViewDTO changeDirectory(
      EnvironmentId environmentId,
      ClusterOwnerRoute owner,
      String viewId,
      EnvironmentIdeChangeDirectoryDTO request) {
    return exchange(
        "PUT",
        IdeInternalPaths.directoryPath(environmentId, viewId),
        owner,
        request,
        EnvironmentIdeViewDTO.class);
  }

  public EnvironmentIdeViewsDTO listViews(EnvironmentId environmentId, ClusterOwnerRoute owner) {
    return exchange(
        "GET",
        IdeInternalPaths.viewsPath(environmentId),
        owner,
        null,
        EnvironmentIdeViewsDTO.class);
  }

  public EnvironmentIdeViewSnapshotDTO readView(
      EnvironmentId environmentId,
      ClusterOwnerRoute owner,
      String viewId,
      EnvironmentIdeReadViewDTO request) {
    return exchange(
        "GET",
        IdeInternalPaths.viewPath(environmentId, viewId, request),
        owner,
        null,
        EnvironmentIdeViewSnapshotDTO.class,
        true,
        false);
  }

  public void focusTerminal(
      EnvironmentId environmentId,
      ClusterOwnerRoute owner,
      String viewId,
      EnvironmentIdeViewBindingDTO request) {
    exchange("POST", IdeInternalPaths.terminalPath(environmentId, viewId), owner, request, null);
  }

  public void collapseMobile(
      EnvironmentId environmentId,
      ClusterOwnerRoute owner,
      String viewId,
      EnvironmentIdeViewBindingDTO request) {
    exchange(
        "POST", IdeInternalPaths.mobileLayoutPath(environmentId, viewId), owner, request, null);
  }

  public EnvironmentIdeDirectoryListingDTO listDirectories(
      EnvironmentId environmentId, ClusterOwnerRoute owner, String path) {
    String target = IdeInternalPaths.environmentPrefix(environmentId) + "/directories";
    if (path != null) {
      target += "?path=" + UrlEncoding.encode(path);
    }
    return exchange("GET", target, owner, null, EnvironmentIdeDirectoryListingDTO.class);
  }

  public void closeView(
      EnvironmentId environmentId,
      ClusterOwnerRoute owner,
      String viewId,
      EnvironmentIdeCloseViewDTO request) {
    exchange(
        "POST",
        IdeInternalPaths.closePath(environmentId, viewId),
        owner,
        request,
        null,
        request.isDiscard());
  }

  private <T> T exchange(
      String method, String path, ClusterOwnerRoute owner, Object body, Class<T> type) {
    return exchange(method, path, owner, body, type, true, true);
  }

  private <T> T exchange(
      String method,
      String path,
      ClusterOwnerRoute owner,
      Object body,
      Class<T> type,
      boolean boundedTime) {
    return exchange(method, path, owner, body, type, boundedTime, true);
  }

  private <T> T exchange(
      String method,
      String path,
      ClusterOwnerRoute owner,
      Object body,
      Class<T> type,
      boolean boundedTime,
      boolean commandMayHaveExecuted) {
    URI uri = owner.internalUrl().resolve(path);
    Map<String, String> authHeaders = authentication.sign(method, path, owner.nodeInstanceId());
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri).header("Accept", "application/json");
    if (boundedTime) {
      builder.timeout(requestTimeout);
    }
    HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
    if (body != null) {
      byte[] payload = writeRequestJson(body);
      builder.header("Content-Type", "application/json");
      publisher = HttpRequest.BodyPublishers.ofByteArray(payload);
    }
    authHeaders.forEach(builder::header);
    HttpRequest request = builder.method(method, publisher).build();

    BoundedBodySubscriber subscriber =
        new BoundedBodySubscriber(
            maxResponseBytes,
            () ->
                DomainException.unavailable(
                    "IDE_UNAVAILABLE", "cluster IDE owner response exceeds the metadata limit"));
    CompletableFuture<byte[]> bodyFuture = subscriber.bodyFuture();
    CompletableFuture<HttpResponse<byte[]>> responseFuture =
        httpClient.sendAsync(request, responseInfo -> subscriber);
    // requestTimeout 覆盖响应体读取：超时使 subscriber future 异常完成，whenComplete 触发
    // subscription.cancel() 并关闭 HTTP 连接；get(timeout) 作为线程不被无限挂起的硬兜底。
    bodyFuture.whenComplete(
        (value, error) -> {
          if (error != null) {
            subscriber.cancel();
          }
        });
    if (boundedTime) {
      bodyFuture.orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    HttpResponse<byte[]> response;
    try {
      response =
          boundedTime
              ? responseFuture.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
              : responseFuture.get();
    } catch (TimeoutException error) {
      subscriber.cancel();
      responseFuture.cancel(true);
      throw transportFailure("cluster IDE owner response timed out", error, commandMayHaveExecuted);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      subscriber.cancel();
      responseFuture.cancel(true);
      throw transportFailure("cluster IDE request was interrupted", error, commandMayHaveExecuted);
    } catch (ExecutionException error) {
      throw mapFailure(error.getCause(), commandMayHaveExecuted);
    }
    int status = response.statusCode();
    byte[] payload = response.body();
    String responseBody = payload == null ? "" : new String(payload, StandardCharsets.UTF_8);
    if (status == 200) {
      return type == null ? null : readJson(responseBody, type);
    }
    if (status == 204) {
      return null;
    }
    Problem problem = ClusterInternalErrors.parse(objectMapper, payload);
    String detail =
        problem.detail() == null ? "cluster IDE owner rejected the request" : problem.detail();
    throw problemFailure(problem, detail);
  }

  private static DomainException mapFailure(Throwable cause, boolean commandMayHaveExecuted) {
    if (cause instanceof DomainException error) {
      return error;
    }
    if (cause instanceof TimeoutException) {
      return transportFailure(
          "cluster IDE owner response timed out", cause, commandMayHaveExecuted);
    }
    return transportFailure("cluster IDE owner is unreachable", cause, commandMayHaveExecuted);
  }

  private static DomainException transportFailure(
      String message, Throwable cause, boolean commandMayHaveExecuted) {
    String reason = commandMayHaveExecuted ? "IDE_OUTCOME_UNKNOWN" : "IDE_UNAVAILABLE";
    return DomainException.unavailable(reason, message, cause);
  }

  private static DomainException problemFailure(Problem problem, String detail) {
    String code = problem.code();
    String reason = problem.reason();
    if ("validation".equals(code)) {
      return hasReason(reason)
          ? DomainException.validationReason(reason, detail)
          : DomainException.validation("environment", detail);
    }
    if ("conflict".equals(code)) {
      return DomainException.conflict(hasReason(reason) ? reason : "IDE_CONFLICT", detail);
    }
    if ("unavailable".equals(code)) {
      return DomainException.unavailable(hasReason(reason) ? reason : "IDE_UNAVAILABLE", detail);
    }
    if ("not_found".equals(code)) {
      return DomainException.notFound("environment", detail);
    }
    if ("upstream_failed".equals(code)) {
      return DomainException.upstream("environment", detail);
    }
    if ("unauthorized".equals(code)) {
      return DomainException.unavailable("IDE_UNAUTHORIZED", detail);
    }
    return DomainException.unavailable("IDE_UNAVAILABLE", detail);
  }

  /** 只校验 reason 的结构，不猜测其语义；完整原因仍由 owner 提供。 */
  private static boolean hasReason(String reason) {
    if (reason == null || reason.isBlank()) {
      return false;
    }
    for (int index = 0; index < reason.length(); index++) {
      char c = reason.charAt(index);
      if (!(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9') && c != '_') {
        return false;
      }
    }
    return reason.charAt(0) >= 'A' && reason.charAt(0) <= 'Z';
  }

  private byte[] writeRequestJson(Object body) {
    byte[] payload;
    try {
      payload = objectMapper.writeValueAsBytes(body);
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot encode cluster IDE metadata request", error);
    }
    if (payload.length > IdeInternalPaths.MAX_METADATA_BODY_BYTES) {
      throw DomainException.validation(
          "environment", "cluster IDE metadata request body is too large");
    }
    return payload;
  }

  private <T> T readJson(String body, Class<T> type) {
    try {
      return objectMapper.readValue(body, type);
    } catch (JacksonException error) {
      throw DomainException.unavailable(
          "IDE_UNAVAILABLE", "cluster IDE owner returned an invalid response", error);
    }
  }
}
