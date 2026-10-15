package fun.fengwk.kkstudio.plugin.opencli;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.io.DeadlineResponseBody;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
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

/**
 * OpenCLI Hub 的 HTTP 协议客户端。
 *
 * <p>负责资源上传、作业提交与查询、尽力取消以及产物下载。上传使用定长分块发布者，下载将响应流交给调用方关闭；JSON 和错误响应始终按配置上限读取。
 *
 * <p>远端资源 URL 必须与 Hub 同源并位于 {@code /api/resources/} 下。客户端还会规范化上传文件名，校验响应
 * envelope、输出长度和截断标记，避免把不完整结果交给下游。
 *
 * <p>所有端点统一通过带 scoped 凭据校验的发送路径出网；每次 HTTP 发送前独立解析权威凭据，局部添加 Bearer 认证。 同一份 snapshot 同时提供唯一认证密钥
 * {@code apiKey} 与可选私有身份 {@code instanceId}，后者只填入执行请求体，不构造期读取也不跨请求缓存。 401/403
 * 触发调用期确定性作废，固定去敏错误上抛；无凭据时直接抛出去敏异常，不重试。
 *
 * <p>已取得响应后，任何失败路径都必须释放响应正文流：凭据作废或响应解释抛出的 {@code RuntimeException}/{@code Error} 原样上抛，close 失败只作为
 * suppressed 附加，既不遮蔽原 primary 也不被降级吞掉。
 */
@Slf4j
public class OpenCliHubClient implements AutoCloseable {

  private static final Pattern EXECUTION_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
  private static final Pattern VIRTUAL_RESOURCE_PATH =
      Pattern.compile("/resources/[A-Za-z0-9._~!$&'()+,;=:@%/-]+");
  private static final List<String> TERMINAL_STATUSES =
      List.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED");
  private static final int MAX_UPLOAD_BASENAME_LENGTH = 128;
  private static final int MAX_UPLOAD_EXTENSION_LENGTH = 16;
  private static final String AUTH_REJECTED_ERROR = "OpenCLI Hub authentication rejected";

  private static final ObjectMapper CREDENTIAL_MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final OpenCliSettings settings;
  private final ObjectMapper mapper;
  private final PluginCredentialAccess credentials;
  private final HttpClient httpClient;
  private final URI origin;
  private final boolean ownedClient;
  private final AtomicBoolean closed = new AtomicBoolean();

  public OpenCliHubClient(
      OpenCliSettings settings, ObjectMapper mapper, PluginCredentialAccess credentials) {
    this(
        settings,
        mapper,
        credentials,
        HttpClient.newBuilder()
            .proxy(ProxySelector.getDefault())
            .connectTimeout(
                Duration.ofMillis(
                    Objects.requireNonNull(settings, "settings").connectTimeoutMillis()))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        true);
  }

  OpenCliHubClient(
      OpenCliSettings settings,
      ObjectMapper mapper,
      PluginCredentialAccess credentials,
      HttpClient httpClient) {
    this(settings, mapper, credentials, httpClient, false);
  }

  private OpenCliHubClient(
      OpenCliSettings settings,
      ObjectMapper mapper,
      PluginCredentialAccess credentials,
      HttpClient httpClient,
      boolean ownedClient) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.mapper = Objects.requireNonNull(mapper, "mapper").rebuild().build();
    this.credentials = Objects.requireNonNull(credentials, "credentials");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.ownedClient = ownedClient;
    this.origin =
        settings.baseUrl() == null || settings.baseUrl().isBlank()
            ? null
            : normalizeOrigin(URI.create(settings.baseUrl()));
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      if (ownedClient) {
        httpClient.close();
      }
    }
  }

  /** 以定长 multipart 流上传资源，并校验 Hub 返回的虚拟资源路径。 */
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
                content, size, settings.streamBufferBytes(), "multipart upload"),
            size);
    HttpRequest.BodyPublisher body =
        HttpRequest.BodyPublishers.concat(
            HttpRequest.BodyPublishers.ofByteArray(prefix),
            media,
            HttpRequest.BodyPublishers.ofByteArray(suffix));
    HttpRequest.Builder request =
        request("/api/resources/uploads")
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(body);
    JsonNode data = sendEnvelope(request, requestTimeout());
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
    Objects.requireNonNull(argv, "argv");
    if (argv.isEmpty() || argv.stream().anyMatch(value -> value == null || value.isEmpty())) {
      throw new IllegalArgumentException("argv must contain non-empty tokens");
    }
    List<String> normalized = List.copyOf(argv);
    if (timeoutMillis <= 0L || timeoutMillis > Duration.ofMinutes(30).toMillis()) {
      throw new IllegalArgumentException("timeoutMillis must be between 1 and 1800000");
    }
    // 私有身份只在真正发送前从本次 scoped snapshot 取得，因此请求体在鉴权发送路径内构造。
    CredentialedRequest request =
        credential ->
            request("/api/opencli/execute")
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        writeJson(executeBody(credential.instanceId(), normalized, timeoutMillis)),
                        StandardCharsets.UTF_8));
    return parseExecution(sendEnvelope(request, requestTimeout()));
  }

  /** 执行请求体：只有已配置的私有身份才出现 {@code instanceId}，未配置时交给 Hub 自由路由。 */
  private ObjectNode executeBody(String instanceId, List<String> argv, long timeoutMillis) {
    ObjectNode body = mapper.createObjectNode();
    if (instanceId != null) {
      body.put("instanceId", instanceId);
    }
    ArrayNode argvNode = body.putArray("argv");
    argv.forEach(argvNode::add);
    body.put("timeoutMillis", timeoutMillis);
    return body;
  }

  /** 查询执行状态；{@code waitSeconds > 0} 时使用长轮询超时。 */
  public Execution getExecution(String executionId, int waitSeconds) {
    requireEnabled();
    String id = validateExecutionId(executionId);
    if (waitSeconds < 0 || waitSeconds > 120) {
      throw new IllegalArgumentException("waitSeconds must be between 0 and 120");
    }
    Duration timeout = waitSeconds == 0 ? requestTimeout() : longPollTimeout();
    HttpRequest.Builder request =
        request("/api/executions/" + id + "?waitSeconds=" + waitSeconds).GET();
    return parseExecution(sendEnvelope(request, timeout));
  }

  /** 仅尝试取消 PENDING 作业；Hub 查询或取消失败不会改变调用方的本地终态。 */
  public void cancelPendingBestEffort(String executionId) {
    try {
      Execution execution = getExecution(executionId, 0);
      if (execution.status() != ExecutionStatus.PENDING) {
        return;
      }
      HttpRequest.Builder request =
          request("/api/executions/" + validateExecutionId(executionId) + "/cancel")
              .POST(HttpRequest.BodyPublishers.noBody());
      parseExecution(sendEnvelope(request, requestTimeout()));
    } catch (RuntimeException ignored) {
      log.debug("OpenCLI Hub cancel failed type={}", ignored.getClass().getSimpleName());
    }
  }

  /** 打开通过同源校验的产物响应流；调用方必须关闭返回值。 */
  public HubResourceStream openResource(ExecutionResource resource) {
    requireEnabled();
    Objects.requireNonNull(resource, "resource");
    URI uri = resolveResourceUri(firstText(resource.downloadUrl(), resource.contentUrl()));
    HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET();
    SendResult result = send(request, requestTimeout());
    HttpResponse<InputStream> response = result.response();
    DeadlineResponseBody body = result.body();
    try {
      long contentLength;
      try {
        contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
      } catch (NumberFormatException exception) {
        throw new OpenCliHubException("OpenCLI Hub resource Content-Length is invalid");
      }
      if (contentLength <= 0L) {
        throw new OpenCliHubException(
            "OpenCLI Hub resource response requires positive Content-Length");
      }
      if (resource.size() > 0L && resource.size() != contentLength) {
        throw new OpenCliHubException(
            "OpenCLI Hub resource Content-Length does not match metadata");
      }
      String mediaType = response.headers().firstValue("Content-Type").orElse(resource.mimeType());
      if (mediaType == null || mediaType.isBlank()) {
        throw new OpenCliHubException("OpenCLI Hub resource response requires Content-Type");
      }
      body.expectLength(contentLength);
      return new HubResourceStream(body, contentLength, mediaType.strip());
    } catch (RuntimeException | Error exception) {
      closeAfterFailure(body, exception);
      throw exception;
    }
  }

  /**
   * 统一鉴权发送：每次请求独立解析凭据并构造 Bearer Header； 401/403 触发 rejectUsed CAS 并抛出去敏 HTTP 错误；统一管理 response
   * deadline，并在任何失败路径上释放已取得的响应正文。
   */
  private SendResult send(CredentialedRequest requestFactory, Duration timeout) {
    PluginCredentialSnapshot snapshot = resolveCredential();
    ScopedCredential credential = parseCredential(snapshot.payloadJson());
    HttpRequest request;
    try {
      request =
          requestFactory
              .build(credential)
              .copy()
              .header("Authorization", "Bearer " + credential.apiKey())
              .timeout(timeout)
              .build();
    } catch (IllegalArgumentException exception) {
      log.warn(
          "failed to construct OpenCLI Hub request type={}", exception.getClass().getSimpleName());
      throw new OpenCliHubException("OpenCLI Hub credential is invalid");
    }
    long deadline = System.nanoTime() + timeout.toNanos();
    HttpResponse<InputStream> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException exception) {
      throw hubFailure("OpenCLI Hub request failed", exception);
    } catch (IllegalArgumentException exception) {
      throw new OpenCliHubException("OpenCLI Hub response headers are invalid");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw hubFailure("OpenCLI Hub request interrupted", exception);
    }

    InputStream stream = response.body();
    DeadlineResponseBody body = null;
    try {
      if (response.statusCode() == 401 || response.statusCode() == 403) {
        rejectUsed(snapshot);
      }
      body = new DeadlineResponseBody(stream, deadline);
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw httpError(response.statusCode(), body);
      }
      return new SendResult(response, body);
    } catch (RuntimeException | Error failure) {
      // 凭据作废与错误响应解释都可能任意失败：正文流必须在此释放，且原失败原样上抛。
      closeAfterFailure(body == null ? stream : body, failure);
      throw failure;
    }
  }

  /** 与凭据无关的请求：调用方已在鉴权前构造并校验过 builder。 */
  private SendResult send(HttpRequest.Builder requestBuilder, Duration timeout) {
    return send(credential -> requestBuilder, timeout);
  }

  /** 发送 HTTP 请求并校验解包 OpenCLI Hub 标准响应信封中的业务数据。 */
  private JsonNode sendEnvelope(CredentialedRequest requestFactory, Duration timeout) {
    SendResult result = send(requestFactory, timeout);
    HttpResponse<InputStream> response = result.response();
    try (InputStream body = result.body()) {
      String contentType = response.headers().firstValue("Content-Type").orElse("");
      if (!contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
        throw malformed("success response Content-Type must be application/json");
      }
      byte[] bytes = readBounded(body, settings.maxJsonResponseBytes(), "JSON response");
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
    } catch (JacksonException exception) {
      throw malformed("Result envelope must be valid JSON", exception);
    } catch (IOException exception) {
      throw closeFailure("OpenCLI Hub response", exception);
    }
  }

  /** 与凭据无关的请求：仍走同一鉴权发送路径后再解包信封。 */
  private JsonNode sendEnvelope(HttpRequest.Builder requestBuilder, Duration timeout) {
    return sendEnvelope(credential -> requestBuilder, timeout);
  }

  private PluginCredentialSnapshot resolveCredential() {
    PluginCredentialSnapshot snapshot;
    try {
      snapshot = credentials.resolve();
    } catch (PluginCredentialUnavailableException exception) {
      log.debug("OpenCLI Hub credential unavailable type={}", exception.getClass().getSimpleName());
      throw new OpenCliHubException("OpenCLI Hub credential is not available");
    } catch (RuntimeException exception) {
      log.warn(
          "OpenCLI Hub credential resolution failed type={}", exception.getClass().getSimpleName());
      throw new OpenCliHubException("OpenCLI Hub credential is not available");
    }
    if (snapshot == null || snapshot.payloadJson() == null || snapshot.payloadJson().isBlank()) {
      throw new OpenCliHubException("OpenCLI Hub credential is not available");
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
    return new OpenCliHubException("OpenCLI Hub credential payload is invalid");
  }

  private String validateApiKey(String apiKey) {
    if (apiKey == null || apiKey.isEmpty()) {
      throw new OpenCliHubException("OpenCLI Hub credential payload is invalid");
    }
    for (int i = 0; i < apiKey.length(); i++) {
      char c = apiKey.charAt(i);
      // HTTP header field-value 必须是 ASCII 可打印字符 (0x21 - 0x7E)，严禁换行、空格与控制符
      if (c <= 0x20 || c >= 0x7F) {
        throw new OpenCliHubException("OpenCLI Hub credential payload is invalid");
      }
    }
    return apiKey;
  }

  private void rejectUsed(PluginCredentialSnapshot snapshot) {
    try {
      credentials.rejectUsed(snapshot, AUTH_REJECTED_ERROR);
    } catch (RuntimeException exception) {
      log.debug(
          "failed to reject used OpenCLI Hub credential type={}",
          exception.getClass().getSimpleName());
    }
  }

  private Execution parseExecution(JsonNode data) {
    String id = validateExecutionId(requiredText(data, "id"));
    String rawStatus = requiredText(data, "status");
    ExecutionStatus status;
    try {
      status = ExecutionStatus.valueOf(rawStatus);
    } catch (IllegalArgumentException exception) {
      throw malformed("unknown OpenCLI Hub execution status", exception);
    }
    if (requiredBoolean(data, "stdoutTruncated") || requiredBoolean(data, "stderrTruncated")) {
      throw malformed("truncated OpenCLI Hub execution output is not accepted");
    }
    String stdout = optionalText(data, "stdout");
    String stderr = optionalText(data, "stderr");
    if (stdout.length() > settings.maxOutputChars()
        || stderr.length() > settings.maxOutputChars()) {
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

  private HttpRequest.Builder request(String pathAndQuery) {
    if (origin == null) {
      throw new OpenCliHubException("OpenCLI Hub baseUrl is not configured");
    }
    return HttpRequest.newBuilder(origin.resolve(pathAndQuery));
  }

  private OpenCliHubException httpError(int status, InputStream body) {
    String message = "OpenCLI Hub HTTP " + status;
    try {
      drainErrorBody(body, settings.maxErrorResponseBytes());
    } catch (IOException exception) {
      return hubFailure(message + " error response read failed", exception);
    }
    return new OpenCliHubException(message);
  }

  private void drainErrorBody(InputStream input, int limit) throws IOException {
    byte[] buffer = new byte[Math.min(settings.streamBufferBytes(), 8192)];
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
      ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
      byte[] buffer = new byte[Math.min(settings.streamBufferBytes(), 8192)];
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
      throw hubFailure("failed to read OpenCLI Hub " + description, exception);
    }
  }

  private URI resolveResourceUri(String value) {
    if (origin == null) {
      throw new OpenCliHubException("OpenCLI Hub baseUrl is not configured");
    }
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
    } catch (JacksonException exception) {
      log.warn(
          "failed to encode OpenCLI Hub request type={}", exception.getClass().getSimpleName());
      throw new IllegalStateException("failed to encode OpenCLI Hub request");
    }
  }

  private void requireEnabled() {
    if (!settings.enabled()) {
      throw new OpenCliHubException("OpenCLI Hub integration is disabled");
    }
  }

  private Duration requestTimeout() {
    return Duration.ofMillis(settings.requestTimeoutMillis());
  }

  private Duration longPollTimeout() {
    return Duration.ofMillis(settings.longPollTimeoutMillis());
  }

  private static OpenCliHubException malformed(String message) {
    return new OpenCliHubException("Malformed OpenCLI Hub response: " + message);
  }

  private static OpenCliHubException malformed(String message, Throwable cause) {
    log.warn(
        "Malformed OpenCLI Hub response: {} type={}", message, cause.getClass().getSimpleName());
    return new OpenCliHubException("Malformed OpenCLI Hub response: " + message);
  }

  private static OpenCliHubException hubFailure(String message, Throwable cause) {
    log.warn("OpenCLI Hub failure: {} type={}", message, cause.getClass().getSimpleName());
    return new OpenCliHubException(message, timeoutCause(cause));
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

  private static void closeQuietly(InputStream input) {
    try {
      input.close();
    } catch (IOException ignored) {
      log.debug("OpenCLI Hub stream close failed type={}", ignored.getClass().getSimpleName());
    }
  }

  /**
   * 已取得 response 后的唯一清理：释放正文流并保留原 primary。
   *
   * <p>close 自身失败只作为 suppressed 附加到 primary，绝不替换 primary，也绝不把 Error 降级或吞掉。
   */
  private static void closeAfterFailure(InputStream body, Throwable primary) {
    try {
      body.close();
    } catch (IOException | RuntimeException | Error closeFailure) {
      log.debug("OpenCLI Hub stream close failed type={}", closeFailure.getClass().getSimpleName());
      if (closeFailure != primary) {
        primary.addSuppressed(closeFailure);
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
  private interface CredentialedRequest {

    HttpRequest.Builder build(ScopedCredential credential);
  }

  private record SendResult(HttpResponse<InputStream> response, DeadlineResponseBody body) {}

  public enum ExecutionStatus {
    /** 任务已排队或等待执行。 */
    PENDING,

    /** 任务正在执行中。 */
    RUNNING,

    /** 任务执行成功完成。 */
    SUCCEEDED,

    /** 任务执行失败。 */
    FAILED,

    /** 任务执行超时。 */
    TIMED_OUT,

    /** 任务已被取消。 */
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
