package fun.fengwk.kkstudio.plugin.canvascomfyui;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.io.DeadlineResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** 只使用标准 ComfyUI HTTP API 的有界、流式客户端。 */
public final class StandardComfyuiClient {

  static final int MAX_JSON_BYTES = 1024 * 1024;

  private final URI baseUri;
  private final String bearerToken;
  private final Duration requestTimeout;
  private final ObjectMapper mapper;
  private final HttpClient client;

  public StandardComfyuiClient(
      String baseUrl,
      String bearerToken,
      Duration connectTimeout,
      Duration requestTimeout,
      ObjectMapper objectMapper) {
    this(
        baseUrl,
        bearerToken,
        requestTimeout,
        objectMapper,
        HttpClient.newBuilder()
            .proxy(ProxySelector.getDefault())
            .connectTimeout(requirePositive(connectTimeout, "connectTimeout"))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  /** 测试注入 HttpClient，不改变生产构造。 */
  StandardComfyuiClient(
      String baseUrl,
      String bearerToken,
      Duration requestTimeout,
      ObjectMapper objectMapper,
      HttpClient httpClient) {
    baseUri = normalizeBaseUrl(baseUrl);
    this.bearerToken = normalizeBearer(bearerToken);
    this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
    // 独立严格 profile：重复键与尾随 token 严格拒绝；自由 workflow / history 树的 number 按 BigDecimal 读回并原样搬运。
    mapper =
        Objects.requireNonNull(objectMapper, "objectMapper")
            .rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();
    client = Objects.requireNonNull(httpClient, "httpClient");
  }

  public H3UploadedFile upload(
      String filename, String mediaType, long contentLength, InputStream content, UUID canvasId) {
    requireFilename(filename);
    requireHeaderToken(mediaType, "mediaType");
    if (contentLength <= 0L) {
      throw new IllegalArgumentException("contentLength must be positive");
    }
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(canvasId, "canvasId");
    String boundary = "kkstudio-" + UUID.randomUUID();
    String subfolder = "kk-studio/" + canvasId;
    try {
      HttpRequest.BodyPublisher body =
          multipart(boundary, filename, mediaType, contentLength, content, subfolder);
      HttpRequest request =
          request("upload/image")
              .header("Content-Type", "multipart/form-data; boundary=" + boundary)
              .POST(body)
              .build();
      ObjectNode response = object(sendJson(request), "upload response");
      H3UploadedFile uploaded =
          new H3UploadedFile(
              text(response, "name"), text(response, "subfolder"), text(response, "type"));
      if (!subfolder.equals(uploaded.subfolder())) {
        throw new IllegalArgumentException("ComfyUI upload returned an unexpected subfolder");
      }
      return uploaded;
    } finally {
      closeQuietly(content);
    }
  }

  public String submit(ObjectNode workflow, String clientId) {
    Objects.requireNonNull(workflow, "workflow");
    if (clientId == null || clientId.isBlank()) {
      throw new IllegalArgumentException("clientId must not be blank");
    }
    ObjectNode body = mapper.createObjectNode();
    body.put("client_id", clientId);
    body.set("prompt", workflow);
    ObjectNode response =
        object(
            sendJson(
                request("prompt")
                    .header("Content-Type", "application/json")
                    .POST(jsonBody(body))
                    .build()),
            "prompt response");
    String promptId = text(response, "prompt_id");
    JsonNode nodeErrors = response.get("node_errors");
    if (!(nodeErrors instanceof ObjectNode errors) || !errors.isEmpty()) {
      throw new IllegalArgumentException("ComfyUI prompt response contains node_errors");
    }
    return promptId;
  }

  public H3ComfyHistory history(String promptId) {
    requirePromptId(promptId);
    ObjectNode root =
        object(sendJson(request("history/" + path(promptId)).GET().build()), "history response");
    JsonNode raw = root.get(promptId);
    if (raw == null) {
      if (root.isEmpty()) {
        return H3ComfyHistory.pending();
      }
      throw new IllegalArgumentException("ComfyUI history response does not match promptId");
    }
    ObjectNode entry = object(raw, "history entry");
    ObjectNode status = object(required(entry, "status"), "history status");
    String statusText = text(status, "status_str").toLowerCase(Locale.ROOT);
    JsonNode completedValue = required(status, "completed");
    if (!completedValue.isBoolean()) {
      throw new IllegalArgumentException("history status.completed must be boolean");
    }
    boolean completed = completedValue.booleanValue();
    if ("error".equals(statusText) || "failed".equals(statusText)) {
      // 只回传受控分类，不回显远端 messages 等不受控字段。
      return H3ComfyHistory.error("ComfyUI execution failed with status=" + statusText);
    }
    if (!completed) {
      return H3ComfyHistory.pending();
    }
    if (!"success".equals(statusText)) {
      throw new IllegalArgumentException(
          "unsupported terminal ComfyUI history status: " + statusText);
    }
    return H3ComfyHistory.success(extractOutput(entry));
  }

  public H3ComfyDownload download(H3OutputDescriptor output) {
    Objects.requireNonNull(output, "output");
    URI uri =
        endpoint(
            "view?filename="
                + query(output.filename())
                + "&subfolder="
                + query(output.subfolder())
                + "&type="
                + query(output.type()));
    ComfyResponse response = open(request(uri).GET().build());
    try {
      if (response.statusCode() / 100 != 2) {
        throw new IllegalStateException("ComfyUI view failed with HTTP " + response.statusCode());
      }
      long length;
      try {
        length =
            response
                .headers()
                .firstValueAsLong("Content-Length")
                .orElseThrow(
                    () ->
                        new IllegalArgumentException(
                            "ComfyUI view response requires Content-Length"));
      } catch (NumberFormatException error) {
        // NumberFormatException 的消息包含远端原值，不传播到错误消息。
        throw new IllegalArgumentException("ComfyUI view Content-Length is invalid");
      }
      if (length <= 0L) {
        throw new IllegalArgumentException("ComfyUI view Content-Length must be positive");
      }
      String mediaType =
          response.headers().firstValue("Content-Type").orElse("application/octet-stream");
      response.body().expectLength(length);
      return new H3ComfyDownload(response.body(), length, mediaType);
    } catch (RuntimeException error) {
      closeQuietly(response.body());
      throw error;
    }
  }

  public boolean cancelPending(String promptId) {
    requirePromptId(promptId);
    ObjectNode queue = object(sendJson(request("queue").GET().build()), "queue response");
    if (containsPrompt(requiredArray(queue, "queue_running"), promptId)) {
      return false;
    }
    if (!containsPrompt(requiredArray(queue, "queue_pending"), promptId)) {
      return false;
    }
    ObjectNode body = mapper.createObjectNode();
    body.putArray("delete").add(promptId);
    HttpRequest request =
        request("queue").header("Content-Type", "application/json").POST(jsonBody(body)).build();
    ComfyResponse response = open(request);
    try (DeadlineResponseBody content = response.body()) {
      if (response.statusCode() / 100 != 2) {
        throw new IllegalStateException(
            "ComfyUI queue delete failed with HTTP " + response.statusCode());
      }
      readBounded(content, MAX_JSON_BYTES);
    } catch (IOException error) {
      throw readFailure(error);
    }
    return true;
  }

  private boolean containsPrompt(ArrayNode queue, String promptId) {
    for (JsonNode item : queue) {
      if (item instanceof ArrayNode tuple
          && tuple.size() >= 2
          && tuple.get(1).isTextual()
          && promptId.equals(tuple.get(1).textValue())) {
        return true;
      }
    }
    return false;
  }

  private H3OutputDescriptor extractOutput(ObjectNode entry) {
    ObjectNode outputs = object(required(entry, "outputs"), "history outputs");
    ObjectNode saveNode = object(required(outputs, "92"), "SaveVideo node 92 output");
    ArrayNode videos = requiredArray(saveNode, "videos");
    if (videos.size() != 1) {
      throw new IllegalArgumentException(
          "ComfyUI history SaveVideo node must contain exactly one video");
    }
    ObjectNode video = object(videos.get(0), "history video");
    String filename = text(video, "filename");
    String subfolder = string(video, "subfolder");
    String type = text(video, "type");
    return new H3OutputDescriptor(filename, subfolder, type);
  }

  private JsonNode sendJson(HttpRequest request) {
    ComfyResponse response = open(request);
    try (DeadlineResponseBody content = response.body()) {
      if (response.statusCode() / 100 != 2) {
        // 远端正文不受控，公开错误只保留 HTTP 数值事实。
        throw new IllegalStateException(
            "ComfyUI request failed with HTTP " + response.statusCode());
      }
      byte[] bytes = readBounded(content, MAX_JSON_BYTES);
      return mapper.readTree(bytes);
    } catch (JacksonException error) {
      throw new IllegalArgumentException("cannot parse ComfyUI JSON response", error);
    } catch (IOException error) {
      throw readFailure(error);
    }
  }

  /**
   * 在 send 前冻结剩余预算，并把同一截止点延伸到响应体读取；headers 与 body 共享一份 requestTimeout。
   *
   * <p>返回的 body 由调用方负责关闭，以便注销看门狗并释放连接。
   */
  private ComfyResponse open(HttpRequest request) {
    long deadlineNanos = System.nanoTime() + request.timeout().orElseThrow().toNanos();
    HttpResponse<InputStream> response;
    try {
      response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException error) {
      throw transportFailure(error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("ComfyUI HTTP request was interrupted", error);
    }
    return new ComfyResponse(
        response.statusCode(),
        response.headers(),
        new DeadlineResponseBody(response.body(), deadlineNanos));
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

  private record ComfyResponse(int statusCode, HttpHeaders headers, DeadlineResponseBody body) {}

  private HttpRequest.Builder request(String relativePath) {
    return request(endpoint(relativePath));
  }

  private HttpRequest.Builder request(URI target) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(target).timeout(requestTimeout).header("Accept", "*/*");
    if (bearerToken != null) {
      builder.header("Authorization", "Bearer " + bearerToken);
    }
    return builder;
  }

  private URI endpoint(String relativePath) {
    String root = baseUri.toString();
    if (!root.endsWith("/")) {
      root = root + "/";
    }
    return URI.create(root + relativePath);
  }

  private HttpRequest.BodyPublisher jsonBody(ObjectNode body) {
    try {
      byte[] bytes = mapper.writeValueAsBytes(body);
      return HttpRequest.BodyPublishers.ofByteArray(bytes);
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot serialize ComfyUI request JSON", error);
    }
  }

  private HttpRequest.BodyPublisher multipart(
      String boundary,
      String filename,
      String mediaType,
      long contentLength,
      InputStream content,
      String subfolder) {
    byte[] header =
        ("""
        --%s\r
        Content-Disposition: form-data; name="subfolder"\r
        \r
        %s\r
        --%s\r
        Content-Disposition: form-data; name="overwrite"\r
        \r
        1\r
        --%s\r
        Content-Disposition: form-data; name="image"; filename="%s"\r
        Content-Type: %s\r
        \r
        """
                .formatted(boundary, subfolder, boundary, boundary, filename, mediaType))
            .getBytes(StandardCharsets.UTF_8);
    byte[] footer = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
    long totalLength = header.length + contentLength + footer.length;
    HttpRequest.BodyPublisher publisher =
        HttpRequest.BodyPublishers.concat(
            HttpRequest.BodyPublishers.ofByteArray(header),
            HttpRequest.BodyPublishers.ofInputStream(contentSupplier(content, contentLength)),
            HttpRequest.BodyPublishers.ofByteArray(footer));
    // ofInputStream 的 contentLength 恒为 -1，concat 因此也是 -1；用声明总长度恢复固定长度。
    return HttpRequest.BodyPublishers.fromPublisher(publisher, totalLength);
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

  private static byte[] readBounded(InputStream content, int maxBytes) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[8 * 1024];
    int read;
    int total = 0;
    while ((read = content.read(chunk)) >= 0) {
      total += read;
      if (total > maxBytes) {
        throw new IllegalArgumentException("ComfyUI response exceeded " + maxBytes + " bytes");
      }
      buffer.write(chunk, 0, read);
    }
    return buffer.toByteArray();
  }

  private static String text(ObjectNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException("field " + field + " must be non-blank text");
    }
    return value.textValue();
  }

  private static String string(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null) {
      return "";
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException("field " + field + " must be a string");
    }
    return value.textValue();
  }

  private static JsonNode required(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException("missing required field: " + field);
    }
    return value;
  }

  private static ObjectNode object(JsonNode value, String path) {
    if (value == null || !(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException(path + " must be a JSON object");
    }
    return object;
  }

  private static ArrayNode requiredArray(ObjectNode node, String field) {
    JsonNode value = required(node, field);
    if (!(value instanceof ArrayNode array)) {
      throw new IllegalArgumentException(field + " must be an array");
    }
    return array;
  }

  private static URI normalizeBaseUrl(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("baseUrl must not be blank");
    }
    URI uri = URI.create(raw.strip());
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

  private static String normalizeBearer(String token) {
    if (token == null || token.isBlank()) {
      return null;
    }
    String trimmed = token.strip();
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (c <= 32 || c >= 127) {
        throw new IllegalArgumentException("bearerToken contains invalid ASCII characters");
      }
    }
    return trimmed;
  }

  private static Duration requirePositive(Duration duration, String field) {
    Objects.requireNonNull(duration, field);
    if (duration.isNegative() || duration.isZero()) {
      throw new IllegalArgumentException(field + " must be strictly positive");
    }
    return duration;
  }

  private static void requireFilename(String filename) {
    requireHeaderToken(filename, "filename");
    if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
      throw new IllegalArgumentException("filename must not contain directory traversal");
    }
  }

  /** filename 与 mediaType 会进入 multipart 头，拒绝 CR/LF 与双引号，避免拆头。 */
  private static void requireHeaderToken(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('"') >= 0) {
      throw new IllegalArgumentException(field + " must not contain CR, LF, or quotes");
    }
  }

  private static void requirePromptId(String promptId) {
    if (promptId == null || promptId.isBlank()) {
      throw new IllegalArgumentException("promptId must not be blank");
    }
    if (promptId.contains("/") || promptId.contains("\\") || promptId.contains("..")) {
      throw new IllegalArgumentException("promptId must not contain path traversal");
    }
  }

  private static String path(String segment) {
    return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** query 值统一按 RFC 3986 编码：空格用 %20 而非 +，与 ComfyUI 的路径解析保持一致。 */
  private static String query(String value) {
    return path(value == null ? "" : value);
  }

  private static void closeQuietly(AutoCloseable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (Exception ignored) {
        // quiet
      }
    }
  }
}
