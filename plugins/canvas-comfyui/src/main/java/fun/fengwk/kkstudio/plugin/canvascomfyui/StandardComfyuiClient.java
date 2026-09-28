package fun.fengwk.kkstudio.plugin.canvascomfyui;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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
    baseUri = normalizeBaseUrl(baseUrl);
    this.bearerToken = normalizeBearer(bearerToken);
    this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
    mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    client =
        HttpClient.newBuilder()
            .connectTimeout(requirePositive(connectTimeout, "connectTimeout"))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public H3UploadedFile upload(
      String filename, String mediaType, long contentLength, InputStream content, UUID canvasId) {
    requireFilename(filename);
    if (mediaType == null || mediaType.isBlank()) {
      throw new IllegalArgumentException("mediaType must not be blank");
    }
    if (contentLength <= 0L) {
      throw new IllegalArgumentException("contentLength must be positive");
    }
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(canvasId, "canvasId");
    String boundary = "kkstudio-" + UUID.randomUUID();
    String subfolder = "kk-studio/" + canvasId;
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
      return H3ComfyHistory.error(errorMessage(status));
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
    HttpResponse<InputStream> response = sendStream(request(uri).GET().build());
    if (response.statusCode() / 100 != 2) {
      closeQuietly(response.body());
      throw new IllegalStateException("ComfyUI view failed with HTTP " + response.statusCode());
    }
    long length =
        response
            .headers()
            .firstValueAsLong("Content-Length")
            .orElseThrow(
                () -> {
                  closeQuietly(response.body());
                  return new IllegalArgumentException(
                      "ComfyUI view response requires Content-Length");
                });
    if (length <= 0L) {
      closeQuietly(response.body());
      throw new IllegalArgumentException("ComfyUI view Content-Length must be positive");
    }
    String mediaType =
        response.headers().firstValue("Content-Type").orElse("application/octet-stream");
    return new H3ComfyDownload(response.body(), length, mediaType);
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
    HttpResponse<InputStream> response = send(request);
    try (InputStream content = response.body()) {
      readBounded(content, MAX_JSON_BYTES);
    } catch (IOException error) {
      throw new UncheckedIOException("cannot read ComfyUI queue response", error);
    }
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException(
          "ComfyUI queue delete failed with HTTP " + response.statusCode());
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

  private String errorMessage(ObjectNode status) {
    JsonNode messages = status.get("messages");
    if (messages instanceof ArrayNode array && !array.isEmpty()) {
      List<String> details = new ArrayList<>(array.size());
      for (JsonNode item : array) {
        if (item.isTextual()) {
          details.add(item.textValue());
        } else if (item != null) {
          details.add(item.toString());
        }
      }
      if (!details.isEmpty()) {
        return String.join("; ", details);
      }
    }
    return "unspecified ComfyUI execution failure";
  }

  private JsonNode sendJson(HttpRequest request) {
    HttpResponse<InputStream> response = send(request);
    try (InputStream content = response.body()) {
      byte[] bytes = readBounded(content, MAX_JSON_BYTES);
      if (response.statusCode() / 100 != 2) {
        throw new IllegalStateException(
            "ComfyUI request failed with HTTP "
                + response.statusCode()
                + ": "
                + new String(bytes, StandardCharsets.UTF_8));
      }
      return mapper.readTree(bytes);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("cannot parse ComfyUI JSON response", error);
    } catch (IOException error) {
      throw new UncheckedIOException("cannot read ComfyUI response body", error);
    }
  }

  private HttpResponse<InputStream> send(HttpRequest request) {
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException error) {
      throw new UncheckedIOException("cannot complete ComfyUI HTTP request", error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("ComfyUI HTTP request was interrupted", error);
    }
  }

  private HttpResponse<InputStream> sendStream(HttpRequest request) {
    return send(request);
  }

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
    } catch (JsonProcessingException error) {
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
    AtomicBoolean contentOpened = new AtomicBoolean();
    HttpRequest.BodyPublisher publisher =
        HttpRequest.BodyPublishers.concat(
            HttpRequest.BodyPublishers.ofByteArray(header),
            HttpRequest.BodyPublishers.ofInputStream(
                () -> {
                  if (!contentOpened.compareAndSet(false, true)) {
                    throw new IllegalStateException("upload content stream is already open");
                  }
                  return new FixedLengthInputStream(content, contentLength);
                }),
            HttpRequest.BodyPublishers.ofByteArray(footer));
    // ofInputStream 的 contentLength 恒为 -1，concat 因此也是 -1；用声明总长度恢复固定长度。
    return HttpRequest.BodyPublishers.fromPublisher(publisher, totalLength);
  }

  /** 调用方传入的上传流只允许打开一次。成功读满后返回 EOF 且不再读底层流；JDK 在正常结束时关闭流。读失败、提前结束或取消时由本类关闭。 */
  private static final class FixedLengthInputStream extends FilterInputStream {

    private final long expected;
    private long remaining;
    private final AtomicBoolean closed = new AtomicBoolean();

    private FixedLengthInputStream(InputStream content, long expected) {
      super(Objects.requireNonNull(content, "content"));
      if (expected <= 0L) {
        throw new IllegalArgumentException("contentLength must be positive");
      }
      this.expected = expected;
      remaining = expected;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (closed.get()) {
        throw new IOException("upload content stream is closed");
      }
      if (remaining == 0L || length == 0) {
        return length == 0 && remaining > 0L ? 0 : -1;
      }
      int requested = (int) Math.min(length, remaining);
      int read;
      try {
        read = in.read(buffer, offset, requested);
      } catch (IOException error) {
        close();
        throw error;
      }
      if (read < 0) {
        close();
        throw new IOException(
            "upload content ended after " + (expected - remaining) + " of " + expected + " bytes");
      }
      if (read > requested) {
        close();
        throw new IOException("upload content read exceeded the requested length");
      }
      remaining -= read;
      return read;
    }

    @Override
    public void close() throws IOException {
      if (closed.compareAndSet(false, true)) {
        in.close();
      }
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
    if (filename == null || filename.isBlank()) {
      throw new IllegalArgumentException("filename must not be blank");
    }
    if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
      throw new IllegalArgumentException("filename must not contain directory traversal");
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
