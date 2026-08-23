package fun.fengwk.kkstudio.platform.canvas.function.h3;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Flow;
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

  private H3OutputDescriptor extractOutput(ObjectNode entry) {
    ObjectNode outputs = object(required(entry, "outputs"), "history outputs");
    ObjectNode saveVideo = object(required(outputs, "92"), "history outputs.92");
    JsonNode videosValue = required(saveVideo, "videos");
    if (!(videosValue instanceof ArrayNode videos) || videos.size() != 1) {
      throw new IllegalArgumentException("history outputs.92.videos must contain exactly one item");
    }
    ObjectNode video = object(videos.get(0), "history outputs.92.videos[0]");
    return new H3OutputDescriptor(
        text(video, "filename"), optionalText(video, "subfolder"), text(video, "type"));
  }

  private HttpRequest.Builder request(String relative) {
    return request(endpoint(relative));
  }

  private HttpRequest.Builder request(URI uri) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(requestTimeout);
    if (bearerToken != null) {
      builder.header("Authorization", "Bearer " + bearerToken);
    }
    return builder;
  }

  private URI endpoint(String relative) {
    return baseUri.resolve(relative);
  }

  private JsonNode sendJson(HttpRequest request) {
    HttpResponse<InputStream> response = send(request);
    byte[] bytes;
    try (InputStream content = response.body()) {
      bytes = readBounded(content, MAX_JSON_BYTES);
    } catch (IOException error) {
      throw new UncheckedIOException("cannot read ComfyUI JSON response", error);
    }
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException(
          "ComfyUI request failed with HTTP "
              + response.statusCode()
              + ": "
              + new String(bytes, StandardCharsets.UTF_8));
    }
    try {
      return mapper.readTree(bytes);
    } catch (IOException error) {
      throw new IllegalArgumentException("ComfyUI returned invalid JSON", error);
    }
  }

  private HttpResponse<InputStream> send(HttpRequest request) {
    return sendStream(request);
  }

  private HttpResponse<InputStream> sendStream(HttpRequest request) {
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException error) {
      throw new UncheckedIOException("ComfyUI HTTP request failed", error);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("ComfyUI HTTP request was interrupted", interrupted);
    }
  }

  private HttpRequest.BodyPublisher jsonBody(JsonNode value) {
    byte[] bytes;
    try {
      bytes = mapper.writeValueAsBytes(value);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("ComfyUI request body is not JSON serializable", error);
    }
    if (bytes.length > MAX_JSON_BYTES) {
      throw new IllegalArgumentException(
          "ComfyUI JSON request exceeds " + MAX_JSON_BYTES + " bytes");
    }
    return HttpRequest.BodyPublishers.ofByteArray(bytes);
  }

  private static HttpRequest.BodyPublisher multipart(
      String boundary,
      String filename,
      String mediaType,
      long contentLength,
      InputStream content,
      String subfolder) {
    List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
    parts.add(textPart(boundary, "type", "input"));
    parts.add(textPart(boundary, "overwrite", "true"));
    parts.add(textPart(boundary, "subfolder", subfolder));
    parts.add(
        bytes(
            "--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\""
                + filename
                + "\"\r\nContent-Type: "
                + mediaType
                + "\r\n\r\n"));
    AtomicBoolean opened = new AtomicBoolean();
    Supplier<InputStream> supplier =
        () -> {
          if (!opened.compareAndSet(false, true)) {
            throw new IllegalStateException("multipart media stream may only be opened once");
          }
          return content;
        };
    Flow.Publisher<? extends ByteBuffer> streamPublisher =
        HttpRequest.BodyPublishers.ofInputStream(supplier);
    parts.add(HttpRequest.BodyPublishers.fromPublisher(streamPublisher, contentLength));
    parts.add(bytes("\r\n--" + boundary + "--\r\n"));
    return HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new));
  }

  private static HttpRequest.BodyPublisher textPart(String boundary, String name, String value) {
    return bytes(
        "--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=\""
            + name
            + "\"\r\n\r\n"
            + value
            + "\r\n");
  }

  private static HttpRequest.BodyPublisher bytes(String value) {
    return HttpRequest.BodyPublishers.ofByteArray(value.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(8192, maxBytes));
    byte[] buffer = new byte[8192];
    int total = 0;
    int read;
    while ((read = input.read(buffer)) != -1) {
      total += read;
      if (total > maxBytes) {
        throw new IllegalArgumentException("ComfyUI JSON response exceeds " + maxBytes + " bytes");
      }
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private static boolean containsPrompt(ArrayNode queue, String promptId) {
    for (JsonNode item : queue) {
      if (!(item instanceof ArrayNode values) || values.size() < 2 || !values.get(1).isTextual()) {
        throw new IllegalArgumentException("ComfyUI queue item has an invalid shape");
      }
      if (promptId.equals(values.get(1).textValue())) {
        return true;
      }
    }
    return false;
  }

  private static ArrayNode requiredArray(ObjectNode object, String field) {
    JsonNode value = required(object, field);
    if (!(value instanceof ArrayNode array)) {
      throw new IllegalArgumentException(field + " must be an array");
    }
    return array;
  }

  private static ObjectNode object(JsonNode value, String field) {
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException(field + " must be an object");
    }
    return object;
  }

  private static JsonNode required(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException(field + " is required and must not be null");
    }
    return value;
  }

  private static String text(ObjectNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(field + " must be non-blank text");
    }
    return value.textValue();
  }

  private static String optionalText(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return "";
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(field + " must be text");
    }
    return value.textValue();
  }

  private static String errorMessage(ObjectNode status) {
    JsonNode messages = status.get("messages");
    return messages == null ? "ComfyUI execution failed" : messages.toString();
  }

  private static URI normalizeBaseUrl(String value) {
    if (value == null || value.isBlank() || !value.equals(value.strip())) {
      throw new IllegalArgumentException("ComfyUI baseUrl must be non-blank without whitespace");
    }
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("ComfyUI baseUrl must be a valid URI", error);
    }
    if (!uri.isAbsolute()
        || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
      throw new IllegalArgumentException(
          "ComfyUI baseUrl must be an absolute HTTP(S) origin without path/query/fragment/userinfo");
    }
    return URI.create(value.endsWith("/") ? value : value + "/");
  }

  private static String normalizeBearer(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    if (!value.equals(value.strip())
        || value.chars().anyMatch(character -> Character.isISOControl(character))) {
      throw new IllegalArgumentException("ComfyUI bearer token is invalid");
    }
    return value;
  }

  private static void requirePromptId(String promptId) {
    if (promptId == null || !promptId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
      throw new IllegalArgumentException("promptId is invalid");
    }
  }

  private static void requireFilename(String filename) {
    if (filename == null
        || !filename.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
        || filename.contains("..")) {
      throw new IllegalArgumentException("filename is invalid");
    }
  }

  private static Duration requirePositive(Duration value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(field + " must be positive");
    }
    return value;
  }

  private static String path(String value) {
    return query(value);
  }

  private static String query(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static void closeQuietly(InputStream input) {
    try {
      input.close();
    } catch (IOException ignored) {
      // best effort
    }
  }
}
