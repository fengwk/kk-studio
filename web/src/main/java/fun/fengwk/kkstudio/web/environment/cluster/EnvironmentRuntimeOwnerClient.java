package fun.fengwk.kkstudio.web.environment.cluster;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.cluster.BoundedBodySubscriber;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalPaths;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.error.AiEnvironmentUnavailableException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentRuntimeStatusDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentUpdateStartDTO;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 非 owner 节点对 Environment READY owner 的单跳内部 HTTP 客户端。
 *
 * <p>目标 origin 只来自 {@code cluster_node} 当前有效租约，路径固定为 canonical UUID；请求使用既有内部 HMAC
 * 认证。响应体与总读取时间都有硬上限， 超时、非 200、无效 origin 或畸形响应一律 fail-closed，绝不回退本地或其它节点，也绝不重试。
 */
@Component
public class EnvironmentRuntimeOwnerClient {

  static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);
  static final int MAX_RESPONSE_BYTES = 64 * 1024;

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  private final HttpClient httpClient;
  private final ClusterInternalAuthentication authentication;
  private final Duration requestTimeout;

  @Autowired
  public EnvironmentRuntimeOwnerClient(
      @Qualifier("clusterInternalHttpClient") HttpClient httpClient,
      ClusterInternalAuthentication authentication) {
    this(httpClient, authentication, DEFAULT_REQUEST_TIMEOUT);
  }

  EnvironmentRuntimeOwnerClient(
      HttpClient httpClient,
      ClusterInternalAuthentication authentication,
      Duration requestTimeout) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.authentication = Objects.requireNonNull(authentication, "authentication");
    this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    if (requestTimeout.isZero() || requestTimeout.isNegative()) {
      throw new IllegalArgumentException("requestTimeout must be positive");
    }
  }

  /** 读取远端 READY owner 的当前运行快照。 */
  public EnvironmentRuntimeStatusDTO runtimeStatus(
      ClusterOwnerRoute owner, EnvironmentId environmentId) {
    return execute("GET", owner, environmentId, false, EnvironmentRuntimeStatusDTO.class);
  }

  /** 在远端 READY owner 上发起更新并读取其当前运行快照。 */
  public EnvironmentUpdateStartDTO startUpdate(
      ClusterOwnerRoute owner, EnvironmentId environmentId) {
    return execute("POST", owner, environmentId, true, EnvironmentUpdateStartDTO.class);
  }

  private <T> T execute(
      String method,
      ClusterOwnerRoute owner,
      EnvironmentId environmentId,
      boolean update,
      Class<T> responseType) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(environmentId, "environmentId");
    if (owner.local()) {
      throw new IllegalArgumentException("local owner route must be handled in-process");
    }
    String path =
        update
            ? ClusterInternalPaths.environmentUpdatePath(environmentId.value())
            : ClusterInternalPaths.environmentRuntimePath(environmentId.value());
    URI uri = resolveStrictly(owner.internalUrl(), path);
    Map<String, String> authHeaders = authentication.sign(method, path, owner.nodeInstanceId());
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri).timeout(requestTimeout).header("Accept", "application/json");
    authHeaders.forEach(builder::header);
    if (update) {
      builder.POST(HttpRequest.BodyPublishers.noBody());
    } else {
      builder.GET();
    }

    BoundedBodySubscriber subscriber =
        new BoundedBodySubscriber(
            MAX_RESPONSE_BYTES,
            () ->
                new AiEnvironmentUnavailableException(
                    "environment owner response exceeds the internal limit"));
    CompletableFuture<HttpResponse<byte[]>> responseFuture =
        httpClient.sendAsync(builder.build(), responseInfo -> subscriber);
    long deadlineNanos = System.nanoTime() + requestTimeout.toNanos();
    try {
      HttpResponse<byte[]> response = await(responseFuture, deadlineNanos);
      byte[] body = await(subscriber.bodyFuture(), deadlineNanos);
      if (response.statusCode() == 200) {
        return decode(body, responseType);
      }
      throw mapFailure(response.statusCode(), body);
    } catch (TimeoutException error) {
      throw new AiEnvironmentUnavailableException("environment owner request timed out", error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AiEnvironmentUnavailableException(
          "environment owner request was interrupted", error);
    } catch (ExecutionException error) {
      Throwable cause = error.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new AiEnvironmentUnavailableException("environment owner is unavailable", cause);
    } finally {
      responseFuture.cancel(true);
      subscriber.cancel();
    }
  }

  private static <T> T decode(byte[] body, Class<T> responseType) {
    try {
      return MAPPER.readValue(body, responseType);
    } catch (JacksonException error) {
      throw new AiEnvironmentUnavailableException(
          "environment owner returned a malformed runtime snapshot", error);
    }
  }

  private static RuntimeException mapFailure(int status, byte[] body) {
    String code = errorCode(body);
    if (status == 409 && "in_use".equals(code)) {
      return new AiInUseException("environment", "environment runtime operation is busy");
    }
    if (status == 409 && "environment_unavailable".equals(code)) {
      return new AiEnvironmentUnavailableException("environment runtime is unavailable");
    }
    return new AiEnvironmentUnavailableException("environment owner returned status " + status);
  }

  private static String errorCode(byte[] body) {
    try {
      JsonNode value = MAPPER.readTree(body);
      JsonNode code = value == null ? null : value.get("code");
      return code != null && code.isString() ? code.stringValue() : null;
    } catch (JacksonException ignored) {
      return null;
    }
  }

  /** origin 必须已经是裸 http(s) origin；解析后的 authority/path 也必须是固定内部路径。 */
  private static URI resolveStrictly(URI origin, String path) {
    if (origin == null) {
      throw new AiEnvironmentUnavailableException("environment owner has no internal origin");
    }
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
      throw new AiEnvironmentUnavailableException("environment owner origin is not trusted");
    }
    URI resolved = origin.resolve(path);
    if (!scheme.equalsIgnoreCase(resolved.getScheme())
        || !origin.getAuthority().equalsIgnoreCase(resolved.getAuthority())
        || !path.equals(resolved.getRawPath())
        || resolved.getRawQuery() != null
        || resolved.getRawFragment() != null) {
      throw new AiEnvironmentUnavailableException("environment owner request path is invalid");
    }
    return resolved;
  }

  private static <T> T await(CompletableFuture<T> future, long deadlineNanos)
      throws TimeoutException, InterruptedException, ExecutionException {
    long remaining = deadlineNanos - System.nanoTime();
    if (remaining <= 0) {
      throw new TimeoutException("environment owner deadline exceeded");
    }
    return future.get(remaining, TimeUnit.NANOSECONDS);
  }
}
