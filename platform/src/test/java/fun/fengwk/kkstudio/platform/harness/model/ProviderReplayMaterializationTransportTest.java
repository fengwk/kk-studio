package fun.fengwk.kkstudio.platform.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.openai.chat.OpenAiChatProviderAdapter;
import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResourceBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 真实 OpenAI Chat transport 的两轮离线回归：本地 HttpServer 回放合成 SSE，经真实 {@link OpenAiChatProviderAdapter} +
 * 真实 {@link JdkHttpSseTransport} 产生 provider completion 与 assistant durable replay state，再经 {@link
 * ProviderResourceMaterializer} 物化后发起第二轮请求。
 *
 * <p>测试意图：物化边界只替换 Resource 块，不得丢弃 assistant replay state；否则第二轮 wire 会退化为语义回退并丢失 {@code
 * reasoning_content}。测试不访问任何真实 Provider，也不使用真实会话内容。
 */
class ProviderReplayMaterializationTransportTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final UUID BLOB_ID = new UUID(0L, 7L);
  private static final String THINKING = "synthetic reasoning";
  private static final String ANSWER = "synthetic answer";

  private static final String TURN_ONE_SSE =
      "data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\""
          + THINKING
          + "\"},\"finish_reason\":null}]}\n\n"
          + "data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
          + ANSWER
          + "\"},\"finish_reason\":null}]}\n\n"
          + "data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
          + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}\n\n"
          + "data: [DONE]\n\n";

  private static final String TURN_TWO_SSE =
      "data: {\"id\":\"c2\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},"
          + "\"finish_reason\":\"stop\"}]}\n\n"
          + "data: [DONE]\n\n";

  private final List<String> requestBodies = new CopyOnWriteArrayList<>();

  private HttpServer server;
  private int port;
  private HttpClient httpClient;
  private ExecutorService workerExecutor;
  private ScheduledExecutorService scheduler;
  private JdkHttpSseTransport transport;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    port = server.getAddress().getPort();
    server.createContext(
        "/chat/completions",
        exchange -> {
          try (exchange) {
            requestBodies.add(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = requestBodies.size() == 1 ? TURN_ONE_SSE : TURN_TWO_SSE;
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(body.getBytes(StandardCharsets.UTF_8));
              out.flush();
            }
          }
        });
    server.start();

    workerExecutor = Executors.newCachedThreadPool();
    scheduler = Executors.newSingleThreadScheduledExecutor();
    httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .executor(workerExecutor)
            .build();
    transport = new JdkHttpSseTransport(httpClient, workerExecutor, scheduler);
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
    if (httpClient != null) {
      httpClient.shutdownNow();
    }
    if (workerExecutor != null) {
      workerExecutor.shutdownNow();
    }
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  /** 意图：第一轮完成产生 replay state，第二轮经物化后 wire 上仍必须出现 assistant 的 reasoning_content。 */
  @Test
  void secondTurnWireRequestKeepsReasoningContentAfterResourceMaterialization() throws Exception {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeTextBlob());
    ProviderResourceMaterializer materializer =
        new ProviderResourceMaterializer(blobManager, mock(S3StorageService.class));
    ProviderDescriptor descriptor = descriptor();
    ModelProvider provider = new OpenAiChatProviderAdapter(transport, "sk-test").create(descriptor);

    // 第一轮：durable 用户消息含 Resource，production 路径先物化再发出。
    ProviderCompletion firstCompletion =
        stream(
            provider,
            request(
                materializer.materialize(
                    List.of(userWithResource()), Set.of(ModelInputModality.TEXT))));

    ProviderReplayState replayState = firstCompletion.replayState();
    assertNotNull(replayState, "COMPLETE 响应必须产出 OpenAI Chat replay state");
    assertEquals(ProviderReplayFormat.OPENAI_CHAT, replayState.format());
    assertEquals(THINKING, firstCompletion.response().thinking());
    // 第一轮已发生物化：wire 上不再出现 durable blob 引用，且尚无 assistant replay。
    assertTrue(
        requestBodies.get(0).contains("blobId: " + BLOB_ID),
        "Resource 必须在 wire 前被物化: " + requestBodies.get(0));
    assertFalse(requestBodies.get(0).contains("reasoning_content"), requestBodies.get(0));

    // 第二轮：assistant durable 消息（thinking 取自 provider response）与同一用户消息一起物化后发出。
    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderTextBlock(firstCompletion.response().text()),
                new ProviderThinkingBlock(firstCompletion.response().thinking())),
            replayState);
    assertNotNull(
        stream(
            provider,
            request(
                materializer.materialize(
                    List.of(userWithResource(), assistant), Set.of(ModelInputModality.TEXT)))));

    JsonNode messages = MAPPER.readTree(requestBodies.get(1)).get("messages");
    assertEquals(2, messages.size(), requestBodies.get(1));
    assertEquals("user", messages.get(0).path("role").asText());
    assertEquals("assistant", messages.get(1).path("role").asText());
    assertEquals(ANSWER, messages.get(1).path("content").asText());
    assertEquals(
        THINKING,
        messages.get(1).path("reasoning_content").asText(),
        "物化边界丢弃 replay 会退化为语义回退并丢失 reasoning_content");
  }

  private static ProviderMessage userWithResource() {
    return new ProviderMessage(
        ProviderMessageRole.USER,
        List.of(
            new ProviderTextBlock("hello"),
            new ProviderResourceBlock(BLOB_ID, "notes.txt", "synthetic preview")));
  }

  private static ProviderCompletion stream(ModelProvider provider, ProviderRequest request)
      throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<ProviderCompletion> completion = new AtomicReference<>();
    AtomicReference<ProviderException> failure = new AtomicReference<>();
    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion value, ProviderStream stream) {
            completion.set(value);
            latch.countDown();
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            failure.set(error);
            latch.countDown();
          }
        });
    assertTrue(latch.await(15, TimeUnit.SECONDS), "provider stream timed out");
    assertNull(failure.get(), "provider stream failed");
    ProviderCompletion result = completion.get();
    assertNotNull(result, "provider stream produced no completion");
    return result;
  }

  private ProviderDescriptor descriptor() {
    return new ProviderDescriptor(
        "test-provider",
        ProviderType.OPENAI,
        "http://127.0.0.1:" + port,
        new ModelCallTimeoutPolicy(Duration.ofSeconds(10), Duration.ofSeconds(10)),
        new UUID(0L, 3L));
  }

  private static ProviderRequest request(List<ProviderMessage> messages) {
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "tier-1",
            "default",
            BigDecimal.ONE,
            "v1",
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ONE);
    ModelDescriptor model =
        new ModelDescriptor(
            "test-provider",
            "test-model",
            "test-model",
            Set.of(ModelInputModality.TEXT),
            false,
            true,
            pricing);
    return new ProviderRequest(
        model, new ModelVariant("default"), 1024, messages, List.of(), ProviderCacheControl.none());
  }

  private static StorageBlob activeTextBlob() {
    StorageBlob blob = new StorageBlob();
    blob.setId(BLOB_ID);
    blob.setMediaType("text/plain");
    blob.setSizeBytes(42L);
    blob.setState(StorageBlobState.ACTIVE);
    return blob;
  }
}
