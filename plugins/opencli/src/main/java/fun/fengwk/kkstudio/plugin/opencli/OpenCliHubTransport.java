package fun.fengwk.kkstudio.plugin.opencli;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.support.DeadlineResponseBody;
import fun.fengwk.kkstudio.platform.plugin.support.PluginHttpSupport;
import fun.fengwk.kkstudio.platform.plugin.support.PluginHttpSupport.ResponseTooLargeException;
import fun.fengwk.kkstudio.plugin.opencli.OpenCliHubException.Kind;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * OpenCLI Hub 的鉴权 HTTP 发送边界。
 *
 * <p>每次发送前独立解析 scoped 凭据并局部添加 Bearer；401/403 只作废当次快照并抛出去敏 HTTP 错误，绝不降级为无认证。响应正文受整体 deadline
 * 约束，任何失败路径都释放已取得的正文流。
 *
 * <p>成功响应必须是标准 JSON 信封；字段读取与固定去敏错误见 {@link OpenCliHubProtocol}。
 */
@Slf4j
final class OpenCliHubTransport {

  static final int MAX_JSON_RESPONSE_BYTES = 512 * 1024;
  static final int MAX_ERROR_RESPONSE_BYTES = 4096;

  private static final String AUTH_REJECTED_ERROR = "OpenCLI Hub authentication rejected";
  private static final ObjectMapper CREDENTIAL_MAPPER =
      PluginHttpSupport.strictJson(JsonMapper.builder().build());

  private final ObjectMapper mapper;
  private final PluginCredentialAccess credentials;
  private final HttpClient httpClient;
  private final URI origin;

  OpenCliHubTransport(
      ObjectMapper mapper,
      PluginCredentialAccess credentials,
      HttpClient httpClient,
      OpenCliSettings settings) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.credentials = Objects.requireNonNull(credentials, "credentials");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.origin =
        settings.baseUrl() == null || settings.baseUrl().isBlank()
            ? null
            : normalizeOrigin(URI.create(settings.baseUrl()));
  }

  HttpRequest.Builder request(String pathAndQuery) {
    if (origin == null) {
      throw new OpenCliHubException(Kind.INDETERMINATE, "OpenCLI Hub baseUrl is not configured");
    }
    return HttpRequest.newBuilder(origin.resolve(pathAndQuery));
  }

  /** 产物 URL 必须与 Hub 同源、位于 {@code /api/resources/} 下且不含 dot segment。 */
  URI resolveResourceUri(String value) {
    if (origin == null) {
      throw new OpenCliHubException(Kind.INDETERMINATE, "OpenCLI Hub baseUrl is not configured");
    }
    if (value == null || value.isBlank()) {
      throw OpenCliHubProtocol.malformed("execution resource has no contentUrl/downloadUrl");
    }
    URI parsed;
    try {
      parsed = URI.create(value);
    } catch (IllegalArgumentException exception) {
      throw OpenCliHubProtocol.malformed("execution resource URL is invalid", exception);
    }
    URI resolved = parsed.isAbsolute() ? parsed : origin.resolve(parsed);
    URI normalized = resolved.normalize();
    if (!sameOrigin(origin, resolved)
        || resolved.getUserInfo() != null
        || resolved.getFragment() != null
        || resolved.getRawPath() == null
        || !resolved.getRawPath().startsWith("/api/resources/")
        || OpenCliHubProtocol.hasDotSegment(resolved.getPath())
        || normalized.getRawPath() == null
        || !normalized.getRawPath().startsWith("/api/resources/")) {
      throw OpenCliHubProtocol.malformed(
          "execution resource URL must stay on the configured origin under /api/resources/");
    }
    return resolved;
  }

  private static URI normalizeOrigin(URI baseUrl) {
    int port = effectivePort(baseUrl);
    try {
      return new URI(
          baseUrl.getScheme().toLowerCase(Locale.ROOT),
          null,
          baseUrl.getHost().toLowerCase(Locale.ROOT),
          port == defaultPort(baseUrl.getScheme()) ? -1 : port,
          "/",
          null,
          null);
    } catch (URISyntaxException exception) {
      throw new IllegalArgumentException("opencli-hub baseUrl origin is invalid", exception);
    }
  }

  private static boolean sameOrigin(URI left, URI right) {
    return left.getScheme().equalsIgnoreCase(right.getScheme())
        && left.getHost().equalsIgnoreCase(right.getHost())
        && effectivePort(left) == effectivePort(right);
  }

  private static int effectivePort(URI uri) {
    return uri.getPort() == -1 ? defaultPort(uri.getScheme()) : uri.getPort();
  }

  private static int defaultPort(String scheme) {
    return "https".equalsIgnoreCase(scheme) ? 443 : 80;
  }

  /** 与凭据无关的请求：仍走同一鉴权发送路径后再解包信封。 */
  JsonNode sendEnvelope(HttpRequest.Builder requestBuilder, Duration timeout) {
    return sendEnvelope(credential -> requestBuilder, timeout);
  }

  /** 发送 HTTP 请求并校验解包 OpenCLI Hub 标准响应信封中的业务数据。 */
  JsonNode sendEnvelope(CredentialedRequest requestFactory, Duration timeout) {
    Response result = send(requestFactory, timeout);
    HttpResponse<InputStream> response = result.response();
    try (InputStream body = result.body()) {
      String contentType = response.headers().firstValue("Content-Type").orElse("");
      if (!contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
        throw OpenCliHubProtocol.malformed(
            "success response Content-Type must be application/json");
      }
      byte[] bytes = readBounded(body, MAX_JSON_RESPONSE_BYTES, "JSON response");
      JsonNode root = mapper.readTree(bytes);
      if (root == null || !root.isObject()) {
        throw OpenCliHubProtocol.malformed("Result envelope must be an object");
      }
      JsonNode status = OpenCliHubProtocol.required(root, "status");
      if (!status.isIntegralNumber()
          || !status.canConvertToInt()
          || status.intValue() != response.statusCode()) {
        throw OpenCliHubProtocol.malformed("Result envelope status must match HTTP status");
      }
      OpenCliHubProtocol.requiredText(root, "code");
      JsonNode success = root.get("success");
      if (success != null && (!success.isBoolean() || !success.booleanValue())) {
        throw OpenCliHubProtocol.malformed("Result envelope success must be true");
      }
      return OpenCliHubProtocol.required(root, "data");
    } catch (JacksonException exception) {
      throw OpenCliHubProtocol.malformed("Result envelope must be valid JSON", exception);
    } catch (IOException exception) {
      throw closeFailure("OpenCLI Hub response", exception);
    }
  }

  /** 与凭据无关的请求：仍走同一鉴权发送路径，但不解包信封。 */
  Response send(HttpRequest.Builder requestBuilder, Duration timeout) {
    return send(credential -> requestBuilder, timeout);
  }

  /**
   * 统一鉴权发送：每次请求独立解析凭据并构造 Bearer Header； 401/403 触发 rejectUsed CAS 并抛出去敏 HTTP 错误；统一管理 response
   * deadline，并在任何失败路径上释放已取得的响应正文。
   */
  Response send(CredentialedRequest requestFactory, Duration timeout) {
    PluginCredentialSnapshot snapshot = resolveCredential();
    ScopedCredential credential = parseCredential(snapshot.payloadJson());
    HttpRequest request;
    try {
      request =
          requestFactory
              .build(credential)
              .copy()
              .header("Authorization", PluginHttpSupport.bearer(credential.apiKey()))
              .timeout(timeout)
              .build();
    } catch (IllegalArgumentException exception) {
      log.warn(
          "failed to construct OpenCLI Hub request type={}", exception.getClass().getSimpleName());
      throw new OpenCliHubException(
          Kind.CREDENTIAL_UNAVAILABLE, "OpenCLI Hub credential is invalid");
    }
    long deadline = System.nanoTime() + timeout.toNanos();
    HttpResponse<InputStream> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException exception) {
      throw hubFailure(transportKind(exception), "OpenCLI Hub request failed", exception);
    } catch (IllegalArgumentException exception) {
      throw new OpenCliHubException(Kind.INDETERMINATE, "OpenCLI Hub response headers are invalid");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw hubFailure(Kind.INDETERMINATE, "OpenCLI Hub request interrupted", exception);
    }

    InputStream stream = response.body();
    DeadlineResponseBody body = null;
    try {
      if (PluginHttpSupport.isAuthRejected(response.statusCode())) {
        rejectUsed(snapshot);
      }
      body = new DeadlineResponseBody(stream, deadline);
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw httpError(response.statusCode(), body);
      }
      return new Response(response, body);
    } catch (RuntimeException | Error failure) {
      // 凭据作废与错误响应解释都可能任意失败：正文流必须在此释放，且原失败原样上抛。
      closeAfterFailure(body == null ? stream : body, failure);
      throw failure;
    }
  }

  private OpenCliHubException httpError(int status, InputStream body) {
    // 已确认收到 HTTP 响应：401/403 是远端确定性拒绝凭据，其它状态码不得被当作认证结论。
    Kind kind = PluginHttpSupport.isAuthRejected(status) ? Kind.AUTH_REJECTED : Kind.HTTP_STATUS;
    String message = "OpenCLI Hub HTTP " + status;
    try {
      drainErrorBody(body, MAX_ERROR_RESPONSE_BYTES);
    } catch (IOException exception) {
      return hubFailure(kind, message + " error response read failed", exception);
    }
    return new OpenCliHubException(kind, message);
  }

  private void drainErrorBody(InputStream input, int limit) throws IOException {
    byte[] buffer = new byte[8192];
    int remaining = limit;
    while (remaining > 0) {
      int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
      if (read == -1) {
        return;
      }
      remaining -= read;
    }
    if (input.read() != -1) {
      log.debug("OpenCLI Hub error response exceeded local limit");
    }
  }

  private byte[] readBounded(InputStream input, int limit, String description) {
    try {
      return PluginHttpSupport.readBounded(input, limit);
    } catch (ResponseTooLargeException exception) {
      throw new OpenCliHubException(
          Kind.PROTOCOL, "OpenCLI Hub " + description + " exceeds local limit");
    } catch (IOException exception) {
      throw hubFailure(Kind.PROTOCOL, "failed to read OpenCLI Hub " + description, exception);
    }
  }

  private PluginCredentialSnapshot resolveCredential() {
    PluginCredentialSnapshot snapshot;
    try {
      snapshot = credentials.resolve();
    } catch (PluginCredentialUnavailableException exception) {
      log.debug("OpenCLI Hub credential unavailable type={}", exception.getClass().getSimpleName());
      throw new OpenCliHubException(
          Kind.CREDENTIAL_UNAVAILABLE, "OpenCLI Hub credential is not available");
    } catch (RuntimeException exception) {
      // 未知运行时失败不是「没有凭据」：只记录异常类型，抛固定去敏错误，绝不把数据库/编程错误
      // 投影成健康结果，也不携带原始消息或 cause。
      log.warn(
          "OpenCLI Hub credential resolution failed type={}", exception.getClass().getSimpleName());
      throw new IllegalStateException("OpenCLI Hub credential resolution failed");
    }
    if (snapshot == null || snapshot.payloadJson() == null || snapshot.payloadJson().isBlank()) {
      throw new OpenCliHubException(
          Kind.CREDENTIAL_UNAVAILABLE, "OpenCLI Hub credential is not available");
    }
    return snapshot;
  }

  /**
   * 从同一次 scoped snapshot 的载荷严格解析本次请求真正使用的凭据：必需 {@code apiKey} 与可选 {@code instanceId}。
   *
   * <p>载荷形状是宿主实际写入的秘密 JSON 对象，只允许这两个声明字段；任何其它字段、类型不符或越界都 fail closed。
   */
  private ScopedCredential parseCredential(String payloadJson) {
    JsonNode root;
    try {
      root = CREDENTIAL_MAPPER.readTree(payloadJson);
    } catch (JacksonException exception) {
      log.warn(
          "failed to parse OpenCLI Hub credential payload type={}",
          exception.getClass().getSimpleName());
      throw invalidCredential();
    }
    if (root == null || !root.isObject() || !root.has("apiKey")) {
      throw invalidCredential();
    }
    String instanceId = null;
    for (String field : root.propertyNames()) {
      if (!"apiKey".equals(field) && !"instanceId".equals(field)) {
        throw invalidCredential();
      }
    }
    JsonNode apiKeyNode = root.get("apiKey");
    if (!apiKeyNode.isTextual() || apiKeyNode.textValue().isBlank()) {
      throw invalidCredential();
    }
    JsonNode instanceIdNode = root.get("instanceId");
    if (instanceIdNode != null) {
      if (!instanceIdNode.isTextual()) {
        throw invalidCredential();
      }
      instanceId = requireInstanceId(instanceIdNode.textValue());
    }
    return new ScopedCredential(validateApiKey(apiKeyNode.textValue()), instanceId);
  }

  /** 私有身份沿用部署期 1..36 字符约束；非法取值只以固定去敏错误暴露，绝不回显取值。 */
  private static String requireInstanceId(String value) {
    try {
      return OpenCliSettingsValidation.requireOptionalBoundedText(value.strip(), 36, "instanceId");
    } catch (IllegalArgumentException error) {
      throw invalidCredential();
    }
  }

  private static OpenCliHubException invalidCredential() {
    return new OpenCliHubException(
        Kind.CREDENTIAL_UNAVAILABLE, "OpenCLI Hub credential payload is invalid");
  }

  private static String validateApiKey(String apiKey) {
    try {
      PluginHttpSupport.bearer(apiKey);
      return apiKey;
    } catch (IllegalArgumentException error) {
      throw invalidCredential();
    }
  }

  private void rejectUsed(PluginCredentialSnapshot snapshot) {
    PluginHttpSupport.rejectUsed(credentials, snapshot, AUTH_REJECTED_ERROR);
  }

  private static OpenCliHubException hubFailure(Kind kind, String message, Throwable cause) {
    log.warn("OpenCLI Hub failure: {} type={}", message, cause.getClass().getSimpleName());
    return new OpenCliHubException(kind, message, timeoutCause(cause));
  }

  /**
   * IO 失败的最小分类：只有可证明「连接从未建立」的失败才是 {@link Kind#TRANSPORT}，其余无法区分的 IO 失败保持 {@link
   * Kind#INDETERMINATE}，绝不把未知失败说成不可达。
   */
  private static Kind transportKind(IOException cause) {
    if (cause instanceof HttpConnectTimeoutException
        || cause instanceof ConnectException
        || cause instanceof UnknownHostException
        || cause instanceof NoRouteToHostException
        || cause instanceof PortUnreachableException) {
      return Kind.TRANSPORT;
    }
    return Kind.INDETERMINATE;
  }

  private static UncheckedIOException closeFailure(String description, IOException cause) {
    log.warn("failed to close {} type={}", description, cause.getClass().getSimpleName());
    return new UncheckedIOException(new IOException("failed to close " + description));
  }

  private static Throwable timeoutCause(Throwable cause) {
    if (cause instanceof HttpTimeoutException) {
      return new HttpTimeoutException("OpenCLI Hub response deadline exceeded");
    }
    return null;
  }

  /**
   * 已取得 response 后的唯一清理：释放正文流并保留原 primary。
   *
   * <p>非致命 close 失败只附加固定分类，不泄露底层异常详情；Error 原样保留为 suppressed，绝不替换 primary。
   */
  static void closeAfterFailure(InputStream body, Throwable primary) {
    try {
      body.close();
    } catch (IOException | RuntimeException | Error closeFailure) {
      log.debug("OpenCLI Hub stream close failed type={}", closeFailure.getClass().getSimpleName());
      if (closeFailure != primary) {
        primary.addSuppressed(
            closeFailure instanceof Error
                ? closeFailure
                : new IOException(
                    "OpenCLI Hub response close failed type="
                        + closeFailure.getClass().getSimpleName()));
      }
    }
  }

  /** 一次请求真正使用的 scoped 凭据：同一份 snapshot 解析出的认证密钥与可选私有身份；永不回显取值。 */
  record ScopedCredential(String apiKey, String instanceId) {

    @Override
    public String toString() {
      return "ScopedCredential[<redacted>]";
    }
  }

  /** 出网请求构造器：在真正鉴权发送前用本次解析出的 scoped 凭据补全请求。 */
  @FunctionalInterface
  interface CredentialedRequest {

    HttpRequest.Builder build(ScopedCredential credential);
  }

  record Response(HttpResponse<InputStream> response, DeadlineResponseBody body) {}
}
