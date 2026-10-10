package fun.fengwk.kkstudio.platform.environment.cluster;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.error.AiEnvironmentIdeUnavailableException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewDTO;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 跨节点 IDE 元数据命令的简洁 HTTP 客户端：只承载小 metadata JSON（view 命令与 ViewDTO 回执），绝不传输环境字节。
 *
 * <p>每次调用最多一次认证 HTTP 请求，失败即上抛，绝不自动重放；响应体以有界缓冲读取，超界即 fail-closed。大字节数据面（HTTP/WS 逐 exchange）由 gateway
 * 宿主复用 {@link #signHeaders} 与既有流式客户端实现，不经过本类。
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
    return authentication.sign(method, requestPath, owner);
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
    Map<String, String> authHeaders = authentication.sign(method, path, owner);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri).timeout(requestTimeout).header("Accept", "application/json");
    if (body != null) {
      builder.header("Content-Type", "application/json");
    }
    authHeaders.forEach(builder::header);
    HttpRequest request =
        body == null
            ? builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
            : builder
                .method(
                    method,
                    HttpRequest.BodyPublishers.ofString(writeJson(body), StandardCharsets.UTF_8))
                .build();
    HttpResponse<String> response;
    try {
      response =
          httpClient.send(
              request,
              HttpResponse.BodyHandlers.buffering(
                  HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), maxResponseBytes));
    } catch (IOException error) {
      throw new AiEnvironmentIdeUnavailableException("cluster IDE owner is unreachable", error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AiEnvironmentIdeUnavailableException("cluster IDE request was interrupted", error);
    }
    int status = response.statusCode();
    if (status == 200) {
      return type == null ? null : readJson(response.body(), type);
    }
    if (status == 204) {
      return null;
    }
    String message = errorMessage(response.body());
    if (status == 400) {
      throw new AiValidationException("environment", message);
    }
    throw new AiEnvironmentIdeUnavailableException(message);
  }

  private String writeJson(Object body) {
    try {
      return objectMapper.writeValueAsString(body);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode cluster IDE metadata request", error);
    }
  }

  private <T> T readJson(String body, Class<T> type) {
    try {
      return objectMapper.readValue(body, type);
    } catch (JsonProcessingException error) {
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
      } catch (JsonProcessingException ignored) {
        // 非 JSON 错误体不是可信来源：退回固定安全文案，绝不回显原始内容。
      }
    }
    return "cluster IDE owner rejected the request";
  }
}
