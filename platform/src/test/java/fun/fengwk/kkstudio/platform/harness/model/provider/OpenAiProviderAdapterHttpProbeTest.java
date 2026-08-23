package fun.fengwk.kkstudio.platform.harness.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** OpenAI Chat Completions HTTP 探针：null/空白凭据仍必须发起 chat 请求，且不能发送 {@code Authorization} 请求头。 */
class OpenAiProviderAdapterHttpProbeTest {

  private static final String PROBE_ERROR =
      "{\"error\":{\"message\":\"probe\",\"type\":\"invalid_request_error\"}}";

  @Test
  @Timeout(30)
  void nullCredentialOmitsAuthorizationAndReachesChatCompletions() throws Exception {
    assertNoAuthorizationOnChatCompletions(new OpenAiProviderAdapter(null));
  }

  @Test
  @Timeout(30)
  void blankCredentialOmitsAuthorizationAndReachesChatCompletions() throws Exception {
    assertNoAuthorizationOnChatCompletions(new OpenAiProviderAdapter("   "));
  }

  @Test
  @Timeout(30)
  void emptyCredentialOmitsAuthorizationAndReachesChatCompletions() throws Exception {
    assertNoAuthorizationOnChatCompletions(new OpenAiProviderAdapter(""));
  }

  @Test
  @Timeout(30)
  void nonEmptyCredentialStillEmitsBearerAuthorization() throws Exception {
    try (ProbeServer server = new ProbeServer(PROBE_ERROR)) {
      server.start();
      ModelProvider provider =
          new OpenAiProviderAdapter("sk-test")
              .create(
                  new ProviderDescriptor(
                      "provider",
                      ProviderType.OPENAI,
                      server.endpoint("/v1"),
                      new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(1))));
      invokeAndAwaitTerminal(provider, server);
      RecordedRequest recorded = server.awaitRequest();
      assertEquals("/v1/chat/completions", recorded.path());
      String auth = recorded.headers().get("authorization");
      assertNotNull(auth, "Bearer Authorization must be present when credential is provided");
      assertTrue(auth.startsWith("Bearer "), "Authorization should be Bearer prefixed");
    }
  }

  private static void assertNoAuthorizationOnChatCompletions(OpenAiProviderAdapter adapter)
      throws Exception {
    try (ProbeServer server = new ProbeServer(PROBE_ERROR)) {
      server.start();
      ModelProvider provider =
          adapter.create(
              new ProviderDescriptor(
                  "provider",
                  ProviderType.OPENAI,
                  server.endpoint("/v1"),
                  new ModelCallTimeoutPolicy(Duration.ofSeconds(5), Duration.ofSeconds(1))));
      invokeAndAwaitTerminal(provider, server);
      RecordedRequest recorded = server.awaitRequest();
      assertEquals("/v1/chat/completions", recorded.path());
      assertFalse(
          recorded.headers().containsKey("authorization"),
          "Authorization header must be absent for null/blank credential: "
              + recorded.headers().keySet());
    }
  }

  private static void invokeAndAwaitTerminal(ModelProvider provider, ProbeServer server)
      throws InterruptedException {
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
    assertTrue(done.await(20, TimeUnit.SECONDS), "provider should produce a terminal callback");
    // 探针服务器响应 400；SDK 会以 INVALID_REQUEST 形式上抛——没有关系，
    // 我们关心的 HTTP 请求在那之前已被捕获。
    assertNotNull(error.get(), "probe server should have caused an error callback");
  }

  private static ProviderRequest request() {
    ModelVariant variant =
        new ModelVariant("default", 256, 0.0, null, null, null, null, List.of(), null);
    ModelDescriptor model =
        new ModelDescriptor(
            "provider",
            "MiniMax-M2.7",
            Set.of(ModelInputModality.TEXT),
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
                ProviderMessageRole.USER, List.of(new ProviderTextBlock("auth-probe")))),
        List.of(),
        ProviderCacheControl.none());
  }

  private static final class ProbeServer implements AutoCloseable {

    private final HttpServer server;
    private final String response;
    private final BlockingQueue<RecordedRequest> requests = new LinkedBlockingQueue<>();

    private ProbeServer(String response) throws IOException {
      this.response = response;
      this.server = HttpServer.create(new InetSocketAddress(0), 0);
      this.server.createContext("/", this::handle);
    }

    private void start() {
      server.start();
    }

    private String endpoint(String path) {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path).toString();
    }

    private RecordedRequest awaitRequest() throws InterruptedException {
      RecordedRequest request = requests.poll(20, TimeUnit.SECONDS);
      assertNotNull(request, "provider did not issue an HTTP request");
      return request;
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
