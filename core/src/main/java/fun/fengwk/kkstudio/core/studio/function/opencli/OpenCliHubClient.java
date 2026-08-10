package fun.fengwk.kkstudio.core.studio.function.opencli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** 零媒体聚合的 OpenCLI Hub HTTP client。 */
public class OpenCliHubClient {

  private static final Pattern EXECUTION_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
  private static final Pattern VIRTUAL_RESOURCE_PATH =
      Pattern.compile("/resources/[A-Za-z0-9._~!$&'()+,;=:@%/-]+");
  private static final List<String> TERMINAL_STATUSES =
      List.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED");
  private static final int MAX_UPLOAD_BASENAME_LENGTH = 128;
  private static final int MAX_UPLOAD_EXTENSION_LENGTH = 16;

  private final OpenCliHubProperties properties;
  private final ObjectMapper mapper;
  private final HttpClient httpClient;
  private final URI origin;

  public OpenCliHubClient(OpenCliHubProperties properties, ObjectMapper objectMapper) {
    this.properties = Objects.requireNonNull(properties, "properties");
    properties.validate();
    mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    origin = normalizeOrigin(properties.getBaseUrl());
    httpClient =
        HttpClient.newBuilder()
            .connectTimeout(properties.getConnectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  OpenCliHubClient(
      OpenCliHubProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
    this.properties = Objects.requireNonNull(properties, "properties");
    properties.validate();
    mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    origin = normalizeOrigin(properties.getBaseUrl());
  }

  public UploadedResource upload(
      String filename, String mediaType, long size, InputStream content) {
    requireEnabled();
    if (size <= 0L) {
      throw new IllegalArgumentException("upload size must be positive");
    }
    Objects.requireNonNull(content, "content");
    String safeFilename = safeBasename(filename);
    String safeMediaType = safeMediaType(mediaType);
    String boundary = "kkstudio-" + UUID.randomUUID();
    byte[] prefix =
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\""
                + safeFilename
                + "\"\r\nContent-Type: "
                + safeMediaType
                + "\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8);
    byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
    HttpRequest.BodyPublisher media =
        HttpRequest.BodyPublishers.fromPublisher(
            new FixedInputStreamPublisher(
                content, size, properties.getStreamBufferBytes(), "multipart upload"),
            size);
    HttpRequest.BodyPublisher body =
        HttpRequest.BodyPublishers.concat(
            HttpRequest.BodyPublishers.ofByteArray(prefix),
            media,
            HttpRequest.BodyPublishers.ofByteArray(suffix));
    HttpRequest request =
        request("/api/resources/uploads", properties.getRequestTimeout())
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(body)
            .build();
    JsonNode data = sendEnvelope(request);
    JsonNode items = required(data, "items");
    if (!(items instanceof ArrayNode array) || array.size() != 1) {
      throw malformed("upload data.items must contain exactly one item");
    }
    String resourcePath = requiredText(array.get(0), "resourcePath");
    validateVirtualResourcePath(resourcePath);
    return new UploadedResource(resourcePath);
  }

  public Execution execute(List<String> argv, long timeoutMillis) {
    requireEnabled();
    List<String> normalized = List.copyOf(Objects.requireNonNull(argv, "argv"));
    if (normalized.isEmpty()
        || normalized.stream().anyMatch(value -> value == null || value.isEmpty())) {
      throw new IllegalArgumentException("argv must contain non-empty tokens");
    }
    if (timeoutMillis <= 0L || timeoutMillis > Duration.ofMinutes(30).toMillis()) {
      throw new IllegalArgumentException("timeoutMillis must be between 1 and 1800000");
    }
    ObjectNode body = mapper.createObjectNode();
    if (properties.getInstanceId() != null) {
      body.put("instanceId", properties.getInstanceId());
    }
    ArrayNode argvNode = body.putArray("argv");
    normalized.forEach(argvNode::add);
    body.put("timeoutMillis", timeoutMillis);
    HttpRequest request =
        request("/api/opencli/execute", properties.getRequestTimeout())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(writeJson(body), StandardCharsets.UTF_8))
            .build();
    return parseExecution(sendEnvelope(request));
  }

  public Execution getExecution(String executionId, int waitSeconds) {
    requireEnabled();
    String id = validateExecutionId(executionId);
    if (waitSeconds < 0 || waitSeconds > 120) {
      throw new IllegalArgumentException("waitSeconds must be between 0 and 120");
    }
    Duration timeout =
        waitSeconds == 0 ? properties.getRequestTimeout() : properties.getLongPollTimeout();
    HttpRequest request =
        request("/api/executions/" + id + "?waitSeconds=" + waitSeconds, timeout).GET().build();
    return parseExecution(sendEnvelope(request));
  }

  public void cancelPendingBestEffort(String executionId) {
    try {
      Execution execution = getExecution(executionId, 0);
      if (execution.status() != ExecutionStatus.PENDING) {
        return;
      }
      HttpRequest request =
          request(
                  "/api/executions/" + validateExecutionId(executionId) + "/cancel",
                  properties.getRequestTimeout())
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      parseExecution(sendEnvelope(request));
    } catch (RuntimeException ignored) {
      // Foundation DB cancellation is authoritative; Hub cancellation is deliberately best effort.
    }
  }

  public HubResourceStream openResource(ExecutionResource resource) {
    requireEnabled();
    Objects.requireNonNull(resource, "resource");
    URI uri = resolveResourceUri(firstText(resource.downloadUrl(), resource.contentUrl()));
    HttpRequest request =
        HttpRequest.newBuilder(uri).timeout(properties.getRequestTimeout()).GET().build();
    HttpResponse<InputStream> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException exception) {
      throw new OpenCliHubException("OpenCLI Hub resource request failed", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new OpenCliHubException("OpenCLI Hub resource request interrupted", exception);
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      try (InputStream body = response.body()) {
        throw httpError(response.statusCode(), body);
      } catch (IOException exception) {
        throw new OpenCliHubException("failed to close OpenCLI Hub error response", exception);
      }
    }
    long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
    if (contentLength <= 0L) {
      closeQuietly(response.body());
      throw new OpenCliHubException(
          "OpenCLI Hub resource response requires positive Content-Length");
    }
    if (resource.size() > 0L && resource.size() != contentLength) {
      closeQuietly(response.body());
      throw new OpenCliHubException("OpenCLI Hub resource Content-Length does not match metadata");
    }
    String mediaType = response.headers().firstValue("Content-Type").orElse(resource.mimeType());
    if (mediaType == null || mediaType.isBlank()) {
      closeQuietly(response.body());
      throw new OpenCliHubException("OpenCLI Hub resource response requires Content-Type");
    }
    return new HubResourceStream(response.body(), contentLength, mediaType.strip());
  }

  private JsonNode sendEnvelope(HttpRequest request) {
    HttpResponse<InputStream> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException exception) {
      throw new OpenCliHubException("OpenCLI Hub request failed", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new OpenCliHubException("OpenCLI Hub request interrupted", exception);
    }
    try (InputStream body = response.body()) {
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw httpError(response.statusCode(), body);
      }
      String contentType = response.headers().firstValue("Content-Type").orElse("");
      if (!contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
        throw malformed("success response Content-Type must be application/json");
      }
      byte[] bytes = readBounded(body, properties.getMaxJsonResponseBytes(), "JSON response");
      JsonNode root = mapper.readTree(bytes);
      if (root == null || !root.isObject()) {
        throw malformed("Result envelope must be an object");
      }
      JsonNode status = required(root, "status");
      if (!status.isIntegralNumber() || status.intValue() != response.statusCode()) {
        throw malformed("Result envelope status must match HTTP status");
      }
      requiredText(root, "code");
      JsonNode success = root.get("success");
      if (success != null && (!success.isBoolean() || !success.booleanValue())) {
        throw malformed("Result envelope success must be true");
      }
      return required(root, "data");
    } catch (JsonProcessingException exception) {
      throw malformed("Result envelope must be valid JSON", exception);
    } catch (IOException exception) {
      throw new UncheckedIOException("failed to close OpenCLI Hub response", exception);
    }
  }

  private Execution parseExecution(JsonNode data) {
    String id = validateExecutionId(requiredText(data, "id"));
    String rawStatus = requiredText(data, "status");
    ExecutionStatus status;
    try {
      status = ExecutionStatus.valueOf(rawStatus);
    } catch (IllegalArgumentException exception) {
      throw malformed("unknown OpenCLI Hub execution status: " + rawStatus, exception);
    }
    if (requiredBoolean(data, "stdoutTruncated") || requiredBoolean(data, "stderrTruncated")) {
      throw malformed("truncated OpenCLI Hub execution output is not accepted");
    }
    String stdout = optionalText(data, "stdout");
    String stderr = optionalText(data, "stderr");
    if (stdout.length() > properties.getMaxOutputChars()
        || stderr.length() > properties.getMaxOutputChars()) {
      throw malformed("OpenCLI Hub execution output exceeds local limit");
    }
    List<ExecutionResource> resources = new ArrayList<>();
    JsonNode rawResources = data.get("resources");
    if (rawResources != null && !rawResources.isNull()) {
      if (!(rawResources instanceof ArrayNode array)) {
        throw malformed("execution resources must be an array");
      }
      for (JsonNode item : array) {
        long size = requiredLong(item, "size");
        if (size <= 0L) {
          throw malformed("execution resource size must be positive");
        }
        resources.add(
            new ExecutionResource(
                requiredText(item, "fileName"),
                requiredText(item, "mimeType"),
                size,
                optionalNullableText(item, "contentUrl"),
                optionalNullableText(item, "downloadUrl")));
      }
    }
    return new Execution(id, status, stdout, stderr, List.copyOf(resources));
  }

  private HttpRequest.Builder request(String pathAndQuery, Duration timeout) {
    return HttpRequest.newBuilder(origin.resolve(pathAndQuery)).timeout(timeout);
  }

  private OpenCliHubException httpError(int status, InputStream body) {
    TruncatedBytes truncated = readTruncated(body, properties.getMaxErrorResponseBytes());
    String detail =
        new String(truncated.bytes(), StandardCharsets.UTF_8).replaceAll("\\p{Cntrl}", " ").strip();
    if (truncated.truncated()) {
      detail += " [truncated]";
    }
    return new OpenCliHubException(
        "OpenCLI Hub HTTP " + status + (detail.isEmpty() ? "" : ": " + detail));
  }

  private TruncatedBytes readTruncated(InputStream input, int limit) {
    try {
      ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
      byte[] buffer = new byte[Math.min(properties.getStreamBufferBytes(), 8192)];
      int remaining = limit;
      while (remaining > 0) {
        int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
        if (read == -1) {
          return new TruncatedBytes(output.toByteArray(), false);
        }
        output.write(buffer, 0, read);
        remaining -= read;
      }
      return new TruncatedBytes(output.toByteArray(), input.read() != -1);
    } catch (IOException exception) {
      throw new OpenCliHubException("failed to read OpenCLI Hub error response", exception);
    }
  }

  private byte[] readBounded(InputStream input, int limit, String description) {
    try {
      ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
      byte[] buffer = new byte[Math.min(properties.getStreamBufferBytes(), 8192)];
      int total = 0;
      int read;
      while ((read = input.read(buffer)) != -1) {
        if (total > limit - read) {
          throw new OpenCliHubException("OpenCLI Hub " + description + " exceeds local limit");
        }
        output.write(buffer, 0, read);
        total += read;
      }
      return output.toByteArray();
    } catch (IOException exception) {
      throw new OpenCliHubException("failed to read OpenCLI Hub " + description, exception);
    }
  }

  private URI resolveResourceUri(String value) {
    if (value == null || value.isBlank()) {
      throw malformed("execution resource has no contentUrl/downloadUrl");
    }
    URI parsed;
    try {
      parsed = URI.create(value);
    } catch (IllegalArgumentException exception) {
      throw malformed("execution resource URL is invalid", exception);
    }
    URI resolved = parsed.isAbsolute() ? parsed : origin.resolve(parsed);
    URI normalized = resolved.normalize();
    if (!sameOrigin(origin, resolved)
        || resolved.getUserInfo() != null
        || resolved.getFragment() != null
        || resolved.getRawPath() == null
        || !resolved.getRawPath().startsWith("/api/resources/")
        || hasDotSegment(resolved.getPath())
        || normalized.getRawPath() == null
        || !normalized.getRawPath().startsWith("/api/resources/")) {
      throw malformed(
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

  private static String safeBasename(String filename) {
    if (filename == null) {
      throw new IllegalArgumentException("filename must not be null");
    }
    String normalized = Normalizer.normalize(filename, Normalizer.Form.NFKC);
    int slash = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
    String basename = (slash >= 0 ? normalized.substring(slash + 1) : normalized).strip();
    if (basename.isEmpty() || ".".equals(basename) || "..".equals(basename)) {
      return "resource.bin";
    }

    String extension = safeTrailingExtension(basename);
    String rawStem = basename.substring(0, basename.length() - extension.length());
    int stemBudget = MAX_UPLOAD_BASENAME_LENGTH - extension.length();
    StringBuilder safeStem = new StringBuilder(Math.min(rawStem.length(), stemBudget));
    for (int index = 0; index < rawStem.length() && safeStem.length() < stemBudget; ) {
      int codePoint = rawStem.codePointAt(index);
      if ((codePoint >= 'A' && codePoint <= 'Z')
          || (codePoint >= 'a' && codePoint <= 'z')
          || (codePoint >= '0' && codePoint <= '9')
          || codePoint == '.'
          || codePoint == '_'
          || codePoint == '-') {
        safeStem.append((char) codePoint);
      } else {
        safeStem.append('_');
      }
      index += Character.charCount(codePoint);
    }
    if (safeStem.isEmpty()) {
      safeStem.append('_');
    }
    for (int index = 0; index < safeStem.length() && safeStem.charAt(index) == '.'; index++) {
      safeStem.setCharAt(index, '_');
    }
    for (int index = safeStem.length() - 1; index >= 0 && safeStem.charAt(index) == '.'; index--) {
      safeStem.setCharAt(index, '_');
    }

    String result = safeStem.append(extension).toString();
    if (result.isBlank() || ".".equals(result) || "..".equals(result)) {
      return "resource.bin";
    }
    return result;
  }

  private static String safeTrailingExtension(String basename) {
    int dot = basename.lastIndexOf('.');
    int extensionLength = basename.length() - dot - 1;
    if (dot < 0 || extensionLength < 1 || extensionLength > MAX_UPLOAD_EXTENSION_LENGTH) {
      return "";
    }
    for (int index = dot + 1; index < basename.length(); index++) {
      char character = basename.charAt(index);
      if (!((character >= 'A' && character <= 'Z')
          || (character >= 'a' && character <= 'z')
          || (character >= '0' && character <= '9'))) {
        return "";
      }
    }
    return basename.substring(dot);
  }

  private static String safeMediaType(String mediaType) {
    if (mediaType == null) {
      throw new IllegalArgumentException("mediaType must not be null");
    }
    String normalized = mediaType.strip().toLowerCase(Locale.ROOT);
    if (!normalized.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) {
      throw new IllegalArgumentException("mediaType must be a simple MIME type");
    }
    return normalized;
  }

  private static void validateVirtualResourcePath(String resourcePath) {
    URI parsed;
    try {
      parsed = URI.create(resourcePath);
    } catch (IllegalArgumentException exception) {
      throw malformed("upload resourcePath must be a canonical /resources/ path", exception);
    }
    if (!VIRTUAL_RESOURCE_PATH.matcher(resourcePath).matches()
        || parsed.getQuery() != null
        || parsed.getFragment() != null
        || hasDotSegment(parsed.getPath())
        || !parsed.normalize().getPath().startsWith("/resources/")) {
      throw malformed("upload resourcePath must be a canonical /resources/ path");
    }
  }

  private static boolean hasDotSegment(String path) {
    if (path == null) {
      return true;
    }
    for (String segment : path.split("/", -1)) {
      if (".".equals(segment) || "..".equals(segment)) {
        return true;
      }
    }
    return false;
  }

  private static String validateExecutionId(String value) {
    if (value == null || !EXECUTION_ID.matcher(value).matches()) {
      throw new IllegalArgumentException("executionId is invalid");
    }
    return value;
  }

  private static JsonNode required(JsonNode node, String field) {
    if (node == null || !node.isObject()) {
      throw malformed("expected JSON object while reading " + field);
    }
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw malformed(field + " is required");
    }
    return value;
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw malformed(field + " must be non-blank text");
    }
    return value.textValue();
  }

  private static boolean requiredBoolean(JsonNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isBoolean()) {
      throw malformed(field + " must be boolean");
    }
    return value.booleanValue();
  }

  private static long requiredLong(JsonNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw malformed(field + " must be an integer");
    }
    return value.longValue();
  }

  private static String optionalText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return "";
    }
    if (!value.isTextual()) {
      throw malformed(field + " must be text or null");
    }
    return value.textValue();
  }

  private static String optionalNullableText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw malformed(field + " must be non-blank text or null");
    }
    return value.textValue();
  }

  private static String firstText(String first, String second) {
    return first != null && !first.isBlank() ? first : second;
  }

  private String writeJson(JsonNode value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("failed to encode OpenCLI Hub request", exception);
    }
  }

  private void requireEnabled() {
    if (!properties.isEnabled()) {
      throw new OpenCliHubException("OpenCLI Hub integration is disabled");
    }
  }

  private static OpenCliHubException malformed(String message) {
    return new OpenCliHubException("Malformed OpenCLI Hub response: " + message);
  }

  private static OpenCliHubException malformed(String message, Throwable cause) {
    return new OpenCliHubException("Malformed OpenCLI Hub response: " + message, cause);
  }

  private static void closeQuietly(InputStream input) {
    try {
      input.close();
    } catch (IOException ignored) {
      // The primary contract error is more useful than a secondary close failure.
    }
  }

  public enum ExecutionStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED;

    public boolean terminal() {
      return TERMINAL_STATUSES.contains(name());
    }
  }

  public record UploadedResource(String resourcePath) {}

  public record Execution(
      String id,
      ExecutionStatus status,
      String stdout,
      String stderr,
      List<ExecutionResource> resources) {}

  public record ExecutionResource(
      String fileName, String mimeType, long size, String contentUrl, String downloadUrl) {}

  private record TruncatedBytes(byte[] bytes, boolean truncated) {}

  /** 调用方必须关闭的 Hub 媒体响应。 */
  public static final class HubResourceStream implements AutoCloseable {

    private final InputStream content;
    private final long size;
    private final String mediaType;

    HubResourceStream(InputStream content, long size, String mediaType) {
      this.content = content;
      this.size = size;
      this.mediaType = mediaType;
    }

    public InputStream content() {
      return content;
    }

    public long size() {
      return size;
    }

    public String mediaType() {
      return mediaType;
    }

    @Override
    public void close() throws IOException {
      content.close();
    }
  }

  private static final class FixedInputStreamPublisher implements Flow.Publisher<ByteBuffer> {

    private final InputStream input;
    private final long length;
    private final int bufferSize;
    private final String description;
    private final AtomicBoolean subscribed = new AtomicBoolean();

    private FixedInputStreamPublisher(
        InputStream input, long length, int bufferSize, String description) {
      this.input = input;
      this.length = length;
      this.bufferSize = bufferSize;
      this.description = description;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
      Objects.requireNonNull(subscriber, "subscriber");
      if (!subscribed.compareAndSet(false, true)) {
        subscriber.onSubscribe(
            new Flow.Subscription() {
              @Override
              public void request(long count) {}

              @Override
              public void cancel() {}
            });
        subscriber.onError(new IllegalStateException(description + " body cannot be replayed"));
        return;
      }
      subscriber.onSubscribe(new InputSubscription(subscriber));
    }

    private final class InputSubscription implements Flow.Subscription {

      private final Flow.Subscriber<? super ByteBuffer> subscriber;
      private long remaining = length;
      private long demand;
      private boolean draining;
      private boolean done;

      private InputSubscription(Flow.Subscriber<? super ByteBuffer> subscriber) {
        this.subscriber = subscriber;
      }

      @Override
      public synchronized void request(long count) {
        if (done) {
          return;
        }
        if (count <= 0L) {
          fail(new IllegalArgumentException("non-positive subscription demand"));
          return;
        }
        demand = demand > Long.MAX_VALUE - count ? Long.MAX_VALUE : demand + count;
        if (draining) {
          return;
        }
        draining = true;
        try {
          while (!done && demand > 0L) {
            if (remaining == 0L) {
              complete();
              return;
            }
            int chunkSize = (int) Math.min(bufferSize, remaining);
            byte[] bytes = input.readNBytes(chunkSize);
            if (bytes.length != chunkSize) {
              fail(new IOException(description + " ended before declared Content-Length"));
              return;
            }
            remaining -= bytes.length;
            demand--;
            subscriber.onNext(ByteBuffer.wrap(bytes));
            if (!done && remaining == 0L) {
              complete();
              return;
            }
          }
        } catch (Throwable error) {
          fail(error);
        } finally {
          draining = false;
        }
      }

      @Override
      public synchronized void cancel() {
        if (!done) {
          done = true;
          closeQuietly(input);
        }
      }

      private void complete() {
        done = true;
        closeQuietly(input);
        subscriber.onComplete();
      }

      private void fail(Throwable error) {
        done = true;
        closeQuietly(input);
        subscriber.onError(error);
      }
    }
  }
}
