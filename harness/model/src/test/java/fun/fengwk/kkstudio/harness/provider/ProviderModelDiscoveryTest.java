package fun.fengwk.kkstudio.harness.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

class ProviderModelDiscoveryTest {

  private HttpServer server;
  private HttpClient client;
  private final AtomicReference<String> requestTarget = new AtomicReference<>();
  private final AtomicReference<Headers> requestHeaders = new AtomicReference<>();
  private volatile int status = 200;
  private volatile byte[] body = new byte[0];

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requestTarget.set(exchange.getRequestURI().toString());
          requestHeaders.set(exchange.getRequestHeaders());
          exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
    client.close();
  }

  private String baseUrl(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  private void respond(int status, String json) {
    this.status = status;
    this.body = json.getBytes(StandardCharsets.UTF_8);
  }

  private ProviderModelDiscovery discovery() {
    return new ProviderModelDiscovery(client, Duration.ofSeconds(5));
  }

  /** OpenAI 兼容接口：Bearer 认证，data[].id 去重排序。 */
  @Test
  void listsOpenAiCompatibleModelsSortedAndDistinct() {
    respond(200, "{\"data\":[{\"id\":\"gpt-b\"},{\"id\":\"gpt-a\"},{\"id\":\"gpt-b\"},{\"x\":1}]}");

    List<String> ids = discovery().listModelIds(ProviderType.OPENAI, baseUrl("/v1/"), "sk-1");

    assertEquals(List.of("gpt-a", "gpt-b"), ids);
    assertEquals("/v1/models", requestTarget.get());
    assertEquals("Bearer sk-1", requestHeaders.get().getFirst("Authorization"));
  }

  /** 无凭据的本地 OpenAI 兼容服务不发送认证头。 */
  @Test
  void omitsAuthenticationWithoutCredential() {
    respond(200, "{\"data\":[]}");

    assertEquals(
        List.of(), discovery().listModelIds(ProviderType.OPENAI_RESPONSES, baseUrl("/v1"), " "));
    assertNull(requestHeaders.get().getFirst("Authorization"));
  }

  /** Anthropic：base 不带 /v1 时补齐，并携带版本与 key 头。 */
  @Test
  void listsAnthropicModelsWithVersionHeader() {
    respond(200, "{\"data\":[{\"id\":\"claude-x\"}],\"has_more\":false}");

    List<String> ids = discovery().listModelIds(ProviderType.ANTHROPIC, baseUrl(""), "ak");

    assertEquals(List.of("claude-x"), ids);
    assertEquals("/v1/models?limit=1000", requestTarget.get());
    assertEquals("ak", requestHeaders.get().getFirst("x-api-key"));
    assertEquals("2023-06-01", requestHeaders.get().getFirst("anthropic-version"));
  }

  /** Gemini：只保留支持 generateContent 的模型，并去掉 models/ 前缀。 */
  @Test
  void listsGeminiGenerateContentModels() {
    respond(
        200,
        """
        {"models":[
          {"name":"models/gemini-pro","supportedGenerationMethods":["generateContent"]},
          {"name":"models/embedding","supportedGenerationMethods":["embedContent"]},
          {"name":"tuned","supportedGenerationMethods":["generateContent"]}
        ]}
        """);

    List<String> ids = discovery().listModelIds(ProviderType.GOOGLE, baseUrl("/v1beta"), "gk");

    assertEquals(List.of("gemini-pro", "tuned"), ids);
    assertEquals("/v1beta/models?pageSize=1000", requestTarget.get());
    assertEquals("gk", requestHeaders.get().getFirst("x-goog-api-key"));
  }

  /** 非 2xx 返回状态与截断后的正文，便于用户定位认证或地址问题。 */
  @Test
  void reportsHttpFailureWithTruncatedBody() {
    respond(401, "x".repeat(ProviderModelDiscovery.MAX_ERROR_BODY_CHARS + 10));

    ProviderModelDiscovery.Failure failure =
        assertThrows(
            ProviderModelDiscovery.Failure.class,
            () -> discovery().listModelIds(ProviderType.OPENAI, baseUrl("/v1"), "bad"));

    assertTrue(failure.getMessage().startsWith("Provider model list returned HTTP 401\n"));
    assertTrue(failure.getMessage().endsWith(ProviderErrorHelper.TRUNCATION_MARKER));
    assertFalse(failure.getMessage().contains("bad"));
  }

  @Test
  void reportsEmptyHttpFailure() {
    respond(503, "");

    ProviderModelDiscovery.Failure failure =
        assertThrows(
            ProviderModelDiscovery.Failure.class,
            () -> discovery().listModelIds(ProviderType.OPENAI, baseUrl("/v1"), null));

    assertEquals("Provider model list returned HTTP 503", failure.getMessage());
  }

  /** 正文不是 JSON 或缺少列表字段时明确失败，而不是返回空列表。 */
  @Test
  void rejectsUnrecognizedBodies() {
    respond(200, "<html>");
    assertThrows(
        ProviderModelDiscovery.Failure.class,
        () -> discovery().listModelIds(ProviderType.OPENAI, baseUrl("/v1"), null));

    respond(200, "{\"models\":[]}");
    ProviderModelDiscovery.Failure missing =
        assertThrows(
            ProviderModelDiscovery.Failure.class,
            () -> discovery().listModelIds(ProviderType.OPENAI, baseUrl("/v1"), null));
    assertEquals("Provider model list response has no data array", missing.getMessage());
  }

  @Test
  void rejectsOversizedBodies() {
    respond(200, " ".repeat(ProviderModelDiscovery.MAX_BODY_BYTES + 1));

    assertThrows(
        ProviderModelDiscovery.Failure.class,
        () -> discovery().listModelIds(ProviderType.OPENAI, baseUrl("/v1"), null));
  }

  @Test
  void reportsUnreachableProvider() {
    String url = baseUrl("/v1");
    server.stop(0);

    ProviderModelDiscovery.Failure failure =
        assertThrows(
            ProviderModelDiscovery.Failure.class,
            () -> discovery().listModelIds(ProviderType.OPENAI, url, null));

    assertTrue(failure.getMessage().startsWith("Provider model list request failed: "));
  }

  /** baseUrl 必须是不含 user-info/query/fragment 的 http(s) 地址。 */
  @Test
  void validatesBaseUrl() {
    for (String invalid :
        new String[] {
          null,
          " ",
          "ftp://h/v1",
          "http://u:p@h/v1",
          "http://h/v1?x=1",
          "http://h/v1#f",
          "http:///v1",
          "http://h /v1"
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> ProviderModelDiscovery.modelsUri(ProviderType.OPENAI, invalid),
          String.valueOf(invalid));
    }
    assertEquals(
        URI.create("https://h:8443/v1/models?limit=1000"),
        ProviderModelDiscovery.modelsUri(ProviderType.ANTHROPIC, "HTTPS://h:8443/v1/"));
  }
}
