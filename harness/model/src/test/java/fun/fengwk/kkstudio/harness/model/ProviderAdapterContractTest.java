package fun.fengwk.kkstudio.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fun.fengwk.kkstudio.harness.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.adapter.AnthropicProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.adapter.GoogleProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.adapter.OpenAiProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.adapter.OpenAiResponsesProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.adapter.ProviderAdapter;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 当前四种 Provider 对本地 HTTP 探针保持与旧 agent 相同的请求契约。 */
class ProviderAdapterContractTest {

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
                  "provider", type, server.endpoint(endpointPath), Duration.ofSeconds(5)));
      CountDownLatch done = new CountDownLatch(1);
      AtomicReference<ProviderException> error = new AtomicReference<>();
      provider.stream(
          request(),
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
      assertTrue(
          server.requested.await(20, TimeUnit.SECONDS),
          () -> error.get() == null ? "no provider error" : String.valueOf(error.get().getCause()));
      RecordedRequest recorded = server.request.get();
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

  private static ProviderRequest request() {
    ModelVariant variant = new ModelVariant("default", 256, 0.0, null, null, List.of());
    ModelDescriptor model =
        new ModelDescriptor(
            1L,
            2L,
            ProviderType.OPENAI,
            "MiniMax-M2.7",
            "MiniMax",
            4096,
            256,
            Set.of(ModelInputModality.TEXT),
            Set.of(ModelCapability.TEXT, ModelCapability.TOOLS),
            List.of(variant),
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
                BigDecimal.ZERO),
            PromptCachePolicy.disabled());
    return new ProviderRequest(
        model,
        variant,
        List.of(
            new ProviderMessage(
                ProviderMessageRole.USER, List.of(new ProviderTextBlock("contract-prompt")))),
        List.of(
            new ProviderToolDefinition(
                "echo",
                "Echo text",
                "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"],\"additionalProperties\":false}")),
        ProviderCacheControl.none());
  }

  private static final class ProbeServer implements AutoCloseable {

    private final HttpServer server;
    private final String response;
    private final CountDownLatch requested = new CountDownLatch(1);
    private final AtomicReference<RecordedRequest> request = new AtomicReference<>();

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
      assertTrue(requested.await(20, TimeUnit.SECONDS));
      return request.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
      byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
      try {
        request.set(
            new RecordedRequest(
                exchange.getRequestURI().toString(),
                exchange.getRequestHeaders().entrySet().stream()
                    .collect(
                        Collectors.toMap(
                            entry -> entry.getKey().toLowerCase(),
                            entry -> entry.getValue().get(0))),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        requested.countDown();
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
