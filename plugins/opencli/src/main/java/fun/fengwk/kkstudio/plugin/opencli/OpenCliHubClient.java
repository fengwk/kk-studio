package fun.fengwk.kkstudio.plugin.opencli;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.support.DeadlineResponseBody;
import fun.fengwk.kkstudio.platform.plugin.support.PluginHttpSupport;
import fun.fengwk.kkstudio.plugin.opencli.OpenCliHubException.Kind;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * OpenCLI Hub 的 HTTP 协议客户端。
 *
 * <p>负责资源上传、作业提交与查询、尽力取消以及产物下载；鉴权发送、信封校验、凭据解析与 URL 同源判定由 {@link OpenCliHubTransport} 与 {@link
 * OpenCliHubProtocol} 承担。上传使用定长流，下载将响应流交给调用方关闭；JSON 和错误响应始终有界读取。
 *
 * <p>所有端点统一通过带 scoped 凭据校验的发送路径出网；每次 HTTP 发送前独立解析权威凭据，局部添加 Bearer 认证。 同一份 snapshot 同时提供唯一认证密钥
 * {@code apiKey} 与可选私有身份 {@code instanceId}，后者只填入执行请求体，不构造期读取也不跨请求缓存。 401/403
 * 触发调用期确定性作废，固定去敏错误上抛；无凭据时直接抛出去敏异常，不重试。
 */
@Slf4j
public class OpenCliHubClient implements AutoCloseable {

  private static final Pattern EXECUTION_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
  private static final List<String> TERMINAL_STATUSES =
      List.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED");
  static final int MAX_OUTPUT_CHARS = 65_535;

  private final OpenCliSettings settings;
  private final ObjectMapper mapper;
  private final HttpClient httpClient;
  private final boolean ownedClient;
  private final OpenCliHubTransport transport;
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
    this.mapper = PluginHttpSupport.strictJson(mapper);
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.ownedClient = ownedClient;
    this.transport = new OpenCliHubTransport(this.mapper, credentials, httpClient, settings);
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
    String safeFilename = OpenCliHubNames.safeBasename(filename);
    String safeMediaType = OpenCliHubNames.safeMediaType(mediaType);
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
    AtomicBoolean subscribed = new AtomicBoolean();
    HttpRequest.BodyPublisher media =
        HttpRequest.BodyPublishers.fromPublisher(
            HttpRequest.BodyPublishers.ofInputStream(
                () -> {
                  if (!subscribed.compareAndSet(false, true)) {
                    throw new IllegalStateException("multipart upload body cannot be replayed");
                  }
                  return new FixedLengthInputStream(content, size);
                }),
            size);
    HttpRequest.BodyPublisher body =
        HttpRequest.BodyPublishers.concat(
            HttpRequest.BodyPublishers.ofByteArray(prefix),
            media,
            HttpRequest.BodyPublishers.ofByteArray(suffix));
    HttpRequest.Builder request =
        transport
            .request("/api/resources/uploads")
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(body);
    JsonNode data = transport.sendEnvelope(request, requestTimeout());
    JsonNode items = OpenCliHubProtocol.required(data, "items");
    if (!(items instanceof ArrayNode array) || array.size() != 1) {
      throw OpenCliHubProtocol.malformed("upload data.items must contain exactly one item");
    }
    String resourcePath = OpenCliHubProtocol.requiredText(array.get(0), "resourcePath");
    OpenCliHubProtocol.requireCanonicalResourcePath(resourcePath);
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
    OpenCliHubTransport.CredentialedRequest request =
        credential ->
            transport
                .request("/api/opencli/execute")
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        writeJson(executeBody(credential.instanceId(), normalized, timeoutMillis)),
                        StandardCharsets.UTF_8));
    return parseExecution(transport.sendEnvelope(request, requestTimeout()));
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
        transport.request("/api/executions/" + id + "?waitSeconds=" + waitSeconds).GET();
    return parseExecution(transport.sendEnvelope(request, timeout));
  }

  /** 仅尝试取消 PENDING 作业；Hub 查询或取消失败不会改变调用方的本地终态。 */
  public void cancelPendingBestEffort(String executionId) {
    try {
      Execution execution = getExecution(executionId, 0);
      if (execution.status() != ExecutionStatus.PENDING) {
        return;
      }
      HttpRequest.Builder request =
          transport
              .request("/api/executions/" + validateExecutionId(executionId) + "/cancel")
              .POST(HttpRequest.BodyPublishers.noBody());
      parseExecution(transport.sendEnvelope(request, requestTimeout()));
    } catch (RuntimeException ignored) {
      log.debug("OpenCLI Hub cancel failed type={}", ignored.getClass().getSimpleName());
    }
  }

  /**
   * 显式只读连接检查：用与其它端点完全相同的 scoped 凭据解析、Bearer、redirect NEVER、有界正文与 deadline 发送 {@code GET
   * /api/resources/dates}，并要求返回合法信封且 {@code data} 是数组。
   *
   * <p>它不提交作业、不生成内容、也不写入任何远端状态；401/403 复用同一鉴权路径，只作废本次凭据。失败只抛出带 {@link OpenCliHubException#kind()}
   * 的去敏异常，绝不回显凭据、私有身份、远端正文或 URL。
   */
  public void probeConnection() {
    requireEnabled();
    JsonNode data =
        transport.sendEnvelope(transport.request("/api/resources/dates").GET(), requestTimeout());
    if (!(data instanceof ArrayNode)) {
      throw OpenCliHubProtocol.malformed("resources dates data must be an array");
    }
  }

  /** 打开通过同源校验的产物响应流；调用方必须关闭返回值。 */
  public HubResourceStream openResource(ExecutionResource resource) {
    requireEnabled();
    Objects.requireNonNull(resource, "resource");
    URI uri =
        transport.resolveResourceUri(firstText(resource.downloadUrl(), resource.contentUrl()));
    OpenCliHubTransport.Response result =
        transport.send(HttpRequest.newBuilder(uri).GET(), requestTimeout());
    HttpResponse<InputStream> response = result.response();
    DeadlineResponseBody body = result.body();
    try {
      long contentLength;
      try {
        contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
      } catch (NumberFormatException exception) {
        throw new OpenCliHubException(
            Kind.PROTOCOL, "OpenCLI Hub resource Content-Length is invalid");
      }
      if (contentLength <= 0L) {
        throw new OpenCliHubException(
            Kind.PROTOCOL, "OpenCLI Hub resource response requires positive Content-Length");
      }
      if (resource.size() > 0L && resource.size() != contentLength) {
        throw new OpenCliHubException(
            Kind.PROTOCOL, "OpenCLI Hub resource Content-Length does not match metadata");
      }
      String mediaType = response.headers().firstValue("Content-Type").orElse(resource.mimeType());
      if (mediaType == null || mediaType.isBlank()) {
        throw new OpenCliHubException(
            Kind.PROTOCOL, "OpenCLI Hub resource response requires Content-Type");
      }
      body.expectLength(contentLength);
      return new HubResourceStream(body, contentLength, mediaType.strip());
    } catch (RuntimeException | Error exception) {
      OpenCliHubTransport.closeAfterFailure(body, exception);
      throw exception;
    }
  }

  private Execution parseExecution(JsonNode data) {
    String id = validateExecutionId(OpenCliHubProtocol.requiredText(data, "id"));
    String rawStatus = OpenCliHubProtocol.requiredText(data, "status");
    ExecutionStatus status;
    try {
      status = ExecutionStatus.valueOf(rawStatus);
    } catch (IllegalArgumentException exception) {
      throw OpenCliHubProtocol.malformed("unknown OpenCLI Hub execution status", exception);
    }
    if (OpenCliHubProtocol.requiredBoolean(data, "stdoutTruncated")
        || OpenCliHubProtocol.requiredBoolean(data, "stderrTruncated")) {
      throw OpenCliHubProtocol.malformed("truncated OpenCLI Hub execution output is not accepted");
    }
    String stdout = OpenCliHubProtocol.optionalText(data, "stdout");
    String stderr = OpenCliHubProtocol.optionalText(data, "stderr");
    if (stdout.length() > MAX_OUTPUT_CHARS || stderr.length() > MAX_OUTPUT_CHARS) {
      throw OpenCliHubProtocol.malformed("OpenCLI Hub execution output exceeds local limit");
    }
    List<ExecutionResource> resources = new ArrayList<>();
    JsonNode rawResources = data.get("resources");
    if (rawResources != null && !rawResources.isNull()) {
      if (!(rawResources instanceof ArrayNode array)) {
        throw OpenCliHubProtocol.malformed("execution resources must be an array");
      }
      for (JsonNode item : array) {
        long size = OpenCliHubProtocol.requiredLong(item, "size");
        if (size <= 0L) {
          throw OpenCliHubProtocol.malformed("execution resource size must be positive");
        }
        resources.add(
            new ExecutionResource(
                OpenCliHubProtocol.requiredText(item, "fileName"),
                OpenCliHubProtocol.requiredText(item, "mimeType"),
                size,
                OpenCliHubProtocol.optionalNullableText(item, "contentUrl"),
                OpenCliHubProtocol.optionalNullableText(item, "downloadUrl")));
      }
    }
    return new Execution(id, status, stdout, stderr, List.copyOf(resources));
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
      throw new OpenCliHubException(Kind.DISABLED, "OpenCLI Hub integration is disabled");
    }
  }

  private Duration requestTimeout() {
    return Duration.ofMillis(settings.requestTimeoutMillis());
  }

  private Duration longPollTimeout() {
    return Duration.ofMillis(settings.longPollTimeoutMillis());
  }

  private static String validateExecutionId(String value) {
    if (value == null || !EXECUTION_ID.matcher(value).matches()) {
      throw new IllegalArgumentException("executionId is invalid");
    }
    return value;
  }

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

  /** 上传流不可越过声明长度，提前 EOF 必须使 JDK publisher 失败。 */
  private static final class FixedLengthInputStream extends FilterInputStream {
    private long remaining;

    private FixedLengthInputStream(InputStream input, long length) {
      super(input);
      remaining = length;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      Objects.checkFromIndexSize(offset, length, bytes.length);
      if (length == 0) {
        return 0;
      }
      if (remaining == 0) {
        return -1;
      }
      int count = in.read(bytes, offset, (int) Math.min(length, remaining));
      if (count == -1) {
        throw new IOException("multipart upload ended before declared Content-Length");
      }
      remaining -= count;
      return count;
    }
  }
}
