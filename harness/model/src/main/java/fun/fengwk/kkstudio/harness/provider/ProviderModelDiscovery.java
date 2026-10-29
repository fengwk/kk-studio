package fun.fengwk.kkstudio.harness.provider;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 通过 Provider 自身的模型列表接口发现可用模型 id，供 Catalog 添加模型时预填。
 *
 * <p>每种协议只读取一页：OpenAI 兼容接口 {@code GET <baseUrl>/models}；Anthropic {@code GET
 * <base>/v1/models?limit=1000}；Gemini {@code GET <baseUrl>/models?pageSize=1000}，只保留支持 {@code
 * generateContent} 的模型。结果去重排序；凭据只放在请求头里，错误消息不回显 baseUrl 或凭据。
 */
public final class ProviderModelDiscovery {

  static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
  static final int MAX_ERROR_BODY_CHARS = 300;
  private static final String ANTHROPIC_VERSION = "2023-06-01";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpClient httpClient;
  private final Duration timeout;

  public ProviderModelDiscovery(HttpClient httpClient, Duration timeout) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  /**
   * 列出 Provider 当前公布的模型 id。
   *
   * @throws IllegalArgumentException baseUrl 缺失或不是合法的 http(s) 地址
   * @throws Failure 上游不可达、返回非 2xx 或返回无法识别的正文
   */
  public List<String> listModelIds(ProviderType type, String baseUrl, String credential) {
    Objects.requireNonNull(type, "type");
    HttpRequest.Builder request =
        HttpRequest.newBuilder(modelsUri(type, baseUrl))
            .GET()
            .timeout(timeout)
            .header("Accept", "application/json");
    boolean hasCredential = credential != null && !credential.isBlank();
    switch (type) {
      case OPENAI, OPENAI_RESPONSES -> {
        if (hasCredential) {
          request.header("Authorization", "Bearer " + credential);
        }
      }
      case ANTHROPIC -> {
        request.header("anthropic-version", ANTHROPIC_VERSION);
        if (hasCredential) {
          request.header("x-api-key", credential);
          request.header("Authorization", "Bearer " + credential);
        }
      }
      case GOOGLE -> {
        if (hasCredential) {
          request.header("x-goog-api-key", credential);
        }
      }
    }
    return parse(type, send(request.build()));
  }

  static URI modelsUri(ProviderType type, String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("Provider base URL is required to list models");
    }
    URI uri;
    try {
      uri = URI.create(baseUrl.trim());
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Provider base URL is not a valid URI");
    }
    String scheme = uri.getScheme();
    if (scheme == null
        || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null) {
      throw new IllegalArgumentException(
          "Provider base URL must be an http(s) URL without user-info, query or fragment");
    }
    String path = uri.getRawPath() == null ? "" : uri.getRawPath();
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    String modelsPath =
        switch (type) {
          case OPENAI, OPENAI_RESPONSES -> path + "/models";
          case ANTHROPIC -> (path.endsWith("/v1") ? path : path + "/v1") + "/models?limit=1000";
          case GOOGLE -> path + "/models?pageSize=1000";
        };
    return URI.create(scheme.toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority() + modelsPath);
  }

  private byte[] send(HttpRequest request) {
    HttpResponse<InputStream> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException unreachable) {
      throw new Failure("Provider model list request failed: " + unreachable.getClass().getName());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new Failure("Provider model list request was interrupted");
    }
    byte[] body;
    try (InputStream stream = response.body()) {
      body = stream.readNBytes(MAX_BODY_BYTES + 1);
    } catch (IOException readFailure) {
      throw new Failure("Provider model list response could not be read");
    }
    int status = response.statusCode();
    if (status < 200 || status >= 300) {
      String text = new String(body, StandardCharsets.UTF_8).strip();
      if (text.length() > MAX_ERROR_BODY_CHARS) {
        text = text.substring(0, MAX_ERROR_BODY_CHARS) + ProviderErrorHelper.TRUNCATION_MARKER;
      }
      throw new Failure(
          text.isEmpty()
              ? "Provider model list returned HTTP " + status
              : "Provider model list returned HTTP " + status + "\n" + text);
    }
    if (body.length > MAX_BODY_BYTES) {
      throw new Failure("Provider model list response exceeds " + MAX_BODY_BYTES + " bytes");
    }
    return body;
  }

  private static List<String> parse(ProviderType type, byte[] body) {
    JsonNode root;
    try {
      root = JSON.readTree(body);
    } catch (JacksonException invalid) {
      throw new Failure("Provider model list response is not JSON");
    }
    boolean google = type == ProviderType.GOOGLE;
    JsonNode items = root.path(google ? "models" : "data");
    if (!items.isArray()) {
      throw new Failure(
          "Provider model list response has no " + (google ? "models" : "data") + " array");
    }
    TreeSet<String> ids = new TreeSet<>();
    for (JsonNode item : items) {
      String id = google ? googleModelId(item) : item.path("id").asString(null);
      if (id != null && !id.isBlank()) {
        ids.add(id.strip());
      }
    }
    return List.copyOf(ids);
  }

  private static String googleModelId(JsonNode item) {
    boolean generates = false;
    for (JsonNode method : item.path("supportedGenerationMethods")) {
      generates |= "generateContent".equals(method.asString(null));
    }
    String name = item.path("name").asString(null);
    if (!generates || name == null) {
      return null;
    }
    return name.startsWith("models/") ? name.substring("models/".length()) : name;
  }

  /** 上游模型列表不可用；消息可直接展示给用户，不含 baseUrl 或凭据。 */
  public static final class Failure extends RuntimeException {

    public Failure(String message) {
      super(message);
    }
  }
}
