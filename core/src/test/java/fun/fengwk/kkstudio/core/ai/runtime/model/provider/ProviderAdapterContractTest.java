package fun.fengwk.kkstudio.core.ai.runtime.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** 验证四种 Provider adapter 各自的外部 HTTP 请求契约。 */
class ProviderAdapterContractTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  @Timeout(30)
  void sendsExpectedHttpContractsForEveryProvider() throws Exception {
    assertContract(
        ProviderType.OPENAI,
        new OpenAiProviderAdapter("test-api-key"),
        "/v1",
        "/v1/chat/completions",
        "authorization",
        "Bearer ",
        "{\"error\":{\"message\":\"contract probe\",\"type\":\"invalid_request_error\"}}");
    assertContract(
        ProviderType.OPENAI_RESPONSES,
        new OpenAiResponsesProviderAdapter("test-api-key"),
        "/v1",
        "/v1/responses",
        "authorization",
        "Bearer ",
        "{\"error\":{\"message\":\"contract probe\",\"type\":\"invalid_request_error\"}}");
    assertContract(
        ProviderType.ANTHROPIC,
        new AnthropicProviderAdapter("test-api-key"),
        "/v1",
        "/v1/messages",
        "x-api-key",
        "",
        "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"contract"
            + " probe\"}}");
    assertContract(
        ProviderType.GOOGLE,
        new GoogleProviderAdapter("test-api-key"),
        "/v1beta",
        "/v1beta/models/MiniMax-M2.7:streamGenerateContent?alt=sse",
        "x-goog-api-key",
        "",
        "{\"error\":{\"code\":400,\"message\":\"contract"
            + " probe\",\"status\":\"INVALID_ARGUMENT\"}}");
  }

  @Test
  @Timeout(30)
  void mapsVariantReasoningEffortForEveryProviderProtocol() throws Exception {
    JsonNode openAi =
        reasoningBody(
            ProviderType.OPENAI,
            new OpenAiProviderAdapter("test-api-key"),
            "/v1",
            "{\"error\":{\"message\":\"probe\",\"type\":\"invalid_request_error\"}}");
    assertEquals("high", openAi.path("reasoning_effort").asText());

    JsonNode responses =
        reasoningBody(
            ProviderType.OPENAI_RESPONSES,
            new OpenAiResponsesProviderAdapter("test-api-key"),
            "/v1",
            "{\"error\":{\"message\":\"probe\",\"type\":\"invalid_request_error\"}}");
    assertEquals("high", responses.path("reasoning").path("effort").asText());

    JsonNode anthropic =
        reasoningBody(
            ProviderType.ANTHROPIC,
            new AnthropicProviderAdapter("test-api-key"),
            "/v1",
            "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"probe\"}}");
    assertEquals("adaptive", anthropic.path("thinking").path("type").asText());
    assertEquals("high", anthropic.path("output_config").path("effort").asText());

    JsonNode google =
        reasoningBody(
            ProviderType.GOOGLE,
            new GoogleProviderAdapter("test-api-key"),
            "/v1beta",
            "{\"error\":{\"code\":400,\"message\":\"probe\",\"status\":\"INVALID_ARGUMENT\"}}");
    JsonNode thinking = google.path("generationConfig").path("thinkingConfig");
    assertTrue(thinking.path("includeThoughts").asBoolean());
    assertEquals("high", thinking.path("thinkingLevel").asText());
  }

  /**
   * OpenAI Chat 在 SHORT + non-empty key 控制下应透传 {@code prompt_cache_key} custom 参数；NONE / LONG / 带
   * breakpoints 的 control 在请求边界 fail fast。
   */
  @Test
  @Timeout(30)
  void openAiChatMapsPromptCacheKeyAndRejectsInvalidControls() throws Exception {
    String probe = "{\"error\":{\"message\":\"probe\",\"type\":\"invalid_request_error\"}}";
    try (ProbeServer server = new ProbeServer(probe)) {
      server.start();
      ModelProvider provider =
          new OpenAiProviderAdapter("test-api-key")
              .create(
                  new ProviderDescriptor(
                      "provider",
                      ProviderType.OPENAI,
                      server.endpoint("/v1"),
                      timeoutPolicy(Duration.ofSeconds(5))));
      assertPromptCacheKeyForControl(provider, ProviderCacheControl.none(), null, server);
      assertPromptCacheKeyForControl(
          provider,
          ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "aff-key"),
          "aff-key",
          server);
      // LONG/breakpoints/AFFINITY-其它 retention 全部在 validateRequest 抛 INVALID_REQUEST。
      assertControlRejected(
          provider,
          ProviderCacheControl.affinity(PromptCacheRetention.LONG, "aff-key"),
          "OpenAI prompt cache retention",
          server);
    }
  }

  /**
   * OpenAI Responses 在 SHORT + non-empty key 控制下应下发 {@code prompt_cache_key}，NONE 时 SDK 调用不应带
   * promptCacheKey；LONG/breakpoints 仍 fail fast。
   */
  @Test
  @Timeout(30)
  void openAiResponsesMapsPromptCacheKeyAndRejectsInvalidControls() throws Exception {
    String probe = "{\"error\":{\"message\":\"probe\",\"type\":\"invalid_request_error\"}}";
    try (ProbeServer server = new ProbeServer(probe)) {
      server.start();
      ModelProvider provider =
          new OpenAiResponsesProviderAdapter("test-api-key")
              .create(
                  new ProviderDescriptor(
                      "provider",
                      ProviderType.OPENAI_RESPONSES,
                      server.endpoint("/v1"),
                      timeoutPolicy(Duration.ofSeconds(5))));
      assertPromptCacheKeyForControl(provider, ProviderCacheControl.none(), null, server);
      assertPromptCacheKeyForControl(
          provider,
          ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "aff-key-2"),
          "aff-key-2",
          server);
      assertControlRejected(
          provider,
          ProviderCacheControl.affinity(PromptCacheRetention.LONG, "aff-key-2"),
          "OpenAI prompt cache retention",
          server);
    }
  }

  /**
   * Anthropic BREAKPOINTS 仅在 SHORT 形态下接受，必须显式声明 SYSTEM/TOOLS 中至少一个；NONE 两项均不启用，body 中不出现 {@code
   * cache_control}；LONG/隐式空 control 抛 INVALID_REQUEST。
   */
  @Test
  @Timeout(30)
  void anthropicMapsBreakpointsAndRejectsInvalidControls() throws Exception {
    String probe =
        "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"probe\"}}";
    try (ProbeServer server = new ProbeServer(probe)) {
      server.start();
      ModelProvider provider =
          new AnthropicProviderAdapter("test-api-key")
              .create(
                  new ProviderDescriptor(
                      "provider",
                      ProviderType.ANTHROPIC,
                      server.endpoint("/v1"),
                      timeoutPolicy(Duration.ofSeconds(5))));
      assertAnthropicBreakpointsFor(provider, ProviderCacheControl.none(), false, false, server);
      assertAnthropicBreakpointsFor(
          provider,
          ProviderCacheControl.breakpoints(
              PromptCacheRetention.SHORT,
              "model-anthropic-system",
              EnumSet.of(PromptCacheBreakpoint.SYSTEM)),
          true,
          false,
          server);
      assertAnthropicBreakpointsFor(
          provider,
          ProviderCacheControl.breakpoints(
              PromptCacheRetention.SHORT,
              "model-anthropic-tools",
              EnumSet.of(PromptCacheBreakpoint.TOOLS)),
          false,
          true,
          server);
      assertAnthropicBreakpointsFor(
          provider,
          ProviderCacheControl.breakpoints(
              PromptCacheRetention.SHORT,
              "model-anthropic",
              EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS)),
          true,
          true,
          server);
      assertControlRejected(
          provider,
          ProviderCacheControl.breakpoints(
              PromptCacheRetention.LONG,
              "model-anthropic",
              EnumSet.of(PromptCacheBreakpoint.SYSTEM)),
          "Anthropic prompt cache retention",
          server);
      assertControlRejected(
          provider,
          ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "model-anthropic"),
          "Anthropic BREAKPOINTS requires at least SYSTEM or TOOLS",
          server);
    }
  }

  /** Google 在 AUTOMATIC 模式下完全不发送 cached-content 字段，body 不应出现 cache 控制字段。 */
  @Test
  @Timeout(30)
  void googleSendsNoExplicitCacheResource() throws Exception {
    String probe =
        "{\"error\":{\"code\":400,\"message\":\"probe\",\"status\":\"INVALID_ARGUMENT\"}}";
    try (ProbeServer server = new ProbeServer(probe)) {
      server.start();
      ModelProvider provider =
          new GoogleProviderAdapter("test-api-key")
              .create(
                  new ProviderDescriptor(
                      "provider",
                      ProviderType.GOOGLE,
                      server.endpoint("/v1beta"),
                      timeoutPolicy(Duration.ofSeconds(5))));
      RecordedRequest none =
          runAndAwait(provider, request(ProviderCacheControl.none()), server, error -> {});
      assertNoExplicitGoogleCache(none);
      RecordedRequest ignoredAffinity =
          runAndAwait(
              provider,
              request(ProviderCacheControl.affinity(PromptCacheRetention.SHORT, "ignored")),
              server,
              error -> {});
      assertNoExplicitGoogleCache(ignoredAffinity);
    }
  }

  private static void assertContract(
      ProviderType type,
      ProviderAdapter adapter,
      String endpointPath,
      String expectedPath,
      String authorizationHeader,
      String authorizationPrefix,
      String response)
      throws Exception {
    try (ProbeServer server = new ProbeServer(response)) {
      server.start();
      ModelProvider provider =
          adapter.create(
              new ProviderDescriptor(
                  "provider",
                  type,
                  server.endpoint(endpointPath),
                  timeoutPolicy(Duration.ofSeconds(5))));
      CountDownLatch done = new CountDownLatch(1);
      AtomicReference<ProviderException> error = new AtomicReference<>();
      provider.stream(
          request(ProviderCacheControl.none()),
          new ProviderStreamHandler() {
            @Override
            public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

            @Override
            public void onComplete(ProviderResponse response, ProviderStream stream) {
              done.countDown();
            }

            @Override
            public void onError(ProviderException providerError, ProviderStream stream) {
              error.set(providerError);
              done.countDown();
            }
          });
      assertTrue(done.await(20, TimeUnit.SECONDS));
      assertNotNull(error.get());
      RecordedRequest recorded = server.awaitRequest();
      assertEquals(ProviderErrorKind.INVALID_REQUEST, error.get().kind());
      assertEquals(expectedPath, recorded.path());
      assertTrue(recorded.headers().get(authorizationHeader).startsWith(authorizationPrefix));
      if (type != ProviderType.GOOGLE) {
        assertTrue(recorded.body().contains("MiniMax-M2.7"));
      }
      assertTrue(recorded.body().contains("contract-prompt"));
      assertTrue(recorded.body().contains("echo"));
      assertTrue(recorded.body().contains("text"));
      if (type == ProviderType.ANTHROPIC) {
        assertTrue(recorded.headers().containsKey("anthropic-version"));
      }
    }
  }

  private static ModelCallTimeoutPolicy timeoutPolicy(Duration totalTimeout) {
    return new ModelCallTimeoutPolicy(totalTimeout, Duration.ofSeconds(1));
  }

  private static JsonNode reasoningBody(
      ProviderType type, ProviderAdapter adapter, String endpointPath, String response)
      throws Exception {
    try (ProbeServer server = new ProbeServer(response)) {
      server.start();
      ModelProvider provider =
          adapter.create(
              new ProviderDescriptor(
                  "provider",
                  type,
                  server.endpoint(endpointPath),
                  timeoutPolicy(Duration.ofSeconds(5))));
      return jsonBody(
          runAndAwait(provider, request(ProviderCacheControl.none(), "high"), server, error -> {}));
    }
  }

  private static void assertPromptCacheKeyForControl(
      ModelProvider provider,
      ProviderCacheControl control,
      String expectedPromptCacheKey,
      ProbeServer server)
      throws InterruptedException {
    AtomicReference<ProviderException> error = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    provider.stream(
        request(control),
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderResponse response, ProviderStream stream) {
            done.countDown();
          }

          @Override
          public void onError(ProviderException providerError, ProviderStream stream) {
            error.set(providerError);
            done.countDown();
          }
        });
    assertTrue(done.await(20, TimeUnit.SECONDS));
    RecordedRequest recorded = server.awaitRequest();
    JsonNode body = jsonBody(recorded);
    if (expectedPromptCacheKey == null) {
      assertFalse(body.has("prompt_cache_key"), () -> "body=" + recorded.body());
    } else {
      assertEquals(expectedPromptCacheKey, body.path("prompt_cache_key").asText());
    }
    assertFalse(hasField(body, "prompt_cache_retention"));
    assertFalse(hasField(body, "breakpoints"));
  }

  private static void assertAnthropicBreakpointsFor(
      ModelProvider provider,
      ProviderCacheControl control,
      boolean systemMarked,
      boolean toolsMarked,
      ProbeServer server)
      throws InterruptedException {
    AtomicReference<ProviderException> error = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    provider.stream(
        request(control),
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderResponse response, ProviderStream stream) {
            done.countDown();
          }

          @Override
          public void onError(ProviderException providerError, ProviderStream stream) {
            error.set(providerError);
            done.countDown();
          }
        });
    assertTrue(done.await(20, TimeUnit.SECONDS));
    RecordedRequest recorded = server.awaitRequest();
    JsonNode body = jsonBody(recorded);
    assertEquals(
        systemMarked, hasField(body.path("system"), "cache_control"), () -> recorded.body());
    assertEquals(toolsMarked, hasField(body.path("tools"), "cache_control"), () -> recorded.body());
    assertFalse(hasField(body, "prompt_cache_retention"));
  }

  private static void assertControlRejected(
      ModelProvider provider,
      ProviderCacheControl control,
      String containsMessage,
      ProbeServer server) {
    AtomicReference<ProviderException> error = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    provider.stream(
        request(control),
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderResponse response, ProviderStream stream) {
            done.countDown();
          }

          @Override
          public void onError(ProviderException providerError, ProviderStream stream) {
            error.set(providerError);
            done.countDown();
          }
        });
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          assertTrue(done.await(3, TimeUnit.SECONDS));
        });
    assertNotNull(error.get(), () -> "expected error for control=" + control);
    assertEquals(ProviderErrorKind.INVALID_REQUEST, error.get().kind());
    assertTrue(
        error.get().getMessage() != null && error.get().getMessage().contains(containsMessage),
        () -> "message=" + error.get().getMessage());
    // 确保 cause 链能上抛 IllegalArgumentException
    Throwable cause = error.get().getCause();
    assertTrue(
        cause == null || cause instanceof IllegalArgumentException,
        () -> "cause should be IllegalArgumentException when control invalid, got " + cause);
    try {
      assertNull(
          server.pollRequest(200, TimeUnit.MILLISECONDS),
          "invalid cache control must fail before issuing an HTTP request");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static RecordedRequest runAndAwait(
      ModelProvider provider,
      ProviderRequest request,
      ProbeServer server,
      Consumer<ProviderException> errorAssert)
      throws InterruptedException {
    AtomicReference<ProviderException> error = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderResponse response, ProviderStream stream) {
            done.countDown();
          }

          @Override
          public void onError(ProviderException providerError, ProviderStream stream) {
            error.set(providerError);
            done.countDown();
          }
        });
    assertTrue(done.await(20, TimeUnit.SECONDS));
    RecordedRequest recorded = server.awaitRequest();
    if (errorAssert != null && error.get() != null) {
      errorAssert.accept(error.get());
    }
    return recorded;
  }

  private static void assertNoExplicitGoogleCache(RecordedRequest recorded) {
    JsonNode body = jsonBody(recorded);
    assertFalse(hasField(body, "cached_content"), () -> recorded.body());
    assertFalse(hasField(body, "cachedContent"), () -> recorded.body());
    assertFalse(hasField(body, "cache_control"), () -> recorded.body());
    assertFalse(hasField(body, "prompt_cache_key"), () -> recorded.body());
  }

  private static JsonNode jsonBody(RecordedRequest recorded) {
    try {
      return OBJECT_MAPPER.readTree(recorded.body());
    } catch (IOException error) {
      throw new AssertionError("request body must be valid JSON: " + recorded.body(), error);
    }
  }

  private static boolean hasField(JsonNode node, String fieldName) {
    if (node.isObject()) {
      if (node.has(fieldName)) {
        return true;
      }
      var fields = node.fields();
      while (fields.hasNext()) {
        if (hasField(fields.next().getValue(), fieldName)) {
          return true;
        }
      }
    } else if (node.isArray()) {
      for (JsonNode element : node) {
        if (hasField(element, fieldName)) {
          return true;
        }
      }
    }
    return false;
  }

  private static ProviderRequest request(ProviderCacheControl control) {
    return request(control, null);
  }

  private static ProviderRequest request(ProviderCacheControl control, String reasoningEffort) {
    ModelVariant variant =
        new ModelVariant("default", 256, 0.0, null, null, null, null, List.of(), reasoningEffort);
    ModelDescriptor model =
        new ModelDescriptor(
            "provider",
            "MiniMax-M2.7",
            true,
            true,
            new ModelPricing(
                "USD",
                "tier-1",
                "default",
                BigDecimal.ONE,
                "v1",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
    return new ProviderRequest(
        model,
        variant,
        List.of(
            new ProviderMessage(
                ProviderMessageRole.SYSTEM, List.of(new ProviderTextBlock("contract-system"))),
            new ProviderMessage(
                ProviderMessageRole.USER, List.of(new ProviderTextBlock("contract-prompt")))),
        List.of(
            new ProviderToolDefinition(
                "echo",
                "Echo text",
                "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"],\"additionalProperties\":false}")),
        control);
  }

  private static final class ProbeServer implements AutoCloseable {

    private final HttpServer server;
    private final String response;
    private final BlockingQueue<RecordedRequest> requests = new LinkedBlockingQueue<>();

    private ProbeServer(String response) throws IOException {
      this.response = response;
      server = HttpServer.create(new InetSocketAddress(0), 0);
      server.createContext("/", this::handle);
    }

    private void start() {
      server.start();
    }

    private String endpoint(String path) {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path).toString();
    }

    private RecordedRequest awaitRequest() throws InterruptedException {
      RecordedRequest request = pollRequest(20, TimeUnit.SECONDS);
      assertNotNull(request, "provider did not issue an HTTP request");
      return request;
    }

    private RecordedRequest pollRequest(long timeout, TimeUnit unit) throws InterruptedException {
      return requests.poll(timeout, unit);
    }

    private void handle(HttpExchange exchange) throws IOException {
      byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
      try {
        requests.add(
            new RecordedRequest(
                exchange.getRequestURI().toString(),
                exchange.getRequestHeaders().entrySet().stream()
                    .collect(
                        Collectors.toMap(
                            entry -> entry.getKey().toLowerCase(),
                            entry -> entry.getValue().get(0))),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(400, responseBytes.length);
        exchange.getResponseBody().write(responseBytes);
      } finally {
        exchange.close();
      }
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  private record RecordedRequest(String path, Map<String, String> headers, String body) {}
}
