package fun.fengwk.kkstudio.platform.environment.cluster;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.error.AiEnvironmentIdeUnavailableException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewDTO;

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
 * <p>每次调用最多一次认证 HTTP 请求，失败即上抛，绝不自动重放。响应体不再用 {@code readNBytes} 读取返回的 {@code InputStream}（{@code
 * HttpRequest.timeout} 不覆盖响应体读取，可在返回响应头后无限阻塞），而是交给一个私有的有界 {@link
 * HttpResponse.BodySubscriber}：按到达字节数真正计数，一旦超过上限就 {@code subscription.cancel()} 并让 completion 异常完成，
 * 同时以 {@code requestTimeout} 作为覆盖响应体的硬截止（{@code CompletableFuture.orTimeout} + {@code
 * get(timeout)}）取消订阅并关闭连接， 绝不整包无界读取。大字节数据面（HTTP/WS 逐 exchange）由 gateway 宿主复用 {@link #signHeaders}
 * 与既有流式客户端实现，不经过本类。
 */
public class ClusterInternalClient {

  public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private static final int DEFAULT_MAX_RESPONSE_BYTES = 64 * 1024;

  private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final ClusterInternalAuthentication authentication;
  private final Duration requestTimeout;
  private final int maxResponseBytes;

  public ClusterInternalClient(
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

  public ClusterInternalClient(
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
   *     /internal/environment-ide/{env}/http?path=...}
   * @param owner 目标 owner 路由
   * @return 认证头
   */
  public Map<String, String> signHeaders(
      String method, String requestPath, ClusterIdeOwnerRoute owner) {
    return authentication.sign(method, requestPath, owner.nodeInstanceId());
  }

  public EnvironmentIdeViewDTO openView(
      EnvironmentId environmentId, ClusterIdeOwnerRoute owner, EnvironmentIdeOpenViewDTO request) {
    return exchange(
        "POST",
        ClusterInternalPaths.viewsPath(environmentId),
        owner,
        request,
        EnvironmentIdeViewDTO.class);
  }

  public EnvironmentIdeViewDTO changeDirectory(
      EnvironmentId environmentId,
      ClusterIdeOwnerRoute owner,
      String viewId,
      EnvironmentIdeChangeDirectoryDTO request) {
    return exchange(
        "PUT",
        ClusterInternalPaths.directoryPath(environmentId, viewId),
        owner,
        request,
        EnvironmentIdeViewDTO.class);
  }

  public void focusTerminal(
      EnvironmentId environmentId, ClusterIdeOwnerRoute owner, String viewId) {
    exchange("POST", ClusterInternalPaths.terminalPath(environmentId, viewId), owner, null, null);
  }

  public void closeView(EnvironmentId environmentId, ClusterIdeOwnerRoute owner, String viewId) {
    exchange("DELETE", ClusterInternalPaths.viewPath(environmentId, viewId), owner, null, null);
  }

  private <T> T exchange(
      String method, String path, ClusterIdeOwnerRoute owner, Object body, Class<T> type) {
    URI uri = owner.internalUrl().resolve(path);
    Map<String, String> authHeaders = authentication.sign(method, path, owner.nodeInstanceId());
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri).timeout(requestTimeout).header("Accept", "application/json");
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
                new AiEnvironmentIdeUnavailableException(
                    "cluster IDE owner response exceeds the metadata limit"));
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
    bodyFuture.orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);

    HttpResponse<byte[]> response;
    try {
      response = responseFuture.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException error) {
      subscriber.cancel();
      throw new AiEnvironmentIdeUnavailableException("cluster IDE owner response timed out", error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      subscriber.cancel();
      throw new AiEnvironmentIdeUnavailableException("cluster IDE request was interrupted", error);
    } catch (ExecutionException error) {
      throw mapFailure(error.getCause());
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
    String message = errorMessage(responseBody);
    if (status == 400) {
      throw new AiValidationException("environment", message);
    }
    throw new AiEnvironmentIdeUnavailableException(message);
  }

  private static AiEnvironmentIdeUnavailableException mapFailure(Throwable cause) {
    if (cause instanceof AiEnvironmentIdeUnavailableException unavailable) {
      return unavailable;
    }
    if (cause instanceof TimeoutException) {
      return new AiEnvironmentIdeUnavailableException(
          "cluster IDE owner response timed out", cause);
    }
    return new AiEnvironmentIdeUnavailableException("cluster IDE owner is unreachable", cause);
  }

  private byte[] writeRequestJson(Object body) {
    byte[] payload;
    try {
      payload = objectMapper.writeValueAsBytes(body);
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot encode cluster IDE metadata request", error);
    }
    if (payload.length > ClusterInternalPaths.MAX_METADATA_BODY_BYTES) {
      throw new AiValidationException(
          "environment", "cluster IDE metadata request body is too large");
    }
    return payload;
  }

  private <T> T readJson(String body, Class<T> type) {
    try {
      return objectMapper.readValue(body, type);
    } catch (JacksonException error) {
      throw new AiEnvironmentIdeUnavailableException(
          "cluster IDE owner returned an invalid response", error);
    }
  }

  private String errorMessage(String body) {
    if (body != null && !body.isBlank()) {
      try {
        JsonNode root = objectMapper.readTree(body);
        JsonNode message = root.get("message");
        if (message == null) {
          message = root.get("detail");
        }
        if (message != null && !message.asText().isBlank()) {
          String text = message.asText();
          return text.length() > MAX_ERROR_MESSAGE_LENGTH
              ? text.substring(0, MAX_ERROR_MESSAGE_LENGTH)
              : text;
        }
      } catch (JacksonException ignored) {
        // 非 JSON 错误体不是可信来源：退回固定安全文案，绝不回显原始内容。
      }
    }
    return "cluster IDE owner rejected the request";
  }
}
