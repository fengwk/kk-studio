package fun.fengwk.kkstudio.plugin.comfyui;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.uri.UrlEncoding;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.support.DeadlineResponseBody;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 只使用标准 ComfyUI HTTP API 的有界、流式客户端。
 *
 * <p>上传/submit/history/queue/download 共用同源、禁止 redirect 与同一整体 deadline；鉴权、deadline 与响应体所有权由 {@link
 * ComfyuiHttpTransport} 承担。
 *
 * <p>{@link #history(String)} 只回传远端协议对象，不解释任何输出节点；输出节点的解释由冻结工作流的 selector 在定义侧完成。
 */
public final class StandardComfyuiClient implements Closeable {

  private final ComfyuiHttpTransport transport;
  private final ObjectMapper mapper;

  public StandardComfyuiClient(
      String baseUrl,
      ComfyuiSettings.AuthMode authMode,
      Duration connectTimeout,
      Duration requestTimeout,
      ObjectMapper objectMapper,
      PluginCredentialAccess credentialAccess) {
    this(
        baseUrl,
        authMode,
        requestTimeout,
        objectMapper,
        credentialAccess,
        HttpClient.newBuilder()
            .proxy(ProxySelector.getDefault())
            .connectTimeout(requirePositive(connectTimeout, "connectTimeout"))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        true);
  }

  /** 测试注入 HttpClient，不改变生产构造。 */
  StandardComfyuiClient(
      String baseUrl,
      ComfyuiSettings.AuthMode authMode,
      Duration requestTimeout,
      ObjectMapper objectMapper,
      PluginCredentialAccess credentialAccess,
      HttpClient httpClient) {
    this(baseUrl, authMode, requestTimeout, objectMapper, credentialAccess, httpClient, false);
  }

  private StandardComfyuiClient(
      String baseUrl,
      ComfyuiSettings.AuthMode authMode,
      Duration requestTimeout,
      ObjectMapper objectMapper,
      PluginCredentialAccess credentialAccess,
      HttpClient httpClient,
      boolean ownsClient) {
    this.transport =
        new ComfyuiHttpTransport(
            baseUrl,
            authMode,
            requestTimeout,
            objectMapper,
            credentialAccess,
            httpClient,
            ownsClient);
    this.mapper = transport.mapper();
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
          transport.multipart(
              multipartHeader(boundary, filename, mediaType, subfolder),
              contentLength,
              content,
              multipartFooter(boundary));
      ObjectNode response =
          object(
              transport.sendJson(
                  transport
                      .request("upload/image")
                      .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                      .POST(body)),
              "upload response");
      H3UploadedFile uploaded =
          new H3UploadedFile(
              text(response, "name"), text(response, "subfolder"), text(response, "type"));
      if (!subfolder.equals(uploaded.subfolder())) {
        throw new IllegalArgumentException("ComfyUI upload returned an unexpected subfolder");
      }
      return uploaded;
    } finally {
      ComfyuiHttpTransport.closeQuietly(content);
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
            transport.sendJson(
                transport
                    .request("prompt")
                    .header("Content-Type", "application/json")
                    .POST(transport.jsonBody(body))),
            "prompt response");
    String promptId = text(response, "prompt_id");
    JsonNode nodeErrors = response.get("node_errors");
    if (!(nodeErrors instanceof ObjectNode errors) || !errors.isEmpty()) {
      throw new IllegalArgumentException("ComfyUI prompt response contains node_errors");
    }
    return promptId;
  }

  /** 只归一化状态并回传远端 {@code entry.outputs} 协议对象，绝不解释或硬编码任何输出节点。 */
  public H3ComfyHistory history(String promptId) {
    requirePromptId(promptId);
    ObjectNode root =
        object(
            transport.sendJson(transport.request("history/" + path(promptId)).GET()),
            "history response");
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
      // 远端 status_str 不受控：只回传固定分类，不记录也不回显原值。
      throw new IllegalArgumentException("ComfyUI history terminal status is not supported");
    }
    return H3ComfyHistory.success(object(required(entry, "outputs"), "history outputs"));
  }

  public H3ComfyDownload download(H3OutputDescriptor output) {
    Objects.requireNonNull(output, "output");
    URI uri =
        transport.endpoint(
            "view?filename="
                + query(output.filename())
                + "&subfolder="
                + query(output.subfolder())
                + "&type="
                + query(output.type()));
    ComfyuiHttpTransport.Response response = transport.open(transport.request(uri).GET());
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
      ComfyuiHttpTransport.closeQuietly(response.body());
      throw error;
    }
  }

  public boolean cancelPending(String promptId) {
    requirePromptId(promptId);
    ObjectNode queue =
        object(transport.sendJson(transport.request("queue").GET()), "queue response");
    if (containsPrompt(requiredArray(queue, "queue_running"), promptId)) {
      return false;
    }
    if (!containsPrompt(requiredArray(queue, "queue_pending"), promptId)) {
      return false;
    }
    ObjectNode body = mapper.createObjectNode();
    body.putArray("delete").add(promptId);
    ComfyuiHttpTransport.Response response =
        transport.open(
            transport
                .request("queue")
                .header("Content-Type", "application/json")
                .POST(transport.jsonBody(body)));
    try (DeadlineResponseBody content = response.body()) {
      if (response.statusCode() / 100 != 2) {
        throw new IllegalStateException(
            "ComfyUI queue delete failed with HTTP " + response.statusCode());
      }
      ComfyuiHttpTransport.readBounded(content, ComfyuiHttpTransport.MAX_JSON_BYTES);
    } catch (IOException error) {
      throw readFailure(error);
    }
    return true;
  }

  /** 只释放本实例自建的 transport；注入的宿主 client 绝不被关闭。 */
  @Override
  public void close() {
    transport.close();
  }

  static URI normalizeBaseUrl(String raw) {
    return ComfyuiHttpTransport.normalizeBaseUrl(raw);
  }

  static Supplier<InputStream> contentSupplier(InputStream content, long contentLength) {
    return ComfyuiHttpTransport.contentSupplier(content, contentLength);
  }

  private static byte[] multipartHeader(
      String boundary, String filename, String mediaType, String subfolder) {
    return ("""
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
  }

  private static byte[] multipartFooter(String boundary) {
    return ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
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

  /** 响应体读取失败：超时仍是 HttpTimeoutException，其余只给固定信息，不回显远端正文或原始 cause。 */
  private static RuntimeException readFailure(IOException error) {
    if (error instanceof HttpTimeoutException) {
      return new UncheckedIOException(
          new HttpTimeoutException("ComfyUI response read exceeded the request deadline"));
    }
    return new UncheckedIOException(new IOException("cannot read ComfyUI response body"));
  }

  private static String text(ObjectNode node, String field) {
    JsonNode value = required(node, field);
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException("field " + field + " must be non-blank text");
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

  /** 路径段按 RFC 3986 编码：空格是 %20，{@code /} 也被编码，不产生表单语义的 {@code +}。 */
  private static String path(String segment) {
    return UrlEncoding.encode(segment);
  }

  /** query 值与路径段共用同一编码，接收方按表单或 RFC 3986 解码都得到原文。 */
  private static String query(String value) {
    return UrlEncoding.encode(value == null ? "" : value);
  }
}
