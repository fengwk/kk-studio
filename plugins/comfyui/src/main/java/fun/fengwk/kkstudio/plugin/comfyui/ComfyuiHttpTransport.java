package fun.fengwk.kkstudio.plugin.comfyui;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.json.JsonValues;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.support.DeadlineResponseBody;
import fun.fengwk.kkstudio.platform.plugin.support.PluginHttpSupport;
import fun.fengwk.kkstudio.platform.plugin.support.PluginHttpSupport.ResponseTooLargeException;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * ComfyUI 的同源 HTTP 传输边界：请求构造、scoped 凭据、整体 deadline 与响应体所有权。
 *
 * <p>认证按请求生效：{@code NONE} 显式不发送任何凭据头；{@code BEARER} 每次出网前独立解析本插件 scoped 凭据快照并局部添加 Bearer，401/403
 * 只作废当次快照、绝不静默降级到无认证。
 *
 * <p>send 自身的 IO 失败与响应体解析失败都可能携带远端 URI/header/正文片段，因此对外只保留固定去敏事实；上传流长度诊断由本地事实构成，按原样保留。
 */
@Slf4j
final class ComfyuiHttpTransport implements Closeable {

  static final int MAX_JSON_BYTES = 1024 * 1024;

  private static final String AUTH_REJECTED_ERROR = "ComfyUI rejected the bearer credential";

  private final URI baseUri;
  private final ComfyuiSettings.AuthMode authMode;
  private final Duration requestTimeout;
  private final ObjectMapper mapper;
  private final HttpClient client;
  private final boolean ownsClient;
  private final PluginCredentialAccess credentialAccess;

  ComfyuiHttpTransport(
      String baseUrl,
      ComfyuiSettings.AuthMode authMode,
      Duration requestTimeout,
      ObjectMapper objectMapper,
      PluginCredentialAccess credentialAccess,
      HttpClient httpClient,
      boolean ownsClient) {
    this.baseUri = normalizeBaseUrl(baseUrl);
    this.authMode = Objects.requireNonNull(authMode, "authMode");
    this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
    // 独立严格 profile：重复键与尾随 token 严格拒绝；自由 workflow / history 树的 number 按 BigDecimal 读回并原样搬运。
    this.mapper =
        PluginHttpSupport.strictJson(
            Objects.requireNonNull(objectMapper, "objectMapper")
                .rebuild()
                .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build());
    this.client = Objects.requireNonNull(httpClient, "httpClient");
    this.ownsClient = ownsClient;
    this.credentialAccess =
        this.authMode == ComfyuiSettings.AuthMode.BEARER
            ? Objects.requireNonNull(credentialAccess, "credentialAccess")
            : credentialAccess;
  }

  /** 只释放本实例自建的 transport；注入的宿主 client 绝不被关闭。 */
  @Override
  public void close() {
    if (ownsClient) {
      client.close();
    }
  }

  ObjectMapper mapper() {
    return mapper;
  }

  HttpRequest.Builder request(String relativePath) {
    return request(endpoint(relativePath));
  }

  /** 同源请求构造：只声明 Accept 与目标地址，认证与 deadline 统一在 {@link #open} 中施加。 */
  HttpRequest.Builder request(URI target) {
    return HttpRequest.newBuilder(target).header("Accept", "*/*");
  }

  URI endpoint(String relativePath) {
    String root = baseUri.toString();
    if (!root.endsWith("/")) {
      root = root + "/";
    }
    return URI.create(root + relativePath);
  }

  HttpRequest.BodyPublisher jsonBody(ObjectNode body) {
    try {
      return HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body));
    } catch (JacksonException error) {
      // 序列化错误可能带 prompt 片段，公开错误只给固定事实，不串原始 cause。
      throw new IllegalStateException("cannot serialize ComfyUI request JSON");
    }
  }

  /** 定长 multipart：header/footer 由协议调用方给出，正文流只允许开启一次且不得偏离声明长度。 */
  HttpRequest.BodyPublisher multipart(
      byte[] header, long contentLength, InputStream content, byte[] footer) {
    long totalLength = header.length + contentLength + footer.length;
    HttpRequest.BodyPublisher publisher =
        HttpRequest.BodyPublishers.concat(
            HttpRequest.BodyPublishers.ofByteArray(header),
            HttpRequest.BodyPublishers.ofInputStream(contentSupplier(content, contentLength)),
            HttpRequest.BodyPublishers.ofByteArray(footer));
    // ofInputStream 的 contentLength 恒为 -1，concat 因此也是 -1；用声明总长度恢复固定长度。
    return HttpRequest.BodyPublishers.fromPublisher(publisher, totalLength);
  }

  JsonNode sendJson(HttpRequest.Builder builder) {
    Response response = open(builder);
    try (DeadlineResponseBody content = response.body()) {
      if (response.statusCode() / 100 != 2) {
        // 远端正文不受控，公开错误只保留 HTTP 数值事实。
        throw new IllegalStateException(
            "ComfyUI request failed with HTTP " + response.statusCode());
      }
      byte[] bytes = readBounded(content, MAX_JSON_BYTES);
      return mapper.readTree(bytes);
    } catch (JacksonException error) {
      // 解析错误可能带 body 字段名/片段，公开错误只给固定事实，不串原始 cause。
      throw new IllegalArgumentException("cannot parse ComfyUI JSON response");
    } catch (IOException error) {
      throw readFailure(error);
    }
  }

  /**
   * 每次出网前独立解析本插件 scoped 凭据并冻结同一个整体 deadline；headers 与 body 共享一份 requestTimeout。
   *
   * <p>{@code NONE} 不解析凭据也不添加认证头；{@code BEARER} 解析失败时确定性失败，绝不降级。401/403 只作废当次快照，随后仍按 HTTP 事实报错。
   */
  Response open(HttpRequest.Builder builder) {
    PluginCredentialSnapshot snapshot = null;
    if (authMode == ComfyuiSettings.AuthMode.BEARER) {
      snapshot = resolveCredential();
      builder.header("Authorization", authorization(snapshot.payloadJson()));
    }
    HttpRequest request;
    try {
      request = builder.timeout(requestTimeout).build();
    } catch (IllegalArgumentException error) {
      // 凭据/头值非法时只给固定分类，不回显取值。
      throw new IllegalStateException("ComfyUI request is invalid");
    }
    long deadlineNanos = System.nanoTime() + requestTimeout.toNanos();
    HttpResponse<InputStream> response;
    try {
      response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException error) {
      throw transportFailure(error);
    } catch (IllegalArgumentException error) {
      // JDK 可能在返回 body 前解析非法 Content-Length；不回显 header 原值或原始 cause。
      throw new IllegalStateException("ComfyUI response headers are invalid");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("ComfyUI HTTP request was interrupted");
    }
    if (snapshot != null && PluginHttpSupport.isAuthRejected(response.statusCode())) {
      try {
        rejectUsed(snapshot);
      } catch (RuntimeException | Error failure) {
        // 已经拿到的响应体属于本方法：任何清理结果都不得替换主失败，Error 按 primary/suppressed 约定附加。
        releaseBodyOnFailure(response.body(), failure);
        throw failure;
      }
    }
    return new Response(
        response.statusCode(),
        response.headers(),
        new DeadlineResponseBody(response.body(), deadlineNanos));
  }

  /** 有界读取响应正文；超限与读失败都收敛为固定去敏错误。 */
  static byte[] readBounded(InputStream content, int maxBytes) throws IOException {
    try {
      return PluginHttpSupport.readBounded(content, maxBytes);
    } catch (ResponseTooLargeException error) {
      throw new IllegalArgumentException("ComfyUI response exceeded " + maxBytes + " bytes");
    }
  }

  /** 清理阶段的释放失败不影响主流程，也绝不记录原始 Throwable/消息；只记录受控类型，Error 继续上抛。 */
  static void closeQuietly(AutoCloseable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (Exception error) {
        log.debug("ComfyUI client cleanup close failed type={}", error.getClass().getSimpleName());
      }
    }
  }

  static Supplier<InputStream> contentSupplier(InputStream content, long contentLength) {
    AtomicBoolean opened = new AtomicBoolean();
    return () -> {
      if (!opened.compareAndSet(false, true)) {
        throw new IllegalStateException("upload content stream is already open");
      }
      return new ExactUploadContent(content, contentLength);
    };
  }

  /**
   * 纯 URI 门禁：只接受声明 host、无 userInfo/路径/query/fragment 的 http(s) 目标，不创建任何 transport。
   *
   * <p>包内复用于配置期校验（{@link ComfyuiSettings}），保证"保存配置"与"真实出网"用同一份判定。
   */
  static URI normalizeBaseUrl(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("baseUrl must not be blank");
    }
    String trimmed = raw.strip();
    URI uri;
    try {
      uri = URI.create(trimmed);
    } catch (IllegalArgumentException error) {
      // URI.create 的消息会带原始串（可能含 userInfo 凭据）；只给固定分类。
      throw new IllegalArgumentException("baseUrl must be a valid URI");
    }
    String scheme = uri.getScheme();
    if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
      throw new IllegalArgumentException("baseUrl must use http or https scheme");
    }
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw new IllegalArgumentException("baseUrl must declare a host");
    }
    if (uri.getUserInfo() != null) {
      throw new IllegalArgumentException("baseUrl must not declare userInfo");
    }
    String path = uri.getPath();
    if (path != null && !path.isEmpty() && !"/".equals(path)) {
      throw new IllegalArgumentException("baseUrl must not include a path");
    }
    if (uri.getQuery() != null || uri.getFragment() != null) {
      throw new IllegalArgumentException("baseUrl must not declare query or fragment");
    }
    return uri;
  }

  private PluginCredentialSnapshot resolveCredential() {
    try {
      PluginCredentialSnapshot snapshot = credentialAccess.resolve();
      if (snapshot == null || snapshot.payloadJson() == null || snapshot.payloadJson().isBlank()) {
        throw new IllegalStateException("ComfyUI credential is not available");
      }
      return snapshot;
    } catch (PluginCredentialUnavailableException unavailable) {
      // 局部拒绝/未配置/过期都是确定性结果：原样上报，绝不静默降级到无认证。
      throw unavailable;
    } catch (RuntimeException error) {
      // 未知解析故障只声明去敏的解析失败，不伪装成本地未配置；Error 原样上抛。两者都在出网前失败。
      log.debug("ComfyUI credential resolution failed type={}", error.getClass().getSimpleName());
      throw new IllegalStateException("ComfyUI credential resolution failed");
    }
  }

  /** 只接受唯一秘密字段 {@code apiKey} 的严格 JSON object；非法形状与非法字符都行不通。 */
  private static String authorization(String payloadJson) {
    JsonNode root;
    try {
      root = JsonValues.readTree(payloadJson);
    } catch (IllegalArgumentException error) {
      throw invalidCredential();
    }
    if (!(root instanceof ObjectNode object) || object.size() != 1) {
      throw invalidCredential();
    }
    JsonNode value = object.get("apiKey");
    if (value == null || !value.isTextual()) {
      throw invalidCredential();
    }
    String token = value.textValue();
    if (token.isBlank() || !token.equals(token.strip())) {
      throw invalidCredential();
    }
    try {
      return PluginHttpSupport.bearer(token);
    } catch (IllegalArgumentException error) {
      throw invalidCredential();
    }
  }

  private static IllegalStateException invalidCredential() {
    return new IllegalStateException("ComfyUI credential payload is invalid");
  }

  private void rejectUsed(PluginCredentialSnapshot snapshot) {
    PluginHttpSupport.rejectUsed(credentialAccess, snapshot, AUTH_REJECTED_ERROR);
  }

  private static Duration requirePositive(Duration duration, String field) {
    Objects.requireNonNull(duration, field);
    if (duration.isNegative() || duration.isZero()) {
      throw new IllegalArgumentException(field + " must be strictly positive");
    }
    return duration;
  }

  /**
   * send 自身 IOException 的 class/message 可能含 URI 或 header 值，公开错误只给固定事实，不串原始 cause。
   *
   * <p>调用方上传流的长度诊断由本地事实构成，剥离 JDK 传输 cause 后仍按原样保留。
   */
  private static RuntimeException transportFailure(IOException error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof UploadContentException upload) {
        return new UncheckedIOException(new UploadContentException(upload.getMessage()));
      }
    }
    if (error instanceof HttpTimeoutException) {
      return new UncheckedIOException(
          new HttpTimeoutException("ComfyUI request timed out waiting for response headers"));
    }
    return new UncheckedIOException(new IOException("ComfyUI HTTP transport failed"));
  }

  /** body 读取失败：超时仍是 HttpTimeoutException，其余只给固定信息，不回显远端正文或原始 cause。 */
  private static RuntimeException readFailure(IOException error) {
    if (error instanceof HttpTimeoutException) {
      return new UncheckedIOException(
          new HttpTimeoutException("ComfyUI response read exceeded the request deadline"));
    }
    return new UncheckedIOException(new IOException("cannot read ComfyUI response body"));
  }

  /**
   * 资源所有权边界：释放本方法已经取得的响应体，清理结果绝不替换主失败。
   *
   * <p>清理期 {@link Error} 按仓库 primary/suppressed 约定附加到主失败后照常传播；清理期 {@link Exception}（含 body 关闭的
   * {@code IOException}）只按去敏类型日志。
   */
  private static void releaseBodyOnFailure(InputStream body, Throwable primary) {
    try {
      body.close();
    } catch (Error cleanup) {
      if (cleanup != primary) {
        primary.addSuppressed(cleanup);
      }
    } catch (Exception cleanup) {
      log.debug("ComfyUI response body cleanup failed type={}", cleanup.getClass().getSimpleName());
    }
  }

  record Response(int statusCode, HttpHeaders headers, DeadlineResponseBody body) {}

  /** 只读声明长度。提前结束或声明长度之后仍有字节都失败，不截断。流由 upload 关闭。 */
  private static final class ExactUploadContent extends FilterInputStream {

    private final long declared;
    private long remaining;

    private ExactUploadContent(InputStream content, long declared) {
      super(content);
      this.declared = declared;
      remaining = declared;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      int read = read(one, 0, 1);
      return read < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      Objects.checkFromIndexSize(offset, length, buffer.length);
      if (length == 0) {
        return 0;
      }
      if (remaining == 0L) {
        int extra = in.read(buffer, offset, length);
        if (extra > 0) {
          throw new UploadContentException("upload content is longer than declared contentLength");
        }
        return extra;
      }
      int requested = (int) Math.min(length, remaining);
      int read = in.read(buffer, offset, requested);
      if (read < 0) {
        throw new UploadContentException(shortContent());
      }
      remaining -= read;
      return read;
    }

    private String shortContent() {
      return "upload content ended after " + (declared - remaining) + " of " + declared + " bytes";
    }
  }

  /** 只描述调用方自己声明的长度事实，不含 URI、header 或远端内容；让上传长度诊断在剥离 JDK 传输 cause 后仍可保留。 */
  private static final class UploadContentException extends IOException {

    private UploadContentException(String message) {
      super(message);
    }
  }
}
